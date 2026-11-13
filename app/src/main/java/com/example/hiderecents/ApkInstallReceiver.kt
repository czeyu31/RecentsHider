package com.example.hiderecents

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.widget.Toast

/**
 * 系统安装会话的结果回调（见 SilentApkInstaller.installViaSystem）。
 * 安装确认页由用户手动操作，这里只负责汇报结果。
 */
class ApkInstallReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val sessionId = intent.getIntExtra("session_id", -1)
        val apkName = intent.getStringExtra("apk_name") ?: ""
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: ""
        val ok = status == PackageInstaller.STATUS_SUCCESS
        val dump = try {
            val b = intent.extras
            if (b == null) "none" else b.keySet().joinToString(", ") { k -> k + "=" + b.get(k) }
        } catch (_: Exception) {
            "unreadable"
        }
        AppLogger.i("ApkInstallReceiver extras: " + dump)
        AppLogger.i(
            "ApkInstallReceiver: session=" + sessionId + " status=" + status +
                " ok=" + ok + " apk=" + apkName + " msg=" + message.take(200)
        )
        // 系统要求用户操作（安装页）时，把页面拉起来交给用户确认
        // MIUI 不走 AOSP 的 STATUS_PENDING_USER_ACTION，而是把 STATUS 塞成 -1，
        // 同时把 CONFIRM_INSTALL 意图放在 android.intent.extra.INTENT 里
        val innerIntent = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
        if (status != PackageInstaller.STATUS_SUCCESS && innerIntent != null) {
            try {
                innerIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(innerIntent)
                AppLogger.i("ApkInstallReceiver: started install UI " + innerIntent.component + " status=" + status)
            } catch (t: Throwable) {
                AppLogger.e("ApkInstallReceiver: start install UI failed", t)
            }
        }
        val text = if (ok) {
            "安装完成：" + apkName
        } else if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            "等待确认安装：" + apkName
        } else {
            "安装未完成：" + message.take(80)
        }
        try {
            Toast.makeText(context, text, Toast.LENGTH_LONG).show()
        } catch (_: Exception) {
        }
    }
}
