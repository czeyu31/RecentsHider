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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.hiderecents.ui.components.AppIconTile
import com.example.hiderecents.ui.components.BreathingDot
import com.example.hiderecents.ui.components.DshDivider
import com.example.hiderecents.ui.components.DshGhostButton
import com.example.hiderecents.ui.components.DshIcon
import com.example.hiderecents.ui.components.DshIconButton
import com.example.hiderecents.ui.components.DshIconKind
import com.example.hiderecents.ui.components.DshPanel
import com.example.hiderecents.ui.components.DshPressRow
import com.example.hiderecents.ui.components.MiniToggle
import com.example.hiderecents.ui.components.SectionHeader
import com.example.hiderecents.ui.components.StaggeredEnter
import com.example.hiderecents.ui.components.dshCanvasBackground
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
)

@Composable
fun HomeScreen(
    state: HomeUiState,
    onOpenToolbox: () -> Unit,
    onOpenAccessibility: () -> Unit,
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
                .systemBarsPadding()
                .padding(horizontal = dimens.screenPadding),
        ) {
            Spacer(Modifier.height(4.dp))

            // ---------------- 顶栏 ----------------
            Row(
                Modifier.fillMaxWidth().height(56.dp),
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
                    Spacer(Modifier.width(7.dp))
                    Text("v30", style = type.monoSmall, color = colors.inkFaint)
                } else {
                    Box(Modifier.size(8.dp).background(color = colors.accent))
                    Spacer(Modifier.width(10.dp))
                    Text("SYSTEM TOOL", style = type.label, color = colors.ink)
                }
                Spacer(Modifier.weight(1f))
                DshIconButton(DshIconKind.GEAR, onClick = onOpenSettings)
            }

            // ---------------- 状态块 ----------------
            StaggeredEnter(index = 0) {
                Column {
                    Text("Shizuku 服务", style = type.label, color = colors.inkFaint)
                    Spacer(Modifier.height(14.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        BreathingDot(
                            color = if (state.connected) colors.accent else colors.inkFaint,
                            dotSize = 8.dp,
                        )
                        Spacer(Modifier.width(if (surface.hasPanels) 14.dp else 10.dp))
                        Text(state.statusText, style = type.display, color = colors.ink)
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

            // ---------------- 数卡 ----------------
            StaggeredEnter(index = 1) {
                if (surface.hasPanels) {
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
                } else {
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 16.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        FactColumn("已隐藏", state.hiddenCount.toString(), "个应用", Modifier.weight(1f))
                        Box(Modifier.width(1.dp).height(44.dp).background(color = colors.hairline))
                        FactColumn("守护中", state.protectedCount.toString(), "个服务", Modifier.weight(1f).padding(start = 22.dp))
                    }
                }
            }

            // 已隐藏应用图标行
            if (state.hiddenPreview.isNotEmpty()) {
                StaggeredEnter(index = 2) {
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
                }
            }

            Spacer(Modifier.height(dimens.gapXl))

            // ---------------- 入口组 ----------------
            StaggeredEnter(index = 3) {
                Column {
                    if (surface.hasPanels) {
                        SectionHeader("入口", "长按查看更多")
                        Spacer(Modifier.height(10.dp))
                    }
                    DshPanel {
                        EntryRow(
                            icon = DshIconKind.GRID,
                            title = "工具箱",
                            desc = "ADB 命令 · 应用管理 · 通知历史 · 文件传输",
                            onClick = onOpenToolbox,
                        )
                        DshDivider()
                        EntryRow(
                            icon = DshIconKind.SHIELD,
                            title = "无障碍管理",
                            desc = "防回收保活 · 一键开关服务",
                            onClick = onOpenAccessibility,
                        )
                    }
                }
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
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.End,
                ) {
                    Text("右滑进入无障碍", style = type.label, color = colors.inkFaint)
                    Spacer(Modifier.width(8.dp))
                    DshIcon(DshIconKind.ARROW_RIGHT, iconSize = 13.dp, tint = colors.accent)
                }
            }
        }

        // 右侧滑动导轨：仅石墨主题（纸本不用光带）
        if (surface.hasPanels) {
            Box(
                Modifier
                    .align(Alignment.CenterEnd)
                    .width(34.dp)
                    .fillMaxHeight(),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(
                            brush = Brush.horizontalGradient(
                                listOf(Color.Transparent, colors.accent.copy(alpha = 0.10f)),
                            ),
                        ),
                )
                Text(
                    text = "右滑进入无障碍",
                    style = type.label,
                    color = colors.accent.copy(alpha = 0.85f),
                    modifier = Modifier.rotate(90f),
                )
            }
        }
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
        Text(unit, style = type.caption, color = colors.inkFaint, modifier = Modifier.padding(bottom = 4.dp))
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
            Text(unit, style = type.caption, color = colors.inkFaint, modifier = Modifier.padding(bottom = 3.dp))
        }
    }
}

@Composable
private fun EntryRow(
    icon: DshIconKind,
    title: String,
    desc: String,
    onClick: () -> Unit,
) {
    val colors = Dsh.colors
    val type = Dsh.type
    val surface = Dsh.surface

    DshPressRow(onClick = onClick) {
        Row(
            Modifier.fillMaxWidth().padding(
                horizontal = if (surface.hasPanels) 15.dp else 0.dp,
                vertical = if (surface.hasPanels) 15.dp else 17.dp,
            ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (surface.hasPanels) {
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
            }
            Column(Modifier.weight(1f)) {
                Text(title, style = if (surface.hasPanels) type.rowTitle else type.title, color = colors.ink)
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
            DshIcon(
                kind = DshIconKind.CHEVRON,
                iconSize = 13.dp,
                tint = if (surface.hasPanels) colors.inkFaint else colors.accent,
            )
        }
    }
}
