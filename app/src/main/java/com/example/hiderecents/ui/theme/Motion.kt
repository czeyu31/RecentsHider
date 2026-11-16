package com.example.hiderecents.ui.theme

import android.provider.Settings
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.CubicBezierEasing
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
 * 缓动曲线与时长。
 *
 * 两套主题共用这一套：这里表达的是「东西怎么进出」，与材质无关。
 * 三档语义按出现/消失/移动区分，不再逐个组件各调一个时长：
 * - 消失（弹窗关闭、提示消失）：ease-in，最短，收得干脆
 * - 出现（弹窗出现、抽屉滑入、卡片进场）：ease-out，稍长，落得稳
 * - 移动（页内位移、展开收起、页面切换）：ease-in-out，最长，两头都缓
 */
object MotionEase {

    /** CSS ease-in：起步慢、收尾快 —— 用于「离开」 */
    val In = CubicBezierEasing(0.42f, 0f, 1f, 1f)

    /** CSS ease-out：起步快、收尾慢 —— 用于「进入」 */
    val Out = CubicBezierEasing(0f, 0f, 0.58f, 1f)

    /** CSS ease-in-out：两头缓 —— 用于「移动」 */
    val InOut = CubicBezierEasing(0.42f, 0f, 0.58f, 1f)

    const val DUR_EXIT = 150
    const val DUR_ENTER = 250
    const val DUR_MOVE = 300

    /** 主题切换：圆形遮罩扩散/收回 */
    const val DUR_THEME_REVEAL = 500

    private fun dur(millis: Int, enabled: Boolean) =
        if (enabled) millis else MotionSpecs.REDUCED_MILLIS

    fun <T> enter(enabled: Boolean = true): AnimationSpec<T> =
        tween(durationMillis = dur(DUR_ENTER, enabled), easing = Out)

    fun <T> exit(enabled: Boolean = true): AnimationSpec<T> =
        tween(durationMillis = dur(DUR_EXIT, enabled), easing = In)

    fun <T> move(enabled: Boolean = true): AnimationSpec<T> =
        tween(durationMillis = dur(DUR_MOVE, enabled), easing = InOut)

    /** 逐个错开：同一段移动，但每个元素晚 delayMillis 起步 */
    fun <T> spread(enabled: Boolean = true, delayMillis: Int): AnimationSpec<T> = tween(
        durationMillis = dur(DUR_MOVE, enabled),
        delayMillis = if (enabled) delayMillis else 0,
        easing = InOut,
    )

    /** 逐级散开：每个元素之间的间隔 */
    const val SPREAD_STEP_MS = 40

    /** 复制反馈：对勾保持多久后复原 */
    const val COPY_HOLD_MS = 1000L
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
