package com.example.hiderecents

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.os.IBinder
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

    /** 偏好：shell 静默安装失败时，是否交给系统安装页（安装页由用户手动确认） */
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
     * 「继续安装」页，需要用户手动点确认。
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
        if (!apkFile.exists()) {
            return Pair(false, "文件不存在: " + apkFile.absolutePath)
        }
        // 用户服务 binder 优先；连不上（例如 Shizuku NEXT 起不来用户服务）时
        // ShizukuShell 会自动改走服务端 newProcess，静默安装照样能跑。
        val binder = TaskHideBridge.resolve(ownBinder)

        fun exec(cmd: String): Pair<Boolean, String> {
            val res = ShizukuShell.exec(binder, cmd)
            AppLogger.i(
                "SilentInstall: " + cmd.take(110) + " => ok=" + res.first + " out=" + res.second.take(200)
            )
            return res
        }

        val size = apkFile.length()
        val tmp = "/data/local/tmp/dsh_silent_" + System.currentTimeMillis() + ".apk"
        val cp = exec("cp '" + apkFile.absolutePath + "' " + tmp)
        val src = if (cp.first) tmp else apkFile.absolutePath

        // MIUI 会以 INSTALL_FAILED_USER_RESTRICTED 拦掉「没有安装器身份」的 shell 安装，
        // 换上应用商店 / 安装器的包名再试一遍，多数机器就能放过
        val installers = listOf("com.android.vending", "com.miui.packageinstaller", null)
        var last = ""

        // 1) 管道形式：这台 ROM 上最稳的一条
        for (pkg in installers) {
            val id = if (pkg == null) "" else " -i " + pkg
            val pipe = exec("cat '" + src + "' | pm install -r -S " + size + id)
            last = pipe.second
            // 只看退出码：会话式脚本里 install-write 的输出也带 Success，不能拿来判成功
            if (pipe.first && !pipe.second.contains("Failure")) {
                exec("rm -f " + tmp)
                return Pair(true, pipe.second)
            }
        }

        // 2) 直接 pm install -r（同样带安装器身份）
        for (pkg in installers) {
            val id = if (pkg == null) "" else " -i " + pkg
            val direct = exec("pm install -r " + id.trim() + " " + src)
            last = direct.second
            if (direct.first && !direct.second.contains("Failure")) {
                exec("rm -f " + tmp)
                return Pair(true, direct.second)
            }
        }

        // 3) 会话式安装：必须一条命令跑完。
        // Shizuku 的 newProcess 每条命令都是新进程，分开发会丢会话
        // （install-commit 报 SecurityException: Caller has no access to session）
        for (pkg in installers) {
            val id = if (pkg == null) "" else " -i " + pkg
            val script = """
                SID=${'$'}(pm install-create -r -S $size$id 2>&1 | sed -n 's/.*\[\([0-9]*\)\].*/\1/p')
                if [ -z "${'$'}SID" ]; then echo NO_SESSION; exit 1; fi
                pm install-write ${'$'}SID base '$src' && pm install-commit ${'$'}SID
            """.trimIndent()
            val session = exec(script)
            last = session.second
            exec("rm -f " + tmp)
            if (session.first && !session.second.contains("Failure")) return Pair(true, session.second)
        }
        exec("rm -f " + tmp)
        return Pair(false, last)
    }

    /**
     * 统一入口：先试 shell 静默安装；失败且偏好 install_page_fallback=true 时，
     * 再交给系统安装会话（会显示安装页，由用户手动确认）。
     * 关闭该偏好 = 纯静默（失败只报错，不弹任何页面），与 3.0.45 之前行为一致。
     */
    fun install(apkFile: File, ownBinder: IBinder?): Pair<Boolean, String> {
        val direct = installShell(apkFile, ownBinder)
        // 某些 ROM 上 shell 安装会回 Success，但随后被系统静默取消；
        // 这种情况必须核对包到底有没有落地，否则任务会被误标成「已安装」，用户什么都看不到
        val reallyInstalled = direct.first && verifyInstalled(apkFile)
        AppLogger.i("install: shell ok=" + direct.first + " verified=" + reallyInstalled)
        if (reallyInstalled) return direct
        val ctx = appContext
        if (ctx == null || !pageFallbackEnabled()) {
            AppLogger.i("install: shell failed, page fallback off => " + direct.second.take(120))
            return direct
        }
        val sys = installViaSystem(ctx, apkFile)
        return if (sys.first) sys else Pair(false, direct.second.ifBlank { sys.second })
    }

    /**
     * 核对 APK 是否真的装上了：读 apk 里的包名与版本号，和系统里已安装的比一比。
     * 读不到信息时按「无法判断」放行，避免误弹安装页。
     */
    private fun verifyInstalled(apkFile: File): Boolean {
        val ctx = appContext ?: return true
        val archive = runCatching {
            @Suppress("DEPRECATION")
            ctx.packageManager.getPackageArchiveInfo(apkFile.absolutePath, 0)
        }.getOrNull() ?: return true
        val want = archive.longVersionCode
        repeat(4) { attempt ->
            val installed = runCatching {
                ctx.packageManager.getPackageInfo(archive.packageName, 0)
            }.getOrNull()
            if (installed != null && installed.longVersionCode >= want) return true
            if (attempt < 3) Thread.sleep(350)
        }
        AppLogger.w("verifyInstalled: " + archive.packageName + " 期望 " + want + "，系统里没有对应版本")
        return false
    }
}
