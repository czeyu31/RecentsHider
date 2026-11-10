package com.example.hiderecents.ui.theme

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color

/**
 * 双主题入口。theme 从 SharedPreferences 的 ui_theme 读出后传进来。
 * 切换主题时颜色做 320ms 过渡，不硬切。
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
    val fade = tween<Color>(durationMillis = base.motion.themeFadeMillis)

    val canvas by animateColorAsState(target.canvas, fade, label = "canvas")
    val canvasLift by animateColorAsState(target.canvasLift, fade, label = "canvasLift")
    val canvasShade by animateColorAsState(target.canvasShade, fade, label = "canvasShade")
    val panelTop by animateColorAsState(target.panelTop, fade, label = "panelTop")
    val panelBottom by animateColorAsState(target.panelBottom, fade, label = "panelBottom")
    val ink by animateColorAsState(target.ink, fade, label = "ink")
    val inkMuted by animateColorAsState(target.inkMuted, fade, label = "inkMuted")
    val inkFaint by animateColorAsState(target.inkFaint, fade, label = "inkFaint")
    val hairline by animateColorAsState(target.hairline, fade, label = "hairline")
    val hairlineStrong by animateColorAsState(target.hairlineStrong, fade, label = "hairlineStrong")
    val accent by animateColorAsState(target.accent, fade, label = "accent")
    val accentInk by animateColorAsState(target.accentInk, fade, label = "accentInk")
    val accentDim by animateColorAsState(target.accentDim, fade, label = "accentDim")
    val accentBorder by animateColorAsState(target.accentBorder, fade, label = "accentBorder")
    val accentGlow by animateColorAsState(target.accentGlow, fade, label = "accentGlow")
    val track by animateColorAsState(target.track, fade, label = "track")
    val knob by animateColorAsState(target.knob, fade, label = "knob")

    val colors = DshColors(
        canvas = canvas,
        canvasLift = canvasLift,
        canvasShade = canvasShade,
        panelTop = panelTop,
        panelBottom = panelBottom,
        ink = ink,
        inkMuted = inkMuted,
        inkFaint = inkFaint,
        hairline = hairline,
        hairlineStrong = hairlineStrong,
        accent = accent,
        accentInk = accentInk,
        accentDim = accentDim,
        accentBorder = accentBorder,
        accentGlow = accentGlow,
        track = track,
        knob = knob,
    )

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
