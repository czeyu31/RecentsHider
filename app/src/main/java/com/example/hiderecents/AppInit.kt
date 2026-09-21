package com.example.hiderecents

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import rikka.shizuku.Shizuku

/**
 * 在开屏动画期间完成 Shizuku 完整初始化流程：
 * 监听 binder → 请求权限 → 绑定服务，MainActivity 直接复用结果。
 */
object AppInit {
    private const val TAG = "AppInit"

    @Volatile var shizukuReady = false; private set
    @Volatile var shizukuPermissionGranted = false; private set
    @Volatile var shizukuServiceBound = false; private set
    @Volatile var taskHideService: IBinder? = null; private set
    @Volatile var cpuModel: String? = null; private set

    private var serviceConnection: ServiceConnection? = null
    private var initialized = false

    /**
     * 注册 Shizuku 监听并启动预初始化。必须在 Activity 生命周期内调用。
     */
    fun startPreInit(context: Context) {
        if (initialized) return
        initialized = true

        // 注册权限结果监听
        Shizuku.addRequestPermissionResultListener { _, grantResult ->
            shizukuPermissionGranted = grantResult == PackageManager.PERMISSION_GRANTED
            Log.d(TAG, "Shizuku permission result: $shizukuPermissionGranted")
            if (shizukuPermissionGranted) {
                bindService(context)
            }
        }

        // 注册 binder 监听（sticky 会立即回调如果已就绪）
        Shizuku.addBinderReceivedListener {
            Log.d(TAG, "Shizuku binder received")
            checkAndRequestPermission(context)
        }

        // 如果 binder 已经就绪，直接检查
        Thread {
            Thread.sleep(300) // 等 sticky 回调
            if (!shizukuPermissionGranted) {
                checkAndRequestPermission(context)
            }
            shizukuReady = true
        }.start()

        // CPU 型号检测（并行）
        Thread {
            cpuModel = detectCpuModel()
        }.start()
    }

    private fun checkAndRequestPermission(context: Context) {
        try {
            if (!Shizuku.pingBinder() || Shizuku.isPreV11()) {
                shizukuReady = true
                return
            }
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                shizukuPermissionGranted = true
                bindService(context)
            } else {
                Shizuku.requestPermission(1001)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Shizuku check failed", e)
        }
        shizukuReady = true
    }

    private fun bindService(context: Context) {
        try {
            serviceConnection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName, service: IBinder) {
                    taskHideService = service
                    shizukuServiceBound = true
                    Log.d(TAG, "Shizuku service bound")
                }
                override fun onServiceDisconnected(name: ComponentName) {
                    taskHideService = null
                    shizukuServiceBound = false
                }
            }
            val conn = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName, service: IBinder) {
                    taskHideService = service
                    shizukuServiceBound = true
                    Log.d(TAG, "Shizuku service bound")
                }
                override fun onServiceDisconnected(name: ComponentName) {
                    taskHideService = null
                    shizukuServiceBound = false
                }
            }
            serviceConnection = conn
            Shizuku.bindUserService(
                Shizuku.UserServiceArgs(ComponentName(context, TaskHideService::class.java))
                    .daemon(false).processNameSuffix("taskhide").debuggable(true).version(1),
                conn
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to bind Shizuku service", e)
        }
    }

    private fun detectCpuModel(): String {
        return try {
            val hw = if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL else {
                try { Build::class.java.getField("HARDWARE").get(null) as String } catch (_: Exception) { Build.HARDWARE }
            }
            val cores = Runtime.getRuntime().availableProcessors()
            "$hw ($cores cores)"
        } catch (_: Exception) {
            "${Build.HARDWARE} (${Runtime.getRuntime().availableProcessors()} cores)"
        }
    }
}
