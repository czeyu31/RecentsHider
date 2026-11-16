package com.example.hiderecents.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.example.hiderecents.ui.theme.Dsh
import com.example.hiderecents.ui.theme.MotionEase
import com.example.hiderecents.ui.theme.rememberMotionEnabled
import kotlinx.coroutines.delay

/**
 * 复制按钮与它的反馈。
 *
 * 点击后图标原地转半圈换成对勾，按钮上方淡入「已复制」，一秒后一起复原：
 * 出现用 ease-out（250ms），消失用 ease-in（150ms）——收得比出现快。
 *
 * 提示浮在按钮上方 26dp，调用方需要在容器里留出这点空间，否则会被裁掉。
 */
@Composable
fun DshCopyButton(
    text: String,
    modifier: Modifier = Modifier,
    tint: Color = Dsh.colors.inkMuted,
) {
    val colors = Dsh.colors
    val type = Dsh.type
    val shape = RoundedCornerShape(Dsh.dimens.iconRadius)
    val motionEnabled = rememberMotionEnabled()
    val clipboard = LocalClipboardManager.current

    var copied by remember { mutableStateOf(false) }

    LaunchedEffect(copied) {
        if (copied) {
            delay(MotionEase.COPY_HOLD_MS)
            copied = false
        }
    }

    val spin by animateFloatAsState(
        targetValue = if (copied) 1f else 0f,
        animationSpec = MotionEase.move(motionEnabled),
        label = "dshCopySpin",
    )
    val hint by animateFloatAsState(
        targetValue = if (copied) 1f else 0f,
        animationSpec = if (copied) MotionEase.enter(motionEnabled) else MotionEase.exit(motionEnabled),
        label = "dshCopyHint",
    )

    Box(modifier) {
        if (hint > 0.01f) {
            Text(
                text = "已复制",
                style = type.monoSmall,
                color = colors.accentInk,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .offset(y = (-25).dp)
                    .graphicsLayer {
                        alpha = hint
                        translationY = (1f - hint) * 6f
                    }
                    .clip(RoundedCornerShape(4.dp))
                    .background(color = colors.accent)
                    .padding(horizontal = 7.dp, vertical = 3.dp),
            )
        }

        Box(
            Modifier
                .size(34.dp)
                .clip(shape)
                .background(
                    color = if (Dsh.surface.hasPanels) Color.White.copy(alpha = 0.03f) else Color.Transparent,
                )
                .border(1.dp, colors.hairline, shape)
                .dshClickable {
                    clipboard.setText(AnnotatedString(text))
                    copied = true
                },
            contentAlignment = Alignment.Center,
        ) {
            DshIcon(
                kind = DshIconKind.CLIPBOARD,
                iconSize = 16.dp,
                tint = tint,
                modifier = Modifier.graphicsLayer {
                    rotationZ = 180f * spin
                    alpha = 1f - spin
                },
            )
            CopyCheckMark(
                color = colors.accent,
                progress = spin,
                modifier = Modifier.graphicsLayer { rotationZ = -180f * (1f - spin) },
            )
        }
    }
}

/** 对勾：按进度描出来，配合旋转一起进场 */
@Composable
private fun CopyCheckMark(
    color: Color,
    progress: Float,
    modifier: Modifier = Modifier,
) {
    if (progress > 0.01f) {
        Canvas(modifier.size(16.dp)) {
            val path = Path().apply {
                moveTo(size.width * 0.20f, size.height * 0.54f)
                lineTo(size.width * 0.41f, size.height * 0.75f)
                lineTo(size.width * 0.80f, size.height * 0.27f)
            }
            drawPath(
                path = path,
                color = color,
                alpha = progress,
                style = Stroke(
                    width = 1.7.dp.toPx(),
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round,
                ),
            )
        }
    }
}

/**
 * 复制反馈的状态机。
 *
 * 复制动作可能发生在别处（比如整行可点的共享链接行），这里只管「复制了 → 转成对勾 → 一秒后复原」
 * 这一段节奏，触发方调用 [markCopied] 即可。
 */
@Stable
class CopyFeedbackState internal constructor() {
    var copied by mutableStateOf(false)
        internal set
}

fun CopyFeedbackState.markCopied() {
    copied = true
}

@Composable
fun rememberCopyFeedback(): CopyFeedbackState {
    val state = remember { CopyFeedbackState() }
    LaunchedEffect(state.copied) {
        if (state.copied) {
            delay(MotionEase.COPY_HOLD_MS)
            state.copied = false
        }
    }
    return state
}

/**
 * 行内复制反馈：图标原地转成对勾，文字淡换成「已复制」，一秒后一起复原。
 *
 * 与 [DshCopyButton] 同一套节奏，区别是不飘浮泡——整行可点的位置上方通常是别的文字，
 * 浮泡会压住内容或被面板裁掉。
 */
@Composable
fun DshInlineCopyFeedback(
    state: CopyFeedbackState,
    modifier: Modifier = Modifier,
    idleLabel: String = "复制",
    tint: Color = Dsh.colors.inkMuted,
) {
    val colors = Dsh.colors
    val type = Dsh.type
    val motionEnabled = rememberMotionEnabled()

    val spin by animateFloatAsState(
        targetValue = if (state.copied) 1f else 0f,
        animationSpec = MotionEase.move(motionEnabled),
        label = "dshInlineCopySpin",
    )
    val labelAlpha by animateFloatAsState(
        targetValue = if (state.copied) 1f else 0f,
        animationSpec = if (state.copied) MotionEase.enter(motionEnabled) else MotionEase.exit(motionEnabled),
        label = "dshInlineCopyLabel",
    )

    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(contentAlignment = Alignment.Center) {
            DshIcon(
                kind = DshIconKind.CLIPBOARD,
                iconSize = 14.dp,
                tint = tint,
                modifier = Modifier.graphicsLayer {
                    rotationZ = 180f * spin
                    alpha = 1f - spin
                },
            )
            CopyCheckMark(
                color = colors.accent,
                progress = spin,
                modifier = Modifier.graphicsLayer { rotationZ = -180f * (1f - spin) },
            )
        }
        Spacer(Modifier.width(6.dp))
        Box {
            Text(
                text = idleLabel,
                style = type.caption,
                color = tint,
                modifier = Modifier.graphicsLayer { alpha = 1f - labelAlpha },
            )
            Text(
                text = "已复制",
                style = type.caption,
                color = colors.accent,
                modifier = Modifier.graphicsLayer {
                    alpha = labelAlpha
                    translationY = (1f - labelAlpha) * 4f
                },
            )
        }
    }
}

/**
 * 长按看全文用的浮层。
 *
 * 列表里正文被截断成两三行，长按就在这里给完整内容，并允许直接复制。
 */
@Composable
fun LongTextDialog(
    title: String,
    body: String,
    onDismiss: () -> Unit,
    subtitle: String? = null,
) {
    val colors = Dsh.colors
    val type = Dsh.type
    val dimens = Dsh.dimens

    DshAnimatedDialog(onDismissRequest = onDismiss) { close ->
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            Box(
                Modifier
                    .fillMaxSize()
                    .dshClickable(scaleOnPress = false, haptic = false) { close() },
            )
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 28.dp)
                    .clip(RoundedCornerShape(dimens.panelRadius))
                    .background(color = colors.canvas)
                    .border(1.dp, colors.hairlineStrong, RoundedCornerShape(dimens.panelRadius))
                    .padding(18.dp),
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(title, style = type.h2, color = colors.ink, modifier = Modifier.weight(1f))
                    Box(
                        Modifier
                            .size(30.dp)
                            .clip(RoundedCornerShape(dimens.iconRadius))
                            .then(
                                if (Dsh.surface.hasPanels) {
                                    Modifier.border(1.dp, colors.hairline, RoundedCornerShape(dimens.iconRadius))
                                } else {
                                    Modifier
                                },
                            )
                            .dshClickable(onClick = close),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("×", style = type.rowTitle, color = colors.inkMuted)
                    }
                }

                if (subtitle != null) {
                    Spacer(Modifier.height(6.dp))
                    Text(subtitle, style = type.caption, color = colors.inkFaint)
                }

                Spacer(Modifier.height(14.dp))
                Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    Text(body, style = type.body, color = colors.ink)
                }

                Spacer(Modifier.height(20.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Spacer(Modifier.weight(1f))
                    DshCopyButton(text = body)
                }
            }
        }
    }
}
