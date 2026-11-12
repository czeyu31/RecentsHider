package com.example.hiderecents

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * 隐藏守护服务。
 *
 * 为什么需要它：本 ROM 的 IActivityTaskManager 没有 setExcludeFromRecents，
 * 「隐藏」只能一次性 removeTask。用户从桌面重新打开被隐藏的应用会建出新任务，
 * 之后没有任何东西看管它，于是它又出现在最近任务里 —— 就是
 * 「开关开着，但重新打开后后台没有隐藏」。
 *
 * 本服务在 hidden_apps 非空时以前台服务常驻，每 5 秒通过 Shizuku 调一次
 * TaskHideService.sweepHidden 把新出现的任务清掉；hidden_apps 变空后自行停止。
 */
class HiddenGuardService : Service() {

    companion object {
        private const val TAG = "HiddenGuard"
        private const val CHANNEL_ID = "hidden_guard"
        private const val NOTIFICATION_ID = 3020
        private const val INTERVAL = 10_000L

        /** 按当前 hidden_apps 决定启动或停止守护服务。 */
        fun sync(ctx: Context) {
            val prefs = ctx.getSharedPreferences("hide_recents_prefs", Context.MODE_PRIVATE)
            val hidden = prefs.getStringSet("hidden_apps", emptySet()) ?: emptySet()
            val guard = prefs.getBoolean("hide_guard_enabled", true)
            val enabled = prefs.getBoolean("hide_all_enabled", true)
            if (hidden.isEmpty() || !guard || !enabled) stopGuard(ctx) else startGuard(ctx)
        }

        private fun startGuard(ctx: Context) {
            try {
                val intent = Intent(ctx, HiddenGuardService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    ctx.startForegroundService(intent)
                } else {
                    ctx.startService(intent)
                }
            } catch (t: Throwable) {
                Log.e(TAG, "start failed", t)
            }
        }

        private fun stopGuard(ctx: Context) {
            try {
                ctx.stopService(Intent(ctx, HiddenGuardService::class.java))
            } catch (_: Throwable) {
            }
        }
    }

    private lateinit var prefs: SharedPreferences
    private val handler = Handler(Looper.getMainLooper())
    private var binder: IBinder? = null
    private var ready = false
    private var binding = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            binder = service
            ready = true
            binding = false
            TaskHideBridge.binder = service
            Log.d(TAG, "shizuku user service connected")
            sweep()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            binder = null
            ready = false
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            sweep()
            handler.postDelayed(this, INTERVAL)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences("hide_recents_prefs", Context.MODE_PRIVATE)
        startForegroundIfNeeded()
        bindShizuku()
        handler.post(tick)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundIfNeeded()
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        try {
            rikka.shizuku.Shizuku.unbindUserService(
                rikka.shizuku.Shizuku.UserServiceArgs(ComponentName(this, TaskHideService::class.java)),
                connection,
                true,
            )
        } catch (_: Exception) {
        }
        super.onDestroy()
    }

    private fun startForegroundIfNeeded() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val nm = getSystemService(NotificationManager::class.java)
                val channel = NotificationChannel(
                    CHANNEL_ID, "隐藏守护", NotificationManager.IMPORTANCE_MIN,
                ).apply {
                    description = "让被隐藏的应用持续不出现在最近任务里"
                    setShowBadge(false)
                }
                nm.createNotificationChannel(channel)
            }
            val open = PendingIntent.getActivity(
                this, 0, Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val notification = NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("隐藏守护运行中")
                .setContentText("被隐藏的应用不会出现在最近任务")
                .setSmallIcon(R.drawable.ic_settings)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .setContentIntent(open)
                .build()
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "startForeground failed", t)
        }
    }

    private fun bindShizuku() {
        if (binding) return
        try {
            if (!rikka.shizuku.Shizuku.pingBinder()) {
                handler.postDelayed({ bindShizuku() }, 5000)
                return
            }
            if (rikka.shizuku.Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                handler.postDelayed({ bindShizuku() }, 8000)
                return
            }
            binding = true
            rikka.shizuku.Shizuku.bindUserService(
                rikka.shizuku.Shizuku.UserServiceArgs(ComponentName(this, TaskHideService::class.java))
                    .daemon(false).processNameSuffix("taskhide").debuggable(true).version(2),
                connection,
            )
        } catch (t: Throwable) {
            binding = false
            Log.e(TAG, "bind failed", t)
            handler.postDelayed({ bindShizuku() }, 8000)
        }
    }

    private fun sweep() {
        val hidden = prefs.getStringSet("hidden_apps", emptySet()) ?: emptySet()
        if (hidden.isEmpty() || !prefs.getBoolean("hide_guard_enabled", true) || !prefs.getBoolean("hide_all_enabled", true)) {
            handler.removeCallbacksAndMessages(null)
            stopSelf()
            return
        }
        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
        if (pm != null && !pm.isInteractive) return
        val b = binder
        if (!ready || b == null) {
            bindShizuku()
            return
        }
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(ITaskHideService.DESCRIPTOR)
            data.writeStringList(hidden.toList())
            data.writeString(packageName)
            b.transact(ITaskHideService.TRANSACTION_sweepHidden, data, reply, 0)
            reply.readException()
            val removed = reply.readInt()
            if (removed > 0) Log.d(TAG, "sweep removed=" + removed)
        } catch (t: Throwable) {
            Log.e(TAG, "sweep failed", t)
        } finally {
            data.recycle()
            reply.recycle()
        }
    }
}
