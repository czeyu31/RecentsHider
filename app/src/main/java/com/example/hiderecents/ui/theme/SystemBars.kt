package com.example.hiderecents.ui.theme

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import androidx.core.view.WindowCompat

/**
 * 统一的边到边（edge-to-edge）与系统栏处理。
 *
 * 为什么需要它：
 * - 旧做法靠 window.statusBarColor 改颜色，但内容仍被系统挤到状态栏下方，
 *   状态栏那块露出的是「窗口背景色」，纸本主题下就是一条黑带。
 * - 挖孔屏还需要声明 windowLayoutInDisplayCutoutMode，否则窗口被裁到刘海下沿。
 *
 * 现在改为：内容延伸到系统栏与刘海区域，背景由 Compose 画布铺满；
 * 内容安全区由各页面的 safeDrawingPadding() 负责。
 */
fun Activity.applyDshSystemBars(theme: AppTheme) {
    val systemDark = (resources.configuration.uiMode and
        android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
        android.content.res.Configuration.UI_MODE_NIGHT_YES

    val paper = when (theme) {
        AppTheme.SYSTEM -> !systemDark
        AppTheme.PAPER -> true
        AppTheme.GRAPHITE -> false
    }

    // 1) 允许内容画进系统栏与刘海区域
    WindowCompat.setDecorFitsSystemWindows(window, false)

    // 2) 状态栏 / 导航栏图标随主题反色
    val controller = WindowCompat.getInsetsController(window, window.decorView)
    controller.isAppearanceLightStatusBars = paper
    controller.isAppearanceLightNavigationBars = paper

    // 3) 系统栏透明，避免纸本主题下顶部出现深色带
    @Suppress("DEPRECATION")
    window.statusBarColor = Color.TRANSPARENT
    @Suppress("DEPRECATION")
    window.navigationBarColor = Color.TRANSPARENT

    // 4) 窗口底色跟随主题：Compose 首帧之前也不会闪深色
    window.setBackgroundDrawable(
        ColorDrawable(if (paper) 0xFFF1EDE3.toInt() else 0xFF0F1214.toInt()),
    )

    // 5) 关掉系统给透明系统栏自动加的对比度遮罩（否则纸本下会变灰）
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        @Suppress("DEPRECATION")
        window.isStatusBarContrastEnforced = false
        @Suppress("DEPRECATION")
        window.isNavigationBarContrastEnforced = false
    }
}
