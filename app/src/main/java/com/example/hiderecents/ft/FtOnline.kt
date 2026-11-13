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
 * 打开软件（或在文件传输页打开开关）后就常驻一个接收端：HTTP 收文件、UDP 53317 应答扫描，
 * 这样电脑端软件能一直扫到这台手机，收到文件直接弹通知。
 */
object FtOnline {

    const val PREF = "hide_recents_prefs"
    const val KEY_ONLINE = "ft_online"

    /** 在线接收通道的固定端口，避免每次重启换端口导致电脑端找不到。 */
    private const val BASE_PORT = 53318
    private const val UDP_PORT = 53317

    const val CHANNEL_ONLINE = "ft_online"
    const val CHANNEL_RECEIVE = "ft_receive"
    const val ONLINE_NOTIF_ID = 4310
    private const val RECEIVE_NOTIF_BASE = 4400

    /** 文件传输页共享出去的文件：id -> 文件。在线时服务端也直接从这里取。 */
    val shared = linkedMapOf<String, File>()

    /** 当前监听端口，未启动时为 0。 */
    var port: Int = 0
        private set

    /** 收到文件后的回调，页面在前台时用来把记录插进列表。 */
    var onReceived: ((File, String) -> Unit)? = null

    /** 记住发过发现包/带过名字的对方的 IP → 设备名，通知里就不用再露 IP 了。 */
    private val peers = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** 把 IP 翻译成人能看懂的设备名：优先对方自报的名字，其次按浏览器 UA 猜，最后兜底。 */
    fun peerLabel(ip: String, userAgent: String? = null, declared: String? = null): String {
        declared?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        peers[ip]?.let { return it }
        return deskLabel(userAgent) ?: "附近的设备"
    }

    /** 从浏览器 UA 里认出「Windows 电脑 · Edge」这种名字。 */
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
        startUdp(app)
        return port
    }

    @Synchronized
    fun stop() {
        runCatching { server?.stop() }
        server = null
        port = 0
        runCatching { udp?.close() }
        udp = null
        runCatching { udpThread?.interrupt() }
        udpThread = null
    }

    /** 自检：能连上自己就说明接收端还活着（accept 线程可能已经挂了但端口仍在 LISTEN）。 */
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

    private fun startUdp(context: Context) {
        if (udp != null) return
        val socket = try {
            DatagramSocket(UDP_PORT)
        } catch (e: Exception) {
            null
        } ?: return
        udp = socket
        udpThread = Thread {
            val buf = ByteArray(8192)
            while (!Thread.currentThread().isInterrupted) {
                val packet = DatagramPacket(buf, buf.size)
                try {
                    socket.receive(packet)
                } catch (e: Exception) {
                    return@Thread
                }
                val msg = String(packet.data, 0, packet.length)
                // 记住对方自报的名字，收到文件时就能说「来自 哪台设备」而不是 IP
                runCatching {
                    val json = JSONObject(msg)
                    val alias = json.optString("alias", "").ifEmpty { json.optString("deviceName", "") }
                    if (alias.isNotEmpty()) {
                        packet.address?.hostAddress?.let { peers[it] = alias }
                    }
                }
                val reply = JSONObject().apply {
                    put("alias", Build.MODEL)
                    put("port", port)
                    put("protocol", "http")
                    put("version", "2.1")
                    put("deviceModel", Build.MODEL)
                    put("deviceType", "mobile")
                    put("ip", localIpAddress() ?: "")
                    put("online", true)
                }.toString().toByteArray()
                runCatching {
                    socket.send(DatagramPacket(reply, reply.size, packet.address, packet.port))
                }
                // 对方带过来的文件（老协议）这里不处理，只应答发现请求
                if (msg.isNotEmpty()) Unit
            }
        }.apply { isDaemon = true; start() }
    }

    private fun localIpAddress(): String? =
        runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .flatMap { it.inetAddresses.toList() }
                .firstOrNull { !it.isLoopbackAddress && it is Inet4Address }
                ?.hostAddress
        }.getOrNull()

    // ------------------------------------------------------------------ 通知

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = context.getSystemService(NotificationManager::class.java) ?: return

        if (mgr.getNotificationChannel(CHANNEL_ONLINE) == null) {
            val ch = NotificationChannel(
                CHANNEL_ONLINE,
                "文件传输在线",
                NotificationManager.IMPORTANCE_MIN,
            ).apply {
                description = "在线接收开启时，让电脑端一直能扫到这台手机"
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
            }
            mgr.createNotificationChannel(ch)
        }

        if (mgr.getNotificationChannel(CHANNEL_RECEIVE) == null) {
            val ch = NotificationChannel(
                CHANNEL_RECEIVE,
                "收到文件",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "收到电脑发来的文件时在屏幕上方弹出提醒"
                enableVibration(true)
                enableLights(true)
            }
            mgr.createNotificationChannel(ch)
        }
    }

    fun onlineNotification(context: Context): android.app.Notification {
        ensureChannels(context)
        val open = PendingIntent.getActivity(
            context,
            10,
            Intent(context, FileTransferActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val off = PendingIntent.getService(
            context,
            11,
            Intent(context, FtOnlineService::class.java).setAction(FtOnlineService.ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(context, CHANNEL_ONLINE)
            .setSmallIcon(R.drawable.ic_ft_file)
            .setContentTitle("文件传输在线")
            .setContentText("电脑可以发送文件到这台手机 · 端口 $port")
            .setContentIntent(open)
            .addAction(0, "关闭", off)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    /** 收到文件：顶部弹出提醒，点开看文件；打不开就进文件传输页。 */
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
                return newFixedLengthResponse(
                    Response.Status.OK,
                    "application/json",
                    "{\"online\":true,\"port\":$listeningPort}",
                )
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
                    // 直接 POST 原始字节（curl -T / PowerShell -InFile 这类）也认
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

        /** 按 Content-Length 精确读，避免一直读到连接超时（客户端会以为请求失败）。 */
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
                <title>文件传输</title>
                <style>
                  body{background:#0F1214;color:#E9EDEC;font:15px/1.6 -apple-system,system-ui,sans-serif;margin:0;padding:28px}
                  h1{font-size:20px;margin:0 0 6px}
                  p{color:#8A938F;margin:0 0 22px}
                  ul{list-style:none;padding:0;margin:0}
                  li{display:flex;justify-content:space-between;padding:12px 0;border-top:1px solid rgba(255,255,255,.08)}
                  a{color:#8FB3A4;text-decoration:none}
                  span{color:#68716F;font-size:13px}
                </style>
                <h1>系统工具 · 文件传输</h1>
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
