package com.example.hiderecents.ft

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.example.hiderecents.KeepAliveService
import com.example.hiderecents.StatusCenter

/**
 * 「在线接收」在后台也活着：进程留着，HTTP 接收端和 UDP 应答都还在。
 *
 * 它不再自己挂「文件传输在线」那条常驻通知 —— 通知栏只留一条状态通知（见 [StatusCenter]），
 * 是否在线写在那条里。进程由无障碍服务 / 状态通知那条前台服务撑着。
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
            StatusCenter.refresh(this)
            stopSelf()
            return START_NOT_STICKY
        }

        val port = FtOnline.start(this)
        if (port <= 0) {
            stopSelf()
            return START_NOT_STICKY
        }

        // 只在「没有保活服务」时自己把自己拎成前台：否则退到后台几分钟就会被系统回收，
        // 电脑再也连不上。前台用的仍然是那条状态通知，不再单独占一条。
        if (!KeepAliveService.isRunning) {
            StatusCenter.ensureChannel(this)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    StatusCenter.NOTIFICATION_ID,
                    StatusCenter.build(this),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
                )
            } else {
                startForeground(StatusCenter.NOTIFICATION_ID, StatusCenter.build(this))
            }
        } else {
            StatusCenter.refresh(this)
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
                    runCatching { StatusCenter.refresh(this) }
                } else if (!FtOnline.isUdpAlive()) {
                    // HTTP 还活着但 53317 没在听了——别的设备就永远扫不到这台手机
                    FtOnline.restartUdp(this)
                } else if (FtOnline.isAnnounceStalled()) {
                    // 播报发不出去（tx_queue 卡死）：多半是被系统省电策略掐了后台联网
                    FtOnline.recoverFromBlock(this)
                    runCatching { StatusCenter.refresh(this) }
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
                context.startService(Intent(context, FtOnlineService::class.java))
            }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, FtOnlineService::class.java)) }
        }
    }
}
