package com.example.hiderecents

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.database.ContentObserver
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent

class AccessibilityKeepAliveService : AccessibilityService() {

    companion object {
        private const val TAG = "A11yKeepAlive"
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

        bindShizuku()
        registerContentObserver()
        registerUnlockReceiver()
        startPeriodicCheck()

        // 启动时立即检查一次
        handler.postDelayed({ checkAndRestore() }, 500)

        Log.d(TAG, "AccessibilityKeepAliveService connected, monitoring active")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 监听解锁事件作为额外触发
        if (event?.eventType == android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            handler.postDelayed({ checkAndRestore() }, 1000)
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        handler.removeCallbacksAndMessages(null)
        contentObserver?.let {
            try { contentResolver.unregisterContentObserver(it) } catch (_: Exception) {}
        }
        unlockReceiver?.let {
            try { unregisterReceiver(it) } catch (_: Exception) {}
        }
        try {
            unbindService(serviceConnection)
        } catch (_: Exception) {}
        Log.d(TAG, "AccessibilityKeepAliveService destroyed")
    }

    private fun bindShizuku() {
        try {
            rikka.shizuku.Shizuku.bindUserService(
                rikka.shizuku.Shizuku.UserServiceArgs(
                    ComponentName(this, TaskHideService::class.java)
                ).daemon(false).processNameSuffix("taskhide").debuggable(true).version(1),
                serviceConnection
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to bind Shizuku in keepalive", e)
        }
    }

    private fun registerContentObserver() {
        contentObserver = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) {
                super.onChange(selfChange)
                // 延迟检查，避免与其他操作冲突
                handler.removeCallbacksAndMessages(null)
                handler.postDelayed({ checkAndRestore() }, 500)
                // 恢复定期检查
                handler.postDelayed({ startPeriodicCheck() }, 10000)
            }
        }
        contentResolver.registerContentObserver(
            Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES),
            true, contentObserver!!
        )
        Log.d(TAG, "ContentObserver registered")
    }

    private fun registerUnlockReceiver() {
        unlockReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == Intent.ACTION_USER_PRESENT) {
                    Log.d(TAG, "User unlocked, checking services")
                    // 解锁后延迟检查，等系统稳定
                    handler.postDelayed({ checkAndRestore() }, 2000)
                }
            }
        }
        val filter = IntentFilter(Intent.ACTION_USER_PRESENT)
        try {
            registerReceiver(unlockReceiver, filter)
            Log.d(TAG, "Unlock receiver registered")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register unlock receiver", e)
        }
    }

    private fun startPeriodicCheck() {
        handler.removeCallbacks(periodicRunnable)
        handler.postDelayed(periodicRunnable, 15_000) // 每15秒检查一次
    }

    private val periodicRunnable = object : Runnable {
        override fun run() {
            checkAndRestore()
            handler.postDelayed(this, 15_000)
        }
    }

    private fun checkAndRestore() {
        val autoProtect = prefs.getBoolean("a11y_auto_protect", false)
        if (!autoProtect) return

        // 重新读取保护列表
        protectedServices.clear()
        protectedServices.addAll(prefs.getStringSet("protected_a11y_services", emptySet()) ?: emptySet())
        if (protectedServices.isEmpty()) return

        // 读取用户主动禁用列表
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
            val newValue = currentServices.joinToString(":")
            writeSecureSetting(newValue)
            lastSettingValue = newValue
        } else {
            lastSettingValue = currentSetting
        }
    }

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
            } catch (e: Exception) {
                Log.e(TAG, "Shizuku write failed", e)
                tryDirectWrite(value)
            } finally {
                data.recycle()
                reply.recycle()
            }
        } else {
            tryDirectWrite(value)
        }
    }

    private fun tryDirectWrite(value: String) {
        try {
            Settings.Secure.putString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, value)
            Log.d(TAG, "Direct write setting succeeded")
        } catch (e: SecurityException) {
            Log.e(TAG, "No WRITE_SECURE_SETTINGS permission", e)
        }
    }

    private fun getEnabledServicesString(): String {
        return Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
    }

    private fun normalize(serviceId: String): String {
        val cn = ComponentName.unflattenFromString(serviceId)
        return cn?.flattenToString() ?: serviceId
    }
}
