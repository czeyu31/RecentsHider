package com.example.hiderecents.rc

import android.util.Log
import java.nio.ByteBuffer

/** 三条逻辑通道。TCP 下各自一条连接，UDP 中继下共用一条数据报流。 */
object RcChan {
    const val VIDEO = 1
    const val CTRL = 2
    const val AUDIO = 3
}

/**
 * 传输层抽象：上层只认「往某个通道发一帧 / 从某通道收一帧」，
 * 不再关心底下是三条 TCP 还是一条 UDP。
 *
 * 发送一律走内部队列 + 单线程，调用方可以从编码线程直接调 [send]：
 * socket / datagram 的写不能落在主线程，也不能阻塞编码线程。
 */
abstract class RcLink {

    @Volatile var onFrame: (chan: Int, packet: RcProtocol.Packet) -> Unit = { _, _ -> }
    @Volatile var onState: (String) -> Unit = {}
    @Volatile var onPeer: (connected: Boolean, name: String) -> Unit = { _, _ -> }

    /** 单条通道的连接状态（TCP 下控制、音频各算一条；UDP 下只有主通道）。 */
    @Volatile var onChannel: (chan: Int, connected: Boolean) -> Unit = { _, _ -> }

    private val queue = ArrayDeque<Queued>()
    private val lock = Object()
    @Volatile private var closing = false
    private var sender: Thread? = null

    private class Queued(val chan: Int, val data: ByteArray)

    protected val stopped: Boolean get() = closing

    fun start() {
        closing = false
        sender = Thread({ sendLoop() }, "rc-link-send").apply {
            isDaemon = true
            start()
        }
        openInternal()
    }

    fun stop() {
        closing = true
        synchronized(lock) { lock.notifyAll() }
        closeInternal()
        runCatching { sender?.join(300) }
        sender = null
    }

    /** 编成 RcProtocol 帧（1 + 4 + payload）后入队，满队列丢最旧的。 */
    fun send(chan: Int, type: Int, data: ByteArray, offset: Int = 0, length: Int = data.size) {
        if (closing || length <= 0) return
        val frame = ByteArray(5 + length)
        frame[0] = type.toByte()
        ByteBuffer.wrap(frame, 1, 4).putInt(length)
        System.arraycopy(data, offset, frame, 5, length)
        synchronized(lock) {
            if (queue.size >= MAX_QUEUED) queue.removeFirstOrNull()
            queue.addLast(Queued(chan, frame))
            lock.notifyAll()
        }
    }

    protected fun deliver(chan: Int, type: Int, payload: ByteArray) {
        onFrame(chan, RcProtocol.Packet(type, payload))
    }

    private fun sendLoop() {
        while (true) {
            val item = synchronized(lock) {
                var current: Queued? = queue.removeFirstOrNull()
                while (current == null) {
                    if (closing) return
                    runCatching { lock.wait(200) }
                    current = queue.removeFirstOrNull()
                }
                current
            }
            runCatching { writeFrame(item.chan, item.data) }
                .onFailure { Log.w(TAG, "write failed chan=" + item.chan, it) }
        }
    }

    protected abstract fun openInternal()

    protected abstract fun closeInternal()

    protected abstract fun writeFrame(chan: Int, data: ByteArray)

    private companion object {
        private const val TAG = "RcLink"

        /** 约 130ms 的画面量：队列满就丢最旧的，宁可掉帧也不让编码线程等网络。 */
        private const val MAX_QUEUED = 8
    }
}