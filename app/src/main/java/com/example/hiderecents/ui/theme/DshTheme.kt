package com.example.hiderecents.ui.theme

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.lerp

/** 两组颜色按进度插值，只用一个 t 驱动全部通道。 */
private fun blend(a: DshColors, b: DshColors, t: Float): DshColors = DshColors(
    canvas = lerp(a.canvas, b.canvas, t),
    canvasLift = lerp(a.canvasLift, b.canvasLift, t),
    canvasShade = lerp(a.canvasShade, b.canvasShade, t),
    panelTop = lerp(a.panelTop, b.panelTop, t),
    panelBottom = lerp(a.panelBottom, b.panelBottom, t),
    ink = lerp(a.ink, b.ink, t),
    inkMuted = lerp(a.inkMuted, b.inkMuted, t),
    inkFaint = lerp(a.inkFaint, b.inkFaint, t),
    hairline = lerp(a.hairline, b.hairline, t),
    hairlineStrong = lerp(a.hairlineStrong, b.hairlineStrong, t),
    accent = lerp(a.accent, b.accent, t),
    accentInk = lerp(a.accentInk, b.accentInk, t),
    accentDim = lerp(a.accentDim, b.accentDim, t),
    accentBorder = lerp(a.accentBorder, b.accentBorder, t),
    accentGlow = lerp(a.accentGlow, b.accentGlow, t),
    track = lerp(a.track, b.track, t),
    knob = lerp(a.knob, b.knob, t),
)

/**
 * 双主题入口。theme 从 SharedPreferences 的 ui_theme 读出后传进来。
 *
 * 主题过渡只用「一个 Animatable 进度 + 颜色插值」：
 * 之前是 17 个 animateColorAsState 各跑各的，切换时每帧都在推进 17 个动画、
 * 每帧都要重组整棵树，观感就是卡。现在一次动画驱动全部颜色，只产生一处状态变化。
 */
@Composable
fun DshTheme(
    theme: AppTheme = AppTheme.SYSTEM,
    content: @Composable () -> Unit,
) {
    val systemDark = isSystemInDarkTheme()
    val resolved = when (theme) {
        AppTheme.SYSTEM -> if (systemDark) AppTheme.GRAPHITE else AppTheme.PAPER
        else -> theme
    }
    val base = if (resolved == AppTheme.PAPER) PaperDesign else GraphiteDesign
    val type = if (resolved == AppTheme.PAPER) PaperType else GraphiteType
    val target = base.colors

    var from by remember { mutableStateOf(target) }
    val progress = remember { Animatable(1f) }

    LaunchedEffect(target) {
        // 把「此刻屏幕上真实的颜色」固化成起点，再从 0 跑到 1，避免过渡中途再切换时跳色。
        from = blend(from, target, progress.value)
        progress.snapTo(0f)
        progress.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = base.motion.themeFadeMillis),
        )
    }

    val colors = blend(from, target, progress.value)
    val design = base.copy(colors = colors)

    val scheme = if (resolved == AppTheme.PAPER) {
        lightColorScheme(
            primary = colors.accent,
            onPrimary = colors.accentInk,
            background = colors.canvas,
            onBackground = colors.ink,
            surface = colors.canvasLift,
            onSurface = colors.ink,
            surfaceVariant = colors.canvasShade,
            onSurfaceVariant = colors.inkMuted,
            outline = colors.hairlineStrong,
        )
    } else {
        darkColorScheme(
            primary = colors.accent,
            onPrimary = colors.accentInk,
            background = colors.canvas,
            onBackground = colors.ink,
            surface = colors.panelTop,
            onSurface = colors.ink,
            surfaceVariant = colors.panelBottom,
            onSurfaceVariant = colors.inkMuted,
            outline = colors.hairlineStrong,
        )
    }

    CompositionLocalProvider(
        LocalDshDesign provides design,
        LocalDshType provides type,
    ) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
