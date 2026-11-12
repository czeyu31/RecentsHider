package com.example.hiderecents

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import android.provider.Settings
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.accessibility.AccessibilityManager
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import androidx.core.view.WindowCompat
import com.example.hiderecents.ui.screens.A11yActionSheet
import com.example.hiderecents.ui.screens.A11yFilter
import com.example.hiderecents.ui.screens.A11yServiceItem
import com.example.hiderecents.ui.screens.AccessibilityScreen
import com.example.hiderecents.ui.theme.AppTheme
import com.example.hiderecents.ui.theme.applyDshSystemBars
import com.example.hiderecents.ui.theme.DshTheme
import rikka.shizuku.Shizuku
import kotlin.math.abs

/**
 * 无障碍管理（Compose 重写版）。
 *
 * 功能与原 AccessibilityManageActivity 逐条对齐：
 * - AccessibilityManager 枚举服务 + Settings.Secure 判定启用状态
 * - 通过 Shizuku 写 enabled_accessibility_services 开关服务
 * - 防回收保护三方状态机（protected_a11y_services / user_disabled_a11y / a11y_auto_protect）
 * - 守护服务确保开启，失败回落系统无障碍设置页
 * - 四字段搜索、三档筛选、长按置顶与保护、右滑返回
 */
class AccessibilityManageActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "AccessibilityManage"
    }

    private lateinit var prefs: SharedPreferences

    private val protectedServices = mutableSetOf<String>()
    private val pinnedServices = mutableSetOf<String>()

    private var shizukuPermissionGranted = false
    private var taskHideService: IBinder? = null
    private var serviceReady = false

    private var allItems by mutableStateOf(listOf<A11yServiceItem>())
    private var loading by mutableStateOf(true)
    private var query by mutableStateOf("")
    private var filter by mutableStateOf(A11yFilter.ALL)
    private var autoProtect by mutableStateOf(false)
    private var keepAliveEnabled by mutableStateOf(false)
    private var actionItem by mutableStateOf<A11yServiceItem?>(null)

    private val iconCache = mutableMapOf<String, androidx.compose.ui.graphics.ImageBitmap>()

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            taskHideService = service
            serviceReady = true
            loadAccessibilityServices()
            refreshKeepAliveState()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            taskHideService = null
            serviceReady = false
        }
    }

    private val shizukuPermissionResultListener =
        Shizuku.OnRequestPermissionResultListener { _, grantResult ->
            shizukuPermissionGranted = grantResult == PackageManager.PERMISSION_GRANTED
            if (shizukuPermissionGranted) bindService()
        }

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener { checkShizukuPermission() }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        shizukuPermissionGranted = false
        taskHideService = null
        serviceReady = false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        prefs = getSharedPreferences("hide_recents_prefs", Context.MODE_PRIVATE)
        protectedServices.addAll(prefs.getStringSet("protected_a11y_services", emptySet()) ?: emptySet())
        pinnedServices.addAll(prefs.getStringSet("pinned_a11y_services", emptySet()) ?: emptySet())
        autoProtect = prefs.getBoolean("a11y_auto_protect", false)

        val theme = AppTheme.fromPref(prefs.getString("ui_theme", null))
        applySystemBars(theme)

        setContent {
            DshTheme(theme = theme) {
                val visible = filterItems(allItems, query, filter)
                AccessibilityScreen(
                    services = visible,
                    totalCount = allItems.size,
                    enabledCount = allItems.count { it.isEnabled },
                    protectedCount = activeProtectedCount(),
                    autoProtect = autoProtect,
                    keepAliveEnabled = keepAliveEnabled,
                    query = query,
                    onQueryChange = { query = it },
                    filter = filter,
                    onFilterChange = { filter = it },
                    loading = loading,
                    onBack = { finish() },
                    onToggleAutoProtect = { applyAutoProtect(it) },
                    onToggleService = { item, enable -> toggleService(item, enable) },
                    onOpenActions = { actionItem = it },
                    onOpenSystemSettings = { openSystemAccessibilitySettings() },
                )

                actionItem?.let { item ->
                    A11yActionSheet(
                        item = item,
                        onDismiss = { actionItem = null },
                        onTogglePin = {
                            actionItem = null
                            togglePin(item)
                        },
                        onToggleProtect = {
                            actionItem = null
                            toggleProtection(item, !item.isProtected)
                        },
                    )
                }
            }
        }

        Shizuku.addRequestPermissionResultListener(shizukuPermissionResultListener)
        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
        checkShizukuPermission()

        loadAccessibilityServices()
        refreshKeepAliveState()

        // 首次打开自动开启防回收保护与守护服务
        if (!prefs.getBoolean("a11y_first_setup_done", false)) {
            prefs.edit().putBoolean("a11y_auto_protect", true).apply()
            autoProtect = true
            autoAddEnabledToProtected()
            ensureKeepAliveEnabled()
            prefs.edit().putBoolean("a11y_first_setup_done", true).apply()
        }

        // 每次打开都检查守护服务是否在运行
        if (prefs.getBoolean("a11y_auto_protect", false) && !isKeepAliveServiceEnabled()) {
            ensureKeepAliveEnabled()
        }
    }

    override fun onResume() {
        super.onResume()
        loadAccessibilityServices()
        refreshKeepAliveState()
    }

    override fun onDestroy() {
        super.onDestroy()
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

    // ------------------------------------------------------------------ 主题

    private fun applySystemBars(theme: AppTheme) {
        applyDshSystemBars(theme)
    }

    // ------------------------------------------------------------------ Shizuku

    private fun checkShizukuPermission() {
        if (!Shizuku.pingBinder() || Shizuku.isPreV11()) return
        if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
            shizukuPermissionGranted = true
            bindService()
        } else {
            Shizuku.requestPermission(1001)
        }
    }

    private fun bindService() {
        try {
            Shizuku.bindUserService(
                Shizuku.UserServiceArgs(ComponentName(this, TaskHideService::class.java))
                    .daemon(false).processNameSuffix("taskhide").debuggable(true).version(2),
                serviceConnection,
            )
        } catch (_: Exception) {
        }
    }

    // ------------------------------------------------------------------ 守护服务

    private fun ensureKeepAliveEnabled() {
        if (isKeepAliveServiceEnabled()) {
            keepAliveEnabled = true
            return
        }
        Thread {
            val enabled = Settings.Secure.getString(
                contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ) ?: ""
            val myCn = "$packageName/${AccessibilityKeepAliveService::class.java.name}"
            if (parseEnabledComponents(enabled).any { it.matchesKeepAlive() }) {
                runOnUiThread {
                    keepAliveEnabled = true
                    loadAccessibilityServices()
                }
                return@Thread
            }
            val newValue = if (enabled.isEmpty()) myCn else "$enabled:$myCn"
            val success = executeCommandSync("settings put secure enabled_accessibility_services $newValue")
            if (success) {
                executeCommandSync("settings put secure accessibility_enabled 1")
            }
            runOnUiThread {
                keepAliveEnabled = success || isKeepAliveServiceEnabled()
                if (!success) {
                    Toast.makeText(
                        this,
                        "守护服务未能自动开启，请在系统无障碍设置里手动打开 System Tool",
                        Toast.LENGTH_LONG,
                    ).show()
                }
                loadAccessibilityServices()
            }
        }.start()
    }

    /**
     * 判断我们的守护服务是否在系统无障碍列表里。
     *
     * 不能只用字符串 contains：系统可能把组件名规范化成短形式
     * （`pkg/.ClassName`）或全形式（`pkg/full.ClassName`），两种都要能认出来。
     */
    private fun isKeepAliveServiceEnabled(): Boolean {
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: ""
        return parseEnabledComponents(enabledServices).any { it.matchesKeepAlive() }
    }

    private fun parseEnabledComponents(value: String): List<ComponentName> =
        value.split(':')
            .filter { it.isNotBlank() }
            .mapNotNull { ComponentName.unflattenFromString(it.trim()) }

    private fun ComponentName.matchesKeepAlive(): Boolean =
        packageName == this@AccessibilityManageActivity.packageName &&
            className == AccessibilityKeepAliveService::class.java.name

    private fun isKeepAliveComponent(cn: String): Boolean {
        val parsed = ComponentName.unflattenFromString(cn.trim()) ?: return false
        return parsed.packageName == packageName &&
            parsed.className == AccessibilityKeepAliveService::class.java.name
    }

    private fun refreshKeepAliveState() {
        keepAliveEnabled = isKeepAliveServiceEnabled()
    }

    private fun autoAddEnabledToProtected() {
        // 只保护用户明确标记的服务，不自动全量加入
    }

    private fun openSystemAccessibilitySettings() {
        try {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        } catch (_: Exception) {
            Toast.makeText(this, "无法打开系统无障碍设置", Toast.LENGTH_SHORT).show()
        }
    }

    // ------------------------------------------------------------------ 加载

    private fun loadAccessibilityServices() {
        loading = allItems.isEmpty()
        Thread {
            try {
                val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
                val installed = am.installedAccessibilityServiceList ?: emptyList()
                val currentEnabled = getEnabledServices()
                val pm = packageManager

                val items = installed.mapNotNull { info ->
                    try {
                        val si = info.resolveInfo.serviceInfo
                        val cn = ComponentName(si.packageName, si.name).flattenToString()
                        val appInfo = pm.getApplicationInfo(si.packageName, 0)
                        val appName = pm.getApplicationLabel(appInfo).toString()
                        val isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                        A11yServiceItem(
                            componentName = cn,
                            packageName = si.packageName,
                            appName = appName,
                            serviceName = si.name.substringAfterLast('.'),
                            description = info.description?.toString() ?: "",
                            icon = loadIconBitmap(pm, si.packageName),
                            isEnabled = cn in currentEnabled,
                            isProtected = cn in protectedServices,
                            isPinned = cn in pinnedServices,
                            isSystem = isSystem,
                            seed = cn.hashCode(),
                        )
                    } catch (_: Exception) {
                        null
                    }
                }

                val sorted = if (consumeStableSortFlag()) sortItemsStable(items) else sortItemsInitial(items)

                runOnUiThread {
                    allItems = sorted
                    loading = false
                    keepAliveEnabled = isKeepAliveServiceEnabled()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load accessibility services", e)
                runOnUiThread { loading = false }
            }
        }.start()
    }

    private fun loadIconBitmap(pm: PackageManager, pkg: String): androidx.compose.ui.graphics.ImageBitmap? {
        iconCache[pkg]?.let { return it }
        return try {
            val drawable = AppIconLoader.getCached(pkg)
                ?: pm.getApplicationIcon(pm.getApplicationInfo(pkg, 0)).also {
                    AppIconLoader.put(pkg, it)
                }
            val bitmap = AppIconLoader.toSafeBitmap(drawable, 96).asImageBitmap()
            iconCache[pkg] = bitmap
            bitmap
        } catch (_: Exception) {
            null
        }
    }

    @Volatile
    private var nextSortStable = false

    /** 进入页面时的排序：已开启 / 已保护的服务排到最上面。 */
    private fun sortItemsInitial(list: List<A11yServiceItem>): List<A11yServiceItem> =
        list.sortedWith(
            compareByDescending<A11yServiceItem> { it.isPinned }
                .thenByDescending { it.isProtected }
                .thenByDescending { it.isEnabled }
                .thenBy { it.appName.lowercase() },
        )

    /**
     * 开关变化时的稳定排序：只看置顶 + 名称，位置原地不动。
     * 旧实现把 isProtected / isEnabled 放进比较器，一按开关整列表就跳到顶部。
     */
    private fun sortItemsStable(list: List<A11yServiceItem>): List<A11yServiceItem> =
        list.sortedWith(
            compareByDescending<A11yServiceItem> { it.isPinned }
                .thenBy { it.appName.lowercase() },
        )

    private fun consumeStableSortFlag(): Boolean {
        val v = nextSortStable
        nextSortStable = false
        return v
    }

    /** 开关后重新读取系统状态，但保持原地顺序（不把该项顶到最前）。 */
    private fun reloadItemsInPlace() {
        nextSortStable = true
        loadAccessibilityServices()
    }
    private fun filterItems(
        source: List<A11yServiceItem>,
        q: String,
        f: A11yFilter,
    ): List<A11yServiceItem> {
        var list = source
        when (f) {
            A11yFilter.ALL -> Unit
            A11yFilter.USER -> list = list.filter { !it.isSystem }
            A11yFilter.PROTECTED -> list = list.filter { it.isProtected }
        }
        if (q.isNotBlank()) {
            val needle = q.trim().lowercase()
            list = list.filter {
                it.appName.lowercase().contains(needle) ||
                    it.packageName.lowercase().contains(needle) ||
                    it.serviceName.lowercase().contains(needle) ||
                    it.description.lowercase().contains(needle)
            }
        }
        return list
    }

    private fun getEnabledServices(): Set<String> {
        val result = mutableSetOf<String>()
        try {
            val flat = Settings.Secure.getString(
                contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            )
            if (!flat.isNullOrEmpty()) {
                flat.split(':').forEach { component ->
                    if (component.isNotBlank()) result.add(component.trim())
                }
            }
        } catch (_: Exception) {
        }
        return result
    }

    private fun activeProtectedCount(): Int {
        val userDisabled = prefs.getStringSet("user_disabled_a11y", emptySet()) ?: emptySet()
        return protectedServices.count { it !in userDisabled }
    }

    // ------------------------------------------------------------------ 开关服务

    private fun toggleService(item: A11yServiceItem, enable: Boolean) {
        if (!shizukuPermissionGranted || !serviceReady) {
            Toast.makeText(this, "需要 Shizuku 权限来管理无障碍服务", Toast.LENGTH_SHORT).show()
            return
        }
        Thread {
            val result = if (enable) {
                enableAccessibilityService(item.componentName)
            } else {
                disableAccessibilityService(item.componentName)
            }
            runOnUiThread {
                if (result) {
                    Toast.makeText(this, if (enable) "已启用" else "已禁用", Toast.LENGTH_SHORT).show()
                    val userDisabled = prefs.getStringSet("user_disabled_a11y", mutableSetOf())?.toMutableSet()
                        ?: mutableSetOf()
                    if (enable && prefs.getBoolean("a11y_auto_protect", false)) {
                        protectedServices.add(item.componentName)
                        prefs.edit().putStringSet("protected_a11y_services", protectedServices).apply()
                        userDisabled.remove(item.componentName)
                        prefs.edit().putStringSet("user_disabled_a11y", userDisabled).apply()
                    } else if (!enable) {
                        protectedServices.remove(item.componentName)
                        prefs.edit().putStringSet("protected_a11y_services", protectedServices).apply()
                        userDisabled.add(item.componentName)
                        prefs.edit().putStringSet("user_disabled_a11y", userDisabled).apply()
                    }
                } else {
                    Toast.makeText(this, "操作失败", Toast.LENGTH_SHORT).show()
                }
                reloadItemsInPlace()
            }
        }.start()
    }

    private fun enableAccessibilityService(componentName: String): Boolean {
        if (!serviceReady) return false
        return try {
            val current = Settings.Secure.getString(
                contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ) ?: ""
            val services = current.split(':').filter { it.isNotBlank() }.toMutableList()
            if (componentName !in services) services.add(componentName)
            val newValue = services.joinToString(":")
            val success = executeCommandSync("settings put secure enabled_accessibility_services $newValue")
            executeCommandSync("settings put secure accessibility_enabled 1")
            success
        } catch (e: Exception) {
            Log.e(TAG, "enableAccessibilityService failed", e)
            false
        }
    }

    private fun disableAccessibilityService(componentName: String): Boolean {
        if (!serviceReady) return false
        return try {
            val current = Settings.Secure.getString(
                contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ) ?: ""
            val services = current.split(':')
                .filter { it.isNotBlank() && it != componentName }
                .toMutableList()
            val newValue = services.joinToString(":")
            val success = executeCommandSync("settings put secure enabled_accessibility_services $newValue")
            if (services.isEmpty()) executeCommandSync("settings put secure accessibility_enabled 0")
            success
        } catch (e: Exception) {
            Log.e(TAG, "disableAccessibilityService failed", e)
            false
        }
    }

    // ------------------------------------------------------------------ 保护 / 置顶

    private fun applyAutoProtect(value: Boolean) {
        autoProtect = value
        prefs.edit().putBoolean("a11y_auto_protect", value).apply()
        if (value) {
            ensureKeepAliveEnabled()
            autoAddEnabledToProtected()
        }
        loadAccessibilityServices()
    }

    private fun toggleProtection(item: A11yServiceItem, protect: Boolean) {
        if (protect) {
            protectedServices.add(item.componentName)
            val userDisabled = prefs.getStringSet("user_disabled_a11y", mutableSetOf())?.toMutableSet()
                ?: mutableSetOf()
            userDisabled.remove(item.componentName)
            prefs.edit().putStringSet("user_disabled_a11y", userDisabled).apply()
        } else {
            protectedServices.remove(item.componentName)
        }
        prefs.edit().putStringSet("protected_a11y_services", protectedServices).apply()
        allItems = allItems.map {
            if (it.componentName == item.componentName) it.copy(isProtected = protect) else it
        }
        reloadItemsInPlace()
    }

    private fun togglePin(item: A11yServiceItem) {
        if (item.componentName in pinnedServices) {
            pinnedServices.remove(item.componentName)
            Toast.makeText(this, "已取消置顶", Toast.LENGTH_SHORT).show()
        } else {
            pinnedServices.add(item.componentName)
            Toast.makeText(this, "已置顶", Toast.LENGTH_SHORT).show()
        }
        prefs.edit().putStringSet("pinned_a11y_services", pinnedServices).apply()
        allItems = allItems.map {
            if (it.componentName == item.componentName) {
                it.copy(isPinned = pinnedServices.contains(item.componentName))
            } else {
                it
            }
        }.sortedWith(
            compareByDescending<A11yServiceItem> { it.isPinned }
                .thenByDescending { it.isProtected }
                .thenByDescending { it.isEnabled }
                .thenBy { it.appName.lowercase() },
        )
    }

    // ------------------------------------------------------------------ 命令通道

    private fun executeCommandSync(command: String): Boolean {
        val binder = taskHideService ?: return false
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(ITaskHideService.DESCRIPTOR)
            data.writeString(command)
            binder.transact(ITaskHideService.TRANSACTION_execCommand, data, reply, 0)
            reply.readException()
            return reply.readInt() != 0
        } catch (e: Exception) {
            Log.e(TAG, "Command failed: $command", e)
            return false
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    // ------------------------------------------------------------------ 右滑返回

    private val swipeBackDetector by lazy {
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
                if (abs(diffX) > 100 && abs(velocityX) > 100 && abs(diffX) > abs(diffY) && diffX > 0) {
                    finish()
                    return true
                }
                return false
            }
        })
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        swipeBackDetector.onTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }
}
