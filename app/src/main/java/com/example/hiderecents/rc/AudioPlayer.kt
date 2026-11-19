package com.example.hiderecents.rc

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import java.nio.ByteBuffer

/**
 * 控制端音频：收 AAC → 解码 → AudioTrack 播放。
 *
 * 不再自己连 socket —— 音频帧经 [RcLink] 的音频通道送进来（TCP 独立连接 / UDP 中继的 chan 3）。
 */
class AudioPlayer(
    private val onState: (String) -> Unit,
) {

    private var codec: MediaCodec? = null
    private var track: AudioTrack? = null
    private val info = MediaCodec.BufferInfo()
    private var sampleRate = 44100
    private var channels = 2
    private var frames = 0

    fun onPacket(packet: RcProtocol.Packet) {
        when (packet.type) {
            RcProtocol.TYPE_AUDIO_CFG -> configureDecoder(packet.payload)
            RcProtocol.TYPE_AUDIO -> feed(packet.payload)
        }
    }

    fun stop() {
        releaseAll()
    }

    private fun configureDecoder(csd: ByteArray) {
        releaseAll()
        val format = MediaFormat.createAudioFormat(MIME, sampleRate, channels).apply {
            setByteBuffer("csd-0", ByteBuffer.wrap(csd))
        }
        val decoder = MediaCodec.createDecoderByType(MIME)
        decoder.configure(format, null, null, 0)
        decoder.start()
        codec = decoder

        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(8192)
        val t = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build(),
            )
            .setBufferSizeInBytes(minBuf * 2)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        t.play()
        track = t
        Log.i(TAG, "audio cfg csd=" + csd.size + " rate=" + sampleRate + " ch=" + channels)
        onState("音频就绪")
    }

    private fun feed(data: ByteArray) {
        val decoder = codec ?: return
        val index = runCatching { decoder.dequeueInputBuffer(0) }.getOrDefault(-1)
        if (index >= 0) {
            val buffer = decoder.getInputBuffer(index)
            if (buffer != null) {
                buffer.clear()
                buffer.put(data)
                decoder.queueInputBuffer(index, 0, data.size, System.nanoTime() / 1000, 0)
            }
        }
        while (true) {
            val outIndex = runCatching { decoder.dequeueOutputBuffer(info, 0) }.getOrDefault(-1)
            if (outIndex < 0) break
            val out = decoder.getOutputBuffer(outIndex)
            val sink = track
            if (out != null && info.size > 0 && sink != null) {
                out.position(info.offset)
                out.limit(info.offset + info.size)
                val pcm = ByteArray(info.size)
                out.get(pcm)
                runCatching { sink.write(pcm, 0, pcm.size) }
                frames++
                if (frames % 50 == 0) Log.i(TAG, "decoded frames=" + frames)
                if (frames == 1) Log.i(TAG, "first pcm written=" + pcm.size)
            }
            runCatching { decoder.releaseOutputBuffer(outIndex, false) }
        }
    }

    private fun releaseAll() {
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        codec = null
        runCatching { track?.stop() }
        runCatching { track?.release() }
        track = null
    }

    private companion object {
        const val TAG = "RcAudioPlayer"
        const val MIME = MediaFormat.MIMETYPE_AUDIO_AAC
    }
}