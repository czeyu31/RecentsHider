package com.example.hiderecents.ft

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import com.example.hiderecents.AppLogger
import com.example.hiderecents.FileTransferActivity
import com.example.hiderecents.R
import fi.iki.elonen.NanoHTTPD
import org.json.JSONObject
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.URLDecoder

/**
 * 文件传输的「在线接收」通道。
 *
 * 打开软件（或在文件传输页打开开关）后就常驻一个接收端：HTTP 收文件、UDP 53317 应答扫描。
 * 这样电脑端软件能一直扫到这台手机，收到文件直接弹通知。
 */
object FtOnline {

    const val PREF = "hide_recents_prefs"
    const val KEY_ONLINE = "ft_online"

    /** 在线接收通道的固定端口，避免每次重启换端口导致电脑端找不到。*/
    private const val BASE_PORT = 53318
    private const val UDP_PORT = 53317

    /** 主动播报间隔：对方扫描窗口通常 3 秒，2 秒一喊保证一定撞上。*/
    private const val ANNOUNCE_INTERVAL_MS = 2000L
    // 每 60 次播报（约 2 分钟）重建一次监听 socket，防止旧 socket 半死不活
    private const val RECYCLE_EVERY_TICKS = 60

    const val CHANNEL_ONLINE = "ft_online"
    const val CHANNEL_RECEIVE = "ft_receive"
    private const val RECEIVE_NOTIF_BASE = 4400

    /** 文件传输页共享出去的文件：id -> 文件。在线时服务端也直接从这里取。*/
    val shared = linkedMapOf<String, File>()

    /** 当前监听端口，未启动时为 0。*/
    var port: Int = 0
        private set

    /** 收到文件后的回调，页面在前台时用来把记录插进列表。*/
    var onReceived: ((File, String) -> Unit)? = null

    /** 记住发过发现包、带过名字的对方的 IP → 设备名，通知里就不用再露 IP 了。*/
    private val peers = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** 把 IP 翻译成人能看懂的设备名：优先对方自报的名字，其次按浏览器 UA 猜，最后兜底。*/
    fun peerLabel(ip: String, userAgent: String? = null, declared: String? = null): String {
        declared?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        peers[ip]?.let { return it }
        return deskLabel(userAgent) ?: "附近的设备"
    }

    /** 从浏览器 UA 里认出「Windows 电脑 · Edge」这种名字。*/
    private fun deskLabel(ua: String?): String? {
        if (ua.isNullOrBlank()) return null
        val os = when {
            ua.contains("Windows", ignoreCase = true) -> "Windows 电脑"
            ua.contains("Macintosh", ignoreCase = true) || ua.contains("Mac OS", ignoreCase = true) -> "Mac"
            ua.contains("iPhone", ignoreCase = true) || ua.contains("iPad", ignoreCase = true) -> "iPhone"
            ua.contains("Android", ignoreCase = true) -> "Android 手机"
            ua.contains("Linux", ignoreCase = true) -> "Linux 电脑"
            else -> null
        } ?: return null
        val browser = when {
            ua.contains("Edg/", ignoreCase = true) -> "Edge"
            ua.contains("Chrome/", ignoreCase = true) -> "Chrome"
            ua.contains("Firefox/", ignoreCase = true) -> "Firefox"
            ua.contains("Safari/", ignoreCase = true) -> "Safari"
            else -> null
        }
        return if (browser == null) os else "$os · $browser"
    }

    private var server: Server? = null
    private var udp: DatagramSocket? = null
    private var udpThread: Thread? = null
    private var ctx: Context? = null
    private var receiveSeq = 0

    fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun isOn(context: Context): Boolean = prefs(context).getBoolean(KEY_ONLINE, false)

    fun setOn(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(KEY_ONLINE, on).apply()
    }

    fun downloadDir(): File {
        val public = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        return File(public, "系统工具/文件传输")
    }

    @Synchronized
    fun start(context: Context): Int {
        val app = context.applicationContext
        ctx = app
        if (server != null) return port

        val dir = downloadDir()
        if (!dir.exists()) dir.mkdirs()

        val started = tryStart(app, dir, BASE_PORT) ?: tryStart(app, dir, 0)
        if (started == null) return 0

        server = started
        port = started.listeningPort
        acquireLocks(app)
        startUdp(app)
        return port
    }

    @Synchronized
    fun stop() {
        runCatching { announceSocket?.close() }
        announceSocket = null
        runCatching { announceThread?.interrupt() }
        announceThread = null
        runCatching { server?.stop() }
        server = null
        port = 0
        runCatching { udp?.close() }
        udp = null
        runCatching { udpThread?.interrupt() }
        udpThread = null
        egressBlocked = false
        releaseLocks()
    }

    // ------------------------------------------------------------------ Wi-Fi 锁
    private var wifiLock: android.net.wifi.WifiManager.WifiLock? = null
    private var multicastLock: android.net.wifi.WifiManager.MulticastLock? = null

    /**
     * 应用退到后台后，Wi-Fi 会进入省电模式，**广播包就直接被网卡丢掉**。
     * 于是另一台设备扫不到这台手机（前台时又能扫到——之前就是这个不对称现象）。
     * 常驻一把高性能 Wi-Fi 锁 + 组播锁，后台也照收广播。
     */
    private fun acquireLocks(context: Context) {
        runCatching {
            val wm = context.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
                ?: return
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                android.net.wifi.WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            } else {
                @Suppress("DEPRECATION")
                android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF
            }
            val wifi = wifiLock ?: wm.createWifiLock(mode, "systemtool:ft-online").also { wifiLock = it }
            if (!wifi.isHeld) wifi.acquire()
            val mc = multicastLock ?: wm.createMulticastLock("systemtool:ft-online").also {
                it.setReferenceCounted(false)
                multicastLock = it
            }
            if (!mc.isHeld) mc.acquire()
        }
    }

    private fun releaseLocks() {
        runCatching { wifiLock?.takeIf { it.isHeld }?.release() }
        runCatching { multicastLock?.takeIf { it.isHeld }?.release() }
        wifiLock = null
        multicastLock = null
    }

    /** 自检：能连上自己就说明接收端还活着（accept 线程可能已经挂了但端口仍在 LISTEN）。*/
    fun isAlive(): Boolean {
        val p = port
        if (p <= 0) return false
        return runCatching {
            java.net.Socket().use { s ->
                s.connect(java.net.InetSocketAddress("127.0.0.1", p), 1500)
                s.soTimeout = 1500
                s.getOutputStream().write("GET /ping HTTP/1.0\r\n\r\n".toByteArray())
                s.getOutputStream().flush()
                val buf = ByteArray(64)
                s.getInputStream().read(buf) > 0
            }
        }.getOrDefault(false)
    }

    private fun tryStart(context: Context, dir: File, wantedPort: Int): Server? = try {
        val s = Server(context, dir, wantedPort)
        s.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
        s
    } catch (e: Exception) {
        null
    }

    // ------------------------------------------------------------------ UDP 发现

    private var announceThread: Thread? = null

    /** 正在发包的 socket，卡住时由自愈线程从外面关掉，把阻塞的 send 打断。*/
    @Volatile
    private var announceSocket: DatagramSocket? = null

    /** 上一次播报成功发完的时刻。播报卡住时这个值不再前进，判定被系统限流。*/
    @Volatile
    private var lastAnnounceOkAt: Long = System.currentTimeMillis()

    /** 是否疑似被系统限制后台联网（HyperOS 的「智能限制后台运行」就会这样）。*/
    @Volatile
    var egressBlocked: Boolean = false
        private set

    private var blockedListener: ((Boolean) -> Unit)? = null

    fun setOnEgressBlocked(listener: ((Boolean) -> Unit)?) {
        blockedListener = listener
        runCatching { listener?.invoke(egressBlocked) }
    }

    /** 播报超过 15 秒没有成功发完，说明 send 被内核卡住了。*/
    fun isAnnounceStalled(): Boolean =
        port > 0 && System.currentTimeMillis() - lastAnnounceOkAt > 15_000L

    /** UDP 应答 socket 是否还活着（系统掐过一次联网之后，旧 socket 常常已经废了）。*/
    fun isUdpAlive(): Boolean = udp?.isClosed == false && udpThread?.isAlive == true

    /** 重建 UDP 应答 socket：卡死的 socket 会一直堵在 receive/send 里，只能换掉。*/
    @Synchronized
    fun restartUdp(context: Context) {
        runCatching { udp?.close() }
        udp = null
        runCatching { udpThread?.interrupt() }
        udpThread = null
        if (port > 0) startUdp(context.applicationContext)
    }

    /**
     * 被限流后的自愈：关掉卡住的 socket、重建播报线程和应答 socket。
     *
     * 系统级的联网限制没法从应用里解除（只能让用户把「省电策略」改成无限制），
     * 但把卡死的 socket 换掉至少能在限制解除后立刻恢复，不用等用户手动重启应用。
     */
    fun recoverFromBlock(context: Context) {
        AppLogger.i("FtOnline: announce stalled, rebuilding sockets (egress blocked)")
        runCatching { announceSocket?.close() }
        announceSocket = null
        runCatching { announceThread?.interrupt() }
        announceThread = null
        lastAnnounceOkAt = System.currentTimeMillis()
        if (!egressBlocked) {
            egressBlocked = true
            runCatching { blockedListener?.invoke(true) }
        }
        restartUdp(context.applicationContext)
        startAnnounce()
    }

    private fun markAnnounceOk() {
        lastAnnounceOkAt = System.currentTimeMillis()
        if (egressBlocked) {
            egressBlocked = false
            runCatching { blockedListener?.invoke(false) }
        }
    }

    private fun startAnnounce() {
        if (announceThread?.isAlive == true) return
        announceThread = Thread {
            var ticks = 0
            while (!Thread.currentThread().isInterrupted) {
                runCatching { sendAnnounce() }
                ticks++
                // 长期挂在后台时定期重建监听 socket：被系统掐过一次联网之后，
                // 旧 socket 常常还「在」但发不出包（tx_queue 只涨不降），重建最省事。
                if (ticks % RECYCLE_EVERY_TICKS == 0) {
                    val c = ctx
                    if (c != null) runCatching { restartUdp(c) }
                }
                runCatching { Thread.sleep(ANNOUNCE_INTERVAL_MS) }
            }
        }.apply { isDaemon = true; start() }
    }

    private fun sendAnnounce() {
        if (port <= 0) {
            markAnnounceOk()
            return
        }
        val data = presenceJson().toByteArray()
        val targets = ArrayList<String>()
        targets += "255.255.255.255"
        localIpAddress()?.let { ip ->
            val prefix = ip.substringBeforeLast('.', "")
            if (prefix.isNotEmpty()) targets += "$prefix.255"
        }
        // 播报也要用 IPv4 socket，双栈 socket 的 IPv4 广播在部分 ROM 上会被丢掉。
        val sock = runCatching {
            java.nio.channels.DatagramChannel.open(java.net.StandardProtocolFamily.INET).socket().apply {
                broadcast = true
                bind(java.net.InetSocketAddress(java.net.InetAddress.getByName("0.0.0.0"), 0))
            }
        }.getOrElse { DatagramSocket().apply { broadcast = true } }
        announceSocket = sock
        var sent = 0
        try {
            targets.forEach { host ->
                val ok = runCatching {
                    sock.send(
                        DatagramPacket(data, data.size, java.net.InetAddress.getByName(host), UDP_PORT),
                    )
                }.isSuccess
                if (ok) sent++
            }
        } finally {
            announceSocket = null
            runCatching { sock.close() }
        }
        // 一条都没发出去就当这次播报失败，交给 watchdog 判定「被限制」。
        if (sent > 0) markAnnounceOk()
    }

    /**
     * 必须显式开一个 IPv4 socket。
     *
     * 默认的 `DatagramSocket(port)` 在 Android 上是 AF_INET6 双栈 socket，IPv4 的 **广播包**
     * 不会投递给它（单播能收到，所以 PC 单播探测一直是通的，别的手机广播扫描却永远没人应答）。
     */
    private fun bindUdpSocket(): DatagramSocket? {
        for (attempt in 1..4) {
            try {
                val channel = java.nio.channels.DatagramChannel.open(java.net.StandardProtocolFamily.INET)
                val s = channel.socket()
                s.reuseAddress = true
                s.broadcast = true
                s.bind(java.net.InetSocketAddress(java.net.InetAddress.getByName("0.0.0.0"), UDP_PORT))
                return s
            } catch (e: Exception) {
                AppLogger.e("FtOnline: bind udp 53317 failed #$attempt", e)
                runCatching { Thread.sleep(200) }
            }
        }
        return null
    }

    private fun startUdp(context: Context) {
        if (udp != null) return
        val bound = bindUdpSocket()
        if (bound == null) {
            AppLogger.e("FtOnline: no udp listener on 53317, peers will only find us by tcp scan")
            return
        }
        udp = bound
        udpThread = Thread {
            val buf = ByteArray(8192)
            var socket: DatagramSocket? = bound
            while (!Thread.currentThread().isInterrupted) {
                val cur = socket ?: return@Thread
                val packet = DatagramPacket(buf, buf.size)
                try {
                    cur.receive(packet)
                } catch (e: Exception) {
                    if (Thread.currentThread().isInterrupted) return@Thread
                    // 网卡切换、系统回收 socket 都会抛在这里：重建一个接着等，别让应答线程悄悄死掉
                    runCatching { cur.close() }
                    runCatching { Thread.sleep(500) }
                    socket = bindUdpSocket()
                    udp = socket
                    continue
                }
                val msg = String(packet.data, 0, packet.length)
                // 记住对方自报的名字，收到文件时就能说「来自哪台设备」而不是 IP
                runCatching {
                    val json = JSONObject(msg)
                    val alias = json.optString("alias", "").ifEmpty { json.optString("deviceName", "") }
                    if (alias.isNotEmpty()) {
                        packet.address?.hostAddress?.let { peers[it] = alias }
                    }
                }
                val reply = presenceJson().toByteArray()
                runCatching {
                    cur.send(DatagramPacket(reply, reply.size, packet.address, packet.port))
                }
            }
        }.apply { isDaemon = true; start() }

        startAnnounce()
    }

    /** 本机在局域网里的「名片」：发现请求的应答和主动播报用的是同一份。*/
    private fun presenceJson(): String = JSONObject().apply {
        put("alias", Build.MODEL)
        put("port", port)
        put("protocol", "http")
        put("version", "2.1")
        put("deviceModel", Build.MODEL)
        put("deviceType", "mobile")
        put("ip", localIpAddress() ?: "")
        put("online", true)
        put("app", "System Tool")
        put("announce", true)
    }.toString()

    /**
     * 局域网 IP：优先无线网卡，跳过蜂窝、隧道等接口。
     *
     * 双卡手机插着移动数据时默认路由常常落在 ccmni/rmnet 上，直接取「第一个非回环地址」
     * 会拿到 10.x/32，广播和扫描就全跑偏了。
     */
    fun lanIp(): String? = runCatching {
        var best: String? = null
        var bestScore = -1
        val all = java.net.NetworkInterface.getNetworkInterfaces() ?: return@runCatching null
        for (intf in java.util.Collections.list(all)) {
            val up = runCatching { intf.isUp }.getOrDefault(false)
            if (!up || intf.isLoopback) continue
            val name = intf.name.lowercase()
            if (SKIP_IFACE.matches(name)) continue
            for (addr in intf.inetAddresses.toList()) {
                if (addr.isLoopbackAddress || addr !is Inet4Address) continue
                val ip = addr.hostAddress ?: continue
                val score = when {
                    name.startsWith("wlan") -> 3
                    name.startsWith("ap") || name.startsWith("swlan") || name.startsWith("eth") -> 2
                    ip.startsWith("192.168.") -> 1
                    ip.startsWith("10.") || ip.startsWith("172.") -> 0
                    else -> -1
                }
                if (score > bestScore) {
                    bestScore = score
                    best = ip
                }
            }
        }
        best
    }.getOrNull()

    /** 旧名字，保留给老调用点。*/
    fun localIpAddress(): String? = lanIp()

    private val SKIP_IFACE = Regex("^(ccmni|rmnet|pdp|clat|dummy|tun|ipsec|radio)")

    // ------------------------------------------------------------------ 通知

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = context.getSystemService(NotificationManager::class.java) ?: return

        // 「文件传输在线」那条常驻通知已经并进状态通知，老渠道清掉。
        runCatching { mgr.deleteNotificationChannel(CHANNEL_ONLINE) }

        if (mgr.getNotificationChannel(CHANNEL_RECEIVE) == null) {
            val ch = NotificationChannel(
                CHANNEL_RECEIVE,
                "收到文件",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "收到其他设备发来的文件时在屏幕上方弹出提醒"
                enableVibration(true)
                enableLights(true)
            }
            mgr.createNotificationChannel(ch)
        }
    }

    /** 收到文件：顶部弹出提醒，点开看文件；打不开就进文件传输页。*/
    fun notifyReceived(context: Context, file: File, from: String) {
        ensureChannels(context)
        val mgr = context.getSystemService(NotificationManager::class.java) ?: return
        val openFile = viewFileIntent(context, file)
        val intent = openFile ?: Intent(context, FileTransferActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
        val pi = PendingIntent.getActivity(
            context,
            RECEIVE_NOTIF_BASE + (receiveSeq++ % 50),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notif = NotificationCompat.Builder(context, CHANNEL_RECEIVE)
            .setSmallIcon(R.drawable.ic_ft_file)
            .setContentTitle("收到文件：" + file.name)
            .setContentText(if (from.isNotEmpty()) "来自 $from · 点击打开" else "点击打开")
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setStyle(NotificationCompat.BigTextStyle().bigText(file.absolutePath))
            .build()
        runCatching { mgr.notify(RECEIVE_NOTIF_BASE + (receiveSeq % 50), notif) }
    }

    private fun viewFileIntent(context: Context, file: File): Intent? {
        if (!file.exists()) return null
        val uri: Uri = runCatching {
            FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
        }.getOrNull() ?: return null
        val mime = android.webkit.MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(file.extension.lowercase()) ?: "*/*"
        val view = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val resolved = context.packageManager.resolveActivity(view, 0)
        return if (resolved != null) view else null
    }

    // ------------------------------------------------------------------ HTTP 服务端
    private class Server(
        private val appContext: Context,
        private val dir: File,
        port: Int,
    ) : NanoHTTPD("0.0.0.0", port) {

        override fun serve(session: IHTTPSession): Response {
            if (session.method == Method.POST) return handleUpload(session)

            val uri = session.uri.removePrefix("/")
            if (uri.isEmpty() || uri == "index.html") {
                return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", page())
            }
            if (uri == "ping") {
                // 对方扫同网段时用这个认设备：既要能认出是 System Tool，也要拿到设备名
                val body = "{\"online\":true,\"port\":$listeningPort,\"app\":\"System Tool\"," +
                    "\"alias\":\"${Build.MODEL}\",\"deviceModel\":\"${Build.MODEL}\"}"
                return newFixedLengthResponse(Response.Status.OK, "application/json", body)
            }
            if (uri.startsWith("f/")) {
                val name = Uri.decode(uri.removePrefix("f/"))
                val received = File(dir, name)
                if (!received.exists()) {
                    return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "not found")
                }
                return streamFile(received)
            }
            val file = shared[uri]
            if (file != null && file.exists()) {
                val mime = mimeOf(file.name)
                return runCatching {
                    newFixedLengthResponse(Response.Status.OK, mime, file.inputStream(), file.length())
                }.getOrElse {
                    newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "read error")
                }
            }
            return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "not found")
        }

        private fun handleUpload(session: IHTTPSession): Response {
            var fileName = "received_" + System.currentTimeMillis()
            val encoded = session.headers["x-file-name"]
            if (!encoded.isNullOrEmpty()) {
                fileName = runCatching { URLDecoder.decode(encoded, "UTF-8") }.getOrDefault(fileName)
            }
            return try {
                val dest = uniqueFile(dir, fileName)
                val contentType = session.headers["content-type"] ?: ""
                if (!contentType.startsWith("multipart/form-data")) {
                    // 直接 POST 原始字节（curl -T / PowerShell -InFile 这类）也支持。
                    // 注意：不能关闭 session 的 inputStream——关掉会把连接一起关掉，响应就发不回去了
                    val limit = session.headers["content-length"]?.toLongOrNull() ?: -1L
                    dest.outputStream().use { output ->
                        copyExactly(session.inputStream, output, limit)
                    }
                } else {
                    val body = HashMap<String, String>()
                    session.parseBody(body)
                    val temp = body["file"] ?: return fail("no file")
                    File(temp).copyTo(dest, overwrite = true)
                    File(temp).delete()
                }
                val from = peerLabel(
                    session.remoteIpAddress ?: "",
                    session.headers["user-agent"],
                    session.headers["x-device-name"],
                )
                onReceived?.invoke(dest, from)
                notifyReceived(appContext, dest, from)
                newFixedLengthResponse(
                    Response.Status.OK,
                    "application/json",
                    "{\"status\":\"ok\",\"file\":\"${dest.name}\"}",
                )
            } catch (e: Exception) {
                fail(e.message ?: "upload failed")
            }
        }

        /** 按 Content-Length 精确读，避免一直读到连接超时（客户端会以为请求失败）。*/
        private fun copyExactly(input: java.io.InputStream, output: java.io.OutputStream, limit: Long) {
            val buf = ByteArray(16 * 1024)
            var remaining = limit
            while (true) {
                val want = when {
                    remaining < 0 -> buf.size
                    remaining == 0L -> return
                    remaining < buf.size -> remaining.toInt()
                    else -> buf.size
                }
                val n = input.read(buf, 0, want)
                if (n <= 0) return
                output.write(buf, 0, n)
                if (remaining > 0) remaining -= n
            }
        }

        private fun streamFile(file: File): Response = runCatching {
            newFixedLengthResponse(Response.Status.OK, mimeOf(file.name), file.inputStream(), file.length())
        }.getOrElse {
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "read error")
        }

        private fun mimeOf(name: String): String =
            android.webkit.MimeTypeMap.getSingleton()
                .getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())
                ?: "application/octet-stream"

        private fun fail(msg: String): Response =
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", msg)

        private fun page(): String {
            val received = dir.listFiles()?.filter { it.isFile }?.sortedByDescending { it.lastModified() }
                ?: emptyList()
            val rows = received.take(30).joinToString("") { f ->
                "<li><a href=\"/f/${f.name}\">${f.name}</a><span>${f.length() / 1024} KB</span></li>"
            }
            return """
                <!doctype html><html lang="zh"><meta charset="utf-8">
                <meta name="viewport" content="width=device-width,initial-scale=1">
                <title>System Tool · 文件传输</title>
                <style>
                  body{background:#0F1214;color:#E9EDEC;font:15px/1.6 -apple-system,system-ui,sans-serif;margin:0;padding:28px}
                  h1{font-size:20px;margin:0 0 6px}
                  p{color:#8A938F;margin:0 0 22px}
                  ul{list-style:none;padding:0;margin:0}
                  li{display:flex;justify-content:space-between;padding:12px 0;border-top:1px solid rgba(255,255,255,.08)}
                  a{color:#8FB3A4;text-decoration:none}
                  span{color:#68716F;font-size:13px}
                </style>
                <h1>System Tool · 文件传输</h1>
                <p>在线接收中，直接把文件 POST 到这个地址即可</p>
                <ul>$rows</ul>
                </html>
            """.trimIndent()
        }
    }

    private fun uniqueFile(dir: File, name: String): File {
        var target = File(dir, name)
        if (!target.exists()) return target
        val base = name.substringBeforeLast('.', name)
        val ext = name.substringAfterLast('.', "")
        var i = 1
        while (target.exists() && i < 500) {
            target = File(dir, if (ext.isEmpty()) "$base ($i)" else "$base ($i).$ext")
            i++
        }
        return target
    }
}
