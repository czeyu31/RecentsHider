package com.example.hiderecents.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.hiderecents.ui.components.paperHeaderRule
import com.example.hiderecents.ui.components.AppIconTile
import com.example.hiderecents.ui.components.BreathingDot
import com.example.hiderecents.ui.components.DshDivider
import com.example.hiderecents.ui.components.DshGhostButton
import com.example.hiderecents.ui.components.DshIcon
import com.example.hiderecents.ui.components.DshIconButton
import com.example.hiderecents.ui.components.DottedLeader
import com.example.hiderecents.ui.components.DshIconKind
import com.example.hiderecents.ui.components.DshPanel
import com.example.hiderecents.ui.components.DshPressRow
import com.example.hiderecents.ui.components.MiniToggle
import com.example.hiderecents.ui.components.SectionHeader
import com.example.hiderecents.ui.components.StaggeredEnter
import com.example.hiderecents.ui.components.dshCanvasBackground
import com.example.hiderecents.ui.components.dshClickable
import com.example.hiderecents.ui.theme.Dsh

/** 已隐藏应用的预览项（首字 + 配色种子） */
data class HiddenPreview(val label: String, val seed: Int)

data class HomeUiState(
    val statusText: String = "未运行",
    val statusDetail: String = "",
    val connected: Boolean = false,
    val hiddenCount: Int = 0,
    val protectedCount: Int = 0,
    val keepAlive: Boolean = false,
    val statusNotification: Boolean = false,
    val excludeFromRecents: Boolean = false,
    val hiddenPreview: List<HiddenPreview> = emptyList(),
    /** 运行时长：数值与单位分开，方便大号数字排版 */
    val uptimeValue: String = "—",
    val uptimeUnit: String = "",
    /** 电池温度（摄氏度，一位小数）；读不到时是「—」 */
    val batteryTemp: String = "—",
)

@Composable
fun HomeScreen(
    state: HomeUiState,
    onOpenToolbox: () -> Unit,
    onOpenAccessibility: () -> Unit,
    onOpenTemperature: () -> Unit,
    onOpenSettings: () -> Unit,
    onRetryShizuku: () -> Unit,
    onToggleKeepAlive: (Boolean) -> Unit,
    onToggleNotification: (Boolean) -> Unit,
    onToggleExclude: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Dsh.colors
    val dimens = Dsh.dimens
    val type = Dsh.type
    val surface = Dsh.surface

    Box(modifier.fillMaxSize().dshCanvasBackground()) {
        Column(
            Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(horizontal = dimens.screenPadding),
        ) {
            Spacer(Modifier.height(4.dp))

            // ---------------- 顶栏 ----------------
            Row(
                Modifier.fillMaxWidth().height(56.dp).paperHeaderRule(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (surface.hasPanels) {
                    Box(
                        Modifier
                            .size(26.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(brush = Brush.verticalGradient(listOf(colors.panelTop, colors.panelBottom)))
                            .border(1.dp, colors.hairlineStrong, RoundedCornerShape(8.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            Modifier
                                .size(12.dp)
                                .clip(RoundedCornerShape(3.dp))
                                .background(color = colors.accent.copy(alpha = 0.85f)),
                        )
                    }
                    Spacer(Modifier.width(11.dp))
                    Text("System Tool", style = type.title, color = colors.ink)

                } else {
                    Box(Modifier.size(8.dp).background(color = colors.accent))
                    Spacer(Modifier.width(10.dp))
                    Text("SYSTEM TOOL", style = type.label, color = colors.ink)
                }
                Spacer(Modifier.weight(1f))
                if (surface.hasPanels) {
                    DshIconButton(DshIconKind.SLIDERS, onClick = onOpenSettings)
                } else {
                    Box(
                        Modifier
                            .dshClickable(scaleOnPress = false, onClick = onOpenSettings)
                            .padding(horizontal = 6.dp, vertical = 4.dp),
                    ) {
                        Text("设置", style = type.caption, color = colors.inkMuted)
                    }
                }
            }

            Spacer(Modifier.height(if (surface.hasPanels) 18.dp else 26.dp))

            // ---------------- 状态块 ----------------
            StaggeredEnter(index = 0) {
                Column {
                    Text(
                        text = if (surface.hasPanels) "Shizuku 服务" else "SHIZUKU 服务",
                        style = type.label,
                        color = colors.inkFaint,
                    )
                    Spacer(Modifier.height(14.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // 未连接时也要看得出这是状态标记：9dp + inkMuted，
                        // 8dp + inkFaint 在米白纸上几乎不可见。
                        BreathingDot(
                            color = if (state.connected) colors.accent else colors.inkMuted,
                            dotSize = 9.dp,
                        )
                        Spacer(Modifier.width(if (surface.hasPanels) 14.dp else 10.dp))
                        // 参考图里「连接」是强调色：首字用主墨色，其余用强调色，
                        // 单 Text + SpanStyle，基线与字号天然一致。
                        Text(
                            text = buildAnnotatedString {
                                val raw = state.statusText
                                append(raw.take(1))
                                withStyle(SpanStyle(color = colors.accent)) {
                                    append(raw.drop(1))
                                }
                            },
                            style = type.display,
                            color = colors.ink,
                        )
                        Spacer(Modifier.weight(1f))
                        if (surface.hasPanels) {
                            DshGhostButton("重新检测", onClick = onRetryShizuku)
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = state.statusDetail,
                        style = type.body,
                        color = colors.inkMuted,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Spacer(Modifier.height(dimens.gapL))

            // ---------------- 数卡（纸本三列 / 石墨两卡 + 运行时长行） ----------------
            StaggeredEnter(index = 1) {
                if (surface.hasPanels) {
                    Column {
                        Row(Modifier.fillMaxWidth()) {
                            DshPanel(
                                modifier = Modifier.weight(1.52f),
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(18.dp, 18.dp, 18.dp, 16.dp),
                            ) {
                                TileContent(
                                    label = "已隐藏",
                                    value = state.hiddenCount.toString(),
                                    unit = "个应用",
                                    hint = "最近任务列表中不再出现",
                                )
                            }
                            Spacer(Modifier.width(12.dp))
                            DshPanel(
                                modifier = Modifier.weight(1f),
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(18.dp, 18.dp, 18.dp, 16.dp),
                            ) {
                                TileContent(
                                    label = "守护中",
                                    value = state.protectedCount.toString(),
                                    unit = "个",
                                    hint = "无障碍服务",
                                )
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .dshClickable(scaleOnPress = false, onClick = onOpenTemperature),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                Modifier
                                    .size(5.dp)
                                    .clip(RoundedCornerShape(percent = 50))
                                    .background(color = colors.accent.copy(alpha = 0.8f)),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = "运行 " + state.uptimeValue + " " + state.uptimeUnit +
                                    " · 温度 " + state.batteryTemp + " °C",
                                style = type.label,
                                color = colors.inkFaint,
                            )
                            Spacer(Modifier.weight(1f))
                            DshIcon(DshIconKind.CHEVRON, iconSize = 10.dp, tint = colors.inkFaint)
                        }
                    }
                } else {
                    // 三列大数字 + 一行运行时长小字。
                    // 为什么不放四列：四等分后每列只有约 64dp，而「14」+「天 0 时」
                    // 至少要 83dp，无论怎么调字号都会换行或被裁切（实测「时」和「°C」
                    // 就被切掉了）。把运行时长降为次级信息、用整行小字承载，才彻底解决。
                    // 这样纸本与石墨的信息层级也一致：石墨本来就是「运行 X · 温度 Y」小字行。
                    Column {
                        Row(
                            Modifier.fillMaxWidth().padding(top = 16.dp),
                            verticalAlignment = Alignment.Top,
                        ) {
                            FactColumn("已隐藏", state.hiddenCount.toString(), "个应用", Modifier.weight(1f))
                            Box(Modifier.width(1.dp).height(44.dp).background(color = colors.hairline))
                            FactColumn("守护中", state.protectedCount.toString(), "个服务", Modifier.weight(1f).padding(start = 20.dp))
                            Box(Modifier.width(1.dp).height(44.dp).background(color = colors.hairline))
                            // 这一列就是温度页入口：点温度数字进实时/录制/回放
                            Box(
                                Modifier
                                    .weight(1f)
                                    .dshClickable(scaleOnPress = false, onClick = onOpenTemperature),
                            ) {
                                FactColumn(
                                    "温度",
                                    state.batteryTemp,
                                    "°C",
                                    Modifier.fillMaxWidth().padding(start = 20.dp),
                                )
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier
                                    .size(4.dp)
                                    .clip(RoundedCornerShape(percent = 50))
                                    .background(color = colors.accent.copy(alpha = 0.8f)),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = "运行 " + state.uptimeValue + " " + state.uptimeUnit,
                                style = type.label,
                                color = colors.inkFaint,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }

            // 已隐藏应用图标行（空态给一行占位，避免版面塌掉）
            StaggeredEnter(index = 2) {
                if (state.hiddenPreview.isNotEmpty()) {
                    Row(
                        Modifier.fillMaxWidth().padding(top = if (surface.hasPanels) 14.dp else 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(7.dp),
                    ) {
                        for (item in state.hiddenPreview.take(8)) {
                            AppIconTile(label = item.label, seed = item.seed, tileSize = 30.dp)
                        }
                        val rest = state.hiddenCount - state.hiddenPreview.take(8).size
                        if (rest > 0) {
                            Box(
                                Modifier
                                    .size(30.dp)
                                    .clip(RoundedCornerShape(Dsh.dimens.iconRadius))
                                    .border(1.dp, colors.hairline, RoundedCornerShape(Dsh.dimens.iconRadius)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text("+$rest", style = type.monoSmall, color = colors.inkFaint)
                            }
                        }
                    }
                } else {
                    Text(
                        text = "还没有隐藏任何应用 · 在应用管理里选择",
                        style = type.caption,
                        color = colors.inkFaint,
                        modifier = Modifier.padding(top = if (surface.hasPanels) 14.dp else 6.dp),
                    )
                }
            }

            Spacer(Modifier.height(dimens.gapXl))

            // ---------------- 入口组 ----------------
            StaggeredEnter(index = 3) {
                Column {
                    if (surface.hasPanels) {
                        SectionHeader("入口")
                        Spacer(Modifier.height(10.dp))
                    }
                    DshPanel {
                        EntryRow(
                            icon = DshIconKind.GRID,
                            title = "工具箱",
                            desc = "ADB 命令 · 应用管理 · 通知历史 · 文件传输",
                            meta = "ADB · 应用 · 通知 · 传输",
                            onClick = onOpenToolbox,
                        )
                        DshDivider()
                        EntryRow(
                            icon = DshIconKind.SHIELD,
                            title = "无障碍管理",
                            desc = "防回收保活 · 一键开关服务",
                            meta = "防回收保活",
                            onClick = onOpenAccessibility,
                        )
                    }
                }
            }

            if (!surface.hasPanels) {
                Spacer(Modifier.height(dimens.gapXl))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(color = colors.hairline),
                )
                Spacer(Modifier.height(dimens.gapM))
                Text("工作方式", style = type.label, color = colors.inkFaint)
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "隐藏通过 Shizuku 把任务标记为「不进最近任务」实现（不会结束应用），保活通过 settings put secure 写入，全部在本机执行。",
                    style = type.body,
                    color = colors.inkMuted,
                )
            }

            Spacer(Modifier.weight(1f))

            // ---------------- 底部 ----------------
            Column(Modifier.padding(bottom = 26.dp)) {
                DshDivider()
                Spacer(Modifier.height(14.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    MiniToggle("后台保活", state.keepAlive) { onToggleKeepAlive(!state.keepAlive) }
                    Spacer(Modifier.width(18.dp))
                    MiniToggle("状态通知", state.statusNotification) { onToggleNotification(!state.statusNotification) }
                    Spacer(Modifier.width(18.dp))
                    MiniToggle("后台卡片", state.excludeFromRecents) { onToggleExclude(!state.excludeFromRecents) }
                }
                Spacer(Modifier.height(12.dp))
                if (!surface.hasPanels) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.End,
                ) {
                    // 文案按各自参考图走：纸本是「右手向右滑动」，石墨是「右滑进入无障碍」
                    Text(
                        text = if (surface.hasPanels) "右滑进入无障碍" else "右手向右滑动",
                        style = type.label,
                        color = colors.inkFaint,
                    )
                    Spacer(Modifier.width(8.dp))
                    DshIcon(DshIconKind.ARROW_RIGHT, iconSize = 13.dp, tint = colors.accent)
                }
                }
            }
        }

        // 石墨主题右侧的「右滑」竖排提示与右侧光带（高光）已移除。
    }
}

@Composable
private fun TileContent(label: String, value: String, unit: String, hint: String) {
    val colors = Dsh.colors
    val type = Dsh.type
    Text(label, style = type.label, color = colors.inkFaint)
    Spacer(Modifier.height(12.dp))
    Row(verticalAlignment = Alignment.Bottom) {
        Text(value, style = type.number, color = colors.ink)
        Spacer(Modifier.width(5.dp))
        Text(unit, style = type.caption, color = colors.inkMuted, modifier = Modifier.padding(bottom = 4.dp))
    }
    Spacer(Modifier.height(12.dp))
    Text(hint, style = type.caption, color = colors.inkFaint, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

@Composable
private fun FactColumn(label: String, value: String, unit: String, modifier: Modifier = Modifier) {
    val colors = Dsh.colors
    val type = Dsh.type
    Column(modifier) {
        Text(label, style = type.label, color = colors.inkFaint)
        Spacer(Modifier.height(9.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, style = type.numberSmall, color = colors.ink)
            Spacer(Modifier.width(5.dp))
            Text(
                text = unitWithReadableDigits(unit),
                style = type.caption,
                color = colors.inkMuted,
                // 禁止换行：复合单位（天 / 时）被折断成两行比字小更难看
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.padding(bottom = 3.dp),
            )
        }
    }
}

/**
 * 单位串里的数字单独放大。
 *
 * 问题：运行时长是「14 天 0 时」这种复合单位，整串按 caption(11sp) 渲染时，
 * 里面的 0 / 23 会和中文一样小，在大号数字旁边看起来像个错字。
 * 处理：按「数字段 / 非数字段」切分，数字段提到 15sp + 中等字重，中文字保持 11sp。
 */
private fun unitWithReadableDigits(unit: String): AnnotatedString = buildAnnotatedString {
    var i = 0
    while (i < unit.length) {
        val isDigit = unit[i].isDigit()
        var j = i
        while (j < unit.length && unit[j].isDigit() == isDigit) j++
        val chunk = unit.substring(i, j)
        if (isDigit) {
            // 13sp 而不是 15sp：15sp 时「14 天 0 时」在四等分列宽里会换行，
            // 13sp 仍明显大于中文的 11sp（0 不再是个小点），但整串能放进一行。
            withStyle(SpanStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium)) { append(chunk) }
        } else {
            append(chunk)
        }
        i = j
    }
}

@Composable
private fun EntryRow(
    icon: DshIconKind,
    title: String,
    desc: String,
    meta: String,
    onClick: () -> Unit,
) {
    val colors = Dsh.colors
    val type = Dsh.type
    val surface = Dsh.surface

    DshPressRow(onClick = onClick) {
        if (surface.hasPanels) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 15.dp, vertical = 15.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(34.dp)
                        .clip(RoundedCornerShape(Dsh.dimens.iconRadius))
                        .background(color = Color.White.copy(alpha = 0.045f))
                        .border(1.dp, colors.hairline, RoundedCornerShape(Dsh.dimens.iconRadius)),
                    contentAlignment = Alignment.Center,
                ) {
                    DshIcon(icon, iconSize = 15.dp, tint = colors.accent)
                }
                Spacer(Modifier.width(13.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, style = type.rowTitle, color = colors.ink)
                    Spacer(Modifier.height(5.dp))
                    Text(
                        text = desc,
                        style = type.caption,
                        color = colors.inkFaint,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.width(10.dp))
                DshIcon(DshIconKind.CHEVRON, iconSize = 13.dp, tint = colors.inkFaint)
            }
        } else {
            // 纸本：标题 → 点状引导线 → 等宽说明 → 朱红箭头（对齐参考图）
            Row(
                Modifier.fillMaxWidth().padding(vertical = 17.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = title,
                    style = type.title,
                    color = colors.ink,
                    maxLines = 1,
                )
                Spacer(Modifier.width(12.dp))
                DottedLeader(Modifier.weight(1f))
                Spacer(Modifier.width(12.dp))
                Text(
                    text = meta,
                    style = type.monoSmall,
                    color = colors.inkFaint,
                    maxLines = 1,
                )
                Spacer(Modifier.width(10.dp))
                DshIcon(DshIconKind.ARROW_RIGHT, iconSize = 14.dp, tint = colors.accent)
            }
        }
    }
}
