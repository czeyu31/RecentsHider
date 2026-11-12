package com.example.hiderecents.ui.screens

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.hiderecents.ui.components.DshDivider
import com.example.hiderecents.ui.components.DshIcon
import com.example.hiderecents.ui.components.DshIconKind
import com.example.hiderecents.ui.components.DshTextField
import com.example.hiderecents.ui.components.StaggeredEnter
import com.example.hiderecents.ui.components.dshCanvasBackground
import com.example.hiderecents.ui.components.dshClickable
import com.example.hiderecents.ui.components.paperHeaderRule
import com.example.hiderecents.ui.theme.Dsh

/** 控制台一行的类型 */
enum class AdbLineKind { COMMAND, OUTPUT, DIVIDER }

data class AdbOutputLine(val text: String, val kind: AdbLineKind)

/** 常用命令：点一下即执行 */
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
 * 键盘适配（这是重排的原因）：
 * 输入框原来在页面最底部，`imePadding()` 撑起后整个 Column 被顶上去，
 * 输出框直接落到键盘后面，看不见执行结果。
 *
 * 现在：
 * - 输入区紧贴顶栏，键盘弹起时它仍在可见区域
 * - 键盘可见时隐藏「设备」块与「常用命令」列表，把纵向空间全让给输出框
 * - 输出框继续用 weight(1f) 吸收剩余高度，所以键盘态下依然完整可见
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
    val hasPanels = Dsh.surface.hasPanels
    val consoleScroll = rememberScrollState()

    val density = LocalDensity.current
    val imeVisible = WindowInsets.ime.getBottom(density) > 0

    LaunchedEffect(output.size) {
        if (output.isNotEmpty()) consoleScroll.animateScrollTo(consoleScroll.maxValue)
    }

    Box(modifier.fillMaxSize().dshCanvasBackground()) {
        Column(
            Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .imePadding()
                .padding(horizontal = dimens.screenPadding),
        ) {
            Spacer(Modifier.height(4.dp))

            // ---------------- 顶栏 ----------------
            Row(
                Modifier.fillMaxWidth().height(56.dp).paperHeaderRule(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .width(32.dp)
                        .height(32.dp)
                        .clip(RoundedCornerShape(dimens.iconRadius))
                        .dshClickable(onClick = onBack),
                    contentAlignment = Alignment.Center,
                ) {
                    DshIcon(DshIconKind.BACK, iconSize = 15.dp, tint = colors.inkMuted)
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
                        .dshClickable(scaleOnPress = false, onClick = onClear)
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("清空", style = type.caption, color = colors.inkMuted)
                }
            }

            Spacer(Modifier.height(if (hasPanels) dimens.gapM else 22.dp))

            // ---------------- 输入区（置顶，键盘弹起时仍在可见区域） ----------------
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CommandField(
                    command = command,
                    onCommandChange = onCommandChange,
                    onExecute = onExecute,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(12.dp))
                ExecuteButton(running = running, ready = ready, onClick = onExecute)
            }

            Spacer(Modifier.height(dimens.gapM))

            // 键盘可见时让位：设备块与常用命令都收起，把高度全给输出框
            if (!imeVisible) {
                StaggeredEnter(index = 0) {
                    DeviceBlock(model = deviceModel, info = deviceInfo)
                }
                Spacer(Modifier.height(dimens.gapM))
            }

            // ---------------- 输出框（吸收剩余高度） ----------------
            StaggeredEnter(index = 1, modifier = Modifier.weight(1f)) {
                ConsoleBlock(output = output, scroll = consoleScroll)
            }

            if (!imeVisible) {
                Spacer(Modifier.height(dimens.gapM))
                StaggeredEnter(index = 2) {
                    QuickCommands(onQuickCommand = onQuickCommand)
                }
            }

            Spacer(Modifier.height(if (imeVisible) 10.dp else 22.dp))
        }
    }
}

@Composable
private fun DeviceBlock(model: String, info: String) {
    val colors = Dsh.colors
    val type = Dsh.type
    val hasPanels = Dsh.surface.hasPanels
    val dimens = Dsh.dimens
    val shape = RoundedCornerShape(dimens.panelRadius)

    Column(
        Modifier
            .fillMaxWidth()
            .then(
                if (hasPanels) {
                    Modifier
                        .clip(shape)
                        .background(brush = Brush.verticalGradient(listOf(colors.panelTop, colors.panelBottom)))
                        .border(1.dp, colors.hairline, shape)
                        .padding(16.dp)
                } else {
                    Modifier.padding(vertical = 2.dp)
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

@Composable
private fun ConsoleBlock(
    output: List<AdbOutputLine>,
    scroll: ScrollState,
) {
    val colors = Dsh.colors
    val type = Dsh.type
    val hasPanels = Dsh.surface.hasPanels
    val dimens = Dsh.dimens
    val shape = RoundedCornerShape(if (hasPanels) dimens.panelRadius else 0.dp)

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("输出", style = type.label, color = colors.inkFaint)
            Spacer(Modifier.weight(1f))
            Text("uid 2000", style = type.monoSmall, color = colors.inkFaint)
        }
        Spacer(Modifier.height(8.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(shape)
                .then(
                    if (hasPanels) {
                        Modifier.background(brush = Brush.verticalGradient(listOf(colors.panelTop, colors.panelBottom)))
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
                            AdbLineKind.COMMAND -> Text(line.text, style = type.monoSmall, color = colors.accent)
                            AdbLineKind.OUTPUT -> Text(line.text, style = type.monoSmall, color = colors.inkMuted)
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
}

@Composable
private fun QuickCommands(onQuickCommand: (String) -> Unit) {
    val colors = Dsh.colors
    val type = Dsh.type
    val hasPanels = Dsh.surface.hasPanels
    val dimens = Dsh.dimens
    val shape = RoundedCornerShape(dimens.panelRadius)

    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("常用命令", style = type.label, color = colors.inkFaint)
            Spacer(Modifier.weight(1f))
            Text("点按执行", style = type.monoSmall, color = colors.inkFaint)
        }
        Spacer(Modifier.height(8.dp))
        Column(
            Modifier
                .fillMaxWidth()
                .then(
                    if (hasPanels) {
                        Modifier
                            .clip(shape)
                            .background(brush = Brush.verticalGradient(listOf(colors.panelTop, colors.panelBottom)))
                            .border(1.dp, colors.hairline, shape)
                    } else {
                        Modifier
                    },
                ),
        ) {
            AdbQuickCommands.forEachIndexed { index, cmd ->
                if (index > 0) {
                    DshDivider(Modifier.padding(horizontal = if (hasPanels) 14.dp else 0.dp))
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .dshClickable(scaleOnPress = false) { onQuickCommand(cmd) }
                        .padding(
                            horizontal = if (hasPanels) 14.dp else 0.dp,
                            vertical = 11.dp,
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = cmd,
                        style = type.monoSmall,
                        color = colors.inkMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text("运行", style = type.caption, color = colors.accent)
                }
            }
        }
    }
}

@Composable
private fun CommandField(
    command: String,
    onCommandChange: (String) -> Unit,
    onExecute: () -> Unit,
    modifier: Modifier = Modifier,
) {
    DshTextField(
        value = command,
        onValueChange = onCommandChange,
        label = "输入命令",
        modifier = modifier,
    )
}

@Composable
private fun ExecuteButton(running: Boolean, ready: Boolean, onClick: () -> Unit) {
    val colors = Dsh.colors
    val type = Dsh.type
    val hasPanels = Dsh.surface.hasPanels
    val dimens = Dsh.dimens
    val enabled = ready && !running
    val label = if (running) "执行中" else "执行"

    if (hasPanels) {
        val shape = RoundedCornerShape(dimens.controlRadius)
        Box(
            Modifier
                .height(46.dp)
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
