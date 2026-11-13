package com.example.hiderecents.ui.screens

import android.graphics.Paint
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.floor
import kotlin.math.roundToInt
import com.example.hiderecents.ui.components.DshDivider
import com.example.hiderecents.ui.components.DshGhostButton
import com.example.hiderecents.ui.components.DshIcon
import com.example.hiderecents.ui.components.DshIconButton
import com.example.hiderecents.ui.components.DshIconKind
import com.example.hiderecents.ui.components.DshPanel
import com.example.hiderecents.ui.components.dshCanvasBackground
import com.example.hiderecents.ui.components.dshClickable
import com.example.hiderecents.ui.components.paperHeaderRule
import com.example.hiderecents.ui.theme.Dsh

/** 心率区间分布的一段（报告页用）。仅作展示分档，不是医学判断。 */
data class HrZoneSlice(
    val label: String,
    val range: String,
    val percent: Int,
    val duration: String,
)

private val zoneLabels = listOf(
    "静息" to "< 60",
    "日常" to "60 – 100",
    "燃脂" to "100 +",
)

private fun zoneOf(bpm: Int): Int = when {
    // 没有数据时不落在任何一档，否则「待机」时会莫名高亮「日常」
    bpm <= 0 -> -1
    bpm < 60 -> 0
    bpm < 100 -> 1
    else -> 2
}

/**
 * 心率广播页 · 实时态（设计预览版，未接入工具箱）。
 *
 * 结构：页头 / 主读数 / 区间尺 / 60 秒曲线 / 三档统计 / 广播明细 / 底部录制按钮。
 * 同一份代码靠设计令牌分叉：纸本（无卡片 + 横线 + 衬线大数字）、石墨（面板 + 无衬线 + 强调色按钮）。
 * 主题由调用方决定，页内不提供切换入口。
 */
@Composable
fun HeartRateScreen(
    bpm: Int,
    values: List<Int>,
    deviceName: String,
    deviceMac: String,
    rssi: Int,
    packets: Int,
    dropRate: String,
    sessionText: String,
    ageText: String,
    sourceLabel: String,
    recording: Boolean,
    recTime: String,
    onBack: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenSettings: () -> Unit,
    onToggleRecording: () -> Unit,
    onChangeDevice: () -> Unit,
    statusLine: String = "",
    statusIsError: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val colors = Dsh.colors
    val dimens = Dsh.dimens
    val type = Dsh.type
    val surface = Dsh.surface

    val zone = zoneOf(bpm)
    val hasData = bpm > 0

    Box(modifier.fillMaxSize().dshCanvasBackground()) {
        Column(
            Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(horizontal = dimens.screenPadding),
        ) {
            Spacer(Modifier.height(4.dp))
            HrHeader(
                title = "心率广播",
                subtitle = sourceLabel + " · 免配对",
                onBack = onBack,
                trailing = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // 石墨沿用首页的图标按钮；纸本继续用「设置」两个字
                        if (Dsh.surface.hasPanels) {
                            DshIconButton(DshIconKind.SLIDERS, onClick = onOpenSettings)
                        } else {
                            Text(
                                text = "设置",
                                style = type.label,
                                color = colors.inkMuted,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(dimens.iconRadius))
                                    .dshClickable(scaleOnPress = false, onClick = onOpenSettings)
                                    .padding(start = 6.dp, top = 6.dp, bottom = 6.dp, end = 6.dp),
                            )
                        }
                        Spacer(Modifier.width(4.dp))
                        if (recording) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    Modifier
                                        .size(5.dp)
                                        .clip(RoundedCornerShape(1.dp))
                                        .background(colors.accent),
                                )
                                Spacer(Modifier.width(6.dp))
                                Text("REC", style = type.label, color = colors.accent)
                                Spacer(Modifier.width(6.dp))
                                Text(recTime, style = type.monoSmall, color = colors.inkMuted)
                            }
                        } else {
                            Row(
                                Modifier
                                    .clip(RoundedCornerShape(dimens.iconRadius))
                                    .dshClickable(scaleOnPress = false, onClick = onOpenHistory)
                                    .padding(start = 4.dp, top = 6.dp, bottom = 6.dp, end = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text("录制历史", style = type.label, color = colors.inkMuted)
                                Spacer(Modifier.width(4.dp))
                                DshIcon(DshIconKind.CHEVRON, iconSize = 11.dp, tint = colors.inkFaint)
                            }
                        }
                    }
                },
            )

            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                Spacer(Modifier.height(if (surface.hasPanels) 12.dp else 20.dp))

                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                    // 数字槽固定宽度：心率在 2/3 位数之间跳变时，右边的「BPM / 录制中」不会左右晃。
                    // 占位符用同一个 Text 样式（en dash，比 em dash 窄），保证两种状态下行高一致、不上下跳。
                    Box(Modifier.width(118.dp), contentAlignment = Alignment.BottomStart) {
                        Text(
                            text = if (hasData) bpm.toString() else "–",
                            style = type.number.copy(
                                fontSize = 72.sp,
                                lineHeight = 72.sp,
                                letterSpacing = (-2.2f).sp,
                            ),
                            color = if (hasData) colors.ink else colors.inkFaint,
                        )
                    }
                    Spacer(Modifier.width(9.dp))
                    Column(Modifier.padding(bottom = 8.dp)) {
                        Text("BPM", style = type.label, color = colors.inkFaint)
                        Spacer(Modifier.height(7.dp))
                        LiveDot(recording = recording)
                    }
                    Spacer(Modifier.weight(1f))
                    Column(
                        Modifier.padding(bottom = 8.dp),
                        horizontalAlignment = Alignment.End,
                    ) {
                        ZoneChip(
                            label = zoneLabels.getOrNull(zone)?.first ?: "—",
                            active = hasData,
                        )
                        Spacer(Modifier.height(8.dp))
                        if (rssi == 0) {
                            Text("— dBm", style = type.monoSmall, color = colors.inkFaint)
                        } else {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                SignalBars(rssi = rssi)
                                Spacer(Modifier.width(6.dp))
                                Text("$rssi dBm", style = type.monoSmall, color = colors.inkFaint)
                            }
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))
                Text(
                    text = if (deviceName.isEmpty() || deviceName == "未连接设备") {
                        "未连接 · 扫描并选择一台心率设备"
                    } else {
                        deviceName + " · " + deviceMac + " · " + ageText
                    },
                    style = type.caption,
                    color = colors.inkFaint,
                )
                if (statusLine.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = statusLine,
                        style = type.caption,
                        color = if (statusIsError) colors.accent else colors.inkMuted,
                    )
                }

                Spacer(Modifier.height(if (surface.hasPanels) dimens.gapM else 18.dp))
                Row(Modifier.fillMaxWidth()) {
                    ZoneSegment("静息", "< 60", zone == 0, 1f)
                    Spacer(Modifier.width(8.dp))
                    ZoneSegment("日常", "60 – 100", zone == 1, 1f)
                    Spacer(Modifier.width(8.dp))
                    ZoneSegment("燃脂", "100 +", zone == 2, 1f)
                }

                Spacer(Modifier.height(if (surface.hasPanels) dimens.gapM else 18.dp))
                if (!surface.hasPanels) DshDivider()
                Spacer(Modifier.height(if (surface.hasPanels) 0.dp else 16.dp))

                DshPanel(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = if (surface.hasPanels) PaddingValues(14.dp) else PaddingValues(0.dp),
                ) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("最近 60 秒", style = type.label, color = colors.inkFaint)
                        Spacer(Modifier.weight(1f))
                        Text(
                            text = if (values.isEmpty()) "--"
                            else values.minOrNull().toString() + " – " + values.maxOrNull().toString() + " bpm",
                            style = type.label,
                            color = colors.inkFaint,
                        )
                    }
                    Spacer(Modifier.height(14.dp))
                    LiveChart(
                        values = values,
                        active = recording,
                        modifier = Modifier.fillMaxWidth().height(104.dp),
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("−60s", style = type.monoSmall, color = colors.inkFaint)
                        Text("−30s", style = type.monoSmall, color = colors.inkFaint)
                        Text("现在", style = type.monoSmall, color = colors.inkFaint)
                    }
                }

                Spacer(Modifier.height(if (surface.hasPanels) dimens.gapM else 20.dp))
                if (!surface.hasPanels) DshDivider()
                Spacer(Modifier.height(if (surface.hasPanels) 0.dp else 16.dp))

                DshPanel(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = if (surface.hasPanels) PaddingValues(14.dp) else PaddingValues(0.dp),
                ) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        StatCell("最低", values.minOrNull(), 1f)
                        VHairline()
                        StatCell("平均", if (values.isEmpty()) null else values.average().toInt(), 1f)
                        VHairline()
                        StatCell("最高", values.maxOrNull(), 1f)
                    }
                }

                Spacer(Modifier.height(if (surface.hasPanels) dimens.gapM else 20.dp))
                if (!surface.hasPanels) DshDivider()
                Spacer(Modifier.height(if (surface.hasPanels) 0.dp else 12.dp))

                DshPanel(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = if (surface.hasPanels) PaddingValues(horizontal = 16.dp, vertical = 6.dp)
                    else PaddingValues(0.dp),
                ) {
                    MetaRow("数据包", packets.toString() + " 包")
                    if (!surface.hasPanels) DshDivider()
                    MetaRow("丢包率", dropRate)
                    if (!surface.hasPanels) DshDivider()
                    MetaRow("会话时长", sessionText)
                }

                Spacer(Modifier.height(14.dp))
                Text(
                    text = "非医疗设备，数据仅供运动参考",
                    style = type.caption,
                    color = colors.inkFaint,
                )
                Spacer(Modifier.height(12.dp))
            }

            HrPrimaryButton(
                text = if (recording) "停止录制" else "开始录制",
                onClick = onToggleRecording,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            HrSecondaryAction(
                text = "选择设备",
                onClick = onChangeDevice,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
        }
    }
}

/**
 * 心率会话报告页 · 停止录制后生成。
 *
 * 结构：页头 / 平均读数 + 时长样本 / 整段曲线（区间底色带 + 阈值虚线 + 空档标记）/ 区间分布 / 分析 / 导出。
 */
@Composable
fun HeartRateReportScreen(
    periodText: String,
    durationText: String,
    avg: Int,
    min: Int,
    max: Int,
    samples: Int,
    completeness: String,
    values: List<Int>,
    zones: List<HrZoneSlice>,
    analysis: List<String>,
    gapIndex: Int,
    axisStart: String,
    axisMid: String,
    axisEnd: String,
    onBack: () -> Unit,
    onDelete: () -> Unit,
    onExportCsv: () -> Unit,
    onShareChart: () -> Unit,
    pointLabel: (Int) -> String = { "" },
    modifier: Modifier = Modifier,
) {
    val colors = Dsh.colors
    val dimens = Dsh.dimens
    val type = Dsh.type
    val surface = Dsh.surface
    var confirmDelete by remember { mutableStateOf(false) }

    if (confirmDelete) {
        HrConfirmDialog(
            title = "删除这次会话？",
            detail = periodText,
            note = "平均 $avg bpm · 删除后无法恢复",
            confirmText = "删除",
            onCancel = { confirmDelete = false },
            onConfirm = {
                confirmDelete = false
                onDelete()
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
            HrHeader(
                title = "心率会话",
                subtitle = periodText,
                onBack = onBack,
                trailing = {
                    Row(
                        Modifier
                            .clip(RoundedCornerShape(dimens.iconRadius))
                            .dshClickable(scaleOnPress = false) { confirmDelete = true }
                            .padding(start = 8.dp, top = 6.dp, bottom = 6.dp, end = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("删除", style = type.label, color = colors.inkMuted)
                    }
                },
            )

            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                Spacer(Modifier.height(if (surface.hasPanels) 12.dp else 20.dp))

                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = avg.toString(),
                        style = type.number.copy(
                            fontSize = 60.sp,
                            lineHeight = 60.sp,
                            letterSpacing = (-2f).sp,
                        ),
                        color = colors.ink,
                    )
                    Spacer(Modifier.width(9.dp))
                    Column(Modifier.padding(bottom = 9.dp)) {
                        Text("平均 BPM", style = type.label, color = colors.inkFaint)
                        Spacer(Modifier.height(7.dp))
                        Text("$min – $max 区间", style = type.monoSmall, color = colors.inkFaint)
                    }
                    Spacer(Modifier.weight(1f))
                    Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(bottom = 6.dp)) {
                        MiniStat("时长", durationText)
                        Spacer(Modifier.height(6.dp))
                        MiniStat("样本", samples.toString())
                        Spacer(Modifier.height(6.dp))
                        MiniStat("完整度", completeness)
                    }
                }

                Spacer(Modifier.height(if (surface.hasPanels) dimens.gapM else 18.dp))
                if (!surface.hasPanels) DshDivider()
                Spacer(Modifier.height(if (surface.hasPanels) 0.dp else 16.dp))

                DshPanel(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = if (surface.hasPanels) PaddingValues(14.dp) else PaddingValues(0.dp),
                ) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("整段曲线", style = type.label, color = colors.inkFaint)
                        Spacer(Modifier.weight(1f))
                        Text("点按查看", style = type.label, color = colors.inkFaint)
                    }
                    Spacer(Modifier.height(14.dp))
                    SessionChart(
                        values = values,
                        gapIndex = gapIndex,
                        pointLabel = pointLabel,
                        modifier = Modifier.fillMaxWidth().height(104.dp),
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(axisStart, style = type.monoSmall, color = colors.inkFaint)
                        Text(axisMid, style = type.monoSmall, color = colors.inkFaint)
                        Text(axisEnd, style = type.monoSmall, color = colors.inkFaint)
                    }
                }

                Spacer(Modifier.height(if (surface.hasPanels) dimens.gapM else 18.dp))
                if (!surface.hasPanels) DshDivider()
                Spacer(Modifier.height(if (surface.hasPanels) 0.dp else 16.dp))

                DshPanel(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = if (surface.hasPanels) PaddingValues(14.dp) else PaddingValues(0.dp),
                ) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("区间分布", style = type.label, color = colors.inkFaint)
                        Spacer(Modifier.weight(1f))
                        Text("总计 " + durationText, style = type.label, color = colors.inkFaint)
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(RoundedCornerShape(if (surface.hasPanels) 4.dp else 0.dp)),
                    ) {
                        zones.forEachIndexed { index, slice ->
                            // 0% 的区间不画：给它留个最小宽度会在条两端多出一条别的颜色
                            if (slice.percent > 0) {
                                Box(
                                    Modifier
                                        .weight(slice.percent.toFloat())
                                        .fillMaxHeight()
                                        .background(sliceColor(index)),
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    Row(Modifier.fillMaxWidth()) {
                        zones.forEachIndexed { index, slice ->
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        Modifier
                                            .size(5.dp)
                                            .clip(RoundedCornerShape(1.dp))
                                            .background(sliceColor(index)),
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text(slice.label, style = type.label, color = colors.inkMuted)
                                }
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    text = slice.percent.toString() + "%",
                                    style = type.numberSmall.copy(fontSize = 18.sp, lineHeight = 24.sp),
                                    color = colors.ink,
                                )
                                Spacer(Modifier.height(3.dp))
                                Text(slice.duration, style = type.monoSmall, color = colors.inkFaint)
                            }
                        }
                    }
                }

                Spacer(Modifier.height(if (surface.hasPanels) dimens.gapM else 18.dp))
                if (!surface.hasPanels) DshDivider()
                Spacer(Modifier.height(if (surface.hasPanels) 0.dp else 16.dp))

                DshPanel(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = if (surface.hasPanels) PaddingValues(14.dp) else PaddingValues(0.dp),
                ) {
                    Text("分析", style = type.label, color = colors.inkFaint)
                    Spacer(Modifier.height(12.dp))
                    analysis.forEachIndexed { index, line ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                            Text(
                                text = if (index < 9) "0" + (index + 1) else (index + 1).toString(),
                                style = type.monoSmall,
                                color = colors.accent,
                            )
                            Spacer(Modifier.width(12.dp))
                            Text(line, style = type.body, color = colors.inkMuted, modifier = Modifier.weight(1f))
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))
                Text(
                    text = "非医疗设备，数据仅供运动参考",
                    style = type.caption,
                    color = colors.inkFaint,
                )
                Spacer(Modifier.height(12.dp))
            }

            HrPrimaryButton(
                text = "导出 CSV",
                onClick = onExportCsv,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
            HrSecondaryAction(
                text = "分享曲线图",
                onClick = onShareChart,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
        }
    }
}

// ---------------------------------------------------------------------------
// 局部组件
// ---------------------------------------------------------------------------

@Composable
private fun HrHeader(
    title: String,
    subtitle: String,
    onBack: () -> Unit,
    trailing: @Composable () -> Unit,
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
            Text(title, style = type.h2, color = colors.ink)
            Spacer(Modifier.height(5.dp))
            Text(subtitle, style = type.label, color = colors.inkFaint)
        }
        Spacer(Modifier.width(10.dp))
        trailing()
    }
}

@Composable
private fun sliceColor(index: Int): Color {
    val accent = Dsh.colors.accent
    return when (index) {
        0 -> accent.copy(alpha = 0.28f)
        1 -> accent.copy(alpha = 0.62f)
        else -> accent
    }
}

@Composable
private fun MiniStat(label: String, value: String) {
    val colors = Dsh.colors
    val type = Dsh.type
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = type.label, color = colors.inkFaint)
        Spacer(Modifier.width(8.dp))
        Text(value, style = type.monoSmall, color = colors.inkMuted)
    }
}

/** 状态点：静止不动，录制时是实心强调色，待机时是暗色。不做呼吸动画——它会让整行跟着抖。 */
@Composable
private fun LiveDot(recording: Boolean) {
    val colors = Dsh.colors
    val type = Dsh.type
    val dotColor = if (recording) colors.accent else colors.inkFaint

    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(dotColor),
        )
        Spacer(Modifier.width(7.dp))
        Text(
            text = if (recording) "录制中" else "待机",
            style = type.caption,
            color = colors.inkMuted,
        )
    }
}

/** 区间标签：小号等宽字，前面一个实心方点。 */
@Composable
private fun ZoneChip(label: String, active: Boolean) {
    val colors = Dsh.colors
    val type = Dsh.type
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(5.dp)
                .clip(RoundedCornerShape(1.dp))
                .background(if (active) colors.accent else colors.inkFaint),
        )
        Spacer(Modifier.width(6.dp))
        Text(label, style = type.label, color = if (active) colors.accent else colors.inkFaint)
    }
}

/** 三段区间尺：命中段用强调色描一条 2dp 线，其余留发丝线。 */
@Composable
private fun RowScope.ZoneSegment(
    label: String,
    range: String,
    active: Boolean,
    weight: Float,
) {
    val colors = Dsh.colors
    val type = Dsh.type
    Column(Modifier.weight(weight)) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(if (active) 2.dp else 1.dp)
                .background(if (active) colors.accent else colors.hairline),
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = label,
            style = type.rowTitle,
            color = if (active) colors.ink else colors.inkFaint,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = range,
            style = type.monoSmall,
            color = if (active) colors.accent else colors.inkFaint,
        )
    }
}

@Composable
private fun RowScope.StatCell(label: String, value: Int?, weight: Float) {
    val colors = Dsh.colors
    val type = Dsh.type
    Column(Modifier.weight(weight)) {
        Text(label, style = type.label, color = colors.inkFaint)
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = value?.toString() ?: "—",
                style = type.numberSmall,
                color = colors.ink,
            )
            Spacer(Modifier.width(4.dp))
            Text("bpm", style = type.label, color = colors.inkFaint)
        }
    }
}

@Composable
private fun VHairline() {
    val colors = Dsh.colors
    Box(
        Modifier
            .padding(horizontal = 14.dp)
            .width(1.dp)
            .height(38.dp)
            .background(colors.hairline),
    )
}

@Composable
private fun MetaRow(label: String, value: String) {
    val colors = Dsh.colors
    val type = Dsh.type
    Row(
        Modifier.fillMaxWidth().height(38.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = type.label, color = colors.inkFaint)
        Spacer(Modifier.weight(1f))
        Text(value, style = type.monoSmall, color = colors.inkMuted)
    }
}

/** 主操作：纸本走墨线框，石墨走强调色实底。 */
@Composable
private fun HrPrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = Dsh.colors
    val type = Dsh.type
    val dimens = Dsh.dimens
    val hasPanels = Dsh.surface.hasPanels

    if (hasPanels) {
        Box(
            modifier
                .height(48.dp)
                .clip(RoundedCornerShape(dimens.controlRadius))
                .background(colors.accent)
                .dshClickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Text(text, style = type.title, color = colors.accentInk)
        }
    } else {
        Box(
            modifier
                .height(48.dp)
                .drawBehind {
                    drawRect(color = colors.ink, style = Stroke(1.dp.toPx()))
                }
                .dshClickable(scaleOnPress = false, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Text(text, style = type.title, color = colors.ink)
        }
    }
}

/** 次操作：纸本文字链 + 短下划线，石墨描边胶囊。 */
@Composable
private fun HrSecondaryAction(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = Dsh.colors
    val type = Dsh.type
    val hasPanels = Dsh.surface.hasPanels
    if (hasPanels) {
        Box(modifier, contentAlignment = Alignment.Center) {
            DshGhostButton(text = text, onClick = onClick)
        }
    } else {
        Column(
            modifier.dshClickable(scaleOnPress = false, onClick = onClick),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(text, style = type.rowTitle, color = colors.inkMuted)
            Spacer(Modifier.height(3.dp))
            Box(Modifier.height(1.dp).width(56.dp).background(colors.hairlineStrong))
        }
    }
}

/** 信号强度：三根递增的竖条，按 RSSI 点亮。 */
@Composable
private fun SignalBars(rssi: Int) {
    val colors = Dsh.colors
    val tint = colors.accent
    val idle = colors.inkFaint
    val level = when {
        rssi >= -60 -> 3
        rssi >= -70 -> 2
        rssi >= -80 -> 1
        else -> 0
    }
    Row(verticalAlignment = Alignment.Bottom) {
        for (i in 0 until 3) {
            Box(
                Modifier
                    .padding(start = if (i == 0) 0.dp else 2.dp)
                    .width(2.5.dp)
                    .height((4 + i * 3).dp)
                    .background(if (i < level) tint else idle.copy(alpha = 0.45f)),
            )
        }
    }
}

/**
 * 60 秒实时曲线：三条参考线 + 平滑折线 + 渐变面积 + 末端呼吸点。
 * 值域按数据动态取值，避免贴着上下边。
 */
@Composable
private fun LiveChart(
    values: List<Int>,
    active: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = Dsh.colors
    val accent = colors.accent
    val hairline = colors.hairline
    val faint = colors.inkFaint
    val glow = colors.accentGlow

    val transition = rememberInfiniteTransition(label = "hrPulse")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1100, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "hrPulsePhase",
    )

    Canvas(modifier) {
        val rows = 3
        for (i in 0..rows) {
            val y = size.height - size.height * i / rows
            drawLine(
                color = hairline,
                start = Offset(0f, y),
                end = Offset(size.width, y),
                strokeWidth = 1.dp.toPx(),
            )
        }
        if (values.size < 2) {
            drawLine(
                color = faint.copy(alpha = 0.35f),
                start = Offset(0f, size.height * 0.62f),
                end = Offset(size.width, size.height * 0.62f),
                strokeWidth = 1.dp.toPx(),
            )
            return@Canvas
        }

        val floor = (values.minOrNull() ?: 60) - 6
        val ceil = (values.maxOrNull() ?: 100) + 6
        val span = (ceil - floor).coerceAtLeast(1).toFloat()
        val stepX = size.width / (values.size - 1)
        fun yOf(v: Int): Float {
            val t = ((v - floor) / span).coerceIn(0f, 1f)
            return size.height - t * size.height
        }

        val points = values.mapIndexed { i, v -> Offset(i * stepX, yOf(v)) }
        val line = Path()
        smoothLine(line, points)
        val area = Path()
        smoothLine(area, points)
        area.lineTo(size.width, size.height)
        area.lineTo(0f, size.height)
        area.close()

        drawPath(
            path = area,
            brush = Brush.verticalGradient(
                colors = listOf(glow.copy(alpha = glow.alpha * 0.75f), glow.copy(alpha = 0f)),
                startY = 0f,
                endY = size.height,
            ),
        )
        drawPath(
            path = line,
            color = if (active) accent else faint,
            style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round),
        )

        val last = points.last()
        if (active) {
            drawCircle(
                color = accent.copy(alpha = 0.30f * (1f - phase)),
                radius = (5.dp.toPx() + 7.dp.toPx() * phase),
                center = last,
            )
        }
        drawCircle(color = accent, radius = 3.dp.toPx(), center = last)
    }
}

/** 点一下时小球滑过去的时间；拖动时更短，才跟得上手指。 */
private const val DRAG_FOLLOW_MS = 120

/** 三秒后回末尾的时长：慢一点才看得出是滑回去的。 */
private const val RETURN_MS = 1400

/**
 * 整段会话曲线：三段区间底色带 + 60/100 阈值虚线 + 空档竖线。
 * 值域固定 40–160，这样阈值虚线位置稳定，右缘才能标数。
 */
@Composable
private fun SessionChart(
    values: List<Int>,
    gapIndex: Int,
    pointLabel: (Int) -> String,
    modifier: Modifier = Modifier,
) {
    val colors = Dsh.colors
    val surface = Dsh.surface
    val type = Dsh.type
    val accent = colors.accent
    val faint = colors.inkFaint
    val glow = colors.accentGlow
    val track = colors.hairline

    val lastIndex = (values.size - 1).coerceAtLeast(0)
    var chartSize by remember { mutableStateOf(IntSize.Zero) }
    // 小球位置用「第几个样本」的小数表示：点击滑过去、拖动跟手，都不是瞬移。
    val position = remember { Animatable(lastIndex.toFloat()) }
    var focused by remember { mutableStateOf(false) }
    var touchedAt by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()

    fun indexAt(x: Float): Float {
        val w = chartSize.width.toFloat()
        if (w <= 0f || lastIndex <= 0) return 0f
        return ((x / w) * lastIndex).coerceIn(0f, lastIndex.toFloat())
    }

    fun yFor(v: Float, h: Float): Float {
        val t = ((v - 40f) / 120f).coerceIn(0f, 1f) // 值域固定 40–160
        return h - t * h
    }

    /** 某个（可带小数）下标在画布上的落点，段内按线性插值取心率。 */
    fun pointAt(f: Float): Offset {
        val w = chartSize.width.toFloat()
        val h = chartSize.height.toFloat()
        if (w <= 0f || h <= 0f || lastIndex <= 0) return Offset.Zero
        val ff = f.coerceIn(0f, lastIndex.toFloat())
        val i0 = floor(ff).toInt().coerceIn(0, lastIndex)
        val i1 = (i0 + 1).coerceAtMost(lastIndex)
        val t = ff - i0
        val v = values[i0] + (values[i1] - values[i0]) * t
        return Offset(w * (ff / lastIndex), yFor(v.toFloat(), h))
    }

    // 三秒内没再碰小球，就慢慢滑回末尾；气泡跟着走，一路显示经过位置的心率，到头才淡出。
    LaunchedEffect(focused, touchedAt) {
        if (!focused) return@LaunchedEffect
        delay(3000)
        position.animateTo(lastIndex.toFloat(), tween(RETURN_MS, easing = FastOutSlowInEasing))
        focused = false
    }

    val focusIndex by remember(lastIndex) {
        derivedStateOf { position.value.roundToInt().coerceIn(0, lastIndex) }
    }
    // 回到末尾后才淡出，不是直接消失
    val bubbleAlpha by animateFloatAsState(
        targetValue = if (focused) 1f else 0f,
        animationSpec = tween(240),
        label = "hrBubbleAlpha",
    )

    Box(
        modifier
            .onSizeChanged { chartSize = it }
            .pointerInput(values.size) {
                detectTapGestures { offset ->
                    focused = true
                    touchedAt++
                    scope.launch {
                        position.animateTo(indexAt(offset.x), tween(280, easing = FastOutSlowInEasing))
                    }
                }
            }
            .pointerInput(values.size) {
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        focused = true
                        touchedAt++
                        // 拖动也走动画：每帧从当前位置缓动到手指处，看起来是被拖着走，不是瞬移
                        scope.launch {
                            position.animateTo(
                                indexAt(offset.x),
                                tween(DRAG_FOLLOW_MS, easing = LinearEasing),
                            )
                        }
                    },
                    onDragEnd = { touchedAt++ },
                    onDragCancel = { touchedAt++ },
                    onHorizontalDrag = { change, _ ->
                        change.consume()
                        scope.launch {
                            position.animateTo(
                                indexAt(change.position.x),
                                tween(DRAG_FOLLOW_MS, easing = LinearEasing),
                            )
                        }
                    },
                )
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
        val floor = 40f
        val ceil = 160f
        fun yOf(v: Float): Float {
            val t = ((v - floor) / (ceil - floor)).coerceIn(0f, 1f)
            return size.height - t * size.height
        }

        drawRect(
            color = track,
            topLeft = Offset(0f, yOf(60f)),
            size = Size(size.width, yOf(40f) - yOf(60f)),
        )
        drawRect(
            color = colors.accentDim,
            topLeft = Offset(0f, yOf(100f)),
            size = Size(size.width, yOf(60f) - yOf(100f)),
        )
        drawRect(
            color = colors.accent.copy(alpha = 0.05f),
            topLeft = Offset(0f, yOf(160f)),
            size = Size(size.width, yOf(100f) - yOf(160f)),
        )

        dashHLine(y = yOf(100f), color = faint.copy(alpha = 0.45f), width = 1.dp.toPx())
        dashHLine(y = yOf(60f), color = faint.copy(alpha = 0.45f), width = 1.dp.toPx())

            if (values.size >= 2) {
                val stepX = size.width / lastIndex
                val points = values.mapIndexed { i, v -> Offset(i * stepX, yOf(v.toFloat())) }
                val line = Path()
                smoothLine(line, points)
                drawPath(
                    path = line,
                    color = accent,
                    style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round),
                )

                if (gapIndex in values.indices) {
                    val gx = gapIndex * stepX
                    dashVLine(x = gx, color = faint.copy(alpha = 0.55f), width = 1.dp.toPx())
                    drawCircle(color = faint, radius = 2.dp.toPx(), center = Offset(gx, size.height))
                }

                val ball = pointAt(position.value)
                if (focused) {
                    dashVLine(x = ball.x, color = faint.copy(alpha = 0.4f), width = 1.dp.toPx())
                    drawCircle(color = glow, radius = 10.dp.toPx(), center = ball)
                    drawCircle(color = accent, radius = 4.dp.toPx(), center = ball)
                    drawCircle(color = colors.canvas, radius = 1.4.dp.toPx(), center = ball)
                } else {
                    drawCircle(color = accent, radius = 3.dp.toPx(), center = ball)
                }
            }

            val paint = Paint().apply {
                isAntiAlias = true
                color = faint.toArgb()
                textSize = 9.sp.toPx()
            }
            drawContext.canvas.nativeCanvas.apply {
                drawText("100", size.width - 24.dp.toPx(), yOf(100f) - 4.dp.toPx(), paint)
                drawText("60", size.width - 20.dp.toPx(), yOf(60f) - 4.dp.toPx(), paint)
            }
        }

        // 气泡跟着小球走。offset{} 在布局阶段读位置，滑动时不触发重组。
        if (lastIndex > 0 && (focused || bubbleAlpha > 0.01f)) {
            val bubbleShape = if (surface.hasPanels) RoundedCornerShape(10.dp) else RoundedCornerShape(4.dp)
            val bubbleBg = if (surface.hasPanels) colors.canvasLift else colors.ink
            val bubbleFg = if (surface.hasPanels) colors.ink else colors.canvas
            val bubbleSub = if (surface.hasPanels) colors.inkMuted else colors.canvas.copy(alpha = 0.72f)
            val bubbleW = 104.dp

            Box(
                Modifier
                    .graphicsLayer { alpha = bubbleAlpha }
                    .offset {
                        val ball = pointAt(position.value)
                        val half = bubbleW.toPx() / 2f
                        val maxX = (chartSize.width - bubbleW.toPx()).coerceAtLeast(0f)
                        val above = ball.y - 52.dp.toPx()
                        // 顶部放不下就翻到小球下方，别把「整段曲线」那行压住。
                        val y = if (above >= 0f) above else (ball.y + 16.dp.toPx())
                        IntOffset(
                            (ball.x - half).coerceIn(0f, maxX).roundToInt(),
                            y.roundToInt(),
                        )
                    }
                    .width(bubbleW)
                    .clip(bubbleShape)
                    .background(bubbleBg)
                    .border(
                        1.dp,
                        if (surface.hasPanels) colors.hairlineStrong else Color.Transparent,
                        bubbleShape,
                    )
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            ) {
                Column {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(values[focusIndex].toString(), style = type.rowTitle, color = bubbleFg)
                        Spacer(Modifier.width(3.dp))
                        Text("bpm", style = type.caption, color = bubbleSub)
                    }
                    Text(pointLabel(focusIndex), style = type.monoSmall, color = bubbleSub)
                }
            }
        }
    }
}

private fun DrawScope.dashHLine(y: Float, color: Color, width: Float) {
    val dash = 5.dp.toPx()
    val gap = 5.dp.toPx()
    var x = 0f
    while (x < size.width) {
        val end = (x + dash).coerceAtMost(size.width)
        drawLine(color = color, start = Offset(x, y), end = Offset(end, y), strokeWidth = width)
        x += dash + gap
    }
}

private fun DrawScope.dashVLine(x: Float, color: Color, width: Float) {
    val dash = 5.dp.toPx()
    val gap = 5.dp.toPx()
    var y = 0f
    while (y < size.height) {
        val end = (y + dash).coerceAtMost(size.height)
        drawLine(color = color, start = Offset(x, y), end = Offset(x, end), strokeWidth = width)
        y += dash + gap
    }
}

/** Catmull-Rom 转三次贝塞尔，避免折线在几十个采样点上出现锯齿。 */
private fun smoothLine(path: Path, pts: List<Offset>) {
    if (pts.isEmpty()) return
    path.moveTo(pts[0].x, pts[0].y)
    if (pts.size == 1) return
    for (i in 0 until pts.size - 1) {
        val p0 = pts[(i - 1).coerceAtLeast(0)]
        val p1 = pts[i]
        val p2 = pts[i + 1]
        val p3 = pts[(i + 2).coerceAtMost(pts.size - 1)]
        val c1x = p1.x + (p2.x - p0.x) / 6f
        val c1y = p1.y + (p2.y - p0.y) / 6f
        val c2x = p2.x - (p3.x - p1.x) / 6f
        val c2y = p2.y - (p3.y - p1.y) / 6f
        path.cubicTo(c1x, c1y, c2x, c2y, p2.x, p2.y)
    }
}
