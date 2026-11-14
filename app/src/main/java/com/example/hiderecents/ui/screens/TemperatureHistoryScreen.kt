package com.example.hiderecents.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.hiderecents.ui.components.DshDivider
import com.example.hiderecents.ui.components.DshIcon
import com.example.hiderecents.ui.components.DshIconKind
import com.example.hiderecents.ui.components.DshPanel
import com.example.hiderecents.ui.components.dshCanvasBackground
import com.example.hiderecents.ui.components.dshClickable
import com.example.hiderecents.ui.components.paperHeaderRule
import com.example.hiderecents.ui.theme.Dsh

/** 录制历史里的一条（展示用，已格式化）。 */
data class TempHistoryItem(
    val id: Long,
    val day: String,
    val time: String,
    val duration: String,
    val samples: Int,
    val peak: Float,
    val average: Float,
    /** 历史列表显示的温度是电池温度；没有电池热区时退回主曲线峰值 */
    val isBattery: Boolean = true,
    /** 这次录制跟随的热区名 */
    val zoneLabel: String = "",
)

/**
 * 温度 · 录制历史。
 *
 * 按天分组，每行是「开始时间 · 时长 · 样本数 · 峰值」。
 * 点任意一行进入该次录制的回放页；底部提供导出 CSV 与清空。
 */
@Composable
fun TemperatureHistoryScreen(
    items: List<TempHistoryItem>,
    onBack: () -> Unit,
    onOpen: (Long) -> Unit,
    onClearAll: () -> Unit,
    onExportAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Dsh.colors
    val dimens = Dsh.dimens
    val type = Dsh.type
    val surface = Dsh.surface
    var confirmClear by remember { mutableStateOf(false) }

    if (confirmClear) {
        HrConfirmDialog(
            title = "清空全部录制？",
            detail = "共 " + items.size + " 条记录",
            note = "清空后无法恢复",
            confirmText = "清空",
            onCancel = { confirmClear = false },
            onConfirm = {
                confirmClear = false
                onClearAll()
            },
        )
    }

    Box(modifier.fillMaxSize().dshCanvasBackground()) {
        Column(
            Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(horizontal = dimens.screenPadding),
        ) {
            Spacer(Modifier.height(4.dp))
            Row(
                Modifier.fillMaxWidth().height(56.dp).paperHeaderRule(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(32.dp)
                        .clip(RoundedCornerShape(dimens.iconRadius))
                        .dshClickable(onClick = onBack),
                    contentAlignment = Alignment.Center,
                ) {
                    DshIcon(DshIconKind.BACK, iconSize = 15.dp, tint = colors.inkMuted)
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("录制历史", style = type.h2, color = colors.ink)
                    Spacer(Modifier.height(5.dp))
                    Text(
                        text = if (items.isEmpty()) "暂无记录" else "共 " + items.size + " 次 · 最近在前",
                        style = type.label,
                        color = colors.inkFaint,
                    )
                }
                // 清空放在右上角，列表底部只留导出
                if (items.isNotEmpty()) {
                    Row(
                        Modifier
                            .clip(RoundedCornerShape(dimens.iconRadius))
                            .dshClickable(scaleOnPress = false) { confirmClear = true }
                            .padding(start = 8.dp, top = 6.dp, bottom = 6.dp, end = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("清空", style = type.label, color = colors.inkMuted)
                    }
                }
            }

            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                if (items.isEmpty()) {
                    Spacer(Modifier.height(96.dp))
                    Text("还没有录制过温度", style = type.h2, color = colors.inkMuted)
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "回到温度页点右上角播放键开始，再点一次停止，就会存成一条记录。",
                        style = type.caption,
                        color = colors.inkFaint,
                    )
                    return@Column
                }

                Spacer(Modifier.height(if (surface.hasPanels) 12.dp else 18.dp))

                val groups = items.groupBy { it.day }
                groups.entries.forEach { (day, list) ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(day, style = type.label, color = colors.inkMuted)
                        Spacer(Modifier.weight(1f))
                        Text(list.size.toString() + " 次", style = type.monoSmall, color = colors.inkFaint)
                    }
                    Spacer(Modifier.height(if (surface.hasPanels) 8.dp else 6.dp))
                    if (!surface.hasPanels) DshDivider()

                    DshPanel(
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = if (surface.hasPanels) PaddingValues(horizontal = 14.dp) else PaddingValues(0.dp),
                    ) {
                        list.forEachIndexed { index, item ->
                            HistoryRow(item = item, onClick = { onOpen(item.id) })
                            if (index != list.lastIndex) DshDivider()
                        }
                    }
                    if (!surface.hasPanels) DshDivider()
                    Spacer(Modifier.height(if (surface.hasPanels) dimens.gapM else 22.dp))
                }

                Row(
                    Modifier.fillMaxWidth().padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
                ) {
                    if (surface.hasPanels) {
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(dimens.controlRadius))
                                .dshClickable(onClick = onExportAll)
                                .padding(horizontal = 20.dp, vertical = 10.dp),
                        ) {
                            Text("导出 CSV", style = type.rowTitle, color = colors.accent)
                        }
                    } else {
                        Column(
                            Modifier.dshClickable(onClick = onExportAll).padding(vertical = 4.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text("导出 CSV", style = type.rowTitle, color = colors.accent)
                            Spacer(Modifier.height(3.dp))
                            Box(Modifier.height(1.5.dp).width(72.dp).background(color = colors.accent))
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))
                Text(
                    text = "最多保留最近 50 次 · 读数来自系统热区，非医疗或工业测量",
                    style = type.caption,
                    color = colors.inkFaint,
                )
                Spacer(Modifier.height(20.dp))
            }
        }
    }
}

@Composable
private fun HistoryRow(item: TempHistoryItem, onClick: () -> Unit) {
    val colors = Dsh.colors
    val type = Dsh.type
    val hot = item.peak >= 45f
    Row(
        Modifier
            .fillMaxWidth()
            .dshClickable(scaleOnPress = false, onClick = onClick)
            .padding(vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(item.time, style = type.rowTitle, color = colors.ink)
            Spacer(Modifier.height(5.dp))
            Text(
                text = item.duration + " · " + item.samples + " 样本" +
                    (if (item.zoneLabel.isEmpty()) "" else " · " + item.zoneLabel),
                style = type.monoSmall,
                color = colors.inkFaint,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = fmt1(item.peak) + "°",
                style = type.numberSmall.copy(fontSize = 19.sp),
                color = if (hot) colors.accent else colors.ink,
                textAlign = TextAlign.End,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = if (item.isBattery) "电池" else "峰值",
                style = type.label,
                color = colors.inkFaint,
            )
        }
        Spacer(Modifier.width(8.dp))
        DshIcon(DshIconKind.CHEVRON, iconSize = 12.dp, tint = colors.inkFaint)
    }
}