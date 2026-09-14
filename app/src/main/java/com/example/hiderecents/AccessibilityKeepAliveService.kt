package com.example.hiderecents

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast

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

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            taskHideService = service
            serviceReady = true
            Log.d(TAG, "Shizuku service connected in keepalive")
            handler.postDelayed({ checkAndRestore() }, 1000)
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
        protectedServices.addAll(prefs.getStringSet("protected_a11y_services", emptySet()) ?: emptySet())
        lastSettingValue = getEnabledServicesString()

        bindShizuku()
        registerContentObserver()

        Log.d(TAG, "AccessibilityKeepAliveService connected, monitoring active")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        contentObserver?.let {
            try { contentResolver.unregisterContentObserver(it) } catch (_: Exception) {}
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
                handler.postDelayed({ checkAndRestore() }, 500)
            }
        }
        contentResolver.registerContentObserver(
            Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES),
            true, contentObserver!!
        )
        Log.d(TAG, "ContentObserver registered")
    }

    private fun checkAndRestore() {
        val autoProtect = prefs.getBoolean("a11y_auto_protect", false)
        if (!autoProtect) return

        // 重新读取保护列表（可能被用户更新了）
        protectedServices.clear()
        protectedServices.addAll(prefs.getStringSet("protected_a11y_services", emptySet()) ?: emptySet())
        if (protectedServices.isEmpty()) return

        // 读取用户主动禁用列表，这些不应该被恢复
        val userDisabled = prefs.getStringSet("user_disabled_a11y", emptySet()) ?: emptySet()

        val currentSetting = getEnabledServicesString()
        if (currentSetting == lastSettingValue) return

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
            handler.post {
                Toast.makeText(this, "已恢复 ${needRestore.size} 个被关闭的无障碍服务", Toast.LENGTH_SHORT).show()
            }
        } else {
            lastSettingValue = currentSetting
        }
    }

    private fun writeSecureSetting(value: String) {
        if (serviceReady && taskHideService != null) {
            // 通过 Shizuku 写入（有 root 权限）
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
                Log.e(TAG, "Shizuku write failed, trying direct", e)
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
