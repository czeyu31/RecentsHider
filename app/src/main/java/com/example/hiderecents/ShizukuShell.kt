package com.example.hiderecents

import android.os.IBinder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.util.Log
import moe.shizuku.server.IShizukuService
import rikka.shizuku.Shizuku

/**
 * 统一的 shell 命令通道。
 *
 * 主路径（保留原有做法）：Shizuku 用户服务 TaskHideService.execCommand，
 * 与隐藏/恢复共用同一个 binder，官方 Shizuku 上最直接。
 *
 * 兜底路径：直连 Shizuku 服务端的 IShizukuService.newProcess。
 * 背景：Shizuku NEXT（moe.shizuku.privileged.api 1.0.1_Next）在 Android 15 上
 * 启动用户服务时崩在 LoadedApk.makeApplicationInner（NullPointerException，
 * logcat 里 ShizukuServiceStarter 报 unable to start service），
 * bindUserService 永远连不上，App 首页一直停在「连接中」。
 * 这类分支的服务端能力是好的（rish 也依赖 newProcess），
 * 所以命令类功能（ADB 命令、应用列表、settings 读写、静默安装）可以走 newProcess 兜底。
 * 任务隐藏/恢复仍然必须用用户服务里的 IActivityTaskManager binder，走不了这条兜底。
 */
object ShizukuShell {

    private const val TAG = "ShizukuShell"

    /** 单条命令的最长等待时间：42MB 的 apk 走管道安装实测 10 秒上下，留足余量 */
    private const val TIMEOUT_MS = 180000L

    @Volatile
    var lastError: String? = null
        private set

    /** 通过用户服务 binder 执行；binder 为 null 时直接返回失败，由调用方决定是否兜底 */
    fun viaBinder(binder: IBinder, command: String): Pair<Boolean, String> {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(ITaskHideService.DESCRIPTOR)
            data.writeString(command)
            binder.transact(ITaskHideService.TRANSACTION_execCommand, data, reply, 0)
            reply.readException()
            val ok = reply.readInt() != 0
            Pair(ok, reply.readString() ?: "")
        } catch (t: Throwable) {
            lastError = t.message
            Pair(false, "")
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    /** 先走用户服务，失败再走 newProcess */
    fun exec(own: IBinder?, command: String): Pair<Boolean, String> {
        val binder = TaskHideBridge.resolve(own)
        if (binder != null) {
            val direct = viaBinder(binder, command)
            if (direct.first) return direct
        }
        return execProcess(command)
    }

    fun exec(command: String): Pair<Boolean, String> = exec(null, command)

    /** 只判断 newProcess 通道是否可用（不依赖用户服务） */
    fun probe(): Boolean {
        if (!permissionGranted()) return false
        val res = execProcess("id")
        return res.first || res.second.contains("uid=")
    }

    fun permissionGranted(): Boolean {
        return try {
            Shizuku.pingBinder() && !Shizuku.isPreV11() &&
                Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED
        } catch (t: Throwable) {
            false
        }
    }

    private fun execProcess(command: String): Pair<Boolean, String> {
        if (!permissionGranted()) return Pair(false, "")
        return try {
            val stub = Shizuku.getBinder() ?: return Pair(false, "")
            val service = IShizukuService.Stub.asInterface(stub) ?: return Pair(false, "")
            val process = service.newProcess(
                arrayOf("/system/bin/sh", "-c", command), null, null
            ) ?: return Pair(false, "")
            val out = StringBuilder()
            val err = StringBuilder()
            val tOut = readIntoThread(process.inputStream, out)
            val tErr = readIntoThread(process.errorStream, err)
            tOut.start()
            tErr.start()
            val finished = try {
                process.waitForTimeout(TIMEOUT_MS, "MILLISECONDS")
            } catch (t: Throwable) {
                false
            }
            if (!finished) {
                runCatching { process.destroy() }
            }
            tOut.join(3000)
            tErr.join(3000)
            val code = runCatching { process.exitValue() }.getOrDefault(-1)
            val text = if (out.isNotBlank()) out.toString().trim() else err.toString().trim()
            lastError = if (finished) null else "timeout"
            Pair(finished && code == 0, text)
        } catch (t: Throwable) {
            lastError = t.message
            Log.w(TAG, "newProcess failed: " + (t.message ?: ""))
            Pair(false, "")
        }
    }

    private fun readIntoThread(stream: ParcelFileDescriptor?, sink: StringBuilder): Thread {
        val thread = Thread {
            runCatching {
                ParcelFileDescriptor.AutoCloseInputStream(stream).use { input ->
                    sink.append(input.bufferedReader().readText())
                }
            }
        }
        thread.isDaemon = true
        return thread
    }
}
