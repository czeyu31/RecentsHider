package com.example.hiderecents.ft

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat

/**
 * 让「在线接收」在后台也活着：进程留着，HTTP 接收端和 UDP 应答都还在。
 */
class FtOnlineService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        FtOnline.ensureChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            FtOnline.setOn(this, false)
            FtOnline.stop()
            stopSelf()
            return START_NOT_STICKY
        }

        val port = FtOnline.start(this)
        if (port <= 0) {
            stopSelf()
            return START_NOT_STICKY
        }

        val notif = FtOnline.onlineNotification(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                FtOnline.ONLINE_NOTIF_ID,
                notif,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(FtOnline.ONLINE_NOTIF_ID, notif)
        }
        startWatchdog()
        return START_STICKY
    }

    /**
     * 接收端跑久了偶尔会「端口还在监听、accept 线程已经没了」，那样电脑连得上却收不到回应。
     * 每 20 秒自检一次，挂掉就地重建。
     */
    private fun startWatchdog() {
        if (watchdogRunning) return
        watchdogRunning = true
        Thread {
            while (watchdogRunning) {
                try {
                    Thread.sleep(WATCHDOG_INTERVAL_MS)
                } catch (e: InterruptedException) {
                    return@Thread
                }
                if (!isServiceAlive) return@Thread
                if (!FtOnline.isAlive()) {
                    FtOnline.stop()
                    FtOnline.start(this)
                    val mgr = getSystemService(NotificationManager::class.java)
                    runCatching {
                        mgr?.notify(FtOnline.ONLINE_NOTIF_ID, FtOnline.onlineNotification(this))
                    }
                }
            }
        }.apply { isDaemon = true; start() }
    }

    private var isServiceAlive = true
    private var watchdogRunning = false

    override fun onDestroy() {
        isServiceAlive = false
        watchdogRunning = false
        FtOnline.onReceived = null
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "com.example.hiderecents.ft.STOP"
        private const val WATCHDOG_INTERVAL_MS = 20_000L

        fun start(context: Context) {
            runCatching {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, FtOnlineService::class.java),
                )
            }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, FtOnlineService::class.java)) }
        }
    }
}
