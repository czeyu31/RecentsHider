package com.example.hiderecents

import android.app.*
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import fi.iki.elonen.NanoHTTPD
import org.json.JSONObject
import java.io.*
import java.net.URLConnection
import java.net.*
import java.util.*

class FileServerService : Service() {

    private var httpServer: LocalSendServer? = null
    private var serverPort = 0
    val sharedFiles = mutableMapOf<String, File>()
    val myFingerprint = UUID.randomUUID().toString().replace("-", "")

    companion object {
        const val CHANNEL_ID = "file_server"
        const val NOTIFICATION_ID = 1001
        var instance: FileServerService? = null
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        try {
            createNotificationChannel()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForeground(NOTIFICATION_ID, buildNotification("服务运行中"))
            }
        } catch (_: Exception) {}
        startServer()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        httpServer?.stop()
        instance = null
    }

    fun getPort() = serverPort

    private fun startServer() {
        try {
            val socket = ServerSocket(0)
            serverPort = socket.localPort
            socket.close()
            httpServer = LocalSendServer(serverPort)
            httpServer?.start()
        } catch (_: Exception) {}
    }

    fun addFile(id: String, file: File) {
        sharedFiles[id] = file
        updateNotification("正在分享 ${sharedFiles.size} 个文件")
    }

    fun removeFile(id: String) {
        sharedFiles.remove(id)
        if (sharedFiles.isEmpty()) {
            stopSelf()
        } else {
            updateNotification("正在分享 ${sharedFiles.size} 个文件")
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "文件中转服务", NotificationManager.IMPORTANCE_LOW)
            channel.description = "保持文件中转服务运行"
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val intent = Intent(this, FileTransferActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("System Tool")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_storage)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun getDeviceIpAddress(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val intf = interfaces.nextElement()
                if (intf.isLoopback || !intf.isUp) continue
                val addrs = intf.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        return addr.hostAddress ?: ""
                    }
                }
            }
        } catch (_: Exception) {}
        return ""
    }

    private fun formatSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> String.format("%.1f KB", bytes / 1024.0)
            else -> String.format("%.1f MB", bytes / 1048576.0)
        }
    }

    inner class LocalSendServer(port: Int) : NanoHTTPD("0.0.0.0", port) {
        override fun serve(session: IHTTPSession): Response {
            val uri = session.uri
            val method = session.method

            when {
                uri == "/api/localsend/v2/info" && method == Method.GET -> {
                    val info = JSONObject().apply {
                        put("alias", android.os.Build.MODEL)
                        put("fingerprint", myFingerprint)
                        put("port", serverPort)
                        put("protocol", "http")
                        put("version", "2.1")
                        put("deviceModel", android.os.Build.MODEL)
                        put("deviceType", "mobile")
                        put("download", true)
                    }
                    return newFixedLengthResponse(Response.Status.OK, "application/json", info.toString())
                }

                uri == "/api/localsend/v2/prepare-upload" && method == Method.POST -> {
                    val body = HashMap<String, String>()
                    session.parseBody(body)
                    val sessionId = UUID.randomUUID().toString()
                    return newFixedLengthResponse(Response.Status.OK, "application/json", JSONObject().apply { put("sessionId", sessionId) }.toString())
                }

                uri == "/api/localsend/v2/upload" && method == Method.POST -> {
                    val body = HashMap<String, String>()
                    session.parseBody(body)
                    val content = body["postData"]
                    if (content != null) {
                        val file = File(getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS), "received_${System.currentTimeMillis()}")
                        file.writeText(content)
                    }
                    return newFixedLengthResponse(Response.Status.OK, "application/json", "{}")
                }

                else -> {
                    val fileId = uri.removePrefix("/")

                    if (fileId.isEmpty()) {
                        val html = buildString {
                            append("<!DOCTYPE html><html><head><meta charset='utf-8'><meta name='viewport' content='width=device-width,initial-scale=1'>")
                            append("<title>System Tool - 文件中转</title>")
                            append("<style>body{background:#131313;color:#e5e2e1;font-family:system-ui;padding:20px;max-width:600px;margin:0 auto}")
                            append("h1{color:#b0c6ff;font-size:20px}a{color:#b0c6ff;text-decoration:none;display:block;padding:12px;background:#2a2a2a;border-radius:8px;margin:8px 0}")
                            append("a:hover{background:#353535}.info{color:#c2c6d7;font-size:12px;margin-top:16px}</style></head><body>")
                            append("<h1>System Tool - 文件中转</h1>")
                            if (sharedFiles.isEmpty()) {
                                append("<p>暂无共享文件</p>")
                            } else {
                                for ((id, file) in sharedFiles) {
                                    append("<a href='/$id'>${file.name} (${formatSize(file.length())})</a>")
                                }
                            }
                            append("<p class='info'>由 System Tool 提供</p></body></html>")
                        }
                        return newFixedLengthResponse(Response.Status.OK, "text/html", html)
                    }

                    val file = sharedFiles[fileId]
                    return if (file != null && file.exists()) {
                        val mime = try { URLConnection.guessContentTypeFromName(file.name) ?: "application/octet-stream" } catch (_: Exception) { "application/octet-stream" }
                        newFixedLengthResponse(Response.Status.OK, mime, FileInputStream(file), file.length())
                    } else {
                        val html = "<!DOCTYPE html><html><head><meta charset='utf-8'></head><body style='background:#131313;color:#e5e2e1;font-family:system-ui;padding:20px'><h2>文件不存在</h2><a href='/' style='color:#b0c6ff'>返回</a></body></html>"
                        newFixedLengthResponse(Response.Status.NOT_FOUND, "text/html", html)
                    }
                }
            }
        }
    }
}
