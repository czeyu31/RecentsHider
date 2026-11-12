package com.example.hiderecents

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.os.IBinder
import android.os.Parcel
import java.io.File

/**
 * 静默安装 APK：文件传输收到 APK 与「APK 安装器」页共用这一份逻辑。
 *
 * 实测（设备日志 ApkDetail cmd，同一台机器同一个 apk）：
 *   pm install -r -g <path>            => success=false，输出为空
 *   cat <path> | pm install -S <size>  => success=true，输出 Success
 * 所以把管道形式放在第一步，pm install -r 与会话式安装只作兜底。
 * 全程后台执行，不打开任何页面。
 */
object SilentApkInstaller {

    /** 进程级 Context（DshApplication 里赋值），用于读偏好与发起系统安装会话 */
    @Volatile
    var appContext: Context? = null

    /** 偏好：shell 静默安装失败时，是否交给系统安装页（InstallConfirmService 自动点确认） */
    fun pageFallbackEnabled(): Boolean {
        val ctx = appContext ?: return true
        return ctx.getSharedPreferences("hide_recents_prefs", Context.MODE_PRIVATE)
            .getBoolean("install_page_fallback", true)
    }

    /**
     * 兜底：交给系统安装器（PackageInstaller 会话）。
     *
     * shell 安装在这台 MIUI 上必然被 PackageManagerServiceImpl.verifyInstallFromShell 秒取消
     * （AdbInstallActivity 87ms 内自杀），所以失败后改走系统安装会话：这条路弹的是正常的
     * 「继续安装」页，由 InstallConfirmService 自动点掉。
     *
     * 返回值 true 只代表「会话已提交」，最终结果由 ApkInstallReceiver 回调。
     */
    fun installViaSystem(context: Context, apkFile: File): Pair<Boolean, String> {
        if (!apkFile.exists()) return Pair(false, "文件不存在: " + apkFile.absolutePath)
        return try {
            val installer: PackageInstaller = context.packageManager.packageInstaller
            val size = apkFile.length()
            val params = PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL
            )
            params.setSize(size)
            val sessionId = installer.createSession(params)
            val session = installer.openSession(sessionId)
            val intent = Intent(context, ApkInstallReceiver::class.java)
                .putExtra("session_id", sessionId)
                .putExtra("apk_name", apkFile.name)
            val flags = if (Build.VERSION.SDK_INT >= 31) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            val pending = PendingIntent.getBroadcast(context, sessionId, intent, flags)
            try {
                session.openWrite("base.apk", 0, size).use { out ->
                    apkFile.inputStream().use { input -> input.copyTo(out) }
                    session.fsync(out)
                }
                // 注意：commit 必须在 close 之前（Session.commit(IntentSender)）
                session.commit(pending.intentSender)
            } finally {
                try {
                    session.close()
                } catch (_: Exception) {
                }
            }
            AppLogger.i("installViaSystem: session=" + sessionId + " size=" + size + " committed " + apkFile.name)
            Pair(true, "已交给系统安装器")
        } catch (t: Throwable) {
            AppLogger.e("installViaSystem failed", t)
            Pair(false, t.message ?: "")
        }
    }

    private fun installShell(apkFile: File, ownBinder: IBinder?): Pair<Boolean, String> {
        val binder = TaskHideBridge.resolve(ownBinder)
            ?: return Pair(false, "Shizuku 未就绪")
        if (!apkFile.exists()) {
            return Pair(false, "文件不存在: " + apkFile.absolutePath)
        }

        fun exec(cmd: String): Pair<Boolean, String> {
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            return try {
                data.writeInterfaceToken(ITaskHideService.DESCRIPTOR)
                data.writeString(cmd)
                binder.transact(ITaskHideService.TRANSACTION_execCommand, data, reply, 0)
                reply.readException()
                val ok = reply.readInt() != 0
                val res = reply.readString() ?: ""
                AppLogger.i("SilentInstall: " + cmd.take(110) + " => ok=" + ok + " out=" + res.take(200))
                Pair(ok, res)
            } catch (t: Throwable) {
                AppLogger.e("SilentInstall failed: " + cmd.take(110) + " :: " + (t.message ?: ""))
                Pair(false, t.message ?: "")
            } finally {
                data.recycle()
                reply.recycle()
            }
        }

        val size = apkFile.length()
        val tmp = "/data/local/tmp/dsh_silent_" + System.currentTimeMillis() + ".apk"
        val cp = exec("cp '" + apkFile.absolutePath + "' " + tmp)
        val src = if (cp.first) tmp else apkFile.absolutePath

        // 1) 管道形式：这台 ROM 上唯一稳定成功的一条
        val pipe = exec("cat '" + src + "' | pm install -S " + size)
        if (pipe.first || pipe.second.contains("Success")) {
            exec("rm -f " + tmp)
            return Pair(true, pipe.second)
        }

        // 2) 直接 pm install -r
        val direct = exec("pm install -r " + src)
        if (direct.first || direct.second.contains("Success")) {
            exec("rm -f " + tmp)
            return Pair(true, direct.second)
        }

        // 3) 会话式安装
        val create = exec("pm install-create -r -S " + size)
        val open = create.second.indexOf('[')
        val close = if (open >= 0) create.second.indexOf(']', open + 1) else -1
        val sid = if (open >= 0 && close > open) create.second.substring(open + 1, close) else null
        if (sid != null) {
            val w = exec("pm install-write " + sid + " base " + src)
            val commit = exec("pm install-commit " + sid)
            exec("rm -f " + tmp)
            if (commit.first || commit.second.contains("Success")) return Pair(true, commit.second)
            return Pair(false, commit.second.ifBlank { w.second })
        }
        exec("rm -f " + tmp)
        return Pair(false, direct.second.ifBlank { create.second })
    }

    /**
     * 统一入口：先试 shell 静默安装；失败且偏好 install_page_fallback=true 时，
     * 再交给系统安装会话（会显示安装页，由 InstallConfirmService 自动点掉）。
     * 关闭该偏好 = 纯静默（失败只报错，不弹任何页面），与 3.0.45 之前行为一致。
     */
    fun install(apkFile: File, ownBinder: IBinder?): Pair<Boolean, String> {
        val direct = installShell(apkFile, ownBinder)
        if (direct.first) return direct
        val ctx = appContext
        if (ctx == null || !pageFallbackEnabled()) {
            AppLogger.i("install: shell failed, page fallback off => " + direct.second.take(120))
            return direct
        }
        val sys = installViaSystem(ctx, apkFile)
        return if (sys.first) sys else Pair(false, direct.second.ifBlank { sys.second })
    }
}
