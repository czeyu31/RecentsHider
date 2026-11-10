package com.example.hiderecents

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import com.example.hiderecents.ui.components.DshIconKind
import com.example.hiderecents.ui.screens.ToolboxScreen
import com.example.hiderecents.ui.screens.ToolboxTool
import com.example.hiderecents.ui.theme.AppTheme
import com.example.hiderecents.ui.theme.DshTheme

/**
 * 工具箱（Compose 重写版）。
 *
 * 六项功能与旧版完全一致：应用管理、ADB 命令、历史通知、APK 安装器、文件传输、设置。
 * 旧版是居中弹窗 + 3x3 GridLayout，新版是全屏两列网格（首项独占整行）。
 */
class ToolboxActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences("hide_recents_prefs", Context.MODE_PRIVATE)
        val theme = AppTheme.fromPref(prefs.getString("ui_theme", null))
        applySystemBars(theme)

        setContent {
            DshTheme(theme = theme) {
                ToolboxScreen(
                    tools = buildTools(),
                    onBack = { closePanel() },
                )
            }
        }
    }

    private fun buildTools(): List<ToolboxTool> = listOf(
        ToolboxTool(
            title = "应用管理",
            desc = "从最近任务中隐藏或恢复应用，长按可查看详情、卸载或提取 APK",
            meta = "隐藏 / 恢复 · 搜索 · 详情",
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
            desc = "同一 Wi-Fi 下用电脑浏览器收发文件",
            meta = "局域网",
            icon = DshIconKind.TRANSFER,
            onClick = { open(FileTransferActivity::class.java) },
        ),
        ToolboxTool(
            title = "设置",
            desc = "外观主题、状态通知、后台保活",
            meta = "3 项",
            icon = DshIconKind.GEAR,
            onClick = {
                startActivity(Intent(this, MainActivity::class.java))
                closePanel()
            },
        ),
    )

    private fun open(target: Class<*>) {
        startActivity(Intent(this, target))
    }

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

    private fun closePanel() {
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, R.anim.scale_out)
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        closePanel()
    }
}
