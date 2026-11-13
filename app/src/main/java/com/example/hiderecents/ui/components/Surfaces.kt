package com.example.hiderecents.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.hiderecents.ui.theme.Dsh
import com.example.hiderecents.ui.theme.MotionSpecs
import com.example.hiderecents.ui.theme.rememberMotionEnabled

/** 自绘图标集：统一 24x24 坐标系、1.5dp 描边、圆头端点。不使用 Material 图标库默认形状。 */
enum class DshIconKind {
    GEAR, GRID, SHIELD, CHEVRON, SEARCH, TERMINAL, BELL, PACKAGE, TRANSFER, MENU, ARROW_LEFT, ARROW_RIGHT,
    FILE, IMAGE, CLIPBOARD, FOLDER, BACK, SLIDERS, HEART
}

/** 画布背景：主题底色的纵向渐变（纸本是纸面提亮到纸影，石墨是冷石墨到更深的底）。 */
@Composable
fun Modifier.dshCanvasBackground(): Modifier {
    val colors = Dsh.colors
    return this.background(
        brush = Brush.verticalGradient(
            listOf(colors.canvasLift, colors.canvas, colors.canvasShade),
        ),
    )
}

/**
 * 面板：
 * 石墨 = 圆角 + 渐变填充 + 1dp 发丝描边 + 顶部居中 1px 高光（模拟边缘折射）。
 * 纸本 = 完全透明，靠 DshDivider 切分，不使用卡片。
 */
@Composable
fun DshPanel(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = Dsh.colors
    val dimens = Dsh.dimens
    val surface = Dsh.surface

    if (!surface.hasPanels) {
        Column(modifier.padding(contentPadding), content = content)
        return
    }

    val shape = RoundedCornerShape(dimens.panelRadius)
    Box(modifier) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(brush = Brush.verticalGradient(listOf(colors.panelTop, colors.panelBottom)))
                .border(1.dp, colors.hairline, shape)
                .padding(contentPadding),
            content = content,
        )
        if (surface.topHighlight) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp)
                    .height(1.dp)
                    .align(Alignment.TopCenter)
                    .background(
                        brush = Brush.horizontalGradient(
                            listOf(Color.Transparent, Color.White.copy(alpha = 0.20f), Color.Transparent),
                        ),
                    ),
            )
        }
    }
}

@Composable
fun DshDivider(modifier: Modifier = Modifier, strong: Boolean = false) {
    Box(
        modifier
            .fillMaxWidth()
            .height(if (strong) 1.5.dp else 1.dp)
            .background(color = if (strong) Dsh.colors.hairlineStrong else Dsh.colors.hairline),
    )
}

@Composable
fun SectionHeader(title: String, trailing: String? = null, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = Dsh.type.label, color = Dsh.colors.inkFaint)
        Spacer(Modifier.weight(1f))
        if (trailing != null) {
            Text(trailing, style = Dsh.type.label, color = Dsh.colors.inkFaint.copy(alpha = 0.6f))
        }
    }
}

@Composable
fun DshIconButton(kind: DshIconKind, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = Dsh.colors
    val shape = RoundedCornerShape(Dsh.dimens.iconRadius)
    val bg = if (Dsh.surface.hasPanels) Color.White.copy(alpha = 0.03f) else Color.Transparent
    Box(
        modifier
            .size(34.dp)
            .clip(shape)
            .background(color = bg)
            .border(1.dp, colors.hairline, shape)
            .dshClickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        DshIcon(kind = kind, iconSize = 16.dp, tint = colors.inkMuted)
    }
}

@Composable
fun DshIcon(
    kind: DshIconKind,
    modifier: Modifier = Modifier,
    iconSize: Dp = 18.dp,
    tint: Color = Dsh.colors.inkMuted,
    strokeWidth: Dp = 1.5.dp,
) {
    Canvas(modifier.size(iconSize)) {
        val u = size.minDimension / 24f
        val w = strokeWidth.toPx()
        val stroke = Stroke(width = w, cap = StrokeCap.Round, join = StrokeJoin.Round)

        fun line(x1: Float, y1: Float, x2: Float, y2: Float) {
            drawLine(
                color = tint,
                start = Offset(x1 * u, y1 * u),
                end = Offset(x2 * u, y2 * u),
                strokeWidth = w,
                cap = StrokeCap.Round,
            )
        }

        when (kind) {
            DshIconKind.HEART -> {
                val p = Path().apply {
                    moveTo(12f * u, 20.4f * u)
                    cubicTo(4.4f * u, 15.2f * u, 3.2f * u, 10.6f * u, 5.7f * u, 7.7f * u)
                    cubicTo(7.7f * u, 5.2f * u, 10.8f * u, 5.7f * u, 12f * u, 8.3f * u)
                    cubicTo(13.2f * u, 5.7f * u, 16.3f * u, 5.2f * u, 18.3f * u, 7.7f * u)
                    cubicTo(20.8f * u, 10.6f * u, 19.6f * u, 15.2f * u, 12f * u, 20.4f * u)
                    close()
                }
                drawPath(p, tint, style = stroke)
            }

            DshIconKind.SLIDERS -> {
                line(4f, 7f, 20f, 7f)
                drawCircle(color = tint, radius = 2f * u, center = Offset(9f * u, 7f * u), style = stroke)
                line(4f, 12f, 20f, 12f)
                drawCircle(color = tint, radius = 2f * u, center = Offset(15f * u, 12f * u), style = stroke)
                line(4f, 17f, 20f, 17f)
                drawCircle(color = tint, radius = 2f * u, center = Offset(8f * u, 17f * u), style = stroke)
            }

            DshIconKind.GEAR -> {
                drawCircle(color = tint, radius = 3.1f * u, center = Offset(12f * u, 12f * u), style = stroke)
                line(12f, 3.2f, 12f, 5.4f)
                line(12f, 18.6f, 12f, 20.8f)
                line(3.2f, 12f, 5.4f, 12f)
                line(18.6f, 12f, 20.8f, 12f)
                line(5.9f, 5.9f, 7.5f, 7.5f)
                line(16.5f, 16.5f, 18.1f, 18.1f)
                line(5.9f, 18.1f, 7.5f, 16.5f)
                line(16.5f, 7.5f, 18.1f, 5.9f)
            }

            DshIconKind.GRID -> {
                val r = CornerRadius(2f * u, 2f * u)
                drawRoundRect(tint, Offset(3.5f * u, 4.5f * u), Size(7f * u, 7f * u), r, style = stroke)
                drawRoundRect(tint, Offset(13.5f * u, 4.5f * u), Size(7f * u, 7f * u), r, style = stroke)
                drawRoundRect(tint, Offset(3.5f * u, 14.5f * u), Size(7f * u, 5f * u), r, style = stroke)
                drawRoundRect(tint, Offset(13.5f * u, 14.5f * u), Size(7f * u, 5f * u), r, style = stroke)
            }

            DshIconKind.SHIELD -> {
                val p = Path().apply {
                    moveTo(12f * u, 3.5f * u)
                    lineTo(19f * u, 6.5f * u)
                    lineTo(19f * u, 11.5f * u)
                    cubicTo(19f * u, 15.7f * u, 16.1f * u, 18.8f * u, 12f * u, 20.5f * u)
                    cubicTo(7.9f * u, 18.8f * u, 5f * u, 15.7f * u, 5f * u, 11.5f * u)
                    lineTo(5f * u, 6.5f * u)
                    close()
                }
                drawPath(p, tint, style = stroke)
            }

            DshIconKind.CHEVRON -> {
                val p = Path().apply {
                    moveTo(9f * u, 5f * u)
                    lineTo(16f * u, 12f * u)
                    lineTo(9f * u, 19f * u)
                }
                drawPath(p, tint, style = stroke)
            }

            DshIconKind.SEARCH -> {
                drawCircle(color = tint, radius = 6.4f * u, center = Offset(11f * u, 11f * u), style = stroke)
                line(20f, 20f, 15.8f, 15.8f)
            }

            DshIconKind.TERMINAL -> {
                drawRoundRect(
                    tint,
                    Offset(3f * u, 4.5f * u),
                    Size(18f * u, 15f * u),
                    CornerRadius(3f * u, 3f * u),
                    style = stroke,
                )
                val p = Path().apply {
                    moveTo(7f * u, 10f * u)
                    lineTo(9.5f * u, 12.5f * u)
                    lineTo(7f * u, 15f * u)
                }
                drawPath(p, tint, style = stroke)
                line(12.5f, 15.5f, 17f, 15.5f)
            }

            DshIconKind.BELL -> {
                val p = Path().apply {
                    moveTo(6.5f * u, 9.5f * u)
                    cubicTo(6.5f * u, 6.5f * u, 8.9f * u, 4f * u, 12f * u, 4f * u)
                    cubicTo(15.1f * u, 4f * u, 17.5f * u, 6.5f * u, 17.5f * u, 9.5f * u)
                    lineTo(17.5f * u, 13.5f * u)
                    lineTo(19f * u, 16f * u)
                    lineTo(5f * u, 16f * u)
                    lineTo(6.5f * u, 13.5f * u)
                    close()
                }
                drawPath(p, tint, style = stroke)
                val c = Path().apply {
                    moveTo(10f * u, 18.6f * u)
                    cubicTo(10.4f * u, 19.6f * u, 11.2f * u, 20f * u, 12f * u, 20f * u)
                    cubicTo(12.8f * u, 20f * u, 13.6f * u, 19.6f * u, 14f * u, 18.6f * u)
                }
                drawPath(c, tint, style = stroke)
            }

            DshIconKind.PACKAGE -> {
                val p = Path().apply {
                    moveTo(12f * u, 3.2f * u)
                    lineTo(20f * u, 7.4f * u)
                    lineTo(20f * u, 16.6f * u)
                    lineTo(12f * u, 20.8f * u)
                    lineTo(4f * u, 16.6f * u)
                    lineTo(4f * u, 7.4f * u)
                    close()
                }
                drawPath(p, tint, style = stroke)
                val q = Path().apply {
                    moveTo(4.4f * u, 7.6f * u)
                    lineTo(12f * u, 12f * u)
                    lineTo(19.6f * u, 7.6f * u)
                }
                drawPath(q, tint, style = stroke)
                line(12f, 12f, 12f, 21f)
            }

            DshIconKind.TRANSFER -> {
                val p = Path().apply {
                    moveTo(4f * u, 8.5f * u)
                    lineTo(15f * u, 8.5f * u)
                    moveTo(12.4f * u, 5.9f * u)
                    lineTo(15f * u, 8.5f * u)
                    moveTo(20f * u, 15.5f * u)
                    lineTo(9f * u, 15.5f * u)
                    moveTo(11.6f * u, 18.1f * u)
                    lineTo(9f * u, 15.5f * u)
                }
                drawPath(p, tint, style = stroke)
            }

            DshIconKind.MENU -> {
                line(4f, 7f, 20f, 7f)
                line(4f, 12f, 15f, 12f)
                line(4f, 17f, 18f, 17f)
            }

            DshIconKind.FILE -> {
                val p = Path().apply {
                    moveTo(6f * u, 3.5f * u)
                    lineTo(14f * u, 3.5f * u)
                    lineTo(18.5f * u, 8f * u)
                    lineTo(18.5f * u, 20.5f * u)
                    lineTo(6f * u, 20.5f * u)
                    close()
                }
                drawPath(p, tint, style = stroke)
                val q = Path().apply {
                    moveTo(14f * u, 3.5f * u)
                    lineTo(14f * u, 8f * u)
                    lineTo(18.5f * u, 8f * u)
                }
                drawPath(q, tint, style = stroke)
            }

            DshIconKind.IMAGE -> {
                drawRoundRect(
                    tint,
                    Offset(3.5f * u, 5f * u),
                    Size(17f * u, 14f * u),
                    CornerRadius(2.5f * u, 2.5f * u),
                    style = stroke,
                )
                val p = Path().apply {
                    moveTo(4.5f * u, 16f * u)
                    lineTo(9.5f * u, 11f * u)
                    lineTo(13f * u, 14.5f * u)
                    lineTo(15.5f * u, 12f * u)
                    lineTo(19.5f * u, 16f * u)
                }
                drawPath(p, tint, style = stroke)
                drawCircle(
                    color = tint,
                    radius = 1.4f * u,
                    center = Offset(8.2f * u, 9f * u),
                    style = stroke,
                )
            }

            DshIconKind.CLIPBOARD -> {
                drawRoundRect(
                    tint,
                    Offset(5f * u, 5f * u),
                    Size(14f * u, 15.5f * u),
                    CornerRadius(2.5f * u, 2.5f * u),
                    style = stroke,
                )
                drawRoundRect(
                    tint,
                    Offset(9f * u, 3f * u),
                    Size(6f * u, 3.5f * u),
                    CornerRadius(1.5f * u, 1.5f * u),
                    style = stroke,
                )
                line(8.5f, 11f, 15.5f, 11f)
                line(8.5f, 15f, 13.5f, 15f)
            }

            DshIconKind.FOLDER -> {
                val p = Path().apply {
                    moveTo(3.5f * u, 7f * u)
                    lineTo(9.5f * u, 7f * u)
                    lineTo(11.5f * u, 9.5f * u)
                    lineTo(20.5f * u, 9.5f * u)
                    lineTo(20.5f * u, 19f * u)
                    lineTo(3.5f * u, 19f * u)
                    close()
                }
                drawPath(p, tint, style = stroke)
            }

            DshIconKind.BACK -> {
                val p = Path().apply {
                    moveTo(15f * u, 5f * u)
                    lineTo(8f * u, 12f * u)
                    lineTo(15f * u, 19f * u)
                }
                drawPath(p, tint, style = stroke)
            }

            DshIconKind.ARROW_LEFT -> {
                line(20f, 12f, 5f, 12f)
                val p = Path().apply {
                    moveTo(10f * u, 7f * u)
                    lineTo(5f * u, 12f * u)
                    lineTo(10f * u, 17f * u)
                }
                drawPath(p, tint, style = stroke)
            }

            DshIconKind.ARROW_RIGHT -> {
                line(4f, 12f, 19f, 12f)
                val p = Path().apply {
                    moveTo(14f * u, 7f * u)
                    lineTo(19f * u, 12f * u)
                    lineTo(14f * u, 17f * u)
                }
                drawPath(p, tint, style = stroke)
            }
        }
    }
}

private val TileGradients = listOf(
    listOf(Color(0xFF93AE86), Color(0xFF65805A)),
    listOf(Color(0xFFCDBFA9), Color(0xFFA08C74)),
    listOf(Color(0xFFA6B4C4), Color(0xFF74869A)),
    listOf(Color(0xFFCBA08C), Color(0xFFA2705A)),
    listOf(Color(0xFFB6ABC6), Color(0xFF8A7C9F)),
    listOf(Color(0xFFCFB68C), Color(0xFFA88C5F)),
)

/** 应用图标占位方块：真实图标由 PackageManager 提供，这里用于骨架与预览。 */
@Composable
fun AppIconTile(
    label: String,
    seed: Int,
    tileSize: Dp = 36.dp,
    corner: Dp = Dsh.dimens.iconRadius,
) {
    val n = TileGradients.size
    val index = ((seed % n) + n) % n
    Box(
        Modifier
            .size(tileSize)
            .clip(RoundedCornerShape(corner))
            .background(brush = Brush.linearGradient(TileGradients[index])),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label.take(1),
            style = Dsh.type.rowTitle,
            color = Color(0xFF0C1113),
        )
    }
}

@Composable
fun DshPrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = Dsh.colors
    if (Dsh.surface.hasPanels) {
        Box(
            modifier
                .height(40.dp)
                .clip(RoundedCornerShape(Dsh.dimens.controlRadius))
                .background(color = colors.accent)
                .dshClickable(onClick = onClick)
                .padding(horizontal = 20.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(text, style = Dsh.type.rowTitle, color = colors.accentInk)
        }
    } else {
        Column(
            modifier.dshClickable(onClick = onClick).padding(vertical = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(text, style = Dsh.type.rowTitle, color = colors.accent)
            Spacer(Modifier.height(3.dp))
            Box(Modifier.height(1.5.dp).background(color = colors.accent))
        }
    }
}

@Composable
fun DshGhostButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = Dsh.colors
    val shape = RoundedCornerShape(Dsh.dimens.controlRadius)
    Box(
        modifier
            .height(40.dp)
            .clip(shape)
            .background(color = if (Dsh.surface.hasPanels) Color.White.copy(alpha = 0.02f) else Color.Transparent)
            .border(1.dp, colors.hairlineStrong, shape)
            .dshClickable(onClick = onClick)
            .padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = Dsh.type.rowTitle, color = colors.inkMuted)
    }
}

/**
 * 可点击整行：整行不做缩放（会露出边缘缝隙），改用背景色调按压。
 * 点击瞬间一次触感反馈。
 */
@Composable
fun DshPressRow(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    val motion = Dsh.motion
    val motionEnabled = rememberMotionEnabled()
    val hasPanels = Dsh.surface.hasPanels
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val haptics = LocalHapticFeedback.current

    val tint by animateColorAsState(
        targetValue = if (pressed) {
            if (hasPanels) Color.White.copy(alpha = 0.045f) else Color.Black.copy(alpha = 0.035f)
        } else {
            Color.Transparent
        },
        animationSpec = MotionSpecs.color(motion, motionEnabled),
        label = "dshPressRowTint",
    )

    Row(
        modifier
            .fillMaxWidth()
            .background(color = tint)
            .clickable(interactionSource = interaction, indication = null) {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                onClick()
            },
        content = content,
    )
}

/** 底部微型状态开关：点一下翻转，点亮时强调色实心点。 */
@Composable
fun MiniToggle(
    label: String,
    on: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val colors = Dsh.colors
    Row(
        modifier
            .dshClickable(scaleOnPress = false, onClick = onClick)
            .padding(vertical = 4.dp, horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(5.dp)
                .clip(RoundedCornerShape(percent = 50))
                .background(color = if (on) colors.accent else colors.inkFaint.copy(alpha = 0.55f)),
        )
        Spacer(Modifier.width(7.dp))
        Text(label, style = Dsh.type.label, color = colors.inkFaint)
    }
}

/**
 * 纸本顶栏底线：参考图里顶栏下方是一条 1.5dp 墨黑实线。
 * 石墨不加线——面板本身的分层已经承担了分隔。
 */
@Composable
fun Modifier.paperHeaderRule(): Modifier {
    val hasPanels = Dsh.surface.hasPanels
    val ink = Dsh.colors.ink
    return if (hasPanels) {
        this
    } else {
        this.drawBehind {
            drawLine(
                color = ink,
                start = Offset(0f, size.height),
                end = Offset(size.width, size.height),
                strokeWidth = 1.5.dp.toPx(),
            )
        }
    }
}

/**
 * 点状引导线：纸本入口行里标题与说明之间的那排小点。
 * 用 Canvas 逐点画，而不是虚线边框——虚线的 dash 节奏在窄屏上会断得很难看。
 */
@Composable
fun DottedLeader(
    modifier: Modifier = Modifier,
    color: Color = Dsh.colors.hairlineStrong,
) {
    val dot = color
    Canvas(modifier.height(1.dp)) {
        val radius = 0.8.dp.toPx()
        val step = 4.dp.toPx()
        var x = 0f
        val cy = size.height / 2f
        while (x < size.width) {
            drawCircle(color = dot, radius = radius, center = Offset(x, cy))
            x += step
        }
    }
}

/**
 * 输入框 / 胶囊 / 提示条的底色。
 *
 * 为什么需要它：纸本主题里 panelTop 是全透明 Color(0x00000000)，
 * 直接 panelTop.copy(alpha = 0.5f) 会得到 50% 纯黑 —— 落在米白纸上就是一块脏灰，
 * ADB 页的快捷命令块就是这么变灰的。纸本不用填充色，靠 1px 描边分层。
 */
@Composable
fun dshFieldFill(alpha: Float = 0.55f): Color {
    val colors = Dsh.colors
    return if (Dsh.surface.hasPanels) colors.panelTop.copy(alpha = alpha) else Color.Transparent
}
