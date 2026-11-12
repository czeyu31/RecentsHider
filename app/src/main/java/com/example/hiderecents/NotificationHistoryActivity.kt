package com.example.hiderecents

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import androidx.core.view.WindowCompat
import com.example.hiderecents.ui.screens.AppChip
import com.example.hiderecents.ui.screens.NotificationEntry
import com.example.hiderecents.ui.screens.NotificationFilterSheet
import com.example.hiderecents.ui.screens.NotificationHistoryScreen
import com.example.hiderecents.ui.theme.AppTheme
import com.example.hiderecents.ui.theme.applyDshSystemBars
import com.example.hiderecents.ui.theme.DshTheme
import org.json.JSONArray
import rikka.shizuku.Shizuku

/**
 * 历史通知（Compose 重写版）。
 *
 * 功能与原 NotificationHistoryActivity 逐条对齐：
 * - 通知访问权限检测与未授权横幅、跳系统设置
 * - 通过 Shizuku 自动授权监听器与 QUERY_ALL_PACKAGES
 * - 读 notification_history 的 JSON 数组，按时间倒序，相对时间标签
 * - 记录侧排除筛选（notif_excluded_apps）与展示侧按应用筛选
 * - 一键清空历史
 */
class NotificationHistoryActivity : DshActivity() {

    private lateinit var prefs: SharedPreferences

    private var taskHideService: IBinder? = null
    private var serviceReady = false
    private var grantAttempted = false

    private var entries by mutableStateOf(listOf<NotificationEntry>())
    private var chips by mutableStateOf(listOf<AppChip>())
    private var selectedPackage by mutableStateOf<String?>(null)
    private var permissionGranted by mutableStateOf(false)
    private var excluded by mutableStateOf<Set<String>>(emptySet())
    private var showFilter by mutableStateOf(false)

    private val iconCache = mutableMapOf<String, ImageBitmap?>()

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            taskHideService = service
            serviceReady = true
            autoGrantNotificationPermission()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            taskHideService = null
            serviceReady = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        prefs = getSharedPreferences("notification_history", Context.MODE_PRIVATE)
        excluded = prefs.getStringSet("notif_excluded_apps", emptySet())?.toSet() ?: emptySet()

        val themePrefs = getSharedPreferences("hide_recents_prefs", Context.MODE_PRIVATE)
        val theme = AppTheme.fromPref(themePrefs.getString("ui_theme", null))
        applySystemBars(theme)

        setContent {
            DshTheme(theme = theme) {
                val visible = if (selectedPackage == null) {
                    entries
                } else {
                    entries.filter { it.packageName == selectedPackage }
                }

                NotificationHistoryScreen(
                    items = visible,
                    totalCount = entries.size,
                    chips = chips,
                    selectedPackage = selectedPackage,
                    onSelectPackage = { selectedPackage = it },
                    permissionGranted = permissionGranted,
                    onRequestPermission = { openPermissionSettings() },
                    onBack = { dshGoBack() },
                    onOpenFilter = { showFilter = true },
                    onClearAll = { clearAll() },
                )

                if (showFilter) {
                    NotificationFilterSheet(
                        apps = chips,
                        excluded = excluded,
                        onDismiss = { showFilter = false },
                        onConfirm = { set ->
                            excluded = set
                            prefs.edit().putStringSet("notif_excluded_apps", set).apply()
                            showFilter = false
                            Toast.makeText(this, "筛选已更新", Toast.LENGTH_SHORT).show()
                        },
                    )
                }
            }
        }

        bindServiceIfPossible()
        checkPermission()
        loadNotifications()
    }

    override fun onResume() {
        super.onResume()
        checkPermission()
        loadNotifications()
    }

    override fun onDestroy() {
        super.onDestroy()
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

    private fun bindServiceIfPossible() {
        try {
            if (!Shizuku.pingBinder() || Shizuku.isPreV11()) return
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) return
            Shizuku.bindUserService(
                Shizuku.UserServiceArgs(ComponentName(this, TaskHideService::class.java))
                    .daemon(false).processNameSuffix("taskhide").debuggable(true).version(2),
                serviceConnection,
            )
        } catch (_: Exception) {
        }
    }

    private fun autoGrantNotificationPermission() {
        if (!serviceReady || grantAttempted) return
        grantAttempted = true
        Thread {
            val component = ComponentName(this, NotificationListenerServiceImpl::class.java).flattenToString()
            executeCommandSync("cmd notification allow_listener $component")
            executeCommandSync("appops set $packageName QUERY_ALL_PACKAGES allow")
            runOnUiThread {
                checkPermission()
                loadNotifications()
            }
        }.start()
    }

    private fun executeCommandSync(command: String): String {
        val binder = TaskHideBridge.resolve(taskHideService) ?: return ""
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(ITaskHideService.DESCRIPTOR)
            data.writeString(command)
            binder.transact(ITaskHideService.TRANSACTION_execCommand, data, reply, 0)
            reply.readException()
            reply.readInt()
            return reply.readString() ?: ""
        } catch (_: Exception) {
            return ""
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    // ------------------------------------------------------------------ 权限

    private fun checkPermission() {
        val enabled = Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: ""
        val component = ComponentName(this, NotificationListenerServiceImpl::class.java).flattenToString()
        permissionGranted = enabled.contains(component)
    }

    private fun openPermissionSettings() {
        try {
            startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
        } catch (_: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            } catch (_: Exception) {
                Toast.makeText(this, "请手动开启通知访问权限", Toast.LENGTH_LONG).show()
            }
        }
    }

    // ------------------------------------------------------------------ 数据

    private fun loadNotifications() {
        val raw = prefs.getString("notifications", "[]") ?: "[]"
        val array = try {
            JSONArray(raw)
        } catch (_: Exception) {
            JSONArray()
        }

        val pm = packageManager
        val now = System.currentTimeMillis()
        val list = mutableListOf<NotificationEntry>()

        for (i in 0 until array.length()) {
            try {
                val obj = array.getJSONObject(i)
                val pkg = obj.getString("packageName")
                val title = obj.optString("title", "")
                val text = obj.optString("text", "")
                val time = obj.optLong("time", 0L)
                val appName = try {
                    pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
                } catch (_: Exception) {
                    pkg
                }
                list.add(
                    NotificationEntry(
                        packageName = pkg,
                        appName = appName,
                        icon = loadIcon(pm, pkg),
                        title = title,
                        text = text,
                        time = time,
                        timeLabel = relativeTime(now - time),
                    ),
                )
            } catch (_: Exception) {
            }
        }

        list.sortByDescending { it.time }
        entries = list

        val counts = linkedMapOf<String, Int>()
        val labels = linkedMapOf<String, String>()
        for (item in list) {
            counts[item.packageName] = (counts[item.packageName] ?: 0) + 1
            labels[item.packageName] = item.appName
        }
        chips = counts.entries
            .sortedByDescending { it.value }
            .map { entry ->
                AppChip(
                    packageName = entry.key,
                    label = (labels[entry.key] ?: entry.key) + " " + entry.value,
                )
            }

        if (selectedPackage != null && counts[selectedPackage] == null) {
            selectedPackage = null
        }
    }

    private fun loadIcon(pm: PackageManager, pkg: String): ImageBitmap? {
        if (iconCache.containsKey(pkg)) return iconCache[pkg]
        val bitmap = try {
            val drawable = AppIconLoader.getCached(pkg)
                ?: pm.getApplicationIcon(pm.getApplicationInfo(pkg, 0)).also {
                    AppIconLoader.put(pkg, it)
                }
            AppIconLoader.toSafeBitmap(drawable, 96).asImageBitmap()
        } catch (_: Exception) {
            null
        }
        iconCache[pkg] = bitmap
        return bitmap
    }

    private fun relativeTime(diff: Long): String = when {
        diff < 60_000L -> "刚刚"
        diff < 3_600_000L -> (diff / 60_000L).toString() + " 分钟前"
        diff < 86_400_000L -> (diff / 3_600_000L).toString() + " 小时前"
        else -> (diff / 86_400_000L).toString() + " 天前"
    }

    private fun clearAll() {
        prefs.edit().putString("notifications", "[]").apply()
        entries = emptyList()
        chips = emptyList()
        selectedPackage = null
        Toast.makeText(this, "已清空历史通知", Toast.LENGTH_SHORT).show()
    }
}
