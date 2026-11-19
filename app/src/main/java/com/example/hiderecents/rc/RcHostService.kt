package com.example.hiderecents.rc

import android.app.PendingIntent
import android.app.Service
import androidx.core.app.NotificationCompat
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.example.hiderecents.StatusCenter
import java.net.InetAddress
import java.net.NetworkInterface

/**
 * 被控端前台服务：持有 MediaProjection，采集编码后通过 TCP 推给控制端，
 * 同时接收控制端回传的触控与画质指令。
 *
 * 必须先 startForeground（带 mediaProjection 类型）再 getMediaProjection，
 * 否则 Android 10+ 会拒绝或立刻把投影停掉。
 */
class RcHostService : Service() {

    @Volatile private var link: RcLink? = null
    @Volatile private var lastConfig: ByteArray? = null
    @Volatile private var lastAudioConfig: ByteArray? = null

    private var projection: MediaProjection? = null
    private var encoder: ScreenEncoder? = null
    @Volatile private var stopAdvertise: (() -> Unit)? = null
    private var audioCapture: AudioCapture? = null
    private var appliedFps = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        StatusCenter.ensureChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val code = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                @Suppress("DEPRECATION")
                val data = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
                val width = intent.getIntExtra(EXTRA_WIDTH, 720)
                val height = intent.getIntExtra(EXTRA_HEIGHT, 1280)
                val dpi = intent.getIntExtra(EXTRA_DPI, 320)
                if (code == 0 || data == null) {
                    lastError = "缺少录屏授权"
                    stopSelf()
                    return START_NOT_STICKY
                }
                startSession(code, data, width, height, dpi)
            }

            ACTION_STOP -> {
                teardown()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    /** 被控端转向时重采：VirtualDisplay 尺寸是建时定死的，不跟着转画面就会被压。 */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val enc = encoder ?: return
        val metrics = resources.displayMetrics
        var w = metrics.widthPixels
        var h = metrics.heightPixels
        val longSide = maxOf(w, h)
        if (longSide > MAX_LONG_SIDE) {
            val k = MAX_LONG_SIDE.toFloat() / longSide
            w = (w * k).toInt()
            h = (h * k).toInt()
        }
        runCatching { enc.applySize(w, h) }
            .onFailure { Log.w(TAG, "转向重采失败", it) }
    }

    override fun onDestroy() {
        teardown()
        super.onDestroy()
    }

    /** 重开共享前先放掉上一次的 socket 与编码器：旧监听不关，新监听 bind 必然 EADDRINUSE。 */
    private fun releaseSession() {
        Log.i(TAG, "releaseSession from:", Throwable("caller"))
        runCatching { link?.stop() }
        link = null
        runCatching { audioCapture?.stop() }
        audioCapture = null
        audioConnected = false
        ctrlConnected = false
        runCatching { stopAdvertise?.invoke() }
        stopAdvertise = null
        runCatching { encoder?.release() }
        encoder = null
        lastConfig = null
        lastAudioConfig = null
        runCatching { projection?.stop() }
        projection = null
    }

    private fun startSession(
        resultCode: Int,
        data: Intent,
        width: Int,
        height: Int,
        dpi: Int,
    ) {
        releaseSession()
        // 必须用真实物理分辨率：resources.displayMetrics 不含状态栏和导航栏，
        // 拿它换算会让注入点整体上移（越靠下偏得越多）。
        runCatching {
            val metrics = android.util.DisplayMetrics()
            val display = getSystemService(android.hardware.display.DisplayManager::class.java)
                ?.getDisplay(android.view.Display.DEFAULT_DISPLAY)
            display?.getRealMetrics(metrics)
            if (metrics.widthPixels > 0 && metrics.heightPixels > 0) {
                RcInput.screenWidth = metrics.widthPixels
                RcInput.screenHeight = metrics.heightPixels
            } else {
                RcInput.screenWidth = resources.displayMetrics.widthPixels
                RcInput.screenHeight = resources.displayMetrics.heightPixels
            }
        }
        startForegroundCompat()

        val mgr = getSystemService(MediaProjectionManager::class.java)
        val mp: MediaProjection = runCatching { mgr.getMediaProjection(resultCode, data) }
            .getOrNull()
            ?: run {
                lastError = "取不到 MediaProjection"
                stopSelf()
                return
            }
        projection = mp

        mp.registerCallback(
            object : MediaProjection.Callback() {
                override fun onStop() {
                    Log.i(TAG, "MediaProjection.onStop (system revoked)")
                    lastError = "录屏授权已被系统收回"
                    teardown()
                    stopSelf()
                }
            },
            null,
        )

        val enc = ScreenEncoder(
            projection = mp,
            width = width,
            height = height,
            densityDpi = dpi,
            onConfig = { w, h, sps, pps ->
                // link.send 自己会加帧头，这里只能给 payload
                val payload = RcProtocol.encodeConfigPayload(w, h, sps, pps)
                lastConfig = payload
                link?.send(RcChan.VIDEO, RcProtocol.TYPE_CONFIG, payload)
            },
            onFrame = { buf, offset, length, _ ->
                // 不能在这里直接写 socket：网络一慢就把编码线程堵住，
                // 编码器随即丢帧，表现出来就是糊 + 撕裂 + 整体变慢。
                // 交给 link 的发送队列（内部单线程，队满丢最旧），编码永不等网络。
                link?.send(RcChan.VIDEO, RcProtocol.TYPE_FRAME, buf, offset, length)
            },
        )
        encoder = enc
        runCatching { enc.start() }.onFailure { e ->
            lastError = "编码器启动失败：" + (e.message ?: e.javaClass.simpleName)
            teardown()
            stopSelf()
            return
        }
        usingSoftware = enc.usingSoftwareCodec
        encoderMaxFps = enc.maxFrameRate

        startLink()
        audioCapture = AudioCapture(
            projection = mp,
            onConfig = { csd ->
                lastAudioConfig = csd
                link?.send(RcChan.AUDIO, RcProtocol.TYPE_AUDIO_CFG, csd)
            },
            onData = { data, offset, length ->
                link?.send(RcChan.AUDIO, RcProtocol.TYPE_AUDIO, data, offset, length)
            },
        ).also { if (!it.start()) Log.w(TAG, "audio capture unavailable") }
        stopAdvertise = RcDiscovery.advertise(port, android.os.Build.MODEL ?: "Android")
        isRunning = true
        lastError = null
    }

    /**
     * 远控用自己的通知 id。全应用那条状态通知被保活服务占着同一个 id，
     * 两条前台服务抢同一个 id 会互相顶掉 —— 媒体投影那条前台类型一丢，
     * 系统就会立刻回收录屏授权（日志里表现为 start 后十几毫秒就 onStop）。
     */
    private fun startForegroundCompat() {
        val notification = hostNotification()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun hostNotification(): android.app.Notification {
        StatusCenter.ensureChannel(this)
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, com.example.hiderecents.RemoteControlActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, StatusCenter.CHANNEL_ID)
            .setContentTitle("正在共享屏幕")
            .setContentText("局域网远控进行中，点此回到页面")
            .setSmallIcon(com.example.hiderecents.R.drawable.ic_settings)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    /** 一次会话只建一条 link：旧监听不关时新监听 bind 必然 EADDRINUSE。 */
    private fun startLink() {
        val created: RcLink = TcpLink.host()
        link = created
        created.onFrame = { chan, packet ->
            if (chan == RcChan.CTRL) handleControl(packet)
        }
        created.onPeer = { peerConnected, name ->
            connected = peerConnected
            clientName = name
            if (peerConnected) {
                Log.i(TAG, "peer ready, config=${lastConfig != null} audioCfg=${lastAudioConfig != null}")
                // 新客户端要先拿 SPS/PPS 才能起解码器，再补一个关键帧
                lastConfig?.let { created.send(RcChan.VIDEO, RcProtocol.TYPE_CONFIG, it) }
                encoder?.requestKeyFrame()
            }
        }
        created.onChannel = { chan, isConnected ->
            when (chan) {
                RcChan.CTRL -> ctrlConnected = isConnected
                RcChan.AUDIO -> {
                    audioConnected = isConnected
                    // 音频 cfg 只在采集启动那一刻产生一次，控制端晚连就永远收不到 —— 连上时补发
                    if (isConnected) {
                        lastAudioConfig?.let {
                            created.send(RcChan.AUDIO, RcProtocol.TYPE_AUDIO_CFG, it)
                        }
                    }
                }
            }
        }
        created.start()
        if (created is TcpLink) {
            val error = created.lastError
            if (error != null) {
                lastError = error
                return
            }
            port = created.boundPort
            Log.i(TAG, "link listening on $port")
        } else {
            Log.i(TAG, "relay link started, waiting for room peer")
        }
    }

    

    private fun handleControl(packet: RcProtocol.Packet) {
        when (packet.type) {
            RcProtocol.TYPE_TOUCH -> {
                touchRx++
                val ok = RcInput.handle(packet.payload)
                if (ok) touchOk++
                Log.i(TAG, "touch size=${packet.payload.size} ready=${RcInput.ready} ok=$ok")
            }

            RcProtocol.TYPE_QUALITY -> {
                val bps = RcProtocol.decodeQuality(packet.payload)
                if (bps > 0) feedbackBitrate = encoder?.let {
                    it.setBitrate(bps)
                    bps
                } ?: feedbackBitrate
            }

            RcProtocol.TYPE_OUTPUT -> {
                val out = RcProtocol.decodeOutput(packet.payload)
                val enc = encoder
                if (out != null && enc != null) {
                    enc.setBitrate(out.bitrateBps)
                    feedbackBitrate = out.bitrateBps
                    val current = enc.currentSize
                    val currentLong = maxOf(current.first, current.second)
                    if (currentLong != out.longSide || appliedFps != out.fps) {
                        appliedFps = out.fps
                        enc.applyProfile(out.longSide, out.fps)
                    }
                }
            }
        }
    }

    private fun teardown() {
        Log.i(TAG, "teardown from:", Throwable("caller"))
        isRunning = false
        connected = false
        clientName = ""
        releaseSession()
        runCatching {
            if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE)
            else @Suppress("DEPRECATION") stopForeground(true)
        }
    }

    companion object {
        private const val TAG = "RcHostService"
        private const val MAX_LONG_SIDE = 2560
        private const val NOTIFICATION_ID = 4002

        const val ACTION_START = "com.example.hiderecents.rc.START"
        const val ACTION_STOP = "com.example.hiderecents.rc.STOP"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_WIDTH = "width"
        const val EXTRA_HEIGHT = "height"
        const val EXTRA_DPI = "dpi"

        @Volatile var lanAddress: String = findLanAddress()
            private set
        @Volatile var isRunning: Boolean = false
            private set
        @Volatile var connected: Boolean = false
            private set
        @Volatile var clientName: String = ""
            private set
        @Volatile var usingSoftware: Boolean = false
            private set
        @Volatile var encoderMaxFps: Int = 0
            private set
        @Volatile var touchRx: Int = 0
            private set
        @Volatile var touchOk: Int = 0
            private set
        @Volatile var ctrlConnected: Boolean = false
            private set
        @Volatile var audioConnected: Boolean = false
            private set
        @Volatile var touchReady: Boolean = false
            private set
        @Volatile var feedbackBitrate: Int = 0
            private set
        @Volatile var lastError: String? = null
            private set

        var port: Int = RcProtocol.PORT
            private set

        fun start(
            context: Context,
            resultCode: Int,
            data: Intent,
            width: Int,
            height: Int,
            dpi: Int,
        ) {
            lanAddress = findLanAddress()
            Log.i(TAG, "RC LAN address = $lanAddress")
            val intent = Intent(context, RcHostService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, data)
                putExtra(EXTRA_WIDTH, width)
                putExtra(EXTRA_HEIGHT, height)
                putExtra(EXTRA_DPI, dpi)
                }
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, RcHostService::class.java).apply { action = ACTION_STOP },
            )
        }

        /**
         * 取本机局域网地址。
         *
         * 不能一次流式过滤完再 firstOrNull：某些 ROM 上 `isUp` 会抛 SocketException，
         * 异常会把整条序列中断，结果被 runCatching 吞成空字符串——明明 wlan0 有 IP 也报「未拿到」。
         * 所以逐网卡容错，优先 wlan/eth，跳过 169.254 链路本地地址。
         */
        private fun findLanAddress(): String {
            val candidates = mutableListOf<Pair<String, String>>()
            runCatching {
                val interfaces = NetworkInterface.getNetworkInterfaces() ?: return@runCatching
                while (interfaces.hasMoreElements()) {
                    val nif = interfaces.nextElement() ?: continue
                    runCatching {
                        if (!nif.isUp || nif.isLoopback) return@runCatching
                        val name = nif.name ?: ""
                        val addrs = nif.inetAddresses
                        while (addrs.hasMoreElements()) {
                            val addr = addrs.nextElement() as? InetAddress ?: continue
                            if (addr.isLoopbackAddress) continue
                            if (addr !is java.net.Inet4Address) continue
                            val host = addr.hostAddress ?: continue
                            if (host.startsWith("169.254")) continue
                            candidates.add(name to host)
                        }
                    }
                }
            }

            candidates.firstOrNull {
                it.first.startsWith("wlan") || it.first.startsWith("eth")
            }?.let { return it.second }
            candidates.firstOrNull()?.let { return it.second }

            // 兜底：挑一条到外网的路由，读内核给这个 socket 分配的本地地址（UDP connect 不发包）
            runCatching {
                java.net.DatagramSocket().use { socket ->
                    socket.connect(InetAddress.getByName("8.8.8.8"), 53)
                    val local = socket.localAddress
                    if (local is java.net.Inet4Address && !local.isLoopbackAddress) {
                        return local.hostAddress ?: ""
                    }
                }
            }
            return ""
        }
    }
}