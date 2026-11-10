package com.example.hiderecents.ui.theme

import android.provider.Settings
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp

/**
 * 动效规格的唯一来源，与 DESIGN.md 第 6 节一一对应。
 *
 * 石墨：弹簧物理，有弹性、有重量。
 * 纸本：只用位移 + 透明度，无弹性，克制到像印刷品。
 * 系统「移除动画」开启时，一切降级为极短淡入。
 */
object MotionSpecs {

    /** 按压：始终要快，松手回弹靠弹簧 */
    const val PRESS_SCALE = 0.972f
    const val PRESS_ALPHA = 0.92f

    /** 入场：面板与列表的位移距离 */
    const val ENTER_RISE_DP = 8f

    /** 阶梯入场每行递增 */
    const val STAGGER_STEP_MS = 30

    /** 关闭动画时的统一降级时长 */
    const val REDUCED_MILLIS = 120

    // ---------------- 浮点通道 ----------------

    fun enter(m: DshMotion, enabled: Boolean): AnimationSpec<Float> = when {
        !enabled -> tween(durationMillis = REDUCED_MILLIS, easing = FastOutSlowInEasing)
        m.useSpring -> spring(dampingRatio = 0.86f, stiffness = m.stiffness * 2.2f)
        else -> tween(durationMillis = m.enterMillis, easing = FastOutSlowInEasing)
    }

    fun press(m: DshMotion, enabled: Boolean): AnimationSpec<Float> = when {
        !enabled -> tween(durationMillis = 0)
        m.useSpring -> spring(dampingRatio = 0.55f, stiffness = 1400f)
        else -> tween(durationMillis = 90, easing = FastOutSlowInEasing)
    }

    fun settle(m: DshMotion, enabled: Boolean): AnimationSpec<Float> = when {
        !enabled -> tween(durationMillis = 0)
        m.useSpring -> spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMediumLow,
        )
        else -> tween(durationMillis = 160, easing = FastOutSlowInEasing)
    }

    fun swipe(m: DshMotion, enabled: Boolean): AnimationSpec<Float> = when {
        !enabled -> tween(durationMillis = 0)
        m.useSpring -> spring(dampingRatio = 0.9f, stiffness = 420f)
        else -> tween(durationMillis = 120, easing = FastOutSlowInEasing)
    }

    // ---------------- 尺寸通道（Dp 需要独立 spec） ----------------

    fun settleDp(m: DshMotion, enabled: Boolean): AnimationSpec<Dp> = when {
        !enabled -> tween(durationMillis = 0)
        m.useSpring -> spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMediumLow,
        )
        else -> tween(durationMillis = 160, easing = FastOutSlowInEasing)
    }

    fun enterDp(m: DshMotion, enabled: Boolean): AnimationSpec<Dp> = when {
        !enabled -> tween(durationMillis = REDUCED_MILLIS, easing = FastOutSlowInEasing)
        m.useSpring -> spring(dampingRatio = 0.86f, stiffness = m.stiffness * 2.2f)
        else -> tween(durationMillis = m.enterMillis, easing = FastOutSlowInEasing)
    }

    // ---------------- 颜色通道 ----------------

    fun color(m: DshMotion, enabled: Boolean): AnimationSpec<Color> =
        tween(durationMillis = if (enabled) m.themeFadeMillis else 0)
}

/**
 * 是否允许播动画：读系统的「移除动画」开关。
 * 无障碍要求：系统关掉动画后，我们不能再自己造弹簧。
 */
@Composable
fun rememberMotionEnabled(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        try {
            val scale = Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f,
            )
            scale > 0f
        } catch (t: Throwable) {
            true
        }
    }
}
