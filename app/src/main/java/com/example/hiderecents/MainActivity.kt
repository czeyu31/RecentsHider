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
import androidx.core.view.WindowCompat
import com.example.hiderecents.ft.FtOnline
import com.example.hiderecents.ft.FtOnlineService
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
    // 用户服务连不上、但服务端 newProcess 通道可用：Shizuku NEXT 这类分支的典型状态
    private var shellFallback by mutableStateOf(false)
    private var pendingEnableNotification = false

    private var homeState by mutableStateOf(HomeUiState())
    private var appTheme by mutableStateOf(AppTheme.SYSTEM)
    private var showSettings by mutableStateOf(false)
    private var hideGuard by mutableStateOf(true)
    private var keepAliveBatteryOk by mutableStateOf(false)
    private var keepAliveAutoStartOk by mutableStateOf(false)
    private var keepAliveAutoStartUnknown by mutableStateOf(false)
    private var keepAliveManualAutoStart by mutableStateOf(false)
    private var keepAlivePowerPolicyNeeded by mutableStateOf(false)
    private var keepAlivePowerPolicyOk by mutableStateOf(true)
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

        AppLogger.init(this)
        AppLogger.i("MainActivity onCreate (compose)")

        prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        appTheme = AppTheme.fromPref(prefs.getString("ui_theme", null))
        hideGuard = prefs.getBoolean("hide_guard_enabled", true)
        applySystemBars()

        // 上次开着「在线接收」的话，开机进来就把接收端捞起来
        if (FtOnline.isOn(this)) FtOnlineService.start(this)

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
                        onThemeChange = { setTheme(it) },
                        onToggleKeepAlive = { toggleKeepAlive(it) },
                        onToggleNotification = { toggleNotification(it) },
                        onToggleExclude = { toggleExcludeFromRecents(it) },
                        onHideGuardChange = { applyHideGuard(it) },
                        onDismiss = { showSettings = false },
                    )
                }

                if (showKeepAliveGuide) {
                    KeepAliveGuideDialog(
                        batteryOk = keepAliveBatteryOk,
                        autoStartOk = keepAliveAutoStartOk,
                        autoStartUnknown = keepAliveAutoStartUnknown,
                        manualAutoStart = keepAliveManualAutoStart,
                        powerPolicyNeeded = keepAlivePowerPolicyNeeded,
                        powerPolicyOk = keepAlivePowerPolicyOk,
                        // 点「电池优化 / 自启动」不再关闭卡片：跳系统页，回来自动重新检测
                        onOpenBatterySettings = { KeepAliveGuard.openBatterySettings(this@MainActivity) },
                        onOpenAutoStartSettings = { KeepAliveGuard.openAutoStartSettings(this@MainActivity) },
                        onManualAutoStartChange = {
                            KeepAliveGuard.setManualAutoStart(this@MainActivity, it)
                            refreshKeepAliveGuideState()
                        },
                        onOpenPowerPolicySettings = { KeepAliveGuard.openPowerPolicySettings(this@MainActivity) },
                        onManualPowerPolicyChange = {
                            KeepAliveGuard.setManualPowerPolicy(this@MainActivity, it)
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

            !serviceReady && shellFallback -> {
                statusText = "兼容模式"
                statusDetail = "当前 Shizuku 起不了后台服务，命令类功能可用；隐藏/恢复请换回官方 Shizuku"
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

            ensureA11yServiceEnabled(AccessibilityKeepAliveService::class.java)

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

    /** 把本应用的一个无障碍服务写进 enabled_accessibility_services（已启用则跳过） */
    private fun ensureA11yServiceEnabled(cls: Class<*>) {
        val enabled = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: ""
        // 组件名可能是短形式或全形式，统一解析后比较，不要用字符串 contains
        val listed = enabled.split(':')
            .filter { it.isNotBlank() }
            .mapNotNull { ComponentName.unflattenFromString(it.trim()) }
            .any { it.packageName == packageName && it.className == cls.name }
        if (listed) return
        val cn = "$packageName/" + cls.name
        val newValue = if (enabled.isEmpty()) cn else "$enabled:$cn"
        executeCommandSync("settings put secure enabled_accessibility_services $newValue")
        executeCommandSync("settings put secure accessibility_enabled 1")
        Log.d(TAG, "Re-enabled " + cls.simpleName)
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
        keepAlivePowerPolicyNeeded = KeepAliveGuard.hasVendorPowerPolicy(this)
        keepAlivePowerPolicyOk = KeepAliveGuard.manualPowerPolicy(this)
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
            // 只有「电池优化 + 自启动 + 厂商省电策略」都满足才真正打开开关；
            // 否则先弹引导，开关保持关闭（用户只点「稍后再说」时也不会被打开）。
            refreshKeepAliveGuideState()
            if (KeepAliveGuard.allSatisfied(this)) {
                enableKeepAlive()
            } else {
                showKeepAliveGuide = true
                refreshState()
                val hint = if (keepAlivePowerPolicyNeeded) {
                    "请先完成电池优化、自启动与省电策略设置"
                } else {
                    "请先完成电池优化与自启动设置"
                }
                Toast.makeText(this, hint, Toast.LENGTH_SHORT).show()
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

    private fun updateStatusNotification() {
        // 全应用只有这一条常驻通知（已隐藏几个应用 / 无障碍开着几个应用 / 是否在线），
        // 内容与渠道都在 StatusCenter 里统一维护。
        runCatching { StatusCenter.refresh(this) }
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
        val res = ShizukuShell.exec(TaskHideBridge.resolve(taskHideService), command)
        Log.d(TAG, "Command '$command' => success=" + res.first + " output=" + res.second.take(200))
        return res.second
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
        // 工具箱里也能改外观：回到首页要跟着换，否则会一直停在 onCreate 时读到的旧主题
        val prefTheme = AppTheme.fromPref(prefs.getString("ui_theme", null))
        if (prefTheme != appTheme) {
            appTheme = prefTheme
        }
        refreshState()
        HiddenGuardService.sync(this)
        applySystemBars()
        refreshState()
        updateStatusNotification()
        handler.removeCallbacks(uptimeTicker)
        handler.postDelayed(uptimeTicker, 60_000L)
        probeShellFallback()
    }

    /**
     * 用户服务连不上时探测 newProcess 通道：能用就把首页状态从「连接中」改成「兼容模式」，
     * 免得一直卡在连接中让人以为坏了。官方 Shizuku 上 serviceReady 通常早已为 true，直接跳过。
     */
    private fun probeShellFallback() {
        if (serviceReady) {
            if (shellFallback) shellFallback = false
            return
        }
        Thread {
            val ok = ShizukuShell.probe()
            Log.d(TAG, "shell fallback probe=" + ok)
            runOnUiThread {
                if (serviceReady) return@runOnUiThread
                if (shellFallback != ok) {
                    shellFallback = ok
                    refreshState()
                }
            }
        }.start()
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