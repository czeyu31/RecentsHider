package com.example.hiderecents.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.hiderecents.ui.theme.MotionEase
import com.example.hiderecents.ui.theme.rememberMotionEnabled
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 统一弹窗接入场。
 *
 * 出现：ease-out 250ms，从下方 24dp 淡入；
 * 关闭：ease-in 150ms，比出现更快地反向收走。
 *
 * 注意：**关闭一定要用这里给出来的 close**。Compose 的 Dialog 一旦被父级从组合里摘掉就立刻消失，
 * 所以 close 先播完退场、再回调 onDismissRequest，父级的 `if (show)` 才会真的把它撤掉。
 */
@Composable
fun DshAnimatedDialog(
    onDismissRequest: () -> Unit,
    properties: DialogProperties = DialogProperties(usePlatformDefaultWidth = false),
    content: @Composable (close: () -> Unit) -> Unit,
) {
    val motionEnabled = rememberMotionEnabled()
    val scope = rememberCoroutineScope()

    var entered by remember { mutableStateOf(false) }
    var closing by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { entered = true }

    val close: () -> Unit = {
        if (!closing) {
            closing = true
            scope.launch {
                if (motionEnabled) delay(MotionEase.DUR_EXIT.toLong())
                onDismissRequest()
            }
        }
    }

    val shown = entered && !closing
    val progress by animateFloatAsState(
        targetValue = if (shown) 1f else 0f,
        animationSpec = if (shown) MotionEase.enter(motionEnabled) else MotionEase.exit(motionEnabled),
        label = "dshDialogProgress",
    )

    Dialog(onDismissRequest = close, properties = properties) {
        // 不能加 fillMaxSize：居中弹窗的窗口尺寸由内容决定，撑满后既会顶到屏幕上方，
        // 也会让「点外围空白」落进窗口内部，系统的 dismissOnClickOutside 就失效了。
        // 底部抽屉的内容自己就是 fillMaxSize，不需要这里代劳。
        Box(
            Modifier.graphicsLayer {
                alpha = progress
                translationY = (1f - progress) * 24.dp.toPx()
            },
        ) {
            content(close)
        }
    }
}