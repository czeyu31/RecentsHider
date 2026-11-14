package com.example.hiderecents.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.hiderecents.temp.TempZone
import com.example.hiderecents.ui.components.DshDivider
import com.example.hiderecents.ui.components.DshIcon
import com.example.hiderecents.ui.components.DshIconKind
import com.example.hiderecents.ui.components.DshPanel
import com.example.hiderecents.ui.components.DottedLeader
import com.example.hiderecents.ui.components.dshCanvasBackground
import com.example.hiderecents.ui.components.dshClickable
import com.example.hiderecents.ui.components.paperHeaderRule
import com.example.hiderecents.ui.theme.Dsh
import java.util.Locale

/** 界面上的一个热区行（已格式化）。selected = 当前大数字与曲线正在跟随这一路。 */
data class TempZoneRow(
    val label: String,
    val sub: String,
    val value: Float,
    val selected: Boolean = false,
) {
    val text: String get() = String.format(Locale.US, "%.1f°", value)
}

/**
 * 温度 · 实时。
 *
 * 结构与心率页同构：页头（返回 / 标题 / 录制历史 + 播放键）→ 主读数 → 曲线 → 各热区读数 → 刷新频率。
 * 纸本用横线与点状引导线分隔，石墨用面板；录制中页头右侧换成 方块 + REC + 时长。
 */
@Composable
fun TemperatureScreen(
    head: Float?,
    headLabel: String?,
    zones: List<TempZoneRow>,
    live: List<Float>,
    recording: Boolean,
    recTime: String,
    intervalLabel: String,
    sampleIntervalMs: Long,
    onBack: () -> Unit,
    onOpenHistory: () -> Unit,
    onToggleRecording: () -> Unit,
    onCycleInterval: () -> Unit,
    onSelectZone: (String) -> Unit,
    statusLine: String = "",
    statusIsError: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val colors = Dsh.colors
    val dimens = Dsh.dimens
    val type = Dsh.type
    val surface = Dsh.surface

    val hasData = head != null
    val who = headLabel ?: "最高温区"
    // 「较 1 分钟前」的差值：按真实采样间隔回看一分钟，而不是固定回看 20 个样本
    val back = (60_000L / sampleIntervalMs.coerceAtLeast(1L)).toInt().coerceAtLeast(1)
    val delta = if (live.size >= 2) live.last() - live[maxOf(0, live.size - 1 - back)] else 0f
    val hot = head != null && head >= 40f
    val veryHot = head != null && head >= 45f

    Box(modifier.fillMaxSize().dshCanvasBackground()) {
        Column(
            Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(horizontal = dimens.screenPadding),
        ) {
            Spacer(Modifier.height(4.dp))
            TempHeader(
                recording = recording,
                recTime = recTime,
                onBack = onBack,
                onOpenHistory = onOpenHistory,
                onToggleRecording = onToggleRecording,
            )

            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                Spacer(Modifier.height(if (surface.hasPanels) 12.dp else 18.dp))

                // ---------------- 主读数 ----------------
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                    Box(contentAlignment = Alignment.BottomStart) {
                        Text(
                            text = if (hasData) fmt1(head!!) else "—",
                            style = type.number.copy(fontSize = 64.sp, lineHeight = 64.sp, letterSpacing = (-2.4f).sp),
                            color = if (hasData) colors.ink else colors.inkFaint,
                        )
                    }
                    Text(
                        text = "°C",
                        style = type.caption,
                        color = colors.inkMuted,
                        modifier = Modifier.padding(start = 5.dp, bottom = 12.dp),
                    )
                    Spacer(Modifier.weight(1f))
                    Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(bottom = 8.dp)) {
                        Text(
                            text = if (veryHot) "偏高" else if (hot) "偏热" else if (hasData) "稳定" else "无数据",
                            style = type.label,
                            color = if (hot) colors.accent else colors.inkFaint,
                        )
                        Spacer(Modifier.height(7.dp))
                        Text(
                            text = if (hasData) signOf(delta) + fmt1(kotlin.math.abs(delta)) + "° / 分钟" else "—",
                            style = type.monoSmall,
                            color = colors.inkFaint,
                        )
                    }
                }

                Spacer(Modifier.height(10.dp))
                Text(
                    text = when {
                        statusLine.isNotEmpty() -> statusLine
                        !hasData -> "需要 Shizuku 授权才能读取 /sys/class/thermal"
                        veryHot -> who + "温度较高，建议暂停高负载任务"
                        hot -> who + "偏热，注意散热"
                        else -> who + "运行正常"
                    },
                    style = type.caption,
                    color = if (statusIsError || veryHot) colors.accent else colors.inkMuted,
                )

                Spacer(Modifier.height(18.dp))
                if (!surface.hasPanels) DshDivider()

                // ---------------- 曲线 ----------------
                Spacer(Modifier.height(if (surface.hasPanels) 8.dp else 14.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("近 120 秒", style = type.label, color = colors.inkFaint)
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = if (live.size >= 2) {
                            fmt1(live.minOrNull() ?: 0f) + " — " + fmt1(live.maxOrNull() ?: 0f)
                        } else "—",
                        style = type.monoSmall,
                        color = colors.inkFaint,
                    )
                }
                Spacer(Modifier.height(10.dp))
                LiveChart(values = live, modifier = Modifier.fillMaxWidth().height(126.dp))

                Spacer(Modifier.height(18.dp))
                if (!surface.hasPanels) DshDivider()
                Spacer(Modifier.height(if (surface.hasPanels) 6.dp else 14.dp))

                // ---------------- 各热区 ----------------
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("热区", style = type.label, color = colors.inkFaint)
                    Spacer(Modifier.weight(1f))
                    Text(zones.size.toString() + " 个可读", style = type.monoSmall, color = colors.inkFaint)
                }
                Spacer(Modifier.height(if (surface.hasPanels) 10.dp else 6.dp))

                if (zones.isEmpty()) {
                    Text(
                        "没有读到任何热区。请确认 Shizuku 已授权，然后下拉重试。",
                        style = type.body,
                        color = colors.inkMuted,
                    )
                } else {
                    DshPanel(
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = if (surface.hasPanels) PaddingValues(horizontal = 14.dp) else PaddingValues(0.dp),
                    ) {
                        zones.forEachIndexed { index, z ->
                            ZoneRowItem(z, onClick = { onSelectZone(z.label) })
                            if (index != zones.lastIndex) {
                                DshDivider()
                            }
                        }
                    }
                }

                Spacer(Modifier.height(dimens.gapL))

                // ---------------- 刷新频率 ----------------
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    if (surface.hasPanels) {
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(dimens.controlRadius))
                                .border(1.dp, colors.hairlineStrong, RoundedCornerShape(dimens.controlRadius))
                                .dshClickable(onClick = onCycleInterval)
                                .padding(horizontal = 20.dp, vertical = 10.dp),
                        ) {
                            Text(
                                text = "刷新频率 " + intervalLabel,
                                style = type.rowTitle,
                                color = colors.inkMuted,
                            )
                        }
                    } else {
                        Column(
                            Modifier.dshClickable(onClick = onCycleInterval).padding(vertical = 4.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text("刷新频率 " + intervalLabel, style = type.rowTitle, color = colors.accent)
                            Spacer(Modifier.height(3.dp))
                            Box(Modifier.height(1.5.dp).width(96.dp).background(color = colors.accent))
                        }
                    }
                }

                Spacer(Modifier.height(dimens.gapL))
                Text(
                    text = "读数来自 /sys/class/thermal，本机直接读取（不可读时自动走 Shizuku 兜底）；录制后可在历史里回放曲线。",
                    style = type.caption,
                    color = colors.inkFaint,
                )
                Spacer(Modifier.height(20.dp))
            }
        }
    }
}

@Composable
private fun TempHeader(
    recording: Boolean,
    recTime: String,
    onBack: () -> Unit,
    onOpenHistory: () -> Unit,
    onToggleRecording: () -> Unit,
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
            Text("温度", style = type.h2, color = colors.ink)
            Spacer(Modifier.height(5.dp))
            Text("系统热区 · 实时", style = type.label, color = colors.inkFaint)
        }
        Spacer(Modifier.width(8.dp))

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
            Spacer(Modifier.width(10.dp))
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
            Spacer(Modifier.width(10.dp))
        }

        Box(
            Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(dimens.iconRadius))
                .background(colors.accentDim)
                .border(1.dp, colors.accentBorder, RoundedCornerShape(dimens.iconRadius))
                .dshClickable(onClick = onToggleRecording),
            contentAlignment = Alignment.Center,
        ) {
            DshIcon(
                kind = if (recording) DshIconKind.STOP else DshIconKind.PLAY,
                iconSize = 14.dp,
                tint = colors.accent,
            )
        }
    }
}

@Composable
private fun ZoneRowItem(z: TempZoneRow, onClick: () -> Unit) {
    val colors = Dsh.colors
    val type = Dsh.type
    val surface = Dsh.surface
    val hot = z.value >= 45f

    Row(
        Modifier
            .fillMaxWidth()
            .dshClickable(scaleOnPress = false, onClick = onClick)
            .padding(vertical = if (surface.hasPanels) 13.dp else 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
    ) {
        // 选中标记：实心方块（与录制中的 REC 方块同一套语言）
        Box(
            Modifier
                .size(5.dp)
                .clip(RoundedCornerShape(1.dp))
                .background(if (z.selected) colors.accent else Color.Transparent),
        )
        Spacer(Modifier.width(8.dp))
        Column {
            Text(
                text = z.label,
                style = type.rowTitle,
                color = if (z.selected) colors.accent else colors.ink,
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(z.sub, style = type.monoSmall, color = colors.inkFaint)
        if (!surface.hasPanels) {
            Spacer(Modifier.width(8.dp))
            DottedLeader(Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
        } else {
            Spacer(Modifier.weight(1f))
        }
        Text(
            text = z.text,
            style = type.rowTitle.copy(fontWeight = FontWeight.Medium),
            color = if (hot || z.selected) colors.accent else colors.ink,
            textAlign = TextAlign.End,
            modifier = Modifier.width(64.dp),
        )
    }
}

/** 实时曲线：点状网格 + 平滑折线 + 末端强调点。 */
@Composable
private fun LiveChart(values: List<Float>, modifier: Modifier = Modifier) {
    val colors = Dsh.colors
    if (values.size < 2) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Text("等待采样", style = Dsh.type.caption, color = colors.inkFaint)
        }
    } else {
        val lo = (values.minOrNull() ?: 0f) - 0.5f
        val hi = (values.maxOrNull() ?: 1f) + 0.5f
        val span = (hi - lo).coerceAtLeast(1f)
        Canvas(modifier) {
        val w = size.width
        val h = size.height
        val padT = 8.dp.toPx()
        val padB = 6.dp.toPx()

        // 点状网格
        val dotR = 0.7.dp.toPx()
        for (g in 1..3) {
            val y = padT + (h - padT - padB) * g / 4f
            var x = 4.dp.toPx()
            while (x < w) {
                drawCircle(color = colors.inkFaint.copy(alpha = 0.35f), radius = dotR, center = Offset(x, y))
                x += 12.dp.toPx()
            }
        }

        val stepX = w / (values.size - 1)
        val pts = values.mapIndexed { i, v ->
            Offset(i * stepX, padT + (h - padT - padB) * (1f - (v - lo) / span))
        }
        val path = Path()
        smoothPath(path, pts)
        // 曲线下方强调色渐变（与心率页同款）
        val area = Path()
        smoothPath(area, pts)
        area.lineTo(pts.last().x, h - padB)
        area.lineTo(pts.first().x, h - padB)
        area.close()
        drawPath(
            path = area,
            brush = Brush.verticalGradient(
                colors = listOf(
                    colors.accentGlow.copy(alpha = colors.accentGlow.alpha * 0.75f),
                    colors.accentGlow.copy(alpha = 0f),
                ),
                startY = padT,
                endY = h - padB,
            ),
        )
        drawPath(
            path = path,
            color = colors.ink.copy(alpha = 0.9f),
            style = Stroke(width = 1.3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
        )

        val last = pts.last()
        drawCircle(color = colors.accent.copy(alpha = 0.3f), radius = 6.dp.toPx(), center = last)
        drawCircle(color = colors.accent, radius = 2.6.dp.toPx(), center = last)
        }
    }
}

/** Catmull-Rom 转三次贝塞尔，避免折线在采样点上出现锯齿。 */
internal fun smoothPath(path: Path, pts: List<Offset>) {
    if (pts.isEmpty()) return
    path.moveTo(pts[0].x, pts[0].y)
    if (pts.size == 1) return
    for (i in 0 until pts.size - 1) {
        val p0 = pts[(i - 1).coerceAtLeast(0)]
        val p1 = pts[i]
        val p2 = pts[i + 1]
        val p3 = pts[(i + 2).coerceAtMost(pts.size - 1)]
        path.cubicTo(
            p1.x + (p2.x - p0.x) / 6f,
            p1.y + (p2.y - p0.y) / 6f,
            p2.x - (p3.x - p1.x) / 6f,
            p2.y - (p3.y - p1.y) / 6f,
            p2.x,
            p2.y,
        )
    }
}

internal fun fmt1(v: Float): String = String.format(Locale.US, "%.1f", v)

internal fun signOf(v: Float): String = if (v >= 0f) "+" else "−"