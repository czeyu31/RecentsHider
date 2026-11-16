package com.example.hiderecents.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.hiderecents.BuildConfig
import com.example.hiderecents.ui.theme.MotionEase
import com.example.hiderecents.ui.theme.rememberMotionEnabled
import kotlin.math.roundToInt
import com.example.hiderecents.ui.components.DshAnimatedDialog
import com.example.hiderecents.ui.components.DshDivider
import com.example.hiderecents.ui.components.DshGhostButton
import com.example.hiderecents.ui.components.DshIcon
import com.example.hiderecents.ui.components.DshIconKind
import com.example.hiderecents.ui.components.DshPressRow
import com.example.hiderecents.ui.components.DshSwitch
import com.example.hiderecents.ui.components.SectionHeader
import com.example.hiderecents.ui.components.dshClickable
import com.example.hiderecents.ui.theme.AppTheme
import com.example.hiderecents.ui.theme.Dsh

@Composable
fun SettingsDialog(
    theme: AppTheme,
    keepAlive: Boolean,
    statusNotification: Boolean,
    excludeFromRecents: Boolean,
    hiddenCount: Int,
    hideGuard: Boolean,
    onThemeChange: (AppTheme) -> Unit,
    onToggleKeepAlive: (Boolean) -> Unit,
    onToggleNotification: (Boolean) -> Unit,
    onToggleExclude: (Boolean) -> Unit,
    onHideGuardChange: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = Dsh.colors
    val type = Dsh.type

    DshAnimatedDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(),
    ) { close ->
        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 720.dp)
                .clip(RoundedCornerShape(Dsh.dimens.panelRadius))
                .background(color = colors.canvas)
                .border(1.dp, colors.hairlineStrong, RoundedCornerShape(Dsh.dimens.panelRadius))
                .padding(horizontal = 20.dp, vertical = 20.dp),
        ) {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("设置", style = type.h2, color = colors.ink)
                    Spacer(Modifier.weight(1f))
                    Box(
                        Modifier
                            .size(30.dp)
                            .clip(RoundedCornerShape(Dsh.dimens.iconRadius))
                            .then(
                                if (Dsh.surface.hasPanels) {
                                    Modifier.border(1.dp, colors.hairline, RoundedCornerShape(Dsh.dimens.iconRadius))
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

                Spacer(Modifier.height(20.dp))
                SectionHeader("外观")
                Spacer(Modifier.height(10.dp))

                OptionGroup(
                    options = listOf(
                        OptionSpec("跟随系统", "浅色用纸本，深色用石墨"),
                        OptionSpec("纸本", "暖米白纸面，墨黑正文，朱红强调"),
                        OptionSpec("石墨 · 雾玉", "冷石墨软面板，雾玉绿强调"),
                    ),
                    selectedIndex = when (theme) {
                        AppTheme.SYSTEM -> 0
                        AppTheme.PAPER -> 1
                        AppTheme.GRAPHITE -> 2
                    },
                    onSelect = { index ->
                        onThemeChange(
                            when (index) {
                                0 -> AppTheme.SYSTEM
                                1 -> AppTheme.PAPER
                                else -> AppTheme.GRAPHITE
                            },
                        )
                    },
                )

                Spacer(Modifier.height(22.dp))
                SectionHeader("行为")
                Spacer(Modifier.height(6.dp))

                SettingSwitchRow(
                    title = "隐藏后台卡片",
                    desc = "从最近任务列表中移除本应用",
                    checked = excludeFromRecents,
                    onCheckedChange = onToggleExclude,
                )
                DshDivider()
                SettingSwitchRow(
                    title = "状态通知",
                    desc = "常驻通知显示隐藏数量与保活状态",
                    checked = statusNotification,
                    onCheckedChange = onToggleNotification,
                )
                DshDivider()
                SettingSwitchRow(
                    title = "后台保活",
                    desc = "通过 Shizuku 设置后台运行与电池白名单",
                    checked = keepAlive,
                    onCheckedChange = onToggleKeepAlive,
                )

                Spacer(Modifier.height(22.dp))
                SectionHeader("隐藏策略")
                Spacer(Modifier.height(10.dp))

                OptionGroup(
                    options = listOf(
                        OptionSpec("持续守护", "重新打开被隐藏的应用，也会在数秒内从最近任务消失"),
                        OptionSpec("仅一次生效", "只在打开开关那一刻隐藏当前任务，之后不再干预"),
                    ),
                    selectedIndex = if (hideGuard) 0 else 1,
                    onSelect = { index -> onHideGuardChange(index == 0) },
                )

                Spacer(Modifier.height(20.dp))
                DshDivider()
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("System Tool · v" + BuildConfig.VERSION_NAME, style = type.monoSmall, color = colors.inkFaint)
                    Spacer(Modifier.weight(1f))
                    Text("已隐藏 $hiddenCount 个应用", style = type.monoSmall, color = colors.inkFaint)
                }
            }
        }
    }
}

private data class OptionSpec(val label: String, val desc: String)

/**
 * 一组单选。
 *
 * 选中态的两处高光都不再瞬切：纸本左侧那条红线由一条共享指示条平移并伸缩过去，
 * 石墨则是行底色与描边淡入。单选圆点本身也带缩放，选中/取消都看得见过程。
 */
@Composable
private fun OptionGroup(
    options: List<OptionSpec>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
) {
    val colors = Dsh.colors
    val hasPanels = Dsh.surface.hasPanels
    val motionEnabled = rememberMotionEnabled()
    val density = LocalDensity.current

    val tops = remember { mutableStateListOf<Float>() }
    val heights = remember { mutableStateListOf<Float>() }

    val targetTop = tops.getOrElse(selectedIndex) { 0f }
    val targetHeight = heights.getOrElse(selectedIndex) { 0f }
    val lineTop by animateFloatAsState(targetTop, MotionEase.move(motionEnabled), label = "optionLineTop")
    val lineHeight by animateFloatAsState(targetHeight, MotionEase.move(motionEnabled), label = "optionLineHeight")

    Box(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth()) {
            options.forEachIndexed { index, option ->
                Box(
                    Modifier
                        .fillMaxWidth()
                        .onGloballyPositioned { coords ->
                            val y = coords.positionInParent().y
                            val h = coords.size.height.toFloat()
                            if (tops.size > index) {
                                if (tops[index] != y) tops[index] = y
                                if (heights[index] != h) heights[index] = h
                            } else {
                                tops.add(y)
                                heights.add(h)
                            }
                        },
                ) {
                    OptionRow(
                        label = option.label,
                        desc = option.desc,
                        selected = index == selectedIndex,
                        onClick = { onSelect(index) },
                    )
                }
            }
        }

        if (!hasPanels && lineHeight > 0.5f) {
            Box(
                Modifier
                    .offset { IntOffset(0, lineTop.roundToInt()) }
                    .width(2.dp)
                    .height(with(density) { lineHeight.toDp() })
                    .background(color = colors.accent),
            )
        }
    }
}

@Composable
private fun OptionRow(
    label: String,
    desc: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = Dsh.colors
    val type = Dsh.type
    val motionEnabled = rememberMotionEnabled()
    val shape = RoundedCornerShape(Dsh.dimens.controlRadius)

    val hasPanels = Dsh.surface.hasPanels

    val fill by animateColorAsState(
        targetValue = if (selected && hasPanels) colors.accentDim else Color.Transparent,
        animationSpec = MotionEase.move(motionEnabled),
        label = "optionFill",
    )
    val borderColor by animateColorAsState(
        targetValue = if (selected && hasPanels) colors.accentBorder else colors.hairline,
        animationSpec = MotionEase.move(motionEnabled),
        label = "optionBorder",
    )
    val dotScale by animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = MotionEase.move(motionEnabled),
        label = "optionDotScale",
    )
    val ringColor by animateColorAsState(
        targetValue = if (selected) colors.accent else colors.hairlineStrong,
        animationSpec = MotionEase.move(motionEnabled),
        label = "optionRing",
    )

    Box(
        Modifier
            .fillMaxWidth()
            .padding(vertical = if (hasPanels) 3.dp else 0.dp)
            .then(
                if (hasPanels) {
                    Modifier
                        .clip(shape)
                        .background(color = fill)
                        .border(1.dp, borderColor, shape)
                } else {
                    Modifier.drawBehind {
                        drawLine(
                            color = colors.hairline,
                            start = Offset(0f, size.height),
                            end = Offset(size.width, size.height),
                            strokeWidth = 1.dp.toPx(),
                        )
                    }
                },
            )
            .dshClickable(scaleOnPress = false, onClick = onClick)
            .padding(horizontal = if (hasPanels) 14.dp else 12.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(14.dp)
                    .clip(RoundedCornerShape(percent = 50))
                    .border(1.dp, ringColor, RoundedCornerShape(percent = 50)),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .size(7.dp)
                        .graphicsLayer {
                            scaleX = dotScale
                            scaleY = dotScale
                        }
                        .clip(RoundedCornerShape(percent = 50))
                        .background(color = colors.accent),
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(label, style = type.rowTitle, color = colors.ink)
                Spacer(Modifier.height(4.dp))
                Text(desc, style = type.caption, color = colors.inkFaint)
            }
        }
    }
}

@Composable
private fun SettingSwitchRow(
    title: String,
    desc: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val colors = Dsh.colors
    val type = Dsh.type
    DshPressRow(onClick = { onCheckedChange(!checked) }) {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = type.rowTitle, color = colors.ink)
                Spacer(Modifier.height(5.dp))
                Text(desc, style = type.caption, color = colors.inkFaint)
            }
            Spacer(Modifier.width(14.dp))
            DshSwitch(checked = checked, onCheckedChange = onCheckedChange)
        }
    }
}

@Composable
fun KeepAliveGuideDialog(
    batteryOk: Boolean,
    autoStartOk: Boolean,
    autoStartUnknown: Boolean,
    manualAutoStart: Boolean,
    powerPolicyNeeded: Boolean,
    powerPolicyOk: Boolean,
    onOpenBatterySettings: () -> Unit,
    onOpenAutoStartSettings: () -> Unit,
    onManualAutoStartChange: (Boolean) -> Unit,
    onOpenPowerPolicySettings: () -> Unit,
    onManualPowerPolicyChange: (Boolean) -> Unit,
    onRecheck: () -> Unit,
    onFinish: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = Dsh.colors
    val type = Dsh.type
    val allOk = batteryOk && autoStartOk && (!powerPolicyNeeded || powerPolicyOk)

    DshAnimatedDialog(onDismissRequest = onDismiss, properties = DialogProperties()) { close ->
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(Dsh.dimens.panelRadius))
                .background(color = colors.canvas)
                .border(1.dp, colors.hairlineStrong, RoundedCornerShape(Dsh.dimens.panelRadius))
                .padding(20.dp),
        ) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("后台保活设置", style = type.h2, color = colors.ink)
                    Spacer(Modifier.weight(1f))
                    Box(
                        Modifier
                            .size(28.dp)
                            .clip(RoundedCornerShape(percent = 50))
                            .dshClickable(scaleOnPress = false, onClick = close),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("×", style = type.rowTitle, color = colors.inkMuted)
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    text = if (allOk) "设置都已就绪，点「完成」即可开启保活" else "请先把下面几项都设置好，保活开关才会真正打开",
                    style = type.caption,
                    color = colors.inkFaint,
                )
                Spacer(Modifier.height(18.dp))

                KeepAliveGuideRow(
                    title = "电池优化",
                    desc = "关闭对本应用的电池优化",
                    ok = batteryOk,
                    onOpen = onOpenBatterySettings,
                )
                Spacer(Modifier.height(12.dp))
                KeepAliveGuideRow(
                    title = "自启动",
                    desc = "允许本应用后台自启动",
                    ok = autoStartOk,
                    onOpen = onOpenAutoStartSettings,
                )

                if (powerPolicyNeeded) {
                    Spacer(Modifier.height(12.dp))
                    KeepAliveGuideRow(
                        title = "省电策略",
                        desc = "系统把它设成了「智能限制后台运行」，会连同后台联网一起掐掉",
                        ok = powerPolicyOk,
                        onOpen = onOpenPowerPolicySettings,
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("我已把省电策略设为「无限制」", style = type.rowTitle, color = colors.ink)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "应用信息 → 省电策略 → 无限制；设完请重启一次本应用",
                                style = type.caption,
                                color = colors.inkFaint,
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        DshSwitch(checked = powerPolicyOk, onCheckedChange = onManualPowerPolicyChange)
                    }
                }

                if (autoStartUnknown) {
                    Spacer(Modifier.height(12.dp))
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("我已手动允许自启动", style = type.rowTitle, color = colors.ink)
                            Spacer(Modifier.height(4.dp))
                            Text("本机读不到自启动状态，请自行确认后打开", style = type.caption, color = colors.inkFaint)
                        }
                        Spacer(Modifier.width(12.dp))
                        DshSwitch(checked = manualAutoStart, onCheckedChange = onManualAutoStartChange)
                    }
                }

                Spacer(Modifier.height(20.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.weight(1f)) {
                        DshGhostButton(
                            text = "重新检测",
                            onClick = onRecheck,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Box(Modifier.weight(1f)) {
                        DshGhostButton(
                            text = if (allOk) "完成" else "稍后再说",
                            onClick = { if (allOk) onFinish() else onDismiss() },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun KeepAliveGuideRow(
    title: String,
    desc: String,
    ok: Boolean,
    onOpen: () -> Unit,
) {
    val colors = Dsh.colors
    val type = Dsh.type
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = type.rowTitle, color = colors.ink)
            Spacer(Modifier.height(4.dp))
            Text(
                text = if (ok) "已设置" else desc,
                style = type.caption,
                color = if (ok) colors.accent else colors.inkFaint,
            )
        }
        Spacer(Modifier.width(12.dp))
        DshGhostButton(text = if (ok) "已开启" else "去设置", onClick = onOpen)
    }
}

@Composable
private fun GuideLine(index: String, text: String) {
    val colors = Dsh.colors
    val type = Dsh.type
    Row(verticalAlignment = Alignment.Top) {
        Text(index, style = type.monoSmall, color = colors.accent)
        Spacer(Modifier.width(10.dp))
        Text(text, style = type.body, color = colors.ink, modifier = Modifier.weight(1f))
    }
}