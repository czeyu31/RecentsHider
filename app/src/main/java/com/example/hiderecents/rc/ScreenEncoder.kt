package com.example.hiderecents.rc

import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.os.Build
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 被控端：MediaProjection → VirtualDisplay → 编码器输入 Surface → H.264。
 *
 * 采集侧全程不碰 CPU：VirtualDisplay 直接把帧交到编码器的 input surface 上，
 * 不经 ImageReader、不做 RGB→YUV、不缩放。任何一次 CPU 拷贝都会同时毁掉画质和延迟。
 *
 * Android 14 起同一个 MediaProjection 只能建一次 VirtualDisplay，
 * 所以重开会话时复用已建好的 display（只换 Surface），不能 release 后重建。
 */
class ScreenEncoder(
    private val projection: MediaProjection,
    private var width: Int,
    private var height: Int,
    private val densityDpi: Int,
    private var bitRate: Int = 0,
    private var frameRate: Int = DEFAULT_FRAME_RATE,
    private val onConfig: (width: Int, height: Int, sps: ByteArray, pps: ByteArray) -> Unit,
    private val onFrame: (data: ByteArray, offset: Int, length: Int, ptsUs: Long) -> Unit,
) {

    private var codec: MediaCodec? = null
    private var inputSurface: Surface? = null
    private var display: VirtualDisplay? = null
    private val running = AtomicBoolean(false)
    private var thread: Thread? = null
    private val bufferInfo = MediaCodec.BufferInfo()
    private var scratch = ByteArray(0)
    private var encodedBytes = 0L
    private var lastRateLog = 0L

    /** 硬编拿不到时置 true，由调用方决定要不要在 UI 上标出来。 */
    @Volatile
    var usingSoftwareCodec: Boolean = false
        private set

    /** 本机在采集尺寸下编码器真正能跑的帧率上限，start() 时探测。 */
    @Volatile
    var maxFrameRate: Int = 60
        private set

    fun start() {
        if (running.get()) return

        logCapabilities()
        alignSize()
        maxFrameRate = probeMaxFrameRate(width, height)
        val effectiveFps = frameRate.coerceAtMost(maxFrameRate)
        // 码率不按固定值给：1080p@60 给 8Mbps 每帧每像素只有 0.06bit，必然糊成块。
        // 按 0.09 bit/pixel/frame 反推，并夹在 6..40 Mbps。
        if (bitRate <= 0) {
            // 0.15 bit/pixel/frame，结果单位是 bps
            val auto = (width.toLong() * height * effectiveFps * 20L / 100L).toInt()
            bitRate = auto.coerceIn(8_000_000, 80_000_000)
        }

        val format = MediaFormat.createVideoFormat(MIME, width, height).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface,
            )
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            // 部分编码器只认这个上限，只给 KEY_BIT_RATE 时会按内容无限放大
            runCatching { setInteger("max-bitrate", bitRate) }
                .onFailure { Log.w(TAG, "max bitrate key rejected: ${it.message}") }
            setInteger(MediaFormat.KEY_FRAME_RATE, effectiveFps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL_SEC)
            setInteger(
                MediaFormat.KEY_BITRATE_MODE,
                MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR,
            )
            // 不声明 profile/level 时编码器按最低档限幅，1080p 只能跑到 8 Mbps 左右。
            // 这里不能静默失败：不支持时会直接抛，之后就一直在低档跑
            runCatching {
                setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileHigh)
            }.onFailure { Log.w(TAG, "profile High rejected: ${it.message}") }
            runCatching {
                setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel52)
            }.onFailure { Log.w(TAG, "level 5.2 rejected: ${it.message}") }
            setInteger(MediaFormat.KEY_PRIORITY, 0)
            if (Build.VERSION.SDK_INT >= 29) {
                setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
            }
        }

        val created = createCodec(format)
        usingSoftwareCodec = created.second
        Log.i(TAG, "encoder=" + (if (usingSoftwareCodec) "SOFTWARE" else "HARDWARE") + " size=" + width + "x" + height + " fps=" + effectiveFps + " bitrate=" + (bitRate / 1000) + "kbps")
        val surface = created.first.createInputSurface()
        inputSurface = surface
        codec = created.first
        created.first.start()

        if (!attachDisplay(surface)) {
            runCatching { display?.release() }
            display = null
            throw IllegalStateException("录屏授权需选择「整个屏幕」；单个应用模式在本机无法建立画面")
        }

        running.set(true)
        thread = Thread({ drainLoop() }, "rc-encode").also { it.start() }
    }

    /** 屏幕旋转时调用：复用同一个 VirtualDisplay，只改尺寸。编码器需重建才吃新尺寸。 */
    fun resize(newWidth: Int, newHeight: Int) {
        if (newWidth == width && newHeight == height) return
        width = newWidth
        height = newHeight
        display?.resize(newWidth, newHeight, densityDpi)
    }

    fun requestKeyFrame() {
        runCatching {
            val params = android.os.Bundle()
            params.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            codec?.setParameters(params)
        }
    }

    /**
     * 运行中改码率。编码器支持 setParameters，不需要重建，画面不断。
     * 这是「不退出控制即可调画质」里唯一没有副作用的一项。
     */
    fun setBitrate(bps: Int) {
        if (bps <= 0 || bps == bitRate) return
        val old = bitRate
        bitRate = bps
        val result = runCatching {
            val params = android.os.Bundle()
            params.putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, bps)
            codec?.setParameters(params)
        }
        // 这里是静默失败过的：编码器不认的参数会直接抛，看不出码率根本没生效
        Log.i(
            TAG,
            "setBitrate ${old / 1000}k -> ${bps / 1000}k ok=${result.isSuccess} err=" +
                (result.exceptionOrNull()?.message ?: "-"),
        )
    }

    /**
     * 宽高不对齐时编码器会走降级路径（软解/软编或降质量），码率也跟着上不去。
     * 取硬件编码器声明的对齐要求，向下裁到合法尺寸。
     */
    private fun alignSize() {
        runCatching {
            val align = encoderAlignment() ?: return
            val w = width - width % align.first
            val h = height - height % align.second
            if (w >= 2 && h >= 2 && (w != width || h != height)) {
                Log.i(TAG, "align ${width}x$height -> ${w}x$h (align=${align.first}x${align.second})")
                width = w
                height = h
            }
        }.onFailure { Log.w(TAG, "align failed", it) }
    }

    private fun encoderAlignment(): Pair<Int, Int>? = runCatching {
        val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        for (info in list.codecInfos) {
            if (!info.isEncoder || !info.isHardwareAccelerated) continue
            if (info.supportedTypes.none { it.equals(MIME, true) }) continue
            val video = info.getCapabilitiesForType(MIME).videoCapabilities ?: continue
            return@runCatching video.widthAlignment to video.heightAlignment
        }
        null
    }.getOrNull()

    /** 把编码器真实能力打出来：码率上限、对齐、profile 支持情况。 */
    private fun logCapabilities() {
        runCatching {
            val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
            for (info in list.codecInfos) {
                if (!info.isEncoder || !info.isHardwareAccelerated) continue
                if (info.supportedTypes.none { it.equals(MIME, true) }) continue
                val caps = info.getCapabilitiesForType(MIME)
                val video = caps.videoCapabilities ?: continue
                val profiles = caps.profileLevels.joinToString(",") { "${it.profile}/${it.level}" }
                Log.i(
                    TAG,
                    "cap ${info.name} kbps=${video.bitrateRange.lower / 1000}-${video.bitrateRange.upper / 1000} " +
                        "align=${video.widthAlignment}x${video.heightAlignment} " +
                        "w=${video.supportedWidths} h=${video.supportedHeights} profiles=$profiles",
                )
                return
            }
        }.onFailure { Log.w(TAG, "cap query failed", it) }
    }

    val currentSize: Pair<Int, Int>
        get() = width to height

    /**
     * 改分辨率或帧率：MediaCodec 没有运行时改这两项的接口，只能重建编码器。
     * VirtualDisplay 复用（Android 14 不许重建），只 resize，所以会话不断、黑屏一次。
     */
    fun applyProfile(longSide: Int, fps: Int) {
        val ratio = height.toFloat() / width.toFloat()
        // longSide 指「较长那条边」：竖屏时要落到高度上，否则横竖颠倒
        var w = if (ratio >= 1f) (longSide / ratio).toInt() else longSide
        var h = if (ratio >= 1f) longSide else (longSide * ratio).toInt()
        w -= w % 2
        h -= h % 2
        if (w < 2 || h < 2) return

        stop()
        width = w
        height = h
        frameRate = fps.coerceIn(15, maxFrameRate)
        runCatching { display?.resize(width, height, densityDpi) }
        start()
        requestKeyFrame()
    }

    /**
     * 屏幕转向：按显式尺寸重建。不能用长边法 —— 横竖切换时长边不变、比例却变了，
     * 算出来的宽高是错的，画面会被压。
     */
    fun applySize(newWidth: Int, newHeight: Int) {
        var w = newWidth
        var h = newHeight
        w -= w % 2
        h -= h % 2
        if (w < 2 || h < 2) return
        if (w == width && h == height) return

        stop()
        width = w
        height = h
        runCatching { display?.resize(w, h, densityDpi) }
        start()
        requestKeyFrame()
    }

    fun stop() {
        running.set(false)
        runCatching { thread?.join(500) }
        thread = null
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        codec = null
        runCatching { inputSurface?.release() }
        inputSurface = null
        // 只摘掉 Surface，保留 display —— 见类注释里 Android 14 的约束
        runCatching { display?.surface = null }
    }

    fun release() {
        stop()
        runCatching { display?.release() }
        display = null
    }

    /**
     * 建立虚拟显示器。返回 false 表示系统拒绝 —— 最常见的原因是录屏授权时
     * 选的是「单个应用」，那种模式下部分 ROM 不接受 AUTO_MIRROR。
     * 重试几次再失败就把原因抛给上层，不要静默死掉。
     */
    private fun attachDisplay(surface: Surface): Boolean {
        val existing = display
        if (existing != null) {
            existing.surface = surface
            return true
        }
        repeat(3) { attempt ->
            val created = runCatching {
                projection.createVirtualDisplay(
                    DISPLAY_NAME,
                    width,
                    height,
                    densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    surface,
                    null,
                    null,
                )
            }.onFailure { Log.w(TAG, "createVirtualDisplay attempt ${attempt + 1} failed", it) }.getOrNull()
            if (created != null) {
                display = created
                return true
            }
            runCatching { Thread.sleep(200) }
        }
        return false
    }

    private fun drainLoop() {
        while (running.get()) {
            val c = codec ?: break
            val index = try {
                c.dequeueOutputBuffer(bufferInfo, DEQUEUE_TIMEOUT_US)
            } catch (e: IllegalStateException) {
                Log.w(TAG, "编码器已停止", e)
                break
            }

            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> continue

                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val format = c.outputFormat
                    val sps = format.getByteBuffer("csd-0")?.let { copyOf(it) }
                    val pps = format.getByteBuffer("csd-1")?.let { copyOf(it) }
                    if (sps != null && pps != null) {
                        runCatching { onConfig(width, height, sps, pps) }
                    }
                }

                index >= 0 -> {
                    val out = c.getOutputBuffer(index)
                    if (out != null && bufferInfo.size > 0 && isFrame(bufferInfo)) {
                        if (scratch.size < bufferInfo.size) scratch = ByteArray(bufferInfo.size)
                        out.position(bufferInfo.offset)
                        out.limit(bufferInfo.offset + bufferInfo.size)
                        out.get(scratch, 0, bufferInfo.size)
                        encodedBytes += bufferInfo.size
                        val now = System.currentTimeMillis()
                        if (lastRateLog == 0L) lastRateLog = now
                        if (now - lastRateLog >= 3000) {
                            Log.i(TAG, "encoded " + (encodedBytes * 8 / 3000) + " kbps")
                            encodedBytes = 0
                            lastRateLog = now
                        }
                        runCatching {
                            onFrame(scratch, 0, bufferInfo.size, bufferInfo.presentationTimeUs)
                        }
                    }
                    runCatching { c.releaseOutputBuffer(index, false) }
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
        }
    }

    private fun isFrame(info: MediaCodec.BufferInfo): Boolean =
        info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0

    private fun copyOf(buffer: ByteBuffer): ByteArray {
        val copy = buffer.duplicate()
        val out = ByteArray(copy.remaining())
        copy.get(out)
        return out
    }

    private fun createCodec(format: MediaFormat): Pair<MediaCodec, Boolean> {
        val hardware = selectEncoder(preferSoftware = false)
        if (hardware != null) {
            try {
                val fast = MediaCodec.createByCodecName(hardware)
                // 不再设置 KEY_LOW_LATENCY：MTK 编码器在该模式下无视 KEY_BIT_RATE，
                // 实测动态画面能冲到 240 Mbps，局域网发不完，丢帧后画面成块。
                fast.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                return fast to false
            } catch (e: Throwable) {
                Log.w(TAG, "硬编配置失败，回退常规硬编", e)
            }
            try {
                val normal = MediaCodec.createByCodecName(hardware)
                format.removeKey(KEY_LOW_LATENCY)
                normal.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                return normal to false
            } catch (e: Throwable) {
                Log.w(TAG, "硬编配置失败，尝试软编", e)
            }
        }
        val software = selectEncoder(preferSoftware = true)
            ?: throw IllegalStateException("本机没有可用的 H.264 编码器")
        val codec = MediaCodec.createByCodecName(software)
        format.removeKey(KEY_LOW_LATENCY)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        return codec to true
    }

    private fun selectEncoder(preferSoftware: Boolean): String? {
        val candidates = mutableListOf<MediaCodecInfo>()
        for (info in MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos) {
            if (!info.isEncoder) continue
            if (!info.supportedTypes.any { it.equals(MIME, ignoreCase = true) }) continue
            val caps = runCatching { info.getCapabilitiesForType(MIME) }.getOrNull() ?: continue
            if (caps.colorFormats.any { it == MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface }) {
                candidates.add(info)
            }
        }
        if (candidates.isEmpty()) return null

        val softwareOnly: (MediaCodecInfo) -> Boolean = {
            Build.VERSION.SDK_INT >= 29 && it.isSoftwareOnly
        }
        val preferred = if (preferSoftware) {
            candidates.firstOrNull(softwareOnly) ?: candidates.lastOrNull()
        } else {
            candidates.firstOrNull { !softwareOnly(it) } ?: candidates.firstOrNull()
        }
        return preferred?.name
    }

    /**
     * 帧率上限只能问编码器：media_codecs*.xml 里读不到，而且随分辨率变化。
     * 探测结果用来限幅 —— 把不支持的帧率塞进 configure 会直接失败或静默回落。
     */
    private fun probeMaxFrameRate(w: Int, h: Int): Int {
        var best = 30
        runCatching {
            for (info in MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos) {
                if (!info.isEncoder) continue
                if (!info.supportedTypes.any { it.equals(MIME, ignoreCase = true) }) continue
                val caps = runCatching { info.getCapabilitiesForType(MIME) }.getOrNull() ?: continue
                val surfaceOk = caps.colorFormats.any {
                    it == MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
                }
                if (!surfaceOk) continue
                val range = runCatching {
                    caps.videoCapabilities.getSupportedFrameRatesFor(w, h)
                }.getOrNull() ?: continue
                val upper = range.upper.toInt()
                if (upper > best) best = upper
            }
        }
        return best.coerceIn(30, 120)
    }

    private companion object {
        const val TAG = "RcEncoder"
        const val MIME = MediaFormat.MIMETYPE_VIDEO_AVC
        const val DISPLAY_NAME = "SystemToolRemote"
        const val DEFAULT_BIT_RATE = 8_000_000

        const val DEFAULT_FRAME_RATE = 60
        const val I_FRAME_INTERVAL_SEC = 1
        const val REPEAT_FRAME_US = 100_000
        const val DEQUEUE_TIMEOUT_US = 10_000L
        const val KEY_LOW_LATENCY = "low-latency"
    }
}