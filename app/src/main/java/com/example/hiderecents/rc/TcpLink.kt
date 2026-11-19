package com.example.hiderecents.rc

import android.util.Log
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 局域网直连：视频 / 控制 / 音频各一条 TCP，和改造前的行为一致。
 *
 * 被控端（HOST）开三个监听，控制端（VIEWER）开三条连接；
 * 两端都只通过 [RcLink] 的 send/onFrame 收发包。
 */
class TcpLink private constructor(
    private val mode: Mode,
    private val host: String,
    private val port: Int,
) : RcLink() {

    enum class Mode { HOST, VIEWER }

    @Volatile private var videoSocket: Socket? = null
    @Volatile private var videoOut: OutputStream? = null
    @Volatile private var ctrlSocket: Socket? = null
    @Volatile private var ctrlOut: OutputStream? = null
    @Volatile private var audioSocket: Socket? = null
    @Volatile private var audioOut: OutputStream? = null

    private var server: ServerSocket? = null
    private var ctrlServer: ServerSocket? = null
    private var audioServer: ServerSocket? = null
    private val listening = AtomicBoolean(false)
    private val writeLock = Any()

    @Volatile var boundPort: Int = port
        private set

    @Volatile var lastError: String? = null
        private set

    override fun openInternal() {
        if (mode == Mode.VIEWER) openViewer() else openHost()
    }

    override fun closeInternal() {
        listening.set(false)
        runCatching { server?.close() }
        runCatching { ctrlServer?.close() }
        runCatching { audioServer?.close() }
        server = null
        ctrlServer = null
        audioServer = null
        dropVideo()
        runCatching { ctrlSocket?.close() }
        ctrlSocket = null
        ctrlOut = null
        runCatching { audioSocket?.close() }
        audioSocket = null
        audioOut = null
    }

    override fun writeFrame(chan: Int, data: ByteArray) {
        val out = when (chan) {
            RcChan.CTRL -> ctrlOut
            RcChan.AUDIO -> audioOut
            else -> videoOut
        } ?: return
        try {
            synchronized(writeLock) {
                out.write(data)
                out.flush()
            }
        } catch (e: java.io.IOException) {
            // 对端已断：清掉这条通道的流，否则每帧都会抛一次 Broken pipe 把日志刷爆
            when (chan) {
                RcChan.CTRL -> ctrlOut = null
                RcChan.AUDIO -> audioOut = null
                else -> videoOut = null
            }
            throw e
        }
    }

    // ---- HOST：被控端 ----

    private fun openHost() {
        val socket = runCatching {
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(port))
            }
        }.getOrElse { e ->
            lastError = "端口 $port 打不开：" + (e.message ?: "")
            onState(lastError!!)
            return
        }
        server = socket
        boundPort = socket.localPort
        listening.set(true)
        Log.i(TAG, "listening on $boundPort")

        Thread({ acceptVideo(socket) }, "rc-tcp-video").start()
        Thread({ acceptCtrl() }, "rc-tcp-ctrl").start()
        Thread({ acceptAudio() }, "rc-tcp-audio").start()
    }

    /**
     * 视频连接同时承载控制方向的帧（改造前就是这个行为）。
     * 读循环阻塞在这条连接上，断开后才回到 accept —— 保持和原来一致。
     */
    private fun acceptVideo(server: ServerSocket) {
        while (listening.get() && !stopped) {
            val accepted = runCatching { server.accept() }.getOrNull() ?: break
            accepted.tcpNoDelay = true
            runCatching { videoSocket?.close() }
            videoSocket = accepted
            videoOut = runCatching { BufferedOutputStream(accepted.getOutputStream()) }.getOrNull()
            val name = accepted.inetAddress?.hostAddress ?: "未知"
            Log.i(TAG, "client accepted $name")
            onPeer(true, name)
            onChannel(RcChan.VIDEO, true)
            readLoop(accepted, RcChan.CTRL)
            dropVideo()
        }
    }

    private fun acceptCtrl() {
        val socket = runCatching {
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(RcProtocol.PORT_CTRL))
            }
        }.getOrElse { e ->
            Log.w(TAG, "ctrl bind failed", e)
            return
        }
        ctrlServer = socket
        Log.i(TAG, "ctrl listening on ${RcProtocol.PORT_CTRL}")
        var current: Socket? = null
        while (listening.get() && !stopped) {
            val accepted = runCatching { socket.accept() }.getOrNull() ?: break
            accepted.tcpNoDelay = true
            // 新连接顶替旧连接：旧连接阻塞在 read 上，不关掉就轮不到新连接。
            runCatching { current?.close() }
            current = accepted
            ctrlSocket = accepted
            ctrlOut = runCatching { BufferedOutputStream(accepted.getOutputStream()) }.getOrNull()
            onChannel(RcChan.CTRL, true)
            Thread({ readLoop(accepted, RcChan.CTRL) }, "rc-tcp-ctrl-read").start()
        }
    }

    private fun acceptAudio() {
        val socket = runCatching {
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(RcProtocol.PORT_AUDIO))
            }
        }.getOrElse { e ->
            Log.w(TAG, "audio bind failed", e)
            return
        }
        audioServer = socket
        Log.i(TAG, "audio listening on ${RcProtocol.PORT_AUDIO}")
        var current: Socket? = null
        while (listening.get() && !stopped) {
            val accepted = runCatching { socket.accept() }.getOrNull() ?: break
            accepted.tcpNoDelay = true
            runCatching { current?.close() }
            current = accepted
            audioSocket = accepted
            audioOut = runCatching { BufferedOutputStream(accepted.getOutputStream()) }.getOrNull()
            onChannel(RcChan.AUDIO, true)
            Log.i(TAG, "audio client connected")
        }
    }

    // ---- VIEWER：控制端 ----

    private fun openViewer() {
        // apply 块里的接收者是 Socket，直接用 port 会被解析成 Socket.getPort()（连接前恒为 0）
        val targetPort = port
        onState("正在连接 $host:$targetPort")
        val video = runCatching {
            Socket().apply {
                tcpNoDelay = true
                connect(InetSocketAddress(host, targetPort), 5000)
            }
        }.getOrElse { e ->
            val reason = when (e) {
                is java.net.ConnectException -> "对方还没有开始共享（端口未开）"
                is java.net.SocketTimeoutException -> "连接超时，对方可能不在同一网络"
                else -> e.message ?: e.javaClass.simpleName
            }
            lastError = "视频连接失败：$reason"
            Log.w(TAG, "connect $host:$targetPort failed", e)
            onState(lastError!!)
            return
        }
        videoSocket = video
        videoOut = runCatching { BufferedOutputStream(video.getOutputStream()) }.getOrNull()
        onPeer(true, host)
        onChannel(RcChan.VIDEO, true)
        onState("已连接，等待画面")
        Thread({ readLoop(video, RcChan.VIDEO) }, "rc-tcp-video-read").start()

        runCatching {
            val ctrl = Socket().apply {
                tcpNoDelay = true
                connect(InetSocketAddress(host, RcProtocol.PORT_CTRL), 5000)
            }
            ctrlSocket = ctrl
            ctrlOut = BufferedOutputStream(ctrl.getOutputStream())
            onChannel(RcChan.CTRL, true)
            Log.i(TAG, "ctrl connected localPort=" + ctrl.localPort)
        }.onFailure { Log.w(TAG, "控制通道建立失败", it) }

        runCatching { connectAudio() }.onFailure { Log.w(TAG, "音频通道建立失败", it) }
    }

    /** 被控端的音频监听可能比视频晚一步起来，连不上要重试而不是静默退出。 */
    private fun connectAudio() {
        var result: Socket? = null
        for (attempt in 1..6) {
            if (stopped) break
            val candidate = Socket()
            candidate.tcpNoDelay = true
            val ok = runCatching {
                candidate.connect(InetSocketAddress(host, RcProtocol.PORT_AUDIO), 2000)
            }.isSuccess
            if (ok) {
                result = candidate
                break
            }
            runCatching { candidate.close() }
            Log.w(TAG, "audio connect attempt $attempt failed")
            runCatching { Thread.sleep(500) }
        }
        val connected = result ?: run {
            onChannel(RcChan.AUDIO, false)
            return
        }
        audioSocket = connected
        onChannel(RcChan.AUDIO, true)
        onState("音频已连接")
        Thread({ readLoop(connected, RcChan.AUDIO) }, "rc-tcp-audio-read").start()
    }

    // ---- 公共 ----

    private fun readLoop(socket: Socket, chan: Int) {
        val input = runCatching {
            DataInputStream(BufferedInputStream(socket.getInputStream(), 64 * 1024))
        }.getOrNull() ?: run {
            runCatching { socket.close() }
            return
        }
        while (!stopped) {
            val packet = runCatching { RcProtocol.read(input) }.getOrNull() ?: break
            // 单帧解析失败不能把读线程连人带进程一起干掉
            runCatching { deliver(chan, packet.type, packet.payload) }
                .onFailure { Log.w(TAG, "frame handler failed chan=$chan", it) }
        }
        runCatching { socket.close() }
        if (chan == RcChan.VIDEO) onPeer(false, "")
        onChannel(chan, false)
    }

    private fun dropVideo() {
        onChannel(RcChan.VIDEO, false)
        runCatching { videoOut?.flush() }
        runCatching { videoSocket?.close() }
        videoSocket = null
        videoOut = null
        onPeer(false, "")
    }

    companion object {
        private const val TAG = "TcpLink"

        fun host(port: Int = RcProtocol.PORT): TcpLink = TcpLink(Mode.HOST, "", port)

        fun viewer(host: String, port: Int = RcProtocol.PORT): TcpLink =
            TcpLink(Mode.VIEWER, host, port)
    }
}