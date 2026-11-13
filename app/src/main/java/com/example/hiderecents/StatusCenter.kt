package com.example.hiderecents

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.core.app.NotificationCompat
import com.example.hiderecents.ft.FtOnline

/**
 * 全应用唯一一条常驻通知——「状态通知」。
 *
 * 以前后台保活、无障碍保活守护、隐藏守护、文件传输在线各挂一条通知，
 * 四条说的其实是同一件事，通知栏被占满还都被系统折叠成「还有 4 条通知」。
 * 现在合并成这一条：隐藏了几个应用 / 无障碍开着几个应用 / 是否保活 / 文件传输是否在线。
 */
object StatusCenter {

    const val CHANNEL_ID = "system_tool_status"
    const val NOTIFICATION_ID = 4001

    /** 已经废弃的通知渠道。升级后一次性删掉，免得系统通知设置里还留着几条空壳。 */
    private val LEGACY_CHANNELS = listOf(
        "app_keepalive",
        "a11y_keepalive",
        "hidden_guard",
        "ft_online",
        "file_server",
    )

    fun prefs(context: Context) = context.applicationContext
        .getSharedPreferences("hide_recents_prefs", Context.MODE_PRIVATE)

    /** 用户是否要求常驻这条通知。 */
    fun isWanted(context: Context): Boolean =
        prefs(context).getBoolean("show_status_notification", false)

    /** 系统里当前有几个应用开着无障碍服务（按包名去重）。 */
    fun accessibilityAppCount(context: Context): Int {
        val raw = runCatching {
            Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            )
        }.getOrNull() ?: return 0
        return raw.split(':')
            .map { it.substringBefore('/').trim() }
            .filter { it.isNotEmpty() }
            .toSet()
            .size
    }

    fun statusText(context: Context): String {
        val p = prefs(context)
        val hidden = (p.getStringSet("hidden_apps", emptySet()) ?: emptySet()).size
        val a11y = accessibilityAppCount(context)
        val parts = ArrayList<String>()
        parts += "已隐藏 $hidden 个应用"
        if (a11y > 0) parts += "无障碍 $a11y 个应用"
        if (p.getBoolean("keep_alive_enabled", false)) parts += "保活中"
        if (FtOnline.isOn(context) && FtOnline.port > 0) parts += "文件传输在线"
        return parts.joinToString(" · ")
    }

    fun ensureChannel(context: Context) {
        val mgr = context.getSystemService(NotificationManager::class.java) ?: return
        LEGACY_CHANNELS.forEach { legacy -> runCatching { mgr.deleteNotificationChannel(legacy) } }
        val channel = NotificationChannel(
            CHANNEL_ID,
            "状态通知",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "显示隐藏、无障碍与在线状态"
            setShowBadge(false)
        }
        mgr.createNotificationChannel(channel)
    }

    fun build(context: Context): Notification {
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val text = statusText(context)
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle("System Tool")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSmallIcon(R.drawable.ic_settings)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    /** 只在通知内容变化时重发，省掉无谓的通知更新。 */
    private var lastText: String? = null

    /**
     * 刷新状态通知。
     *
     * 常驻服务（[KeepAliveService]）活着时由它持有这条通知，这里只更新内容，绝不取消；
     * 服务没跑时按用户开关决定显示或取消。
     */
    fun refresh(context: Context) {
        val mgr = context.getSystemService(NotificationManager::class.java) ?: return
        ensureChannel(context)

        // 保活服务或文件传输接收端活着时，这条通知同时是它们的前台通知，只能更新不能撤
        val mustKeep = KeepAliveService.isRunning || (FtOnline.isOn(context) && FtOnline.port > 0)
        if (mustKeep) {
            runCatching { mgr.notify(NOTIFICATION_ID, build(context)) }
            lastText = statusText(context)
            return
        }
        if (!isWanted(context)) {
            runCatching { mgr.cancel(NOTIFICATION_ID) }
            lastText = null
            return
        }
        val text = statusText(context)
        if (text == lastText && hasNotification(context)) return
        lastText = text
        runCatching { mgr.notify(NOTIFICATION_ID, build(context)) }
    }

    private fun hasNotification(context: Context): Boolean = runCatching {
        val mgr = context.getSystemService(NotificationManager::class.java)
        mgr?.activeNotifications?.any { it.id == NOTIFICATION_ID } == true
    }.getOrDefault(false)
}
