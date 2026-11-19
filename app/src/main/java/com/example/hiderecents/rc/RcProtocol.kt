package com.example.hiderecents.rc

import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer

/**
 * M0/M1 线格式：1 字节类型 + 4 字节长度 + 载荷（长度不含头部）。
 *
 * 故意极简：这一版验证「采集→编码→传输→解码→上屏→回注」能不能在百毫秒内跑通，
 * 不做拥塞控制、不做重传、不做分片重组。换 UDP 时这层整体替换。
 */
object RcProtocol {

    const val PORT = 27183

    /** 控制通道独立端口：触控与画质指令不跟视频帧挤同一条连接。 */
    const val PORT_CTRL = 27184

    /** 音频独立端口：AAC 流不跟视频抢同一条连接。 */
    const val PORT_AUDIO = 27186

    const val TYPE_CONFIG = 1
    const val TYPE_FRAME = 2
    const val TYPE_TOUCH = 3
    const val TYPE_QUALITY = 4
    const val TYPE_OUTPUT = 5
    const val TYPE_AUDIO_CFG = 7
    const val TYPE_AUDIO = 8

    private const val MAX_PAYLOAD = 4 * 1024 * 1024

    class Packet(val type: Int, val payload: ByteArray)

    /** 解码端必须拿到宽高才能 configure —— 只靠 SPS 推断在部分机型上会失败。 */
    class Config(val width: Int, val height: Int, val sps: ByteArray, val pps: ByteArray)

    fun write(out: OutputStream, type: Int, payload: ByteArray, offset: Int, length: Int) {
        val header = ByteBuffer.allocate(5)
        header.put(type.toByte())
        header.putInt(length)
        out.write(header.array(), 0, 5)
        out.write(payload, offset, length)
    }

    /** 只产出 payload：交给 RcLink 时不要再套帧头，否则线上会变成双头。 */
    fun encodeConfigPayload(width: Int, height: Int, sps: ByteArray, pps: ByteArray): ByteArray {
        val buf = ByteBuffer.allocate(8 + 4 + sps.size + 4 + pps.size)
        buf.putInt(width)
        buf.putInt(height)
        buf.putInt(sps.size)
        buf.put(sps)
        buf.putInt(pps.size)
        buf.put(pps)
        return buf.array()
    }

    fun writeConfig(
        out: OutputStream,
        width: Int,
        height: Int,
        sps: ByteArray,
        pps: ByteArray,
    ) {
        val payload = encodeConfigPayload(width, height, sps, pps)
        write(out, TYPE_CONFIG, payload, 0, payload.size)
    }

    fun readConfig(payload: ByteArray): Config {
        val buf = ByteBuffer.wrap(payload)
        val width = buf.int
        val height = buf.int
        val sps = ByteArray(buf.int)
        buf.get(sps)
        val pps = ByteArray(buf.int)
        buf.get(pps)
        return Config(width, height, sps, pps)
    }

    /** 运行中改码率：控制端发一个 int 过来，被控端直接 setParameters，不断流。 */
    fun encodeQuality(bitrateBps: Int): ByteArray = ByteBuffer.allocate(4).putInt(bitrateBps).array()

    fun decodeQuality(payload: ByteArray): Int =
        if (payload.size >= 4) ByteBuffer.wrap(payload).int else -1

    /**
     * 画质档位。码率能在线改；帧率与长边要重建编码器，
     * 所以三项一起发，被控端自己判断哪些可以直接生效。
     */
    class Output(val bitrateBps: Int, val fps: Int, val longSide: Int)

    fun encodeOutput(bitrateBps: Int, fps: Int, longSide: Int): ByteArray =
        ByteBuffer.allocate(12).putInt(bitrateBps).putInt(fps).putInt(longSide).array()

    fun decodeOutput(payload: ByteArray): Output? =
        if (payload.size >= 12) {
            val buf = ByteBuffer.wrap(payload)
            Output(buf.int, buf.int, buf.int)
        } else {
            null
        }

    fun read(input: InputStream): Packet? {
        val din = input as? DataInputStream ?: DataInputStream(input)
        val type = din.read()
        if (type < 0) return null
        val length = din.readInt()
        if (length < 0 || length > MAX_PAYLOAD) {
            throw IOException("包长度非法：$length")
        }
        val payload = ByteArray(length)
        din.readFully(payload)
        return Packet(type, payload)
    }
}

/**
 * 触控包。坐标是 0..1 的归一化值，由控制端按自己的显示区域换算，
 * 被控端再乘回真实分辨率 —— 这样两端分辨率不同也不需要协商。
 */
object TouchPacket {

    const val ACTION_TAP = 1
    const val ACTION_SWIPE = 2
    const val ACTION_GLOBAL = 3

    const val GLOBAL_BACK = 0
    const val GLOBAL_HOME = 1
    const val GLOBAL_RECENTS = 2

    fun tap(x: Float, y: Float): ByteArray =
        ByteBuffer.allocate(1 + 8).put(ACTION_TAP.toByte()).putFloat(x).putFloat(y).array()

    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Int): ByteArray =
        ByteBuffer.allocate(1 + 16 + 4)
            .put(ACTION_SWIPE.toByte())
            .putFloat(x1).putFloat(y1).putFloat(x2).putFloat(y2)
            .putInt(durationMs)
            .array()

    fun global(code: Int): ByteArray =
        ByteBuffer.allocate(2).put(ACTION_GLOBAL.toByte()).put(code.toByte()).array()
}