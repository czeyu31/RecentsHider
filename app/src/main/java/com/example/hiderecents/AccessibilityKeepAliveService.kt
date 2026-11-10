package com.example.hiderecents

import android.accessibilityservice.AccessibilityService
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.database.ContentObserver
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import androidx.core.app.NotificationCompat

class AccessibilityKeepAliveService : AccessibilityService() {

    companion object {
        private const val TAG = "A11yKeepAlive"
        private const val CHANNEL_ID = "a11y_keepalive"
        private const val NOTIFICATION_ID = 3001
        private const val ALARM_ACTION = "com.example.hiderecents.A11Y_CHECK_ALARM"
        private const val CHECK_INTERVAL = 10 * 60 * 1000L // 10 minutes
        var instance: AccessibilityKeepAliveService? = null
    }

    private var contentObserver: ContentObserver? = null
    private lateinit var prefs: SharedPreferences
    private val handler = Handler(Looper.getMainLooper())
    private var lastSettingValue = ""
    private var taskHideService: IBinder? = null
    private var serviceReady = false
    private val protectedServices = mutableSetOf<String>()
    private var unlockReceiver: BroadcastReceiver? = null
    private var alarmReceiver: BroadcastReceiver? = null

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            taskHideService = service
            serviceReady = true
            Log.d(TAG, "Shizuku service connected in keepalive")
            checkAndRestore()
        }
        override fun onServiceDisconnected(name: ComponentName) {
            taskHideService = null
            serviceReady = false
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        prefs = getSharedPreferences("hide_recents_prefs", Context.MODE_PRIVATE)
        lastSettingValue = getEnabledServicesString()

        startForegroundIfNeeded()
        bindShizuku()
        registerContentObserver()
        registerUnlockReceiver()
        registerAlarmReceiver()
        scheduleAlarm()

        handler.postDelayed({ checkAndRestore() }, 500)

        Log.d(TAG, "AccessibilityKeepAliveService connected, monitoring active")
    }

    private fun startForegroundIfNeeded() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val nm = getSystemService(NotificationManager::class.java)
                val channel = NotificationChannel(
                    CHANNEL_ID, "无障碍保活守护",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "保持无障碍防回收服务持续运行"
                    setShowBadge(false)
                }
                nm.createNotificationChannel(channel)
            }
            val notification = NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("无障碍保活守护运行中")
                .setContentText("防回收功能正在保护你的无障碍服务")
                .setSmallIcon(R.drawable.ic_settings)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            Log.d(TAG, "Foreground notification started")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start foreground", e)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            handler.postDelayed({ checkAndRestore() }, 1000)
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        // Save instance before super.onDestroy() clears it
        val self = instance
        super.onDestroy()
        if (self === instance) instance = null
        handler.removeCallbacksAndMessages(null)
        contentObserver?.let {
            try { contentResolver.unregisterContentObserver(it) } catch (_: Exception) {}
        }
        unlockReceiver?.let {
            try { unregisterReceiver(it) } catch (_: Exception) {}
        }
        alarmReceiver?.let {
            try { unregisterReceiver(it) } catch (_: Exception) {}
        }
        cancelAlarm()
        try { unbindService(serviceConnection) } catch (_: Exception) {}
        Log.d(TAG, "AccessibilityKeepAliveService destroyed")
    }

    // ── Shizuku ──

    private fun bindShizuku() {
        try {
            rikka.shizuku.Shizuku.addBinderDeadListener {
                Log.w(TAG, "Shizuku binder dead, will retry")
                serviceReady = false
                taskHideService = null
                handler.postDelayed({ bindShizuku() }, 5000)
            }
            rikka.shizuku.Shizuku.bindUserService(
                rikka.shizuku.Shizuku.UserServiceArgs(
                    ComponentName(this, TaskHideService::class.java)
                ).daemon(true).processNameSuffix("taskhide").debuggable(true).version(1),
                serviceConnection
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to bind Shizuku in keepalive", e)
            // Retry later
            handler.postDelayed({ bindShizuku() }, 10000)
        }
    }

    // ── ContentObserver ──

    private fun registerContentObserver() {
        contentObserver = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) {
                super.onChange(selfChange)
                handler.removeCallbacksAndMessages(null)
                handler.postDelayed({ checkAndRestore() }, 500)
                // Reschedule alarm in case it was disrupted
                handler.postDelayed({ scheduleAlarm() }, 10000)
            }
        }
        contentResolver.registerContentObserver(
            Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES),
            true, contentObserver!!
        )
    }

    // ── Unlock Receiver ──

    private fun registerUnlockReceiver() {
        unlockReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == Intent.ACTION_USER_PRESENT) {
                    Log.d(TAG, "User unlocked, checking services")
                    handler.postDelayed({ checkAndRestore() }, 2000)
                }
            }
        }
        try {
            registerReceiver(unlockReceiver, IntentFilter(Intent.ACTION_USER_PRESENT))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register unlock receiver", e)
        }
    }

    // ── Alarm-based periodic check (survives process death & Doze) ──

    private fun registerAlarmReceiver() {
        alarmReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == ALARM_ACTION) {
                    Log.d(TAG, "Alarm fired, checking services")
                    checkAndRestore()
                    // Reschedule next alarm
                    scheduleAlarm()
                }
            }
        }
        try {
            registerReceiver(alarmReceiver, IntentFilter(ALARM_ACTION), RECEIVER_NOT_EXPORTED)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register alarm receiver", e)
        }
    }

    private fun scheduleAlarm() {
        try {
            val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(ALARM_ACTION)
            val pending = PendingIntent.getBroadcast(
                this, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val triggerAt = System.currentTimeMillis() + CHECK_INTERVAL

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                // setAlarmClock is exempt from Doze and battery optimization
                // It shows an alarm icon in status bar, but is the most reliable way
                val showIntent = PendingIntent.getActivity(
                    this, 1,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE
                )
                am.setAlarmClock(AlarmManager.AlarmClockInfo(triggerAt, showIntent), pending)
            } else {
                am.setRepeating(AlarmManager.RTC_WAKEUP, triggerAt, CHECK_INTERVAL, pending)
            }
            Log.d(TAG, "Alarm scheduled for ${CHECK_INTERVAL / 60000}min")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to schedule alarm", e)
        }
    }

    private fun cancelAlarm() {
        try {
            val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pending = PendingIntent.getBroadcast(
                this, 0, Intent(ALARM_ACTION),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            am.cancel(pending)
        } catch (_: Exception) {}
    }

    // ── Core: check & restore ──

    private fun checkAndRestore() {
        if (!::prefs.isInitialized) return

        // 1) Check if this keep-alive service itself is still enabled
        checkSelfEnabled()

        // 2) Restore protected services
        val autoProtect = prefs.getBoolean("a11y_auto_protect", false)
        if (!autoProtect) return

        protectedServices.clear()
        protectedServices.addAll(prefs.getStringSet("protected_a11y_services", emptySet()) ?: emptySet())
        if (protectedServices.isEmpty()) return

        val userDisabled = prefs.getStringSet("user_disabled_a11y", emptySet()) ?: emptySet()
        val currentSetting = getEnabledServicesString()
        val currentServices = currentSetting.split(':').filter { it.isNotBlank() }.toMutableList()
        val normalizedCurrent = currentServices.map { normalize(it) }.toSet()

        val needRestore = mutableListOf<String>()
        for (protected in protectedServices) {
            if (normalize(protected) !in normalizedCurrent && protected !in userDisabled) {
                needRestore.add(protected)
            }
        }

        if (needRestore.isNotEmpty()) {
            Log.d(TAG, "Restoring ${needRestore.size} services: $needRestore")
            currentServices.addAll(needRestore)
            writeSecureSetting(currentServices.joinToString(":"))
        }

        lastSettingValue = getEnabledServicesString()
    }

    /**
     * Check if this AccessibilityKeepAliveService itself is still in enabled_accessibility_services.
     * If the system removed it, re-add it proactively.
     */
    private fun checkSelfEnabled() {
        val current = getEnabledServicesString()
        val myCn = ComponentName(this, AccessibilityKeepAliveService::class.java).flattenToString()
        val myCnShort = "$packageName/${AccessibilityKeepAliveService::class.java.name}"

        if (!current.contains(myCn) && !current.contains(myCnShort)) {
            Log.w(TAG, "Keep-alive service itself was removed! Re-enabling...")
            val services = current.split(':').filter { it.isNotBlank() }.toMutableList()
            services.add(myCn)
            writeSecureSetting(services.joinToString(":"))
            try {
                writeSecureSettingDirect("accessibility_enabled", "1")
            } catch (_: Exception) {}
        }
    }

    // ── Settings write ──

    private fun writeSecureSetting(value: String) {
        if (serviceReady && taskHideService != null) {
            val binder = taskHideService!!
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken(ITaskHideService.DESCRIPTOR)
                data.writeString("settings put secure enabled_accessibility_services $value")
                binder.transact(ITaskHideService.TRANSACTION_execCommand, data, reply, 0)
                reply.readException()
                val success = reply.readInt() != 0
                Log.d(TAG, "Shizuku write setting: $success")
                if (!success) tryDirectWriteEnabled(value)
            } catch (e: Exception) {
                Log.e(TAG, "Shizuku write failed", e)
                tryDirectWriteEnabled(value)
            } finally {
                data.recycle()
                reply.recycle()
            }
        } else {
            // Shizuku not available, try reconnecting then fallback
            bindShizuku()
            tryDirectWriteEnabled(value)
        }
    }

    private fun tryDirectWriteEnabled(value: String) {
        try {
            Settings.Secure.putString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, value)
            Log.d(TAG, "Direct write setting succeeded")
        } catch (e: SecurityException) {
            Log.e(TAG, "No WRITE_SECURE_SETTINGS permission", e)
        }
    }

    private fun writeSecureSettingDirect(key: String, value: String) {
        if (serviceReady && taskHideService != null) {
            val binder = taskHideService!!
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken(ITaskHideService.DESCRIPTOR)
                data.writeString("settings put secure $key $value")
                binder.transact(ITaskHideService.TRANSACTION_execCommand, data, reply, 0)
                reply.readException()
                reply.readInt()
            } catch (e: Exception) {
                try { Settings.Secure.putString(contentResolver, key, value) } catch (_: Exception) {}
            } finally {
                data.recycle()
                reply.recycle()
            }
        } else {
            try { Settings.Secure.putString(contentResolver, key, value) } catch (_: Exception) {}
        }
    }

    // ── Helpers ──

    private fun getEnabledServicesString(): String {
        return Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
    }

    private fun normalize(serviceId: String): String {
        val cn = ComponentName.unflattenFromString(serviceId)
        return cn?.flattenToString() ?: serviceId
    }
}
