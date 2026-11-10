package com.example.hiderecents.ui.screens

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.hiderecents.ui.components.DshDivider
import com.example.hiderecents.ui.components.DshIcon
import com.example.hiderecents.ui.components.DshIconKind
import com.example.hiderecents.ui.components.StaggeredEnter
import com.example.hiderecents.ui.components.dshCanvasBackground
import com.example.hiderecents.ui.components.dshClickable
import com.example.hiderecents.ui.theme.Dsh

/** 控制台一行的类型 */
enum class AdbLineKind { COMMAND, OUTPUT, DIVIDER }

data class AdbOutputLine(val text: String, val kind: AdbLineKind)

/** 快捷命令：原实现的三条 + 两条常用，点一下即执行 */
val AdbQuickCommands = listOf(
    "pm list packages",
    "pm list packages -3",
    "getprop ro.product.model",
    "dumpsys battery",
    "settings get secure enabled_accessibility_services",
)

/**
 * ADB 命令页。
 *
 * 功能与原 AdbActivity 一致：设备信息、快捷命令、命令输入与执行、控制台输出、清空。
 * 所有命令通过 Shizuku 的 execCommand 通道以 shell 身份执行。
 */
@Composable
fun AdbScreen(
    deviceModel: String,
    deviceInfo: String,
    command: String,
    onCommandChange: (String) -> Unit,
    output: List<AdbOutputLine>,
    running: Boolean,
    ready: Boolean,
    onBack: () -> Unit,
    onClear: () -> Unit,
    onExecute: () -> Unit,
    onQuickCommand: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Dsh.colors
    val dimens = Dsh.dimens
    val type = Dsh.type
    val consoleScroll = rememberScrollState()

    LaunchedEffect(output.size) {
        if (output.isNotEmpty()) consoleScroll.animateScrollTo(consoleScroll.maxValue)
    }

    Box(modifier.fillMaxSize().dshCanvasBackground()) {
        Column(
            Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .imePadding()
                .padding(horizontal = dimens.screenPadding),
        ) {
            Spacer(Modifier.height(4.dp))

            // ---------------- 顶栏 ----------------
            Row(
                Modifier.fillMaxWidth().height(56.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(32.dp)
                        .clip(RoundedCornerShape(dimens.iconRadius))
                        .dshClickable(onClick = onBack),
                    contentAlignment = Alignment.Center,
                ) {
                    DshIcon(DshIconKind.ARROW_LEFT, iconSize = 15.dp, tint = colors.inkMuted)
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("ADB 命令", style = type.h2, color = colors.ink)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = if (ready) "以 shell 身份执行 · uid 2000" else "Shizuku 未就绪，命令无法执行",
                        style = type.label,
                        color = if (ready) colors.inkFaint else colors.accent,
                    )
                }
                Box(
                    Modifier
                        .clip(RoundedCornerShape(dimens.iconRadius))
                        .dshClickable(onClick = onClear)
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("清空", style = type.caption, color = colors.inkMuted)
                }
            }

            Spacer(Modifier.height(dimens.gapS))

            // ---------------- 设备信息 ----------------
            StaggeredEnter(index = 0) {
                DeviceBlock(model = deviceModel, info = deviceInfo)
            }

            Spacer(Modifier.height(dimens.gapM))

            // ---------------- 快捷命令 ----------------
            StaggeredEnter(index = 1) {
                QuickCommands(onQuickCommand = onQuickCommand)
            }

            Spacer(Modifier.height(dimens.gapM))

            // ---------------- 控制台 ----------------
            Console(output = output, scroll = consoleScroll, modifier = Modifier.weight(1f))

            Spacer(Modifier.height(12.dp))

            // ---------------- 输入 + 执行 ----------------
            Row(
                Modifier.fillMaxWidth().padding(bottom = 22.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CommandField(
                    command = command,
                    onCommandChange = onCommandChange,
                    onExecute = onExecute,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(10.dp))
                ExecuteButton(running = running, ready = ready, onClick = onExecute)
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 设备信息
// ---------------------------------------------------------------------------

@Composable
private fun DeviceBlock(model: String, info: String) {
    val colors = Dsh.colors
    val type = Dsh.type
    val surface = Dsh.surface
    val dimens = Dsh.dimens
    val shape = RoundedCornerShape(dimens.panelRadius)

    Column(
        Modifier
            .fillMaxWidth()
            .then(
                if (surface.hasPanels) {
                    Modifier
                        .clip(shape)
                        .background(
                            brush = Brush.verticalGradient(listOf(colors.panelTop, colors.panelBottom)),
                        )
                        .border(1.dp, colors.hairline, shape)
                        .padding(16.dp)
                } else {
                    Modifier.padding(vertical = 14.dp)
                },
            ),
    ) {
        Text("设备", style = type.label, color = colors.inkFaint)
        Spacer(Modifier.height(10.dp))
        Text(
            text = model.ifEmpty { "读取中…" },
            style = type.title,
            color = colors.ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = info.ifEmpty { "通过 Shizuku 读取 getprop" },
            style = type.caption,
            color = colors.inkFaint,
        )
    }
}

// ---------------------------------------------------------------------------
// 快捷命令
// ---------------------------------------------------------------------------

@Composable
private fun QuickCommands(onQuickCommand: (String) -> Unit) {
    val colors = Dsh.colors
    val type = Dsh.type
    val dimens = Dsh.dimens
    val shape = RoundedCornerShape(dimens.controlRadius)

    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        AdbQuickCommands.forEach { cmd ->
            Box(
                Modifier
                    .clip(shape)
                    .background(color = colors.panelTop.copy(alpha = 0.5f))
                    .border(1.dp, colors.hairline, shape)
                    .dshClickable(scaleOnPress = false) { onQuickCommand(cmd) }
                    .padding(horizontal = 12.dp, vertical = 9.dp),
            ) {
                Text(
                    text = cmd,
                    style = type.monoSmall,
                    color = colors.inkMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 控制台
// ---------------------------------------------------------------------------

@Composable
private fun Console(
    output: List<AdbOutputLine>,
    scroll: ScrollState,
    modifier: Modifier = Modifier,
) {
    val colors = Dsh.colors
    val type = Dsh.type
    val surface = Dsh.surface
    val dimens = Dsh.dimens
    val shape = RoundedCornerShape(if (surface.hasPanels) dimens.panelRadius else 0.dp)

    Box(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .then(
                if (surface.hasPanels) {
                    Modifier.background(
                        brush = Brush.verticalGradient(listOf(colors.panelTop, colors.panelBottom)),
                    )
                } else {
                    Modifier
                },
            )
            .border(1.dp, colors.hairline, shape)
            .padding(14.dp),
    ) {
        Column(Modifier.fillMaxSize().verticalScroll(scroll)) {
            if (output.isEmpty()) {
                Text("等待执行命令…", style = type.monoSmall, color = colors.inkFaint)
            } else {
                output.forEach { line ->
                    when (line.kind) {
                        AdbLineKind.COMMAND -> Text(
                            text = line.text,
                            style = type.monoSmall,
                            color = colors.accent,
                        )

                        AdbLineKind.OUTPUT -> Text(
                            text = line.text,
                            style = type.monoSmall,
                            color = colors.inkMuted,
                        )

                        AdbLineKind.DIVIDER -> {
                            Spacer(Modifier.height(8.dp))
                            DshDivider()
                            Spacer(Modifier.height(8.dp))
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 命令输入
// ---------------------------------------------------------------------------

@Composable
private fun CommandField(
    command: String,
    onCommandChange: (String) -> Unit,
    onExecute: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Dsh.colors
    val type = Dsh.type
    val surface = Dsh.surface
    val dimens = Dsh.dimens
    val shape = RoundedCornerShape(dimens.controlRadius)

    Box(
        modifier
            .height(48.dp)
            .clip(shape)
            .background(color = if (surface.hasPanels) colors.panelTop.copy(alpha = 0.55f) else colors.panelTop)
            .border(1.dp, colors.hairline, shape)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        BasicTextField(
            value = command,
            onValueChange = onCommandChange,
            singleLine = true,
            textStyle = type.mono.copy(color = colors.ink),
            cursorBrush = SolidColor(colors.accent),
            modifier = Modifier.fillMaxWidth(),
            decorationBox = { innerTextField ->
                Box {
                    if (command.isEmpty()) {
                        Text("输入命令…", style = type.mono, color = colors.inkFaint)
                    }
                    innerTextField()
                }
            },
        )
    }
}

@Composable
private fun ExecuteButton(running: Boolean, ready: Boolean, onClick: () -> Unit) {
    val colors = Dsh.colors
    val type = Dsh.type
    val surface = Dsh.surface
    val dimens = Dsh.dimens
    val enabled = ready && !running
    val label = if (running) "执行中" else "执行"

    if (surface.hasPanels) {
        val shape = RoundedCornerShape(dimens.controlRadius)
        Box(
            Modifier
                .height(48.dp)
                .clip(shape)
                .background(color = if (enabled) colors.accent else colors.inkFaint.copy(alpha = 0.25f))
                .dshClickable(enabled = enabled, onClick = onClick)
                .padding(horizontal = 20.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(label, style = type.rowTitle, color = colors.accentInk)
        }
    } else {
        Column(
            Modifier
                .dshClickable(enabled = enabled, onClick = onClick)
                .padding(horizontal = 6.dp, vertical = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = label,
                style = type.rowTitle,
                color = if (enabled) colors.accent else colors.inkFaint,
            )
            Spacer(Modifier.height(3.dp))
            Box(
                Modifier
                    .width(28.dp)
                    .height(1.5.dp)
                    .background(color = if (enabled) colors.accent else colors.inkFaint),
            )
        }
    }
}
