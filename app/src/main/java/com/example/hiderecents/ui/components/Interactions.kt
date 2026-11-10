package com.example.hiderecents.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.hiderecents.ui.theme.Dsh
import com.example.hiderecents.ui.theme.MotionSpecs
import com.example.hiderecents.ui.theme.rememberMotionEnabled
import kotlinx.coroutines.delay

/**
 * 统一的可点击行为：
 * - 关掉 Material 涟漪（和两套视觉都不搭）
 * - 按下缩放 + 透明度，松手弹簧回弹
 * - 点击瞬间一次轻触感反馈
 *
 * scaleOnPress = false 用于整行列表项：整行缩放会露出边缘缝隙，
 * 行级按压改为「背景色调 + 透明度」。
 */
fun Modifier.dshClickable(
    enabled: Boolean = true,
    scaleOnPress: Boolean = true,
    haptic: Boolean = true,
    onClick: () -> Unit,
): Modifier = composed {
    val motionEnabled = rememberMotionEnabled()
    val motion = Dsh.motion
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val haptics = LocalHapticFeedback.current

    val scale by animateFloatAsState(
        targetValue = if (pressed && scaleOnPress && enabled) MotionSpecs.PRESS_SCALE else 1f,
        animationSpec = MotionSpecs.press(motion, motionEnabled),
        label = "dshPressScale",
    )
    val alpha by animateFloatAsState(
        targetValue = if (pressed && enabled) MotionSpecs.PRESS_ALPHA else 1f,
        animationSpec = MotionSpecs.press(motion, motionEnabled),
        label = "dshPressAlpha",
    )

    this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
            this.alpha = alpha
        }
        .clickable(
            interactionSource = interaction,
            indication = null,
            enabled = enabled,
        ) {
            if (haptic && enabled) {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            }
            onClick()
        }
}

/** 列表行按压：背景色调亮/压暗，不做缩放 */
fun Modifier.dshRowPressTint(): Modifier = composed {
    val motionEnabled = rememberMotionEnabled()
    val motion = Dsh.motion
    val hasPanels = Dsh.surface.hasPanels
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()

    val tint by animateColorAsState(
        targetValue = if (pressed) {
            if (hasPanels) Color.White.copy(alpha = 0.045f) else Color.Black.copy(alpha = 0.035f)
        } else {
            Color.Transparent
        },
        animationSpec = MotionSpecs.color(motion, motionEnabled),
        label = "dshRowTint",
    )

    this.background(color = tint)
}

/**
 * 状态点。
 * 石墨：圆点 + 呼吸光晕（1.0 -> 1.12，1800ms 循环）。
 * 纸本：静止方块，无光晕（纸本不使用发光效果）。
 */
@Composable
fun BreathingDot(
    color: Color,
    modifier: Modifier = Modifier,
    dotSize: Dp = 8.dp,
) {
    val motion = Dsh.motion
    val motionEnabled = rememberMotionEnabled()
    val hasPanels = Dsh.surface.hasPanels
    val glowColor = Dsh.colors.accentGlow
    val dimColor = Dsh.colors.accentDim

    // 纸本：静止方块
    if (!hasPanels) {
        Box(modifier.size(dotSize).background(color = color))
        return
    }

    // 石墨但关掉了动画：静态圆点 + 静态光晕环
    if (!motion.breathe || !motionEnabled) {
        Box(
            modifier
                .size(dotSize)
                .clip(RoundedCornerShape(percent = 50))
                .background(color = color)
                .border(width = 4.dp, color = dimColor, shape = RoundedCornerShape(percent = 50)),
        )
        return
    }

    val transition = rememberInfiniteTransition(label = "dshBreathe")
    val pulse by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "dshBreathePulse",
    )

    val glowSize = dotSize * 3.4f

    Box(
        modifier = modifier.size(glowSize),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(glowSize)
                .graphicsLayer {
                    val s = 0.82f + 0.18f * pulse
                    scaleX = s
                    scaleY = s
                    alpha = 0.35f + 0.35f * pulse
                }
                .background(
                    brush = Brush.radialGradient(colors = listOf(glowColor, Color.Transparent)),
                ),
        )
        Box(
            Modifier
                .size(dotSize)
                .clip(RoundedCornerShape(percent = 50))
                .background(color = color),
        )
    }
}

/**
 * 阶梯入场：面板与列表项逐级显现，间隔 staggerMillis。
 * 只动 alpha 与 translationY，不动布局尺寸。
 */
@Composable
fun StaggeredEnter(
    index: Int = 0,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val motion = Dsh.motion
    val motionEnabled = rememberMotionEnabled()
    val risePx = with(LocalDensity.current) { MotionSpecs.ENTER_RISE_DP.dp.toPx() }
    var shown by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (!enabled || !motionEnabled) {
            shown = true
        } else {
            delay((index.coerceAtLeast(0) * MotionSpecs.STAGGER_STEP_MS).toLong())
            shown = true
        }
    }

    val progress by animateFloatAsState(
        targetValue = if (shown) 1f else 0f,
        animationSpec = MotionSpecs.enter(motion, motionEnabled),
        label = "dshStagger",
    )

    Box(
        modifier.graphicsLayer {
            alpha = progress
            translationY = (1f - progress) * risePx
        },
    ) {
        content()
    }
}

/**
 * 开关：
 * 石墨 = 药丸形，滑块弹簧位移 + 光晕；纸本 = 方角，滑块瞬时位移。
 * 轨道颜色、描边、滑块颜色三者独立过渡。
 */
@Composable
fun DshSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val colors = Dsh.colors
    val dimens = Dsh.dimens
    val motion = Dsh.motion
    val hasPanels = Dsh.surface.hasPanels
    val motionEnabled = rememberMotionEnabled()

    val trackShape = if (hasPanels) RoundedCornerShape(percent = 50) else RoundedCornerShape(0.dp)
    val thumbShape = if (hasPanels) RoundedCornerShape(percent = 50) else RoundedCornerShape(0.dp)

    val travel = dimens.switchWidth - dimens.switchKnob - 6.dp
    val thumbX by animateDpAsState(
        targetValue = if (checked) travel else 3.dp,
        animationSpec = MotionSpecs.settleDp(motion, motionEnabled),
        label = "dshSwitchThumb",
    )
    val trackColor by animateColorAsState(
        targetValue = when {
            checked -> if (hasPanels) colors.accentDim else colors.accent
            else -> if (hasPanels) colors.track else Color.Transparent
        },
        animationSpec = MotionSpecs.color(motion, motionEnabled),
        label = "dshSwitchTrack",
    )
    val borderColor by animateColorAsState(
        targetValue = when {
            checked -> colors.accentBorder
            else -> if (hasPanels) colors.hairline else colors.hairlineStrong
        },
        animationSpec = MotionSpecs.color(motion, motionEnabled),
        label = "dshSwitchBorder",
    )
    val thumbColor by animateColorAsState(
        targetValue = when {
            checked -> if (hasPanels) colors.accent else colors.accentInk
            else -> if (hasPanels) colors.knob else colors.inkMuted
        },
        animationSpec = MotionSpecs.color(motion, motionEnabled),
        label = "dshSwitchThumbColor",
    )

    val clickModifier = if (onCheckedChange != null) {
        Modifier.dshClickable(scaleOnPress = false) { onCheckedChange(!checked) }
    } else {
        Modifier
    }

    Box(
        modifier = modifier
            .then(clickModifier)
            .size(dimens.switchWidth, dimens.switchHeight)
            .clip(trackShape)
            .background(color = trackColor)
            .border(width = 1.dp, color = borderColor, shape = trackShape),
    ) {
        Box(
            Modifier
                .offset(x = thumbX, y = 3.dp)
                .size(dimens.switchKnob)
                .clip(thumbShape)
                .background(color = thumbColor),
        )
    }
}
