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
import android.os.Handler
import android.os.Looper
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
import androidx.compose.runtime.remember
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
import com.example.hiderecents.ui.theme.applyDshSystemBars
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

    /** 正在加载中标记：onCreate / onServiceConnected / 延迟自愈 / onResume 都会触发加载，
     *  并发跑会让 pm list packages 重复执行好几次（实测冷启一次跑 4 遍）。 */
    @Volatile
    private var loadInFlight = false

    /** 后台补图标线程：列表先秒开，图标再逐批填进去。 */
    private var iconFillJob: Thread? = null

    /** 本地兜底连续只拿到 ≤1 个应用的次数：避免把「只有自己」的列表发布出去。 */
    private var localFallbackTries = 0

    /** 兜底重试是否已排队：避免同一次加载排出多个重试定时器。 */
    @Volatile
    private var retryScheduled = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            taskHideService = service
            serviceReady = true
            TaskHideBridge.binder = service
            if (allApps.size <= 1) loadInstalledApps()
            // Shizuku 刚绑定那一瞬间 pm list 可能还没就绪，会只加载到本应用自己
            // （用户看到「应用管理 1 个应用」）。这里延迟再拉一次做自愈。
            Handler(Looper.getMainLooper()).postDelayed({ if (allApps.size <= 1) loadInstalledApps() }, 1200L)
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

        // 进程级缓存：从工具箱 / 磁贴反复进应用管理时先用缓存秒开，
        // 不再每次都重新枚举 550 个包并逐个解码图标（那要十几秒）。
        // 冷启动时进程内缓存是空的，先用磁盘缓存顶上（上次加载的包名 + 应用名），
        // 这样第一次进页面也能立刻看到列表，后台再刷新真实数据。
        if (AppListCache.apps == null) {
            val disk = loadDiskCache()
            if (disk.isNotEmpty()) {
                AppListCache.put(disk)
                Log.d(TAG, "disk cache n=" + disk.size)
            }
        }
        AppListCache.apps?.let { cached ->
            allApps = sortAppsInitial(
                cached.map {
                    it.copy(
                        isHidden = hiddenApps.contains(it.packageName),
                        isPinned = pinnedApps.contains(it.packageName),
                    )
                },
            )
            loading = false
            Log.d(TAG, "prefill cache n=" + allApps.size)
            startIconFill()
        }
        if (allApps.isEmpty()) Log.d(TAG, "cache miss, loading")

        val theme = AppTheme.fromPref(prefs.getString("ui_theme", null))
        applySystemBars(theme)

        setContent {
            DshTheme(theme = theme) {
                // 过滤 + 排序只在这些输入变化时重算，避免每帧重组都跑一遍列表遍历
                val visible = remember(allApps, query, filter) {
                    filterApps(allApps, query, filter)
                }
                AppManageScreen(
                    apps = visible,
                    totalCount = allApps.size,
                    hiddenCount = allApps.count { it.isHidden },
                    query = query,
                    onQueryChange = { query = it },
                    filter = filter,
                    onFilterChange = { filter = it },
                    loading = loading,
                    onBack = { goBack() },
                    onToggleHide = { app, hide -> toggleAppVisibility(app.packageName, hide) },
                    onOpenActions = { actionApp = it },
                    onRestoreAll = { restoreAll() },
                    shizukuReady = serviceReady,
                    onOpenShizuku = { openShizukuApp() },
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
        if (allApps.isEmpty()) {
            loadInstalledApps()
        } else {
            Log.d(TAG, "skip initial load, cache n=" + allApps.size)
        }
    }

    /**
     * 返回上一页。
     *
     * 为什么不能只调 finish()：SplashActivity 的「热恢复」分支会
     * startActivity(上次页面) 然后 finish() 自己，于是那个页面就成了任务根，
     * 栈里它下面什么都没有 —— 这时 finish() 直接退回桌面。
     * 兜底：如果自己已经是任务根，先拉起工具箱再结束自己，
     * 保证返回永远落在应用内的上一页。
     */
    private fun goBack() {
        if (isTaskRoot) {
            startActivity(Intent(this, ToolboxActivity::class.java))
        }
        // 返回时当前页向右抽离（入场动画设 0，目标页不动），与点进去的 slide_left 方向相反
        @Suppress("DEPRECATION")
        overridePendingTransition(0, R.anim.slide_right)
        finish()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        goBack()
    }

    override fun onResume() {
        super.onResume()
        // 列表明显不完整时（只加载到 1 个应用）自动重拉一次：
        // Shizuku 未就绪时的本地兜底会漏掉大部分应用，回到页面必须自愈。
        if (serviceReady && allApps.size <= 1) loadInstalledApps()
    }
    override fun onPause() {
        super.onPause()
        HiddenGuardService.sync(this)
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

    // ------------------------------------------------------------------ 加载

    private fun loadInstalledApps() {
        if (loadInFlight) {
            Log.d(TAG, "load skipped, in flight")
            return
        }
        loadInFlight = true
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
                        val sorted = sortAppsInitial(list)
                        runOnUiThread {
                            allApps = sorted
                            loading = false
                            AppListCache.put(sorted)
                            saveDiskCache(sorted)
                            Log.d(TAG, "list loaded n=" + sorted.size)
                            startIconFill()
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
            } finally {
                loadInFlight = false
            }
        }.start()
    }

    private fun loadInstalledAppsLocal() {
        val pm = packageManager
        val list = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            .filter { pm.getLaunchIntentForPackage(it.packageName) != null }
            .mapNotNull { toManagedApp(pm, it.packageName) }
        Log.d(TAG, "local fallback n=" + list.size + " tries=" + localFallbackTries)
        if (list.size <= 1 && localFallbackTries < 3) {
            localFallbackTries++
            loading = true
            Log.d(TAG, "local fallback too small, retry " + localFallbackTries)
            // 这里不能把 loadInFlight 置回 false：置回会让 900ms 后的重试与仍在跑的
            // 首轮加载并发执行，实测 pm list packages 被跑了两遍（多花 0.5-1 秒）。
            // 改为单次定时器，且首轮若已成功则直接跳过。
            if (!retryScheduled) {
                retryScheduled = true
                Handler(Looper.getMainLooper()).postDelayed({
                    retryScheduled = false
                    if (allApps.size <= 1) loadInstalledApps()
                }, 900L)
            }
            return
        }
        localFallbackTries = 0
        allApps = sortAppsInitial(list)
        loading = false
        loadInFlight = false
        AppListCache.put(allApps)
        saveDiskCache(allApps)
        Log.d(TAG, "list loaded local n=" + allApps.size)
        startIconFill()
    }

    /**
     * 后台补图标。
     *
     * 为什么需要：415 个应用如果都在列表可见之前解码图标，首次进入要 5 秒以上
     * （logcat 实测 cache miss -> list loaded = 5398ms），用户看到的就是「一直加载不出来」。
     * 这里把「取 drawable」留在后台线程，把「栅格化」仍然交给
     * AppIconLoader.toSafeBitmap（内部会切到主线程），保持当初修 SIGSEGV 的安全前提。
     */
    private fun startIconFill() {
        if (iconFillJob?.isAlive == true) return
        val targets = allApps.filter { it.icon == null }.map { it.packageName }
        if (targets.isEmpty()) return
        iconFillJob = Thread {
            val pm = packageManager
            val batch = LinkedHashMap<String, ImageBitmap>()
            for (pkg in targets) {
                if (Thread.currentThread().isInterrupted) return@Thread
                val bmp = AppIconLoader.bitmapFor(pm, pkg, 96)
                if (bmp != null) batch[pkg] = bmp.asImageBitmap()
                if (batch.size >= 16) {
                    applyIcons(batch)
                    batch.clear()
                }
            }
            if (batch.isNotEmpty()) applyIcons(batch)
            Log.d(TAG, "icon fill done n=" + targets.size)
        }.also { it.isDaemon = true }
        iconFillJob?.start()
    }

    private fun applyIcons(batch: Map<String, ImageBitmap>) {
        val snapshot = LinkedHashMap(batch)
        runOnUiThread {
            allApps = allApps.map { app ->
                val bmp = snapshot[app.packageName]
                if (bmp != null && app.icon == null) app.copy(icon = bmp) else app
            }
        }
    }

    /** 磁盘缓存文件：冷启动先用它渲染，避免每次都等 Shizuku 枚举 + 建列表。 */
    private fun diskCacheFile(): File = File(filesDir, "app_list_cache_v1.txt")

    private fun saveDiskCache(list: List<ManagedApp>) {
        runCatching {
            val sb = StringBuilder()
            for (app in list) {
                sb.append(app.packageName).append("|").append(app.appName).append(10.toChar())
            }
            diskCacheFile().writeText(sb.toString())
        }.onFailure { Log.w(TAG, "saveDiskCache failed: " + it.message) }
    }

    private fun loadDiskCache(): List<ManagedApp> {
        return runCatching {
            val f = diskCacheFile()
            if (!f.exists()) return emptyList()
            val pm = packageManager
            val list = ArrayList<ManagedApp>()
            for (line in f.readLines()) {
                if (line.isBlank()) continue
                val sep = line.indexOf("|")
                if (sep <= 0) continue
                val pkg = line.substring(0, sep)
                val name = line.substring(sep + 1)
                val isSystem = runCatching {
                    (pm.getApplicationInfo(pkg, 0).flags and ApplicationInfo.FLAG_SYSTEM) != 0
                }.getOrDefault(false)
                list.add(
                    ManagedApp(
                        packageName = pkg,
                        appName = name,
                        icon = AppIconLoader.getCachedBitmap(pkg)?.asImageBitmap(),
                        isHidden = hiddenApps.contains(pkg),
                        isSystem = isSystem,
                        isPinned = pinnedApps.contains(pkg),
                        seed = pkg.hashCode(),
                    ),
                )
            }
            sortAppsInitial(list)
        }.onFailure { Log.w(TAG, "loadDiskCache failed: " + it.message) }.getOrDefault(emptyList())
    }

    private fun toManagedApp(pm: PackageManager, pkg: String): ManagedApp? {
        return try {
            val info = pm.getApplicationInfo(pkg, 0)
            val isSystem = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            ManagedApp(
                packageName = pkg,
                appName = pm.getApplicationLabel(info).toString(),
                // 初始加载不栅格化图标：只取已缓存的位图（LRU 命中是纯内存操作），
                // 未命中的先留空由 startIconFill() 在后台逐批补上 —— 这样列表能立刻可见。
                icon = AppIconLoader.getCachedBitmap(pkg)?.asImageBitmap(),
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
            val bmp = AppIconLoader.bitmapFor(pm, pkg, 96) ?: return null
            val bitmap = bmp.asImageBitmap()
            iconCache[pkg] = bitmap
            bitmap
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 进入页面时的排序：已隐藏的排到最上面。
     *
     * 只在加载列表时用一次 —— 用户要的是「下次重新进入应用管理页才在最顶上」。
     */
    private fun sortAppsInitial(list: List<ManagedApp>): List<ManagedApp> =
        list.sortedWith(
            compareByDescending<ManagedApp> { it.isPinned }
                .thenByDescending { it.isHidden }
                .thenBy { it.appName.lowercase() },
        )

    /**
     * 列表更新时的稳定排序：只看置顶 + 名称，不含 isHidden。
     *
     * 旧实现把 isHidden 也放进比较器，于是一按开关该项立刻重排到最顶，
     * 视觉上就是「瞬间消失、飞到最上面」。开关只改状态，位置必须原地不动；
     * 「已隐藏的排最前」只在重新进入页面时由 sortAppsInitial 做一次。
     */
    private fun sortApps(list: List<ManagedApp>): List<ManagedApp> =
        list.sortedWith(
            compareByDescending<ManagedApp> { it.isPinned }
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
                        // 现在的隐藏是「标记任务不进最近任务」，需要该应用当前有运行中的任务。
                        // 任务不存在时不再谎报成功，也不回退到会杀进程的 removeTask。
                        Toast.makeText(this, "隐藏失败：应用当前未在运行", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    // 取消隐藏必须等「清掉标记」真的成功，否则会出现
                    // 「开关关了，应用还是不出现在最近任务里」——旧实现就是
                    // 无论成功与否都从 hiddenApps 里删掉并报「已恢复」。
                    if (success) {
                        hiddenApps.remove(packageName)
                        prefs.edit().putStringSet("hidden_apps", hiddenApps).apply()
                        if (hiddenApps.isEmpty()) {
                            prefs.edit().putBoolean("all_hidden", false).apply()
                        }
                        Toast.makeText(this, "已恢复", Toast.LENGTH_SHORT).show()
                        relaunchApp(packageName)
                    } else {
                        Toast.makeText(this, "恢复失败：清除「不进最近任务」标记没成功", Toast.LENGTH_SHORT).show()
                    }
                }
                allApps = sortApps(
                    allApps.map {
                        if (it.packageName == packageName) it.copy(isHidden = hiddenApps.contains(packageName)) else it
                    },
                )
            }
        }.start()
    }

    /**
     * 取消隐藏成功后把应用重新拉起来（修复「关掉开关后应用不回最近任务」）。
     *
     * 为什么需要：本 ROM 的 IActivityTaskManager 没有 setExcludeFromRecents，
     * 隐藏实际走 removeTask —— 销毁的是任务（UI 栈），不是进程。
     * 任务被销毁后，取消隐藏时 findTaskId 返回 -1，服务端直接返回成功、什么都不做，
     * 于是开关关了应用仍不在最近任务里，下次点开还会从开屏冷启动。
     * 这里主动 startActivity 一次，让系统重建任务并把它带回前台。
     */
    private fun relaunchApp(packageName: String) {
        // 用户在设置里关掉「恢复时回到前台」后，取消隐藏只清标记，不拉起应用。
        if (!prefs.getBoolean("relaunch_on_unhide", true)) {
            Log.d(TAG, "relaunch skipped by pref")
            return
        }
        // 为什么不再用 startActivity：MIUI 会把「后台拉起别的应用」拦成系统确认弹窗
        // （「System Tool 想要打开 XX，是否允许？」），每取消隐藏一次就弹一次。
        // 改走 Shizuku 的 shell 通道执行 monkey，等同 adb shell 拉起，不触发该弹窗；
        // Shizuku 不可用时才回落到 startActivity。
        Thread {
            val binder = taskHideService
            if (binder != null) {
                val data = Parcel.obtain()
                val reply = Parcel.obtain()
                try {
                    data.writeInterfaceToken(ITaskHideService.DESCRIPTOR)
                    data.writeString(
                        "monkey -p " + packageName + " -c android.intent.category.LAUNCHER 1",
                    )
                    binder.transact(ITaskHideService.TRANSACTION_execCommand, data, reply, 0)
                    reply.readException()
                    val ok = reply.readInt() != 0
                    if (ok) return@Thread
                } catch (e: Exception) {
                    Log.w(TAG, "relaunch via shizuku failed: " + e.message)
                } finally {
                    data.recycle()
                    reply.recycle()
                }
            }
            runOnUiThread {
                runCatching {
                    val intent = packageManager.getLaunchIntentForPackage(packageName)
                        ?: return@runCatching
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(intent)
                }.onFailure { Log.w(TAG, "relaunch " + packageName + " failed: " + it.message) }
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

    private fun openShizukuApp() {
        try {
            val intent = packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
            if (intent != null) {
                intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
                return
            }
        } catch (_: Exception) {
        }
        Toast.makeText(this, "没有找到 Shizuku，请先安装", Toast.LENGTH_SHORT).show()
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
