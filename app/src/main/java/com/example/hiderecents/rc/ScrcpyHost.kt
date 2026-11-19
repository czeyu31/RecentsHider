package com.example.hiderecents.rc

import android.content.Context
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.util.Log
import com.example.hiderecents.ShizukuShell
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * 增强路径：以 shell 身份运行 scrcpy-server。
 *
 * 采集、编码参数、音频、事件级注入全部交给 scrcpy；App 只负责
 * 把 abstract 套接字拿过来读写。App 自己连不了这个命名空间，
 * 所以走 TaskHideService 代连并回传 fd。
 */
object ScrcpyHost {

    private const val TAG = "ScrcpyHost"
    private const val REMOTE_JAR = "/data/local/tmp/scrcpy-server.jar"
    private const val LOG_FILE = "/data/local/tmp/scrcpy.log"
    private const val SOCKET_NAME = "scrcpy"

    /** 服务端版本号必须与 jar 内 Server 期望的第一个参数一致。 */
    private const val SERVER_VERSION = "2.4"

    /** 把内置的服务端推到 /data/local/tmp；已推过且大小一致就跳过。 */
    fun ensureServer(context: Context, binder: IBinder): Boolean {
        val staged = runCatching {
            val dir = context.getExternalFilesDir(null) ?: context.filesDir
            val f = File(dir, "scrcpy-server.jar")
            context.assets.open("scrcpy-server.jar").use { input ->
                FileOutputStream(f).use { output -> input.copyTo(output) }
            }
            f
        }.getOrNull() ?: run {
            Log.w(TAG, "内置服务端解包失败")
            return false
        }

        val (ok, _) = ShizukuShell.exec(
            binder,
            "cp " + staged.absolutePath + " " + REMOTE_JAR + " && chmod 644 " + REMOTE_JAR,
        )
        if (!ok) Log.w(TAG, "推送服务端失败")
        return ok
    }

    /**
     * 启动服务端。必须在后台跑：它会一直运行到会话结束。
     * 参数用 scrcpy 自己的名字，画质档位直接映射过去。
     */
    fun startServer(
        binder: IBinder,
        maxSize: Int,
        maxFps: Int,
        videoBitRate: Int,
        withAudio: Boolean,
    ): Boolean {
        val audio = if (withAudio) "true" else "false"
        val cmd = buildString {
            append("CLASSPATH=").append(REMOTE_JAR)
            append(" app_process /system/bin com.genymobile.scrcpy.Server ").append(SERVER_VERSION)
            append(" log_level=info")
            append(" max_size=").append(maxSize)
            append(" max_fps=").append(maxFps)
            append(" video_bit_rate=").append(videoBitRate)
            append(" audio=").append(audio)
            append(" control=true")
            append(" tunnel_forward=true")
            append(" cleanup=false")
            append(" >").append(LOG_FILE).append(" 2>&1 &")
        }
        Log.i(TAG, "launch: $cmd")
        val (ok, out) = ShizukuShell.exec(binder, cmd)
        if (!ok) Log.w(TAG, "启动服务端失败: $out")
        return ok
    }

    /** 读服务端的启动日志，参数不对时它会立刻报错退出。 */
    fun readServerLog(binder: IBinder): String {
        val (_, out) = ShizukuShell.exec(binder, "cat " + LOG_FILE + " 2>/dev/null | head -40")
        return out
    }

    /** 连上服务端的 abstract 套接字，拿到可读写的 fd。 */
    fun openSocket(binder: IBinder, retries: Int = 1, delayMs: Long = 400): ParcelFileDescriptor? {
        repeat(retries) {
            val pfd = ShizukuShell.openAbstractSocket(binder, SOCKET_NAME)
            if (pfd != null) return pfd
            runCatching { Thread.sleep(delayMs) }
        }
        Log.w(TAG, "abstract socket 连不上")
        return null
    }

    /**
     * 读视频头并把原始字节打出来。
     * scrcpy 各版本的头部布局有差异，先看清楚再解析，不凭记忆写死。
     */
    fun probeHeader(pfd: ParcelFileDescriptor): ByteArray? {
        val input = FileInputStream(pfd.fileDescriptor)
        val buf = ByteArray(96)
        val head = ByteArray(0)
        var total = 0
        while (total < buf.size) {
            val n = input.read(buf, total, buf.size - total)
            if (n <= 0) break
            total += n
        }
        if (total <= 0) return null
        val hex = StringBuilder()
        for (i in 0 until minOf(total, 96)) {
            hex.append(String.format("%02x", buf[i]))
            if (i % 16 == 15) hex.append('\n')
        }
        Log.i(TAG, "header($total bytes):\n$hex")
        return buf.copyOf(total)
    }
}