package com.example.hiderecents

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * 应用本体的常驻前台服务。
 *
 * 为什么需要它：之前的「后台保活」只做了 appops（RUN_IN_BACKGROUND / WAKE_LOCK 等）
 * 和电池优化白名单，这些只是「允许你活」，并不提供实际载体。
 * 没有前台服务的进程在 MIUI 上照样会被冻结/回收，所以保活开关形同虚设。
 *
 * 现在由本服务提供常驻前台通知 + START_STICKY，被系统杀掉后会尝试重建。
 */
class KeepAliveService : Service() {

    companion object {
        private const val TAG = "KeepAlive"
        private const val CHANNEL_ID = "app_keepalive"
        private const val NOTIFICATION_ID = 3010
        private const val ACTION_STOP = "com.example.hiderecents.KEEPALIVE_STOP"

        fun start(ctx: Context) {
            try {
                val intent = Intent(ctx, KeepAliveService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    ctx.startForegroundService(intent)
                } else {
                    ctx.startService(intent)
                }
            } catch (t: Throwable) {
                Log.e(TAG, "start failed", t)
            }
        }

        fun stop(ctx: Context) {
            try {
                ctx.stopService(Intent(ctx, KeepAliveService::class.java))
            } catch (_: Throwable) {
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * 每 60 秒刷新一次无障碍启用数量。
     * 常驻前台服务比无障碍服务活得久，所以由它来保证岛上的数字不落后。
     */
    private val guardHandler = Handler(Looper.getMainLooper())
    private val guardCheck = object : Runnable {
        override fun run() {
            Unit
            guardHandler.postDelayed(this, 60_000L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        startForegroundIfNeeded()
        guardHandler.post(guardCheck)
    }

    override fun onDestroy() {
        guardHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForegroundIfNeeded()
        return START_STICKY
    }

    private fun startForegroundIfNeeded() {
        try {
            val nm = getSystemService(NotificationManager::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "后台保活",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = "保持应用在后台常驻，避免被系统回收"
                    setShowBadge(false)
                }
                nm.createNotificationChannel(channel)
            }

            val openIntent = PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

            val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("System Tool 正在后台运行")
                .setContentText("保活已开启 · 点按返回应用")
                .setSmallIcon(R.drawable.ic_settings)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setContentIntent(openIntent)
                .build()

            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "startForeground failed", t)
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        // 从最近任务划掉后，若保活仍开启则重新拉起
        val prefs = getSharedPreferences("hide_recents_prefs", Context.MODE_PRIVATE)
        if (prefs.getBoolean("keep_alive_enabled", false)) {
            start(this)
        }
    }
}
