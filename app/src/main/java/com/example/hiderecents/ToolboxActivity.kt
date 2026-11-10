package com.example.hiderecents

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.example.hiderecents.ui.components.DshIconKind
import com.example.hiderecents.ui.screens.SettingsDialog
import com.example.hiderecents.ui.screens.ToolboxScreen
import com.example.hiderecents.ui.screens.ToolboxTool
import com.example.hiderecents.ui.theme.AppTheme
import com.example.hiderecents.ui.theme.DshTheme
import com.example.hiderecents.ui.theme.applyDshSystemBars

/**
 * 工具箱（Compose 重写版）。
 *
 * 六项功能与旧版完全一致：应用管理、ADB 命令、历史通知、APK 安装器、文件传输、设置。
 *
 * 「设置」不再跳回首页——直接在本页弹出同一个 SettingsDialog，改主题/开关就地生效。
 */
class ToolboxActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences

    private var appTheme by mutableStateOf(AppTheme.SYSTEM)
    private var showSettings by mutableStateOf(false)
    private var keepAlive by mutableStateOf(false)
    private var statusNotification by mutableStateOf(false)
    private var excludeFromRecents by mutableStateOf(false)
    private var hiddenCount by mutableStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        prefs = getSharedPreferences("hide_recents_prefs", Context.MODE_PRIVATE)
        appTheme = AppTheme.fromPref(prefs.getString("ui_theme", null))
        readPrefs()
        applyDshSystemBars(appTheme)

        setContent {
            DshTheme(theme = appTheme) {
                Box(Modifier.fillMaxSize()) {
                    ToolboxScreen(
                        tools = buildTools(),
                        onBack = { closePanel() },
                    )

                    if (showSettings) {
                        SettingsDialog(
                            theme = appTheme,
                            keepAlive = keepAlive,
                            statusNotification = statusNotification,
                            excludeFromRecents = excludeFromRecents,
                            hiddenCount = hiddenCount,
                            onThemeChange = { setTheme(it) },
                            onToggleKeepAlive = { applyKeepAlive(it) },
                            onToggleNotification = { applyStatusNotification(it) },
                            onToggleExclude = { applyExcludeFromRecents(it) },
                            onDismiss = { showSettings = false },
                        )
                    }
                }
            }
        }
    }

    private fun readPrefs() {
        keepAlive = prefs.getBoolean("keep_alive_enabled", false)
        statusNotification = prefs.getBoolean("show_status_notification", false)
        excludeFromRecents = prefs.getBoolean("hide_from_recents", false)
        hiddenCount = prefs.getStringSet("hidden_apps", emptySet())?.size ?: 0
    }

    private fun buildTools(): List<ToolboxTool> = listOf(
        ToolboxTool(
            title = "应用管理",
            desc = "从最近任务中隐藏或恢复，长按查看更多",
            meta = "隐藏 · 恢复 · 搜索 · 详情",
            icon = DshIconKind.GRID,
            onClick = { open(AppManageActivity::class.java) },
        ),
        ToolboxTool(
            title = "ADB 命令",
            desc = "以 shell 身份执行常用命令",
            meta = "通过 Shizuku",
            icon = DshIconKind.TERMINAL,
            onClick = { open(AdbActivity::class.java) },
        ),
        ToolboxTool(
            title = "历史通知",
            desc = "按应用筛选通知记录",
            meta = "本机保存",
            icon = DshIconKind.BELL,
            onClick = { open(NotificationHistoryActivity::class.java) },
        ),
        ToolboxTool(
            title = "APK 安装器",
            desc = "本地安装与签名校验",
            meta = "无需联网",
            icon = DshIconKind.PACKAGE,
            onClick = { open(ApkInstallerActivity::class.java) },
        ),
        ToolboxTool(
            title = "文件传输",
            desc = "局域网内收发文件",
            meta = "局域网",
            icon = DshIconKind.TRANSFER,
            onClick = { open(FileTransferActivity::class.java) },
        ),
        ToolboxTool(
            title = "设置",
            desc = "外观主题 · 状态通知 · 保活",
            meta = "3 项",
            icon = DshIconKind.SLIDERS,
            onClick = {
                readPrefs()
                showSettings = true
            },
        ),
    )

    private fun open(target: Class<*>) {
        startActivity(Intent(this, target))
    }

    // ------------------------------------------------------------------ 设置

    private fun setTheme(theme: AppTheme) {
        prefs.edit().putString("ui_theme", theme.prefValue).apply()
        appTheme = theme
        applyDshSystemBars(theme)
    }

    private fun applyKeepAlive(value: Boolean) {
        prefs.edit().putBoolean("keep_alive_enabled", value).apply()
        keepAlive = value
        Toast.makeText(
            this,
            if (value) "已开启后台保活，建议回首页完成电池优化引导" else "已关闭后台保活",
            Toast.LENGTH_SHORT,
        ).show()
    }

    private fun applyStatusNotification(value: Boolean) {
        prefs.edit().putBoolean("show_status_notification", value).apply()
        statusNotification = value
        Toast.makeText(
            this,
            if (value) "已开启状态通知" else "已关闭状态通知",
            Toast.LENGTH_SHORT,
        ).show()
    }

    private fun applyExcludeFromRecents(value: Boolean) {
        prefs.edit().putBoolean("hide_from_recents", value).apply()
        excludeFromRecents = value
        try {
            val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            for (task in am.appTasks) {
                if (task.taskInfo.topActivity?.packageName == packageName) {
                    task.setExcludeFromRecents(value)
                }
            }
        } catch (_: Exception) {
        }
        Toast.makeText(
            this,
            if (value) "已从最近任务中移除" else "已恢复最近任务卡片",
            Toast.LENGTH_SHORT,
        ).show()
    }

    private fun closePanel() {
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, R.anim.scale_out)
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (showSettings) {
            showSettings = false
            return
        }
        closePanel()
    }
}
