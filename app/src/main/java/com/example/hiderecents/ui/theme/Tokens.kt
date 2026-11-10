package com.example.hiderecents.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 双主题：Paper（纸本）与 Graphite（石墨 · 雾玉）。
 * SYSTEM 由系统深色模式决定：浅色 -> PAPER，深色 -> GRAPHITE。
 */
enum class AppTheme(val prefValue: String) {
    SYSTEM("system"),
    PAPER("paper"),
    GRAPHITE("graphite");

    companion object {
        fun fromPref(value: String?): AppTheme {
            if (value == null) return SYSTEM
            for (t in values()) if (t.prefValue == value) return t
            return SYSTEM
        }
    }
}

@Immutable
data class DshColors(
    val canvas: Color,
    val canvasLift: Color,
    val canvasShade: Color,
    val panelTop: Color,
    val panelBottom: Color,
    val ink: Color,
    val inkMuted: Color,
    val inkFaint: Color,
    val hairline: Color,
    val hairlineStrong: Color,
    val accent: Color,
    val accentInk: Color,
    val accentDim: Color,
    val accentBorder: Color,
    val accentGlow: Color,
    val track: Color,
    val knob: Color,
)

@Immutable
data class DshDimens(
    val screenPadding: Dp,
    val panelRadius: Dp,
    val tileRadius: Dp,
    val controlRadius: Dp,
    val iconRadius: Dp,
    val rowHeight: Dp,
    val gapXs: Dp,
    val gapS: Dp,
    val gapM: Dp,
    val gapL: Dp,
    val gapXl: Dp,
    val switchWidth: Dp,
    val switchHeight: Dp,
    val switchKnob: Dp,
)

@Immutable
data class DshSurfaceStyle(
    /** true = 用面板承载信息；false = 用横线切分，不要卡片 */
    val hasPanels: Boolean,
    /** 章节起始是否用 1.5dp 强线（纸本） */
    val ruleSections: Boolean,
    /** 面板顶部 1px 边缘高光（石墨模拟边缘折射） */
    val topHighlight: Boolean,
    /** 强调色出现密度上限提示，供组件层自查 */
    val accentBudgetPerScreen: Int,
)

@Immutable
data class DshMotion(
    val useSpring: Boolean,
    val stiffness: Float,
    val damping: Float,
    val enterMillis: Int,
    val staggerMillis: Int,
    val themeFadeMillis: Int,
    val breathe: Boolean,
)

@Immutable
data class DshDesign(
    val theme: AppTheme,
    val colors: DshColors,
    val dimens: DshDimens,
    val surface: DshSurfaceStyle,
    val motion: DshMotion,
)

// ---------------------------------------------------------------------------
// Paper：暖米白纸面 + 墨黑 + 朱红，无卡片无阴影，靠横线组织
// ---------------------------------------------------------------------------
val PaperColors = DshColors(
    canvas = Color(0xFFF1EDE3),
    canvasLift = Color(0xFFFBF8F1),
    canvasShade = Color(0xFFE7E1D2),
    panelTop = Color(0x00000000),
    panelBottom = Color(0x00000000),
    ink = Color(0xFF151410),
    inkMuted = Color(0xFF4B4840),
    inkFaint = Color(0xFF8A8577),
    hairline = Color(0x21151410),
    hairlineStrong = Color(0x42151410),
    accent = Color(0xFFAE4327),
    accentInk = Color(0xFFFAF7EF),
    accentDim = Color(0x1AAE4327),
    accentBorder = Color(0x80AE4327),
    accentGlow = Color(0x40AE4327),
    track = Color(0x24151410),
    knob = Color(0xFF8A8577),
)

val PaperDimens = DshDimens(
    screenPadding = 30.dp,
    panelRadius = 0.dp,
    tileRadius = 9.dp,
    controlRadius = 0.dp,
    iconRadius = 8.dp,
    rowHeight = 58.dp,
    gapXs = 4.dp,
    gapS = 8.dp,
    gapM = 14.dp,
    gapL = 20.dp,
    gapXl = 28.dp,
    switchWidth = 34.dp,
    switchHeight = 19.dp,
    switchKnob = 11.dp,
)

val PaperSurface = DshSurfaceStyle(
    hasPanels = false,
    ruleSections = true,
    topHighlight = false,
    accentBudgetPerScreen = 3,
)

val PaperMotion = DshMotion(
    useSpring = false,
    stiffness = 0f,
    damping = 0f,
    enterMillis = 220,
    staggerMillis = 30,
    themeFadeMillis = 320,
    breathe = false,
)

val PaperDesign = DshDesign(
    theme = AppTheme.PAPER,
    colors = PaperColors,
    dimens = PaperDimens,
    surface = PaperSurface,
    motion = PaperMotion,
)

// ---------------------------------------------------------------------------
// Graphite：冷石墨 + 去饱和雾玉绿，软面板 + 顶部高光，弹簧动效
// ---------------------------------------------------------------------------
val GraphiteColors = DshColors(
    canvas = Color(0xFF0F1214),
    canvasLift = Color(0xFF161B1D),
    canvasShade = Color(0xFF0A0D0E),
    panelTop = Color(0xFF1C2125),
    panelBottom = Color(0xFF171B1E),
    ink = Color(0xFFE9EDEC),
    inkMuted = Color(0xFF96A0A1),
    inkFaint = Color(0xFF68716F),
    hairline = Color(0x11FFFFFF),
    hairlineStrong = Color(0x1FFFFFFF),
    accent = Color(0xFF8FB3A4),
    accentInk = Color(0xFF0C1310),
    accentDim = Color(0x248FB3A4),
    accentBorder = Color(0x6B8FB3A4),
    accentGlow = Color(0x8C8FB3A4),
    track = Color(0x14FFFFFF),
    knob = Color(0xFF6B7370),
)

val GraphiteDimens = DshDimens(
    screenPadding = 24.dp,
    panelRadius = 20.dp,
    tileRadius = 16.dp,
    controlRadius = 13.dp,
    iconRadius = 11.dp,
    rowHeight = 56.dp,
    gapXs = 6.dp,
    gapS = 10.dp,
    gapM = 14.dp,
    gapL = 20.dp,
    gapXl = 28.dp,
    switchWidth = 38.dp,
    switchHeight = 22.dp,
    switchKnob = 14.dp,
)

val GraphiteSurface = DshSurfaceStyle(
    hasPanels = true,
    ruleSections = false,
    topHighlight = true,
    accentBudgetPerScreen = 3,
)

val GraphiteMotion = DshMotion(
    useSpring = true,
    stiffness = 380f,
    damping = 32f,
    enterMillis = 240,
    staggerMillis = 30,
    themeFadeMillis = 320,
    breathe = true,
)

val GraphiteDesign = DshDesign(
    theme = AppTheme.GRAPHITE,
    colors = GraphiteColors,
    dimens = GraphiteDimens,
    surface = GraphiteSurface,
    motion = GraphiteMotion,
)

val LocalDshDesign = staticCompositionLocalOf { PaperDesign }

/** 组件层的唯一入口：Dsh.colors / Dsh.dimens / Dsh.surface / Dsh.motion / Dsh.type */
object Dsh {
    val design: DshDesign
        @Composable @ReadOnlyComposable get() = LocalDshDesign.current

    val colors: DshColors
        @Composable @ReadOnlyComposable get() = LocalDshDesign.current.colors

    val dimens: DshDimens
        @Composable @ReadOnlyComposable get() = LocalDshDesign.current.dimens

    val surface: DshSurfaceStyle
        @Composable @ReadOnlyComposable get() = LocalDshDesign.current.surface

    val motion: DshMotion
        @Composable @ReadOnlyComposable get() = LocalDshDesign.current.motion

    val type: DshType
        @Composable @ReadOnlyComposable get() = LocalDshType.current
}
