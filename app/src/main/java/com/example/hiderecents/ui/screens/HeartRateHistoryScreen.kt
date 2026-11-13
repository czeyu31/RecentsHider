package com.example.hiderecents.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
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

/** 录制历史里的一条会话（展示用，已格式化）。 */
data class HrHistoryItem(
    val id: Long,
    val day: String,
    val timeRange: String,
    val duration: String,
    val samples: Int,
    val avg: Int,
    val min: Int,
    val max: Int,
    val values: List<Int>,
)

/**
 * 心率 · 录制历史。
 *
 * 结构：页头 / 近 7 天概览 / 按天分组的会话列表（时间段 · 时长区间 · 缩略曲线 · 平均）。
 * 点任意一条进入该次会话报告。纸本走横线分隔，石墨每天一块面板。
 */
@Composable
fun HeartRateHistoryScreen(
    items: List<HrHistoryItem>,
    weekCount: Int,
    weekDuration: String,
    weekAvg: Int?,
    onBack: () -> Unit,
    onOpen: (HrHistoryItem) -> Unit,
    onClearAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Dsh.colors
    val dimens = Dsh.dimens
    val type = Dsh.type
    val surface = Dsh.surface
    var confirmClear by remember { mutableStateOf(false) }

    if (confirmClear) {
        HrConfirmDialog(
            title = "清空全部记录？",
            detail = "共 " + items.size + " 次会话，合计 " + weekDuration,
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
            HistoryHeader(
                subtitle = if (items.isEmpty()) "暂无记录" else "共 " + items.size + " 次 · 最近在前",
                onBack = onBack,
                trailing = {
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
                },
            )

            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                if (items.isEmpty()) {
                    Spacer(Modifier.height(96.dp))
                    Text("还没有录制过心率", style = type.h2, color = colors.inkMuted)
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "回到心率页按「开始录制」，再按一次停止，就会存成一条记录。",
                        style = type.caption,
                        color = colors.inkFaint,
                    )
                    return@Column
                }

                Spacer(Modifier.height(if (surface.hasPanels) 12.dp else 18.dp))

                // 近 7 天概览
                DshPanel(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = if (surface.hasPanels) PaddingValues(14.dp) else PaddingValues(0.dp),
                ) {
                    Text("近 7 天", style = type.label, color = colors.inkFaint)
                    Spacer(Modifier.height(10.dp))
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        SummaryCell("次数", weekCount.toString(), "次")
                        SummaryRule()
                        SummaryCell("总时长", weekDuration, "")
                        SummaryRule()
                        SummaryCell("平均", weekAvg?.toString() ?: "--", "bpm")
                    }
                }

                // 按天分组
                val groups = items.groupBy { it.day }
                groups.entries.forEach { (day, list) ->
                    Spacer(Modifier.height(if (surface.hasPanels) dimens.gapM else 22.dp))
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(day, style = type.label, color = colors.inkMuted)
                        Spacer(Modifier.weight(1f))
                        Text(list.size.toString() + " 次", style = type.monoSmall, color = colors.inkFaint)
                    }
                    Spacer(Modifier.height(if (surface.hasPanels) 8.dp else 6.dp))
                    if (!surface.hasPanels) DshDivider()

                    DshPanel(
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = if (surface.hasPanels) PaddingValues(horizontal = 14.dp, vertical = 2.dp)
                        else PaddingValues(0.dp),
                    ) {
                        list.forEachIndexed { index, item ->
                            HistoryRow(item = item, onClick = { onOpen(item) })
                            if (index != list.lastIndex) DshDivider()
                        }
                    }
                    if (!surface.hasPanels) DshDivider()
                }

                Spacer(Modifier.height(16.dp))
                Text(
                    text = "最多保留最近 50 次 · 非医疗设备，数据仅供运动参考",
                    style = type.caption,
                    color = colors.inkFaint,
                )
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun HistoryHeader(
    subtitle: String,
    onBack: () -> Unit,
    trailing: @Composable () -> Unit = {},
) {
    val colors = Dsh.colors
    val type = Dsh.type
    val dimens = Dsh.dimens
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
            Text(subtitle, style = type.label, color = colors.inkFaint)
        }
        trailing()
    }
}

@Composable
private fun RowScope.SummaryCell(label: String, value: String, unit: String) {
    val colors = Dsh.colors
    val type = Dsh.type
    Column(Modifier.weight(1f)) {
        Text(label, style = type.label, color = colors.inkFaint)
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, style = type.numberSmall, color = colors.ink)
            if (unit.isNotEmpty()) {
                Spacer(Modifier.width(4.dp))
                Text(unit, style = type.label, color = colors.inkFaint)
            }
        }
    }
}

@Composable
private fun SummaryRule() {
    Box(
        Modifier
            .padding(horizontal = 12.dp)
            .width(1.dp)
            .height(34.dp)
            .background(Dsh.colors.hairline),
    )
}

@Composable
private fun HistoryRow(item: HrHistoryItem, onClick: () -> Unit) {
    val colors = Dsh.colors
    val type = Dsh.type
    Row(
        Modifier
            .fillMaxWidth()
            .dshClickable(scaleOnPress = false, onClick = onClick)
            .padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(item.timeRange, style = type.rowTitle, color = colors.ink)
            Spacer(Modifier.height(5.dp))
            Text(
                text = item.duration + " · " + item.min + "–" + item.max + " bpm",
                style = type.monoSmall,
                color = colors.inkFaint,
            )
        }
        Sparkline(values = item.values, modifier = Modifier.width(64.dp).height(26.dp))
        Spacer(Modifier.width(16.dp))
        Column(horizontalAlignment = Alignment.End, modifier = Modifier.width(44.dp)) {
            Text(
                text = item.avg.toString(),
                style = type.numberSmall.copy(fontSize = 20.sp, lineHeight = 22.sp),
                color = colors.ink,
            )
            Spacer(Modifier.height(2.dp))
            Text("平均", style = type.label, color = colors.inkFaint)
        }
        Spacer(Modifier.width(8.dp))
        DshIcon(DshIconKind.CHEVRON, iconSize = 12.dp, tint = colors.inkFaint)
    }
}

/** 缩略曲线：固定按 ±10 bpm 的最小跨度缩放，避免把 79→85 这种小波动画成悬崖。 */
@Composable
private fun Sparkline(values: List<Int>, modifier: Modifier = Modifier) {
    val accent = Dsh.colors.accent
    val hairline = Dsh.colors.hairline
    Canvas(modifier) {
        drawLine(
            color = hairline,
            start = Offset(0f, size.height),
            end = Offset(size.width, size.height),
            strokeWidth = 1.dp.toPx(),
        )
        if (values.size < 2) return@Canvas
        val lo = values.minOrNull() ?: 0
        val hi = values.maxOrNull() ?: 0
        // 至少 ±10 bpm：真实心率在 79–85 之间只是轻微起伏，按自身极值拉满会看成锯齿
        val mid = (lo + hi) / 2f
        val half = maxOf((hi - lo) / 2f, 10f)
        val bottom = mid - half
        val span = half * 2f
        val pad = 2.dp.toPx()
        val h = size.height - pad * 2
        val stepX = size.width / (values.size - 1)
        val path = Path()
        values.forEachIndexed { i, v ->
            val x = i * stepX
            val y = pad + h - (v - bottom) / span * h
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(
            path = path,
            color = accent,
            style = Stroke(width = 1.4.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }
}
