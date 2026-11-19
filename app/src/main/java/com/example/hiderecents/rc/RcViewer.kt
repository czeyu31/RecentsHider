package com.example.hiderecents.rc

import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * 控制端：经 [RcLink] 收 H.264 → 硬解 → 直渲到 [Surface]（SurfaceView）。
 *
 * 链路是 TCP 直连还是 UDP 中继由 link 决定，这里只按通道分发：
 * 视频喂解码器，音频喂 [AudioPlayer]，触控与画质从控制通道回传。
 */
class RcViewer(
    private val onState: (String) -> Unit,
    private val onStats: (fps: Int, kbps: Int) -> Unit,
    private val onVideoSize: (width: Int, height: Int) -> Unit = { _, _ -> },
    private val onConnected: (Boolean) -> Unit = {},
) {

    private var surface: Surface? = null
    @Volatile private var pendingConfig: RcProtocol.Config? = null
    @Volatile private var link: RcLink? = null
    private var audio: AudioPlayer? = null
    private var codec: MediaCodec? = null
    private val running = AtomicBoolean(false)
    private var statsThread: Thread? = null
    private val info = MediaCodec.BufferInfo()

    private val framesInWindow = AtomicInteger(0)
    private val framesSeen = AtomicInteger(0)
    private val bytesInWindow = AtomicLong(0)
    @Volatile private var connectStartedAt = 0L
    @Volatile private var firstFrameAt = 0L

    /** 音频通道状态，供界面显示。 */
    @Volatile var audioState: String = "未连接"
        private set

    @Volatile var lastError: String? = null
        private set

    fun attach(target: Surface?) {
        surface = target
        Log.i(TAG, "attach surface=${target != null} pending=${pendingConfig != null}")
        // 画面容器可能比连接晚到：config 先存着，等 surface 就绪再起解码器，
        // 否则会卡在"等待画面容器就绪"上永远不动。
        val cfg = pendingConfig
        if (target != null && cfg != null) {
            pendingConfig = null
            runCatching { configureDecoder(cfg) }
        }
    }

    fun connect(host: String, port: Int = RcProtocol.PORT) {
        startLink(TcpLink.viewer(host, port))
    }

    fun sendTap(nx: Float, ny: Float) = send(TouchPacket.tap(nx, ny))

    fun sendSwipe(nx1: Float, ny1: Float, nx2: Float, ny2: Float, durationMs: Int) =
        send(TouchPacket.swipe(nx1, ny1, nx2, ny2, durationMs))

    fun sendGlobal(code: Int) = send(TouchPacket.global(code))

    /** 运行中改码率：被控端收到后直接 setParameters，画面不断。 */
    fun sendQuality(bitrateBps: Int) = send(RcProtocol.encodeQuality(bitrateBps))

    /** 画质档位：码率在线生效；帧率与长边会导致被控端重建一次编码器。 */
    fun sendOutput(bitrateBps: Int, fps: Int, longSide: Int) =
        send(RcProtocol.encodeOutput(bitrateBps, fps, longSide), RcProtocol.TYPE_OUTPUT)

    private fun send(payload: ByteArray, type: Int = RcProtocol.TYPE_TOUCH) {
        val channel = link ?: return
        Log.d(TAG, "send type=$type size=${payload.size}")
        channel.send(RcChan.CTRL, type, payload)
    }

    fun stop() {
        running.set(false)
        runCatching { link?.stop() }
        link = null
        runCatching { audio?.stop() }
        audio = null
        audioState = "未连接"
        runCatching { statsThread?.join(300) }
        statsThread = null
        releaseCodec()
        onConnected(false)
    }

    private fun startLink(newLink: RcLink) {
        if (running.getAndSet(true)) return
        val player = AudioPlayer { msg -> audioState = msg }
        audio = player
        lastError = null
        firstFrameAt = 0L
        connectStartedAt = System.currentTimeMillis()
        newLink.onState = { onState(it) }
        newLink.onFrame = { chan, packet ->
            when (chan) {
                RcChan.AUDIO -> player.onPacket(packet)
                else -> handleVideoPacket(packet)
            }
        }
        newLink.onPeer = { peerConnected, _ ->
            onConnected(peerConnected)
            if (!peerConnected) {
                releaseCodec()
                if (running.get()) onState("连接已断开")
            }
        }
        link = newLink
        // connect() 是阻塞的网络操作，落在主线程会被 NetworkOnMainThreadException 打断
        Thread({ newLink.start() }, "rc-link-open").apply { isDaemon = true }.start()
        startStats()
    }

    private fun handleVideoPacket(packet: RcProtocol.Packet) {
        when (packet.type) {
            RcProtocol.TYPE_CONFIG -> {
                val cfg = RcProtocol.readConfig(packet.payload)
                configureDecoder(cfg)
                onState("解码器就绪 ${cfg.width}×${cfg.height}")
            }

            RcProtocol.TYPE_FRAME -> {
                val fed = feed(packet.payload, System.nanoTime() / 1000)
                val seen = framesSeen.incrementAndGet()
                if (seen % 60 == 0) {
                    Log.i(TAG, "frames=$seen fed=$fed codec=${codec != null} size=${packet.payload.size}")
                }
                if (!fed) return
                framesInWindow.incrementAndGet()
                bytesInWindow.addAndGet(packet.payload.size.toLong())
                if (firstFrameAt == 0L) {
                    firstFrameAt = System.currentTimeMillis()
                    onState("首帧 ${firstFrameAt - connectStartedAt}ms")
                }
            }
        }
    }

    private fun startStats() {
        statsThread = Thread({
            var last = System.currentTimeMillis()
            while (running.get()) {
                runCatching { Thread.sleep(200) }
                val now = System.currentTimeMillis()
                if (now - last < 1000) continue
                onStats(framesInWindow.getAndSet(0), (bytesInWindow.getAndSet(0) * 8 / 1000).toInt())
                last = now
            }
        }, "rc-stats").apply {
            isDaemon = true
            start()
        }
    }

    private fun configureDecoder(cfg: RcProtocol.Config) {
        Log.i(TAG, "configure ${cfg.width}x${cfg.height} surface=${surface != null}")
        releaseCodec()
        val target = surface
        if (target == null) {
            pendingConfig = cfg
            Log.i(TAG, "surface not ready, config deferred")
            return
        }
        val format = MediaFormat.createVideoFormat(MIME, cfg.width, cfg.height).apply {
            setByteBuffer("csd-0", ByteBuffer.wrap(cfg.sps))
            setByteBuffer("csd-1", ByteBuffer.wrap(cfg.pps))
        }
        val decoder = MediaCodec.createDecoderByType(MIME)
        decoder.configure(format, target, null, 0)
        decoder.start()
        codec = decoder
        onVideoSize(cfg.width, cfg.height)
    }

    /** 返回 true 表示这一帧进了解码器。取不到输入缓冲就直接丢，不排队等。 */
    private fun feed(data: ByteArray, ptsUs: Long): Boolean {
        val decoder = codec ?: return false
        val index = runCatching { decoder.dequeueInputBuffer(0) }.getOrDefault(-1)
        if (index < 0) return false
        val buffer = decoder.getInputBuffer(index) ?: return false
        buffer.clear()
        buffer.put(data)
        decoder.queueInputBuffer(index, 0, data.size, ptsUs, 0)
        drain(decoder)
        return true
    }

    private fun drain(decoder: MediaCodec) {
        while (true) {
            val index = runCatching { decoder.dequeueOutputBuffer(info, 0) }.getOrDefault(-1)
            when {
                index >= 0 -> decoder.releaseOutputBuffer(index, true)
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit
                else -> return
            }
        }
    }

    private fun releaseCodec() {
        val current = codec ?: return
        codec = null
        runCatching { current.stop() }
        runCatching { current.release() }
    }

    private companion object {
        const val TAG = "RcViewer"
        const val MIME = MediaFormat.MIMETYPE_VIDEO_AVC
    }
}