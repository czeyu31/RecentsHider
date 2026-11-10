package com.example.hiderecents.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
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
    onThemeChange: (AppTheme) -> Unit,
    onToggleKeepAlive: (Boolean) -> Unit,
    onToggleNotification: (Boolean) -> Unit,
    onToggleExclude: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = Dsh.colors
    val type = Dsh.type

    Dialog(onDismissRequest = onDismiss) {
        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 620.dp)
                .clip(RoundedCornerShape(Dsh.dimens.panelRadius))
                .background(color = colors.canvas)
                .border(1.dp, colors.hairlineStrong, RoundedCornerShape(Dsh.dimens.panelRadius))
                .padding(horizontal = 20.dp, vertical = 20.dp),
        ) {
            Column {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("设置", style = type.h2, color = colors.ink)
                    Spacer(Modifier.weight(1f))
                    Box(
                        Modifier
                            .size(30.dp)
                            .clip(RoundedCornerShape(Dsh.dimens.iconRadius))
                            .border(1.dp, colors.hairline, RoundedCornerShape(Dsh.dimens.iconRadius))
                            .dshClickable(onClick = onDismiss),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("×", style = type.rowTitle, color = colors.inkMuted)
                    }
                }

                Spacer(Modifier.height(20.dp))
                SectionHeader("外观")
                Spacer(Modifier.height(10.dp))

                ThemeOption(
                    label = "跟随系统",
                    desc = "浅色用纸本，深色用石墨",
                    selected = theme == AppTheme.SYSTEM,
                    onClick = { onThemeChange(AppTheme.SYSTEM) },
                )
                ThemeOption(
                    label = "纸本",
                    desc = "暖米白纸面，墨黑正文，朱红强调",
                    selected = theme == AppTheme.PAPER,
                    onClick = { onThemeChange(AppTheme.PAPER) },
                )
                ThemeOption(
                    label = "石墨 · 雾玉",
                    desc = "冷石墨软面板，雾玉绿强调",
                    selected = theme == AppTheme.GRAPHITE,
                    onClick = { onThemeChange(AppTheme.GRAPHITE) },
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

                Spacer(Modifier.height(20.dp))
                DshDivider()
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("System Tool · v30.0", style = type.monoSmall, color = colors.inkFaint)
                    Spacer(Modifier.weight(1f))
                    Text("已隐藏 $hiddenCount 个应用", style = type.monoSmall, color = colors.inkFaint)
                }
            }
        }
    }
}

@Composable
private fun ThemeOption(
    label: String,
    desc: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = Dsh.colors
    val type = Dsh.type
    val shape = RoundedCornerShape(Dsh.dimens.controlRadius)

    Box(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .clip(shape)
            .background(color = if (selected) colors.accentDim else Color.Transparent)
            .border(1.dp, if (selected) colors.accentBorder else colors.hairline, shape)
            .dshClickable(scaleOnPress = false, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(14.dp)
                    .clip(RoundedCornerShape(percent = 50))
                    .border(1.dp, if (selected) colors.accent else colors.hairlineStrong, RoundedCornerShape(percent = 50)),
                contentAlignment = Alignment.Center,
            ) {
                if (selected) {
                    Box(
                        Modifier
                            .size(7.dp)
                            .clip(RoundedCornerShape(percent = 50))
                            .background(color = colors.accent),
                    )
                }
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
    onOpenBatterySettings: () -> Unit,
    onOpenAutoStartSettings: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = Dsh.colors
    val type = Dsh.type

    Dialog(onDismissRequest = onDismiss) {
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
                    DshIcon(DshIconKind.SHIELD, iconSize = 16.dp, tint = colors.accent)
                    Spacer(Modifier.width(9.dp))
                    Text("后台保活设置引导", style = type.h2, color = colors.ink)
                }
                Spacer(Modifier.height(16.dp))
                Text(
                    "后台保活已开启。还需要在系统里完成两步，应用才不会被杀掉：",
                    style = type.body,
                    color = colors.inkMuted,
                )
                Spacer(Modifier.height(14.dp))
                GuideLine("01", "关闭电池优化，选择「不限制」")
                Spacer(Modifier.height(10.dp))
                GuideLine("02", "允许自启动，并在最近任务里锁定本应用")
                Spacer(Modifier.height(22.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.weight(1f)) {
                        DshGhostButton("电池优化", onClick = onOpenBatterySettings, modifier = Modifier.fillMaxWidth())
                    }
                    Box(Modifier.weight(1f)) {
                        DshGhostButton("自启动", onClick = onOpenAutoStartSettings, modifier = Modifier.fillMaxWidth())
                    }
                }
                Spacer(Modifier.height(10.dp))
                Box(Modifier.fillMaxWidth()) {
                    DshGhostButton("我知道了", onClick = onDismiss, modifier = Modifier.fillMaxWidth())
                }
            }
        }
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
