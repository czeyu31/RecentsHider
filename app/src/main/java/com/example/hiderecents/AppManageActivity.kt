package com.example.hiderecents

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.IBinder
import android.os.Parcel
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.core.graphics.drawable.toBitmap
import androidx.core.view.WindowCompat
import com.example.hiderecents.ui.components.DshGhostButton
import com.example.hiderecents.ui.screens.AppActionSheet
import com.example.hiderecents.ui.screens.AppDetailDialog
import com.example.hiderecents.ui.screens.AppDetailInfo
import com.example.hiderecents.ui.screens.AppFilter
import com.example.hiderecents.ui.screens.AppManageScreen
import com.example.hiderecents.ui.screens.ManagedApp
import com.example.hiderecents.ui.theme.AppTheme
import com.example.hiderecents.ui.theme.Dsh
import com.example.hiderecents.ui.theme.DshTheme
import rikka.shizuku.Shizuku
import java.io.File

/**
 * 应用管理（Compose 重写版）。
 *
 * 功能与原 AppManageActivity 逐条对齐：
 * - Shizuku 绑定后用 `pm list packages` 拿全量包名，失败回落本地 getInstalledApplications
 * - 搜索（应用名 + 包名）、系统应用过滤（改为三档筛选）、隐藏/恢复、全部恢复
 * - 长按操作面板：置顶 / 应用详情 / 提取 APK / 卸载（保留或删除数据）
 * - 应用详情：版本、版本号、targetSdk、minSdk、权限清单、系统设置入口
 */
class AppManageActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "AppManage"
    }

    private lateinit var prefs: SharedPreferences

    private val hiddenApps = mutableSetOf<String>()
    private val pinnedApps = mutableSetOf<String>()

    private var shizukuPermissionGranted = false
    private var taskHideService: IBinder? = null
    private var serviceReady = false

    private var allApps by mutableStateOf(listOf<ManagedApp>())
    private var loading by mutableStateOf(true)
    private var query by mutableStateOf("")
    private var filter by mutableStateOf(AppFilter.USER)
    private var actionApp by mutableStateOf<ManagedApp?>(null)
    private var detailInfo by mutableStateOf<AppDetailInfo?>(null)
    private var pendingUninstall by mutableStateOf<ManagedApp?>(null)

    private val iconCache = mutableMapOf<String, ImageBitmap>()

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            taskHideService = service
            serviceReady = true
            loadInstalledApps()
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
        hiddenApps.addAll(prefs.getStringSet("hidden_apps", emptySet()) ?: emptySet())
        pinnedApps.addAll(prefs.getStringSet("pinned_apps", emptySet()) ?: emptySet())

        val theme = AppTheme.fromPref(prefs.getString("ui_theme", null))
        applySystemBars(theme)

        setContent {
            DshTheme(theme = theme) {
                val visible = filterApps(allApps, query, filter)
                AppManageScreen(
                    apps = visible,
                    totalCount = allApps.size,
                    hiddenCount = allApps.count { it.isHidden },
                    query = query,
                    onQueryChange = { query = it },
                    filter = filter,
                    onFilterChange = { filter = it },
                    loading = loading,
                    onBack = { finish() },
                    onToggleHide = { app, hide -> toggleAppVisibility(app.packageName, hide) },
                    onOpenActions = { actionApp = it },
                    onRestoreAll = { restoreAll() },
                )

                actionApp?.let { app ->
                    AppActionSheet(
                        app = app,
                        onDismiss = { actionApp = null },
                        onTogglePin = {
                            actionApp = null
                            toggleAppPin(app)
                        },
                        onShowDetail = {
                            actionApp = null
                            showAppDetail(app)
                        },
                        onExtractApk = {
                            actionApp = null
                            extractApk(app.packageName, app.appName)
                        },
                        onUninstall = {
                            actionApp = null
                            pendingUninstall = app
                        },
                    )
                }

                detailInfo?.let { info ->
                    AppDetailDialog(
                        detail = info,
                        onDismiss = { detailInfo = null },
                        onExtractApk = {
                            detailInfo = null
                            extractApk(info.packageName, info.appName)
                        },
                        onOpenSystemSettings = {
                            detailInfo = null
                            openSystemSettings(info.packageName)
                        },
                    )
                }

                pendingUninstall?.let { app ->
                    UninstallConfirmDialog(
                        appName = app.appName,
                        onDismiss = { pendingUninstall = null },
                        onKeepData = {
                            pendingUninstall = null
                            uninstallApp(app.packageName, false)
                        },
                        onDeleteData = {
                            pendingUninstall = null
                            uninstallApp(app.packageName, true)
                        },
                    )
                }
            }
        }

        Shizuku.addRequestPermissionResultListener(shizukuPermissionResultListener)
        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
        checkShizukuPermission()
        loadInstalledApps()
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

    @Suppress("DEPRECATION")
    private fun applySystemBars(theme: AppTheme) {
        val systemDark = (resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        val paper = when (theme) {
            AppTheme.SYSTEM -> !systemDark
            AppTheme.PAPER -> true
            AppTheme.GRAPHITE -> false
        }
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.isAppearanceLightStatusBars = paper
        controller.isAppearanceLightNavigationBars = paper
        val color = if (paper) 0xFFF1EDE3.toInt() else 0xFF0F1214.toInt()
        window.statusBarColor = color
        window.navigationBarColor = color
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
                    .daemon(false).processNameSuffix("taskhide").debuggable(true).version(1),
                serviceConnection,
            )
        } catch (_: Exception) {
        }
    }

    // ------------------------------------------------------------------ 加载

    private fun loadInstalledApps() {
        loading = true
        Thread {
            try {
                val binder = taskHideService
                if (binder == null) {
                    runOnUiThread { loadInstalledAppsLocal() }
                    return@Thread
                }
                val data = Parcel.obtain()
                val reply = Parcel.obtain()
                try {
                    data.writeInterfaceToken(ITaskHideService.DESCRIPTOR)
                    data.writeString("pm list packages")
                    binder.transact(ITaskHideService.TRANSACTION_execCommand, data, reply, 0)
                    reply.readException()
                    val success = reply.readInt() != 0
                    val output = reply.readString() ?: ""
                    Log.d(TAG, "pm list success=$success lines=${output.lines().size}")
                    if (success && output.isNotBlank()) {
                        val pm = packageManager
                        val names = output.lines()
                            .map { it.removePrefix("package:").trim() }
                            .filter { it.isNotEmpty() }
                        val list = names.mapNotNull { pkg -> toManagedApp(pm, pkg) }
                        val sorted = sortApps(list)
                        runOnUiThread {
                            allApps = sorted
                            loading = false
                        }
                    } else {
                        runOnUiThread { loadInstalledAppsLocal() }
                    }
                } finally {
                    data.recycle()
                    reply.recycle()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load via Shizuku", e)
                runOnUiThread { loadInstalledAppsLocal() }
            }
        }.start()
    }

    private fun loadInstalledAppsLocal() {
        val pm = packageManager
        val list = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            .filter { pm.getLaunchIntentForPackage(it.packageName) != null }
            .mapNotNull { toManagedApp(pm, it.packageName) }
        allApps = sortApps(list)
        loading = false
    }

    private fun toManagedApp(pm: PackageManager, pkg: String): ManagedApp? {
        return try {
            val info = pm.getApplicationInfo(pkg, 0)
            val isSystem = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            ManagedApp(
                packageName = pkg,
                appName = pm.getApplicationLabel(info).toString(),
                icon = loadIconBitmap(pm, pkg),
                isHidden = hiddenApps.contains(pkg),
                isSystem = isSystem,
                isPinned = pinnedApps.contains(pkg),
                seed = pkg.hashCode(),
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun loadIconBitmap(pm: PackageManager, pkg: String): ImageBitmap? {
        iconCache[pkg]?.let { return it }
        return try {
            val drawable = AppIconLoader.getCached(pkg)
                ?: pm.getApplicationIcon(pm.getApplicationInfo(pkg, 0)).also {
                    AppIconLoader.put(pkg, it)
                }
            val bitmap = drawable.toBitmap(96, 96).asImageBitmap()
            iconCache[pkg] = bitmap
            bitmap
        } catch (_: Exception) {
            null
        }
    }

    private fun sortApps(list: List<ManagedApp>): List<ManagedApp> =
        list.sortedWith(
            compareByDescending<ManagedApp> { it.isPinned }
                .thenByDescending { it.isHidden }
                .thenBy { it.appName.lowercase() },
        )

    private fun filterApps(
        source: List<ManagedApp>,
        q: String,
        f: AppFilter,
    ): List<ManagedApp> {
        var list = source
        when (f) {
            AppFilter.ALL -> Unit
            AppFilter.USER -> list = list.filter { !it.isSystem }
            AppFilter.HIDDEN -> list = list.filter { it.isHidden }
        }
        if (q.isNotBlank()) {
            val needle = q.trim().lowercase()
            list = list.filter {
                it.appName.lowercase().contains(needle) || it.packageName.lowercase().contains(needle)
            }
        }
        return list
    }

    // ------------------------------------------------------------------ 隐藏 / 恢复

    private fun toggleAppVisibility(packageName: String, hide: Boolean) {
        if (!shizukuPermissionGranted || !serviceReady) {
            Toast.makeText(this, "Shizuku 未就绪", Toast.LENGTH_SHORT).show()
            return
        }
        Thread {
            val success = callSetInvisible(packageName, hide)
            runOnUiThread {
                if (hide) {
                    if (success) {
                        hiddenApps.add(packageName)
                        prefs.edit().putStringSet("hidden_apps", hiddenApps).apply()
                        Toast.makeText(this, "已从最近任务隐藏", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this, "隐藏失败", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    hiddenApps.remove(packageName)
                    prefs.edit().putStringSet("hidden_apps", hiddenApps).apply()
                    if (hiddenApps.isEmpty()) {
                        prefs.edit().putBoolean("all_hidden", false).apply()
                    }
                    Toast.makeText(this, "已恢复", Toast.LENGTH_SHORT).show()
                }
                allApps = sortApps(
                    allApps.map {
                        if (it.packageName == packageName) it.copy(isHidden = hiddenApps.contains(packageName)) else it
                    },
                )
            }
        }.start()
    }

    private fun restoreAll() {
        if (!shizukuPermissionGranted || !serviceReady) {
            Toast.makeText(this, "Shizuku 未就绪", Toast.LENGTH_SHORT).show()
            return
        }
        val targets = hiddenApps.toList()
        if (targets.isEmpty()) return
        Thread {
            var restored = 0
            for (pkg in targets) {
                if (callSetInvisible(pkg, false)) restored++
            }
            runOnUiThread {
                hiddenApps.clear()
                prefs.edit().putStringSet("hidden_apps", emptySet()).putBoolean("all_hidden", false).apply()
                allApps = sortApps(allApps.map { it.copy(isHidden = false) })
                Toast.makeText(this, "已恢复 $restored 个应用", Toast.LENGTH_SHORT).show()
            }
        }.start()
    }

    // ------------------------------------------------------------------ 置顶 / 详情 / 提取

    private fun toggleAppPin(app: ManagedApp) {
        if (app.packageName in pinnedApps) {
            pinnedApps.remove(app.packageName)
            Toast.makeText(this, "已取消置顶", Toast.LENGTH_SHORT).show()
        } else {
            pinnedApps.add(app.packageName)
            Toast.makeText(this, "已置顶", Toast.LENGTH_SHORT).show()
        }
        prefs.edit().putStringSet("pinned_apps", pinnedApps).apply()
        allApps = sortApps(
            allApps.map {
                if (it.packageName == app.packageName) it.copy(isPinned = pinnedApps.contains(app.packageName)) else it
            },
        )
    }

    private fun showAppDetail(app: ManagedApp) {
        Thread {
            try {
                val pm = packageManager
                val appInfo = pm.getApplicationInfo(app.packageName, PackageManager.GET_META_DATA)
                @Suppress("DEPRECATION")
                val packageInfo = pm.getPackageInfo(app.packageName, PackageManager.GET_PERMISSIONS)
                val permissions = packageInfo.requestedPermissions?.toList() ?: emptyList()
                val detail = AppDetailInfo(
                    appName = app.appName,
                    packageName = app.packageName,
                    icon = app.icon,
                    versionName = packageInfo.versionName ?: "—",
                    versionCode = packageInfo.longVersionCode,
                    targetSdk = appInfo.targetSdkVersion,
                    minSdk = appInfo.minSdkVersion,
                    permissions = permissions,
                    seed = app.seed,
                )
                runOnUiThread { detailInfo = detail }
            } catch (e: Exception) {
                runOnUiThread { Toast.makeText(this, "读取详情失败", Toast.LENGTH_SHORT).show() }
            }
        }.start()
    }

    private fun extractApk(packageName: String, appName: String) {
        Thread {
            try {
                val pm = packageManager
                val appInfo = pm.getApplicationInfo(packageName, 0)
                val src = File(appInfo.sourceDir)
                val dest = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    "$appName.apk",
                )
                src.inputStream().use { input ->
                    dest.outputStream().use { output -> input.copyTo(output) }
                }
                runOnUiThread {
                    Toast.makeText(this, "已保存到 ${dest.absolutePath}", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "提取失败：${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    private fun openSystemSettings(packageName: String) {
        try {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")),
            )
        } catch (_: Exception) {
            Toast.makeText(this, "无法打开系统设置", Toast.LENGTH_SHORT).show()
        }
    }

    // ------------------------------------------------------------------ 卸载

    private fun uninstallApp(packageName: String, deleteData: Boolean) {
        if (!shizukuPermissionGranted || !serviceReady) {
            Toast.makeText(this, "Shizuku 未就绪", Toast.LENGTH_SHORT).show()
            return
        }
        Thread {
            val binder = taskHideService ?: return@Thread
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken(ITaskHideService.DESCRIPTOR)
                data.writeString(packageName)
                data.writeByte(if (deleteData) 1 else 0)
                binder.transact(ITaskHideService.TRANSACTION_uninstallApp, data, reply, 0)
                reply.readException()
                val success = reply.readInt() != 0
                runOnUiThread {
                    if (success) {
                        hiddenApps.remove(packageName)
                        pinnedApps.remove(packageName)
                        prefs.edit().putStringSet("hidden_apps", hiddenApps).apply()
                        prefs.edit().putStringSet("pinned_apps", pinnedApps).apply()
                        iconCache.remove(packageName)
                        allApps = sortApps(allApps.filterNot { it.packageName == packageName })
                        Toast.makeText(
                            this,
                            if (deleteData) "已卸载（删除数据）" else "已卸载（保留数据）",
                            Toast.LENGTH_SHORT,
                        ).show()
                    } else {
                        Toast.makeText(this, "卸载失败", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread { Toast.makeText(this, "卸载错误：${e.message}", Toast.LENGTH_SHORT).show() }
            } finally {
                data.recycle()
                reply.recycle()
            }
        }.start()
    }

    private fun callSetInvisible(packageName: String, invisible: Boolean): Boolean {
        val binder = taskHideService ?: return false
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(ITaskHideService.DESCRIPTOR)
            data.writeString(packageName)
            data.writeByte(if (invisible) 1 else 0)
            binder.transact(ITaskHideService.TRANSACTION_setTaskInvisible, data, reply, 0)
            reply.readException()
            return reply.readInt() != 0
        } catch (e: Exception) {
            runOnUiThread { Toast.makeText(this, "错误：${e.message}", Toast.LENGTH_SHORT).show() }
            return false
        } finally {
            data.recycle()
            reply.recycle()
        }
    }
}

@Composable
private fun UninstallConfirmDialog(
    appName: String,
    onDismiss: () -> Unit,
    onKeepData: () -> Unit,
    onDeleteData: () -> Unit,
) {
    val colors = Dsh.colors
    val type = Dsh.type
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(Dsh.dimens.panelRadius))
                .background(color = colors.canvas)
                .border(1.dp, colors.hairlineStrong, RoundedCornerShape(Dsh.dimens.panelRadius))
                .padding(20.dp),
        ) {
            Text("卸载应用", style = type.h2, color = colors.ink)
            Spacer(Modifier.height(12.dp))
            Text(
                text = "确定要卸载 $appName 吗？选择是否一并删除应用数据。",
                style = type.body,
                color = colors.inkMuted,
            )
            Spacer(Modifier.height(20.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    DshGhostButton("保留数据", onClick = onKeepData, modifier = Modifier.fillMaxWidth())
                }
                Spacer(Modifier.height(0.dp))
                Box(Modifier.padding(start = 10.dp).weight(1f)) {
                    DshGhostButton("删除数据", onClick = onDeleteData, modifier = Modifier.fillMaxWidth())
                }
            }
            Spacer(Modifier.height(10.dp))
            Box(Modifier.fillMaxWidth()) {
                DshGhostButton("取消", onClick = onDismiss, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}
