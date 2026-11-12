package com.example.hiderecents

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.provider.Settings
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.NotificationCompat
import androidx.core.view.WindowCompat
import com.example.hiderecents.ui.screens.HiddenPreview
import com.example.hiderecents.ui.screens.HomeScreen
import com.example.hiderecents.ui.screens.HomeUiState
import com.example.hiderecents.ui.screens.KeepAliveGuideDialog
import com.example.hiderecents.ui.screens.SettingsDialog
import com.example.hiderecents.ui.theme.AppTheme
import com.example.hiderecents.ui.theme.applyDshSystemBars
import com.example.hiderecents.ui.theme.DshTheme
import rikka.shizuku.Shizuku
import kotlin.math.abs

/**
 * 首页（Compose 重写版）。
 *
 * 功能面与旧版完全一致：Shizuku 授权与绑定、自动授权、无障碍保活、隐藏计数、
 * 状态通知、隐藏后台卡片、后台保活、右滑进入无障碍管理。
 * 系统监控相关（CPU / 内存 / 电池 / 存储 / 网络 / 运行时长 / 进程数 / 耗电排行）已全部移除。
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "SystemTool"
        private const val REQ_NOTIFICATION = 2001
        private const val PREFS = "hide_recents_prefs"
    }

    private lateinit var prefs: SharedPreferences
    private val handler = Handler(Looper.getMainLooper())

    private var shizukuPermissionGranted = false
    private var taskHideService: IBinder? = null
    private var serviceReady = false
    private var pendingEnableNotification = false

    private var homeState by mutableStateOf(HomeUiState())
    private var appTheme by mutableStateOf(AppTheme.SYSTEM)
    private var showSettings by mutableStateOf(false)
    private var hideGuard by mutableStateOf(true)
    private var relaunchOnUnhide by mutableStateOf(true)
    private var predictiveBack by mutableStateOf(true)
    private var keepAliveBatteryOk by mutableStateOf(false)
    private var keepAliveAutoStartOk by mutableStateOf(false)
    private var keepAliveAutoStartUnknown by mutableStateOf(false)
    private var keepAliveManualAutoStart by mutableStateOf(false)
    private var showKeepAliveGuide by mutableStateOf(false)

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            taskHideService = service
            serviceReady = true
            Log.d(TAG, "Shizuku service connected")
            refreshState()
            autoGrantAllPermissions()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            taskHideService = null
            serviceReady = false
            refreshState()
        }
    }

    private val shizukuPermissionResultListener =
        Shizuku.OnRequestPermissionResultListener { _, grantResult ->
            shizukuPermissionGranted = grantResult == PackageManager.PERMISSION_GRANTED
            if (shizukuPermissionGranted) {
                bindShizukuService()
            }
            refreshState()
        }

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        checkShizukuPermission()
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        shizukuPermissionGranted = false
        taskHideService = null
        serviceReady = false
        refreshState()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyPredictiveBackPref()

        AppLogger.init(this)
        AppLogger.i("MainActivity onCreate (compose)")

        prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        appTheme = AppTheme.fromPref(prefs.getString("ui_theme", null))
        hideGuard = prefs.getBoolean("hide_guard_enabled", true)
        relaunchOnUnhide = prefs.getBoolean("relaunch_on_unhide", true)
        predictiveBack = prefs.getBoolean("predictive_back_enabled", true)
        applySystemBars()

        setContent {
            DshTheme(theme = appTheme) {
                HomeScreen(
                    state = homeState,
                    onOpenToolbox = {
                        startActivity(Intent(this@MainActivity, ToolboxActivity::class.java))
                        // 进场：纯位移（无 alpha），220ms ease-out，与返回的 slide_right 时长对称
                        @Suppress("DEPRECATION")
                        overridePendingTransition(R.anim.enter_right_to_left, 0)
                    },
                    onOpenAccessibility = { openAccessibility() },
                    onOpenSettings = { showSettings = true },
                    onRetryShizuku = {
                        checkShizukuPermission()
                        refreshState()
                    },
                    onToggleKeepAlive = { toggleKeepAlive(it) },
                    onToggleNotification = { toggleNotification(it) },
                    onToggleExclude = { toggleExcludeFromRecents(it) },
                )

                if (showSettings) {
                    SettingsDialog(
                        theme = appTheme,
                        keepAlive = homeState.keepAlive,
                        statusNotification = homeState.statusNotification,
                        excludeFromRecents = homeState.excludeFromRecents,
                        hiddenCount = homeState.hiddenCount,
                        hideGuard = hideGuard,
                            relaunchOnUnhide = relaunchOnUnhide,
                            predictiveBack = predictiveBack,
                        onThemeChange = { setTheme(it) },
                        onToggleKeepAlive = { toggleKeepAlive(it) },
                        onToggleNotification = { toggleNotification(it) },
                        onToggleExclude = { toggleExcludeFromRecents(it) },
                        onHideGuardChange = { applyHideGuard(it) },
                            onRelaunchOnUnhideChange = { applyRelaunchOnUnhide(it) },
                            onPredictiveBackChange = { applyPredictiveBack(it) },
                        onDismiss = { showSettings = false },
                    )
                }

                if (showKeepAliveGuide) {
                    KeepAliveGuideDialog(
                        batteryOk = keepAliveBatteryOk,
                        autoStartOk = keepAliveAutoStartOk,
                        autoStartUnknown = keepAliveAutoStartUnknown,
                        manualAutoStart = keepAliveManualAutoStart,
                        // 点「电池优化 / 自启动」不再关闭卡片：跳系统页，回来自动重新检测
                        onOpenBatterySettings = { KeepAliveGuard.openBatterySettings(this@MainActivity) },
                        onOpenAutoStartSettings = { KeepAliveGuard.openAutoStartSettings(this@MainActivity) },
                        onManualAutoStartChange = {
                            KeepAliveGuard.setManualAutoStart(this@MainActivity, it)
                            refreshKeepAliveGuideState()
                        },
                        onRecheck = { refreshKeepAliveGuideState() },
                        onFinish = { finishKeepAliveGuide() },
                        onDismiss = {
                            // 只点「稍后再说」时不打开保活：条件没满足就不写 keep_alive_enabled
                            showKeepAliveGuide = false
                            refreshState()
                        },
                    )
                }
            }
        }

        refreshState()
        setupShizuku()
        autoGrantPermissions()
        ensureAccessibilityKeepAlive()
        applyExcludeFromRecents(prefs.getBoolean("hide_from_recents", false))
        updateStatusNotification()
        syncKeepAliveService()
        HiddenGuardService.sync(this)

        // 注册即拿到当前温度（sticky 广播的返回值就是最近一次的值）
        val stickyBattery = registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        stickyBattery?.let { applyBatteryTemperature(it) }
    }

    // ------------------------------------------------------------------ 主题

    private fun applyPredictiveBack(value: Boolean) {
        prefs.edit().putBoolean("predictive_back_enabled", value).apply()
        predictiveBack = value
        applyPredictiveBackPref()
        Toast.makeText(this, if (value) "已开启预测返回手势" else "已关闭预测返回手势", Toast.LENGTH_SHORT).show()
    }

    private fun applyRelaunchOnUnhide(value: Boolean) {
        prefs.edit().putBoolean("relaunch_on_unhide", value).apply()
        relaunchOnUnhide = value
        Toast.makeText(this, if (value) "取消隐藏会回到前台" else "取消隐藏不再拉起应用", Toast.LENGTH_SHORT).show()
    }

    private fun applyHideGuard(value: Boolean) {
        prefs.edit().putBoolean("hide_guard_enabled", value).apply()
        hideGuard = value
        HiddenGuardService.sync(this)
        Toast.makeText(this, if (value) "已开启持续守护" else "仅一次生效", Toast.LENGTH_SHORT).show()
    }
    private fun setTheme(theme: AppTheme) {
        prefs.edit().putString("ui_theme", theme.prefValue).apply()
        appTheme = theme
        applySystemBars()
    }

    private fun resolveTheme(): AppTheme {
        val systemDark = (resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        return when (appTheme) {
            AppTheme.SYSTEM -> if (systemDark) AppTheme.GRAPHITE else AppTheme.PAPER
            else -> appTheme
        }
    }

    private fun applySystemBars() {
        applyDshSystemBars(appTheme)
    }

    // ------------------------------------------------------------------ 状态

    private fun refreshState() {
        val hidden = prefs.getStringSet("hidden_apps", emptySet()) ?: emptySet()
        val protectedServices = prefs.getStringSet("protected_a11y_services", emptySet()) ?: emptySet()
        val userDisabled = prefs.getStringSet("user_disabled_a11y", emptySet()) ?: emptySet()
        val activeProtected = protectedServices.count { it !in userDisabled }

        val running = try {
            Shizuku.pingBinder()
        } catch (t: Throwable) {
            false
        }
        val version = try {
            if (running) Shizuku.getVersion().toString() else ""
        } catch (t: Throwable) {
            ""
        }

        val statusText: String
        val statusDetail: String
        var connected = false
        when {
            !running -> {
                statusText = "未运行"
                statusDetail = "未检测到 Shizuku，请先在 Shizuku 中启动服务"
            }

            !shizukuPermissionGranted -> {
                statusText = "未授权"
                statusDetail = "Shizuku 正在运行，本应用还没有获得授权"
            }

            !serviceReady -> {
                statusText = "连接中"
                statusDetail = "Shizuku 已授权，正在绑定后台服务进程"
            }

            else -> {
                connected = true
                statusText = "已连接"
                statusDetail = "服务进程就绪 · v" + version.ifEmpty { "13.1.5" } + " · 权限已授予"
            }
        }

        // 运行时长：优先 /proc/uptime（与原监控页同口径），
        // 但 Android 15 上该文件对应用进程被 SELinux 拦截，读不到时回落 elapsedRealtime()。
        // elapsedRealtime() 本身包含深睡时间，口径一致，且无需任何权限。
        var uptimeSeconds = 0L
        try {
            val text = java.io.File("/proc/uptime").readText().trim()
            uptimeSeconds = text.split(" ")[0].toDoubleOrNull()?.toLong() ?: 0L
        } catch (_: Throwable) {
            uptimeSeconds = 0L
        }
        if (uptimeSeconds <= 0L) {
            uptimeSeconds = android.os.SystemClock.elapsedRealtime() / 1000L
        }

        var uptimeValue = "未知"
        var uptimeUnit = ""
        if (uptimeSeconds > 0L) {
            val days = uptimeSeconds / 86400
            val hours = (uptimeSeconds % 86400) / 3600
            val minutes = (uptimeSeconds % 3600) / 60
            when {
                days > 0 -> {
                    uptimeValue = days.toString()
                    uptimeUnit = "天 " + hours + " 时"
                }
                hours > 0 -> {
                    uptimeValue = hours.toString()
                    uptimeUnit = "时 " + minutes + " 分"
                }
                else -> {
                    uptimeValue = minutes.toString()
                    uptimeUnit = "分"
                }
            }
        }

        homeState = homeState.copy(
            statusText = statusText,
            statusDetail = statusDetail,
            connected = connected,
            hiddenCount = hidden.size,
            protectedCount = activeProtected,
            keepAlive = prefs.getBoolean("keep_alive_enabled", false),
            statusNotification = prefs.getBoolean("show_status_notification", false),
            excludeFromRecents = prefs.getBoolean("hide_from_recents", false),
            uptimeValue = uptimeValue,
            uptimeUnit = uptimeUnit,
        )


        loadHiddenPreview(hidden)
    }

    private fun loadHiddenPreview(hidden: Set<String>) {
        if (hidden.isEmpty()) {
            homeState = homeState.copy(hiddenPreview = emptyList())
            return
        }
        val packages = hidden.take(8)
        Thread {
            val pm = packageManager
            val list = packages.map { pkg ->
                val label = try {
                    pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
                } catch (t: Throwable) {
                    pkg
                }
                HiddenPreview(label = label, seed = pkg.hashCode())
            }
            runOnUiThread { homeState = homeState.copy(hiddenPreview = list) }
        }.start()
    }

    // ------------------------------------------------------------------ Shizuku

    private fun setupShizuku() {
        Shizuku.addRequestPermissionResultListener(shizukuPermissionResultListener)
        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
        checkShizukuPermission()
    }

    private fun checkShizukuPermission() {
        if (!Shizuku.pingBinder() || Shizuku.isPreV11()) {
            shizukuPermissionGranted = false
            serviceReady = false
            refreshState()
            return
        }

        // 开屏期间 AppInit 已完成权限检查与绑定，直接复用
        if (AppInit.shizukuPermissionGranted) {
            shizukuPermissionGranted = true
            val prebound = AppInit.taskHideService
            if (AppInit.shizukuServiceBound && prebound != null) {
                taskHideService = prebound
                serviceReady = true
                Log.d(TAG, "Shizuku service reused from AppInit")
                refreshState()
                autoGrantAllPermissions()
            } else {
                bindShizukuService()
            }
            refreshState()
            return
        }

        if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
            shizukuPermissionGranted = true
            bindShizukuService()
        } else {
            Shizuku.requestPermission(1001)
        }
        refreshState()
    }

    private fun bindShizukuService() {
        try {
            Shizuku.bindUserService(
                Shizuku.UserServiceArgs(ComponentName(this, TaskHideService::class.java))
                    .daemon(false).processNameSuffix("taskhide").debuggable(true).version(2),
                serviceConnection,
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to bind Shizuku service", e)
        }
    }

    // ------------------------------------------------------------------ 自动授权

    private fun autoGrantPermissions() {
        Thread {
            var wait = 0
            while (!serviceReady && wait < 20) {
                Thread.sleep(500)
                wait++
            }
            if (!serviceReady) return@Thread

            val pkg = packageName
            val perms = listOf(
                "android.permission.READ_PHONE_STATE",
                "android.permission.ACCESS_FINE_LOCATION",
                "android.permission.ACCESS_COARSE_LOCATION",
            )
            for (perm in perms) {
                try {
                    executeCommandSync("pm grant $pkg $perm")
                } catch (_: Exception) {
                }
                try {
                    val op = when (perm) {
                        "android.permission.READ_PHONE_STATE" -> "READ_PHONE_STATE"
                        "android.permission.ACCESS_FINE_LOCATION" -> "COARSE_LOCATION"
                        "android.permission.ACCESS_COARSE_LOCATION" -> "COARSE_LOCATION"
                        else -> continue
                    }
                    executeCommandSync("appops set $pkg $op allow")
                } catch (_: Exception) {
                }
            }

            try {
                executeCommandSync("cmd appops set $pkg RUN_IN_BACKGROUND allow")
                executeCommandSync("cmd appops set $pkg RUN_ANY_IN_BACKGROUND allow")
                executeCommandSync("cmd appops set $pkg START_FOREGROUND allow")
                executeCommandSync("cmd appops set $pkg WAKE_LOCK allow")
                executeCommandSync("dumpsys deviceidle whitelist +$pkg")
                Log.d(TAG, "Background keepalive permissions granted")
            } catch (_: Exception) {
            }
        }.start()
    }

    private fun autoGrantAllPermissions() {
        if (!serviceReady) return
        Thread {
            try {
                val componentName = ComponentName(this, NotificationListenerServiceImpl::class.java).flattenToString()
                executeCommandSync("cmd notification allow_listener $componentName")
                Log.d(TAG, "Auto-granted notification permission")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to auto-grant notification permission", e)
            }
            try {
                executeCommandSync("appops set $packageName QUERY_ALL_PACKAGES allow")
                Log.d(TAG, "Auto-granted QUERY_ALL_PACKAGES")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to auto-grant QUERY_ALL_PACKAGES", e)
            }
        }.start()
    }

    // ------------------------------------------------------------------ 无障碍保活

    private fun ensureAccessibilityKeepAlive() {
        Thread {
            var wait = 0
            while (!serviceReady && wait < 20) {
                Thread.sleep(500)
                wait++
            }
            if (!serviceReady) return@Thread

            val enabled = Settings.Secure.getString(
                contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ) ?: ""
            val myCn = "$packageName/${AccessibilityKeepAliveService::class.java.name}"
            // 组件名可能是短形式或全形式，统一解析后比较，不要用字符串 contains
            val listed = enabled.split(':')
                .filter { it.isNotBlank() }
                .mapNotNull { ComponentName.unflattenFromString(it.trim()) }
                .any {
                    it.packageName == packageName &&
                        it.className == AccessibilityKeepAliveService::class.java.name
                }
            if (!listed) {
                val newValue = if (enabled.isEmpty()) myCn else "$enabled:$myCn"
                executeCommandSync("settings put secure enabled_accessibility_services $newValue")
                executeCommandSync("settings put secure accessibility_enabled 1")
                Log.d(TAG, "Re-enabled AccessibilityKeepAliveService")
            }

            val protectedServices = prefs.getStringSet("protected_a11y_services", emptySet()) ?: emptySet()
            val userDisabled = prefs.getStringSet("user_disabled_a11y", emptySet()) ?: emptySet()
            if (prefs.getBoolean("a11y_auto_protect", false) && protectedServices.isNotEmpty()) {
                val current = Settings.Secure.getString(
                    contentResolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                ) ?: ""
                val currentSet = current.split(':').filter { it.isNotBlank() }.toSet()
                val needRestore = protectedServices.filter { it !in currentSet && it !in userDisabled }
                if (needRestore.isNotEmpty()) {
                    val newValue = if (current.isEmpty()) {
                        needRestore.joinToString(":")
                    } else {
                        "$current:${needRestore.joinToString(":")}"
                    }
                    executeCommandSync("settings put secure enabled_accessibility_services $newValue")
                    executeCommandSync("settings put secure accessibility_enabled 1")
                    Log.d(TAG, "MainActivity restored ${needRestore.size} accessibility services")
                }
            }
        }.start()
    }

    // ------------------------------------------------------------------ 设置项

    private fun toggleExcludeFromRecents(value: Boolean) {
        prefs.edit().putBoolean("hide_from_recents", value).apply()
        applyExcludeFromRecents(value)
        refreshState()
        Toast.makeText(this, if (value) "已从最近任务中移除" else "已恢复最近任务卡片", Toast.LENGTH_SHORT).show()
    }

    private fun refreshKeepAliveGuideState() {
        keepAliveBatteryOk = KeepAliveGuard.batteryOk(this)
        keepAliveAutoStartOk = KeepAliveGuard.autoStartOk(this)
        keepAliveAutoStartUnknown = KeepAliveGuard.autoStartUnknown(this)
        keepAliveManualAutoStart = KeepAliveGuard.manualAutoStart(this)
    }

    private fun enableKeepAlive() {
        prefs.edit().putBoolean("keep_alive_enabled", true).apply()
        KeepAliveService.start(this)
        showKeepAliveGuide = false
        refreshState()
        Toast.makeText(this, "已开启后台保活", Toast.LENGTH_SHORT).show()
    }

    private fun finishKeepAliveGuide() {
        refreshKeepAliveGuideState()
        if (!KeepAliveGuard.allSatisfied(this)) {
            Toast.makeText(this, "还有设置没完成，保活暂未开启", Toast.LENGTH_SHORT).show()
            return
        }
        enableKeepAlive()
    }

    private fun toggleKeepAlive(value: Boolean) {
        if (value) {
            // 只有「电池优化 + 自启动」都满足才真正打开开关；
            // 否则先弹引导，开关保持关闭（用户只点「稍后再说」时也不会被打开）。
            refreshKeepAliveGuideState()
            if (KeepAliveGuard.allSatisfied(this)) {
                enableKeepAlive()
            } else {
                showKeepAliveGuide = true
                refreshState()
                Toast.makeText(this, "请先完成电池优化与自启动设置", Toast.LENGTH_SHORT).show()
            }
        } else {
            prefs.edit().putBoolean("keep_alive_enabled", false).apply()
            KeepAliveService.stop(this)
            refreshState()
            Toast.makeText(this, "已关闭后台保活", Toast.LENGTH_SHORT).show()
        }
    }

    /** 保活开关对应的常驻服务：开启则拉起，关闭则停掉 */
    private fun syncKeepAliveService() {
        if (prefs.getBoolean("keep_alive_enabled", false)) {
            KeepAliveService.start(this)
        } else {
            KeepAliveService.stop(this)
        }
    }

    private fun toggleNotification(value: Boolean) {
        if (!value) {
            prefs.edit().putBoolean("show_status_notification", false).apply()
            updateStatusNotification()
            refreshState()
            Toast.makeText(this, "已关闭状态通知", Toast.LENGTH_SHORT).show()
            return
        }

        val needsPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            androidx.core.content.ContextCompat.checkSelfPermission(
                this,
                android.Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED

        if (needsPermission) {
            pendingEnableNotification = true
            handler.postDelayed({
                androidx.core.app.ActivityCompat.requestPermissions(
                    this,
                    arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
                    REQ_NOTIFICATION,
                )
            }, 200)
        } else {
            enableStatusNotification()
        }
    }

    private fun enableStatusNotification() {
        prefs.edit().putBoolean("show_status_notification", true).apply()
        updateStatusNotification()
        refreshState()
        Toast.makeText(this, "已开启状态通知", Toast.LENGTH_SHORT).show()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_NOTIFICATION && pendingEnableNotification) {
            pendingEnableNotification = false
            enableStatusNotification()
            if (grantResults.isEmpty() || grantResults[0] != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "通知权限未授予，通知可能被系统静默", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun openBatteryOptimizationSettings() {
        try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            intent.data = android.net.Uri.parse("package:$packageName")
            startActivity(intent)
        } catch (e: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (_: Exception) {
                Toast.makeText(this, "无法打开电池优化设置，请手动在系统设置中关闭", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun openAutoStartSettings() {
        val intents = listOf(
            Intent().setComponent(
                ComponentName(
                    "com.miui.securitycenter",
                    "com.miui.permcenter.autostart.AutoStartManagementActivity",
                ),
            ),
            Intent().setComponent(
                ComponentName(
                    "com.coloros.safecenter",
                    "com.coloros.safecenter.startupapp.StartupAppListActivity",
                ),
            ),
            Intent().setComponent(
                ComponentName(
                    "com.oppo.safe",
                    "com.oppo.safe.permission.startup.StartupAppListActivity",
                ),
            ),
            Intent().setComponent(
                ComponentName(
                    "com.vivo.permissionmanager",
                    "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
                ),
            ),
            Intent().setComponent(
                ComponentName(
                    "com.huawei.systemmanager",
                    "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
                ),
            ),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:$packageName")),
        )

        for (intent in intents) {
            try {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
                return
            } catch (_: Exception) {
            }
        }

        try {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:$packageName")),
            )
        } catch (_: Exception) {
            Toast.makeText(this, "无法打开自启动设置，请手动在系统设置中查找", Toast.LENGTH_SHORT).show()
        }
    }

    // ------------------------------------------------------------------ 通知

    private val notifChannelId = "system_tool_status"
    private val notifId = 4001

    private fun updateStatusNotification() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        if (!prefs.getBoolean("show_status_notification", false)) {
            nm.cancel(notifId)
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = android.app.NotificationChannel(
                notifChannelId,
                "状态通知",
                android.app.NotificationManager.IMPORTANCE_LOW,
            )
            channel.description = "显示隐藏和保活状态"
            channel.setShowBadge(false)
            nm.createNotificationChannel(channel)
        }

        val hiddenApps = prefs.getStringSet("hidden_apps", emptySet()) ?: emptySet()
        val protectedServices = prefs.getStringSet("protected_a11y_services", emptySet()) ?: emptySet()
        val userDisabled = prefs.getStringSet("user_disabled_a11y", emptySet()) ?: emptySet()
        val activeProtected = protectedServices.count { it !in userDisabled }

        val text = buildString {
            append("已隐藏 ${hiddenApps.size} 个应用")
            if (activeProtected > 0) append(" · 保活 $activeProtected 个无障碍服务")
        }

        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(this, notifChannelId)
            .setContentTitle("System Tool")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_settings)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        nm.notify(notifId, notification)
    }

    private fun applyExcludeFromRecents(exclude: Boolean) {
        try {
            val am = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                for (task in am.appTasks) {
                    val info = task.taskInfo
                    if (info.topActivity?.packageName == packageName) {
                        task.setExcludeFromRecents(exclude)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "applyExcludeFromRecents failed", e)
        }
    }

    // ------------------------------------------------------------------ 手势

    /** 右滑进入无障碍管理（旧版是左滑，按新需求改为右滑）。 */
    private val swipeGestureDetector by lazy {
        GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float,
            ): Boolean {
                if (e1 == null) return false
                val diffX = e2.x - e1.x
                val diffY = e2.y - e1.y
                val horizontal = abs(diffX) > abs(diffY)
                if (horizontal && abs(diffX) > 120f && abs(velocityX) > 80f && diffX > 0) {
                    openAccessibility()
                    return true
                }
                return false
            }
        })
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        swipeGestureDetector.onTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }

    private fun openAccessibility() {
        startActivity(Intent(this, AccessibilityManageActivity::class.java))
        @Suppress("DEPRECATION")
        overridePendingTransition(R.anim.enter_right_to_left, 0)
    }

    // ------------------------------------------------------------------ 命令通道

    private fun executeCommandSync(command: String): String {
        val binder = TaskHideBridge.resolve(taskHideService) ?: return ""
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(ITaskHideService.DESCRIPTOR)
            data.writeString(command)
            binder.transact(ITaskHideService.TRANSACTION_execCommand, data, reply, 0)
            reply.readException()
            val success = reply.readInt() != 0
            val output = reply.readString() ?: ""
            Log.d(TAG, "Command '$command' => success=$success output=${output.take(200)}")
            return output
        } catch (e: Exception) {
            Log.e(TAG, "Command failed: $command", e)
            return ""
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    // ------------------------------------------------------------------ 电池温度

    /**
     * 电池温度接收器。
     *
     * 系统每 10 秒左右主动广播一次 ACTION_BATTERY_CHANGED，比自己轮询省电，
     * 而且不需要任何权限。这是应用进程在 Android 15 上唯一稳定可读的温度源。
     */
    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_BATTERY_CHANGED) {
                applyBatteryTemperature(intent)
            }
        }
    }

    private fun applyBatteryTemperature(intent: Intent) {
        val tenths = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        val text = if (tenths == Int.MIN_VALUE || tenths <= 0) {
            "—"
        } else {
            String.format(java.util.Locale.US, "%.1f", tenths / 10f)
        }
        if (text != homeState.batteryTemp) {
            homeState = homeState.copy(batteryTemp = text)
        }
        // 温度变了就同步岛上的状态（内部按「数量|温度」去重，不会刷屏）
        Unit
    }

    // ------------------------------------------------------------------ 生命周期

    /** 运行时长每分钟刷新一次，不用整页轮询 */
    private val uptimeTicker = object : Runnable {
        override fun run() {
            refreshState()
            handler.postDelayed(this, 60_000L)
        }
    }

    override fun onResume() {
        super.onResume()
        // 从系统设置页返回时重新检测保活条件（引导卡片不关闭）
        if (showKeepAliveGuide) refreshKeepAliveGuideState()
        // 从应用管理 / 工具箱返回时重读偏好：首页「已隐藏 N 个应用」等数字要跟着变，
        // 否则进程没重启时会一直显示 onCreate 时读到的旧值。
        hideGuard = prefs.getBoolean("hide_guard_enabled", true)
        relaunchOnUnhide = prefs.getBoolean("relaunch_on_unhide", true)
        refreshState()
        HiddenGuardService.sync(this)
        applySystemBars()
        refreshState()
        updateStatusNotification()
        handler.removeCallbacks(uptimeTicker)
        handler.postDelayed(uptimeTicker, 60_000L)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(uptimeTicker)
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(batteryReceiver)
        } catch (_: Exception) {
        }
        handler.removeCallbacksAndMessages(null)
        Shizuku.removeRequestPermissionResultListener(shizukuPermissionResultListener)
        Shizuku.removeBinderReceivedListener(binderReceivedListener)
        Shizuku.removeBinderDeadListener(binderDeadListener)
        try {
            Shizuku.unbindUserService(
                Shizuku.UserServiceArgs(ComponentName(this, TaskHideService::class.java)),
                serviceConnection,
                true,
            )
        } catch (_: Exception) {
        }
    }
}
