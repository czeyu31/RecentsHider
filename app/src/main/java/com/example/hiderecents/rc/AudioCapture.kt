package com.example.hiderecents.rc

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.os.Build
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 被控端音频采集：AudioPlaybackCapture 抓本机正在播放的声音 → AAC 硬编。
 *
 * 只能抓 USAGE_MEDIA / USAGE_GAME / USAGE_UNKNOWN 的音频，而且目标 App 若声明
 * allowAudioPlaybackCapture=false 就抓不到 —— 这是系统层面的限制，不是我们能绕的。
 */
class AudioCapture(
    private val projection: MediaProjection,
    private val onConfig: (ByteArray) -> Unit,
    private val onData: (data: ByteArray, offset: Int, length: Int) -> Unit,
) {

    private var record: AudioRecord? = null
    private var codec: MediaCodec? = null
    private var thread: Thread? = null
    private val running = AtomicBoolean(false)
    private val info = MediaCodec.BufferInfo()

    val supported: Boolean
        get() = Build.VERSION.SDK_INT >= 29

    fun start(): Boolean {
        if (!supported) return false
        if (running.get()) return true

        val record = runCatching { buildRecord() }.getOrNull() ?: run {
            Log.w(TAG, "AudioRecord 不可用（可能被目标应用禁止捕获）")
            return false
        }
        val codec = runCatching { buildEncoder() }.getOrNull() ?: run {
            runCatching { record.release() }
            Log.w(TAG, "AAC 编码器不可用")
            return false
        }

        this.record = record
        this.codec = codec
        runCatching { record.startRecording() }.onFailure {
            Log.w(TAG, "startRecording failed", it)
            releaseAll()
            return false
        }
        codec.start()
        running.set(true)
        thread = Thread({ loop() }, "rc-audio").also { it.start() }
        Log.i(TAG, "audio capture started")
        return true
    }

    fun stop() {
        running.set(false)
        runCatching { thread?.join(400) }
        thread = null
        releaseAll()
    }

    private fun releaseAll() {
        runCatching { record?.stop() }
        runCatching { record?.release() }
        record = null
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        codec = null
    }

    private fun buildRecord(): AudioRecord {
        val captureConfig = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
            .build()
        val minBytes = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_STEREO,
            AudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(4096)
        return AudioRecord.Builder()
            .setAudioFormat(format)
            .setBufferSizeInBytes(minBytes * 2)
            .setAudioPlaybackCaptureConfig(captureConfig)
            .build()
    }

    private fun buildEncoder(): MediaCodec {
        val format = MediaFormat.createAudioFormat(MIME, SAMPLE_RATE, CHANNELS).apply {
            setInteger(
                MediaFormat.KEY_AAC_PROFILE,
                MediaCodecInfo.CodecProfileLevel.AACObjectLC,
            )
            setInteger(MediaFormat.KEY_BIT_RATE, BITRATE)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
        }
        val codec = MediaCodec.createEncoderByType(MIME)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        return codec
    }

    private fun loop() {
        val record = record ?: return
        val codec = codec ?: return
        val pcm = ByteArray(PCM_CHUNK)
        var totalBytes = 0L
        var lastLog = System.currentTimeMillis()

        while (running.get()) {
            val read = runCatching {
                record.read(pcm, 0, pcm.size)
            }.getOrDefault(-1)
            if (read <= 0) {
                Thread.sleep(5)
                continue
            }
            totalBytes += read
            val now = System.currentTimeMillis()
            if (now - lastLog >= 3000) {
                Log.i(TAG, "pcm bytes in last 3s: " + totalBytes)
                totalBytes = 0
                lastLog = now
            }
            feedEncoder(codec, pcm, read)
            drainEncoder(codec)
        }
    }

    private fun feedEncoder(codec: MediaCodec, pcm: ByteArray, size: Int) {
        val index = runCatching { codec.dequeueInputBuffer(10_000) }.getOrDefault(-1)
        if (index < 0) return
        val buffer = codec.getInputBuffer(index) ?: return
        buffer.clear()
        buffer.put(pcm, 0, size)
        codec.queueInputBuffer(index, 0, size, System.nanoTime() / 1000, 0)
    }

    private fun drainEncoder(codec: MediaCodec) {
        while (true) {
            val index = runCatching { codec.dequeueOutputBuffer(info, 0) }.getOrDefault(-1)
            when {
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val csd = runCatching { codec.outputFormat.getByteBuffer("csd-0") }.getOrNull()
                    if (csd != null) {
                        val copy = csd.duplicate()
                        val bytes = ByteArray(copy.remaining())
                        copy.get(bytes)
                        runCatching { onConfig(bytes) }
                    }
                }

                index >= 0 -> {
                    val out = codec.getOutputBuffer(index)
                    if (out != null && info.size > 0 &&
                        info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0
                    ) {
                        val bytes = ByteArray(info.size)
                        out.position(info.offset)
                        out.limit(info.offset + info.size)
                        out.get(bytes)
                        runCatching { onData(bytes, 0, bytes.size) }
                    }
                    runCatching { codec.releaseOutputBuffer(index, false) }
                }

                else -> return
            }
        }
    }

    private companion object {
        const val TAG = "RcAudio"
        const val MIME = MediaFormat.MIMETYPE_AUDIO_AAC
        const val SAMPLE_RATE = 44100
        const val CHANNELS = 2
        const val BITRATE = 128_000
        const val PCM_CHUNK = 4096
    }
}