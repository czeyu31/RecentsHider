package com.example.hiderecents

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log

/**
 * 应用本体的常驻前台服务。
 *
 * 为什么需要它：之前的「后台保活」只做了 appops（RUN_IN_BACKGROUND / WAKE_LOCK 等）
 * 和电池优化白名单，这些只是「允许你活」，并不提供实际载体。
 * 没有前台服务的进程在 MIUI 上照样会被冻结/回收，所以保活开关形同虚设。
 *
 * 它持的那条通知就是全应用唯一的「状态通知」（见 [StatusCenter]）：
 * 隐藏了几个应用、无障碍给几个应用开着、文件传输是否在线都写在里面，
 * 不再另外挂「后台保活」「无障碍保活守护」「隐藏守护」「文件传输在线」四条。
 */
class KeepAliveService : Service() {

    companion object {
        private const val TAG = "KeepAlive"
        private const val ACTION_STOP = "com.example.hiderecents.KEEPALIVE_STOP"

        /** 服务是否在跑：在跑的时候状态通知归它所有，别处只更新不取消。 */
        @Volatile
        var isRunning: Boolean = false
            private set

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
     * 每 20 秒把状态通知的内容刷新一遍（无障碍开着几个应用、是否在线都会变）。
     * 常驻前台服务比无障碍服务活得久，由它保证通知里的数字不落后。
     */
    private val guardHandler = Handler(Looper.getMainLooper())
    private val guardCheck = object : Runnable {
        override fun run() {
            runCatching { StatusCenter.refresh(this@KeepAliveService) }
            guardHandler.postDelayed(this, 20_000L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        startForegroundIfNeeded()
        guardHandler.post(guardCheck)
    }

    override fun onDestroy() {
        isRunning = false
        guardHandler.removeCallbacksAndMessages(null)
        // 服务停了：用户还想要状态通知就留一条（此时它不再是前台服务通知），否则撤掉
        runCatching { StatusCenter.refresh(this) }
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
            StatusCenter.ensureChannel(this)
            val notification = StatusCenter.build(this)
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(
                    StatusCenter.NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                )
            } else {
                startForeground(StatusCenter.NOTIFICATION_ID, notification)
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
