package com.example.hiderecents.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.hiderecents.hr.clockOf
import com.example.hiderecents.hr.hm
import com.example.hiderecents.temp.TempSession
import com.example.hiderecents.ui.components.DshDivider
import com.example.hiderecents.ui.components.DshIcon
import com.example.hiderecents.ui.components.DshIconKind
import com.example.hiderecents.ui.components.DshPanel
import com.example.hiderecents.ui.components.DottedLeader
import com.example.hiderecents.ui.components.dshCanvasBackground
import com.example.hiderecents.ui.components.dshClickable
import com.example.hiderecents.ui.components.paperHeaderRule
import com.example.hiderecents.ui.theme.Dsh
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.floor
import kotlin.math.roundToInt

/** 点一下时小球滑过去的时间；拖动时更短，才跟得上手指。 */
private const val TEMP_DRAG_FOLLOW_MS = 120

/** 三秒后回末尾的时长：慢一点才看得出是滑回去的。 */
private const val TEMP_RETURN_MS = 1400

/** 曲线左右各留一点，末端的小球才不会被裁在边缘。 */
private val CHART_INSET = 6.dp

/**
 * 温度 · 回放。
 *
 * 一次录制的完整曲线：末端小球可点击 / 横向拖动定位，松手 3 秒后滑回末尾，
 * 气泡跟随显示当前温度与对应时刻。上方是峰值与统计，下方是各热区峰值。
 */
@Composable
fun TemperatureReplayScreen(
    session: TempSession,
    onBack: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Dsh.colors
    val dimens = Dsh.dimens
    val type = Dsh.type
    val surface = Dsh.surface
    var confirmDelete by remember { mutableStateOf(false) }
    // 回放也能点热区切换：默认落在录制时跟随的那一路
    var selZone by remember(session.id) {
        mutableStateOf(
            session.primaryLabel.ifEmpty { session.zoneSeries.firstOrNull()?.label ?: "" },
        )
    }
    val shown = session.zoneSeries.firstOrNull { it.label == selZone }?.values ?: session.values
    val shownPeak = shown.maxOrNull() ?: session.peak
    val shownAvg = if (shown.isEmpty()) 0f else shown.sum() / shown.size
    val shownPeakIndex = if (shown.isEmpty()) 0 else shown.indices.maxBy { shown[it] }
    val shownLabel = if (selZone.isEmpty()) session.primaryLabel else selZone

    if (confirmDelete) {
        HrConfirmDialog(
            title = "删除这条录制？",
            detail = hm(session.startedAt) + " · " + clockOf(session.durationSec) + "，删除后不可恢复。",
            note = "",
            confirmText = "删除",
            onCancel = { confirmDelete = false },
            onConfirm = {
                confirmDelete = false
                onDelete()
            },
        )
    }

    val secPerPoint = if (shown.isEmpty()) 1.0 else
        session.durationSec.toDouble() / shown.size
    val peakAtSec = (shownPeakIndex * secPerPoint).roundToInt()

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
                    Text("温度回放", style = type.h2, color = colors.ink)
                    Spacer(Modifier.height(5.dp))
                    Text(
                        text = hm(session.startedAt) + " · " + clockOf(session.durationSec),
                        style = type.label,
                        color = colors.inkFaint,
                    )
                }
                // 删除放在右上角
                Row(
                    Modifier
                        .clip(RoundedCornerShape(dimens.iconRadius))
                        .dshClickable(scaleOnPress = false) { confirmDelete = true }
                        .padding(start = 8.dp, top = 6.dp, bottom = 6.dp, end = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("删除", style = type.label, color = colors.inkMuted)
                }
            }

            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                Spacer(Modifier.height(if (surface.hasPanels) 12.dp else 18.dp))

                // ---------------- 峰值 ----------------
                Text("本次峰值", style = type.label, color = colors.inkFaint)
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = fmt1(shownPeak),
                        style = type.number.copy(fontSize = 56.sp, lineHeight = 56.sp, letterSpacing = (-2.2f).sp),
                        color = colors.ink,
                    )
                    Text(
                        text = "°C",
                        style = type.caption,
                        color = colors.inkMuted,
                        modifier = Modifier.padding(start = 5.dp, bottom = 10.dp),
                    )
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    text = (if (shownLabel.isEmpty()) "" else shownLabel + " · ") + "峰值出现于 " + clockOf(peakAtSec) + " · " + shown.size + " 个采样点",
                    style = type.caption,
                    color = colors.inkMuted,
                )

                Spacer(Modifier.height(16.dp))
                DshPanel(
                    Modifier.fillMaxWidth(),
                    contentPadding = if (surface.hasPanels) PaddingValues(horizontal = 14.dp) else PaddingValues(0.dp),
                ) {
                    StatRow("平均温度", fmt1(shownAvg) + "°")
                    DshDivider()
                    StatRow("录制时长", clockOf(session.durationSec))
                    DshDivider()
                    StatRow("采样数", shown.size.toString())
                }

                Spacer(Modifier.height(20.dp))
                if (!surface.hasPanels) DshDivider()
                Spacer(Modifier.height(14.dp))

                // ---------------- 回放曲线 ----------------
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = if (shownLabel.isEmpty()) "回放曲线" else "回放曲线 · " + shownLabel,
                        style = type.label,
                        color = colors.inkFaint,
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = fmt1(shown.minOrNull() ?: 0f) + " — " + fmt1(shownPeak),
                        style = type.monoSmall,
                        color = colors.inkFaint,
                    )
                }
                Spacer(Modifier.height(8.dp))
                if (shown.size >= 2) {
                    TempSessionChart(
                        values = shown,
                        startedAt = session.startedAt,
                        secPerPoint = secPerPoint,
                        modifier = Modifier.fillMaxWidth().height(170.dp),
                    )
                } else {
                    Box(Modifier.fillMaxWidth().height(170.dp), contentAlignment = Alignment.Center) {
                        Text("采样点太少，无法绘制曲线", style = type.caption, color = colors.inkFaint)
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top = 5.dp, start = CHART_INSET, end = CHART_INSET)) {
                    Text(hm(session.startedAt), style = type.monoSmall, color = colors.inkFaint)
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = hm(session.startedAt + session.durationSec * 1000L),
                        style = type.monoSmall,
                        color = colors.inkFaint,
                    )
                }

                Spacer(Modifier.height(20.dp))
                if (!surface.hasPanels) DshDivider()
                Spacer(Modifier.height(14.dp))

                // ---------------- 各热区峰值 ----------------
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("各热区峰值", style = type.label, color = colors.inkFaint)
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = session.zonePeaks.size.toString() + " 个热区",
                        style = type.monoSmall,
                        color = colors.inkFaint,
                    )
                }
                Spacer(Modifier.height(if (surface.hasPanels) 10.dp else 6.dp))
                if (session.zonePeaks.isNotEmpty()) {
                    DshPanel(
                        Modifier.fillMaxWidth(),
                        contentPadding = if (surface.hasPanels) PaddingValues(horizontal = 14.dp) else PaddingValues(0.dp),
                    ) {
                        session.zonePeaks.forEachIndexed { index, p ->
                            val switchable = session.zoneSeries.any { it.label == p.label }
                            PeakRow(
                                label = p.label,
                                sub = p.sub,
                                value = p.peak,
                                selected = p.label == selZone,
                                onClick = if (switchable) {
                                    { selZone = p.label }
                                } else null,
                            )
                            if (index != session.zonePeaks.lastIndex) DshDivider()
                        }
                    }
                }

                Spacer(Modifier.height(dimens.gapL))

                // ---------------- 操作 ----------------
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(dimens.controlRadius))
                            .dshClickable(onClick = onExport)
                            .padding(horizontal = 20.dp, vertical = 10.dp),
                    ) {
                        Text("导出 CSV", style = type.rowTitle, color = colors.accent)
                    }
                }

                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    val colors = Dsh.colors
    val type = Dsh.type
    val surface = Dsh.surface
    Row(
        Modifier.fillMaxWidth().padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = type.rowTitle, color = colors.ink)
        if (!surface.hasPanels) {
            Spacer(Modifier.width(8.dp))
            DottedLeader(Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
        } else {
            Spacer(Modifier.weight(1f))
        }
        Text(value, style = type.monoSmall, color = colors.ink)
    }
}

@Composable
private fun PeakRow(
    label: String,
    sub: String,
    value: Float,
    selected: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val colors = Dsh.colors
    val type = Dsh.type
    val surface = Dsh.surface
    val hot = value >= 45f
    Row(
        Modifier
            .fillMaxWidth()
            .then(
                if (onClick != null) {
                    Modifier.dshClickable(scaleOnPress = false, onClick = onClick)
                } else {
                    Modifier
                },
            )
            .padding(vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 选中标记（与实时页同一套：实心小方块）
        Box(
            Modifier
                .size(5.dp)
                .clip(RoundedCornerShape(1.dp))
                .background(if (selected) colors.accent else Color.Transparent),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = label,
            style = type.rowTitle,
            color = if (selected) colors.accent else colors.ink,
        )
        Spacer(Modifier.width(8.dp))
        Text(sub, style = type.monoSmall, color = colors.inkFaint)
        if (!surface.hasPanels) {
            Spacer(Modifier.width(8.dp))
            DottedLeader(Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
        } else {
            Spacer(Modifier.weight(1f))
        }
        Text(
            text = fmt1(value) + "°",
            style = type.rowTitle,
            color = if (hot || selected) colors.accent else colors.ink,
            textAlign = TextAlign.End,
            modifier = Modifier.width(64.dp),
        )
    }
}

/**
 * 整段曲线：点状网格 + 平滑折线 + 可拖动定位球。
 *
 * 小球位置用「第几个采样点」的小数表示：点击滑过去、拖动跟手，都不是瞬移；
 * 松手 3 秒内没再碰，就缓慢滑回末尾，气泡跟到末尾后才消失。
 */
@Composable
private fun TempSessionChart(
    values: List<Float>,
    startedAt: Long,
    secPerPoint: Double,
    modifier: Modifier = Modifier,
) {
    val colors = Dsh.colors
    val surface = Dsh.surface
    val type = Dsh.type
    val accent = colors.accent
    val faint = colors.inkFaint
    val glow = colors.accent.copy(alpha = 0.28f)

    val lastIndex = (values.size - 1).coerceAtLeast(1)

    var chartSize by remember { mutableStateOf(IntSize.Zero) }
    val insetPx = with(androidx.compose.ui.platform.LocalDensity.current) { CHART_INSET.toPx() }
    val position = remember(lastIndex) { Animatable(lastIndex.toFloat()) }
    var focused by remember { mutableStateOf(false) }
    var touchedAt by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()

    val lo = (values.minOrNull() ?: 0f) - 0.5f
    val hi = (values.maxOrNull() ?: 1f) + 0.5f
    val span = (hi - lo).coerceAtLeast(1f)

    fun innerWidth(): Float = (chartSize.width - insetPx * 2).coerceAtLeast(1f)

    fun indexAt(x: Float): Float {
        val w = chartSize.width.toFloat()
        if (w <= 0f) return 0f
        val rel = (x - insetPx).coerceIn(0f, innerWidth())
        return (rel / innerWidth() * lastIndex).coerceIn(0f, lastIndex.toFloat())
    }

    fun yForWidget(v: Float): Float {
        val h = chartSize.height.toFloat()
        if (h <= 0f) return 0f
        val t = ((v - lo) / span).coerceIn(0f, 1f)
        return h - t * h
    }

    fun pointAt(f: Float): Offset {
        if (chartSize.width <= 0 || chartSize.height <= 0) return Offset.Zero
        val ff = f.coerceIn(0f, lastIndex.toFloat())
        val i0 = floor(ff).toInt().coerceIn(0, lastIndex)
        val i1 = (i0 + 1).coerceAtMost(lastIndex)
        val t = ff - i0
        val v = values[i0] + (values[i1] - values[i0]) * t
        return Offset(
            insetPx + innerWidth() * (ff / lastIndex),
            yForWidget(v),
        )
    }

    // 三秒内没再碰小球，就慢慢滑回末尾；气泡跟到末尾才淡出。
    LaunchedEffect(focused, touchedAt) {
        if (!focused) return@LaunchedEffect
        delay(3000)
        position.animateTo(lastIndex.toFloat(), tween(TEMP_RETURN_MS, easing = FastOutSlowInEasing))
        focused = false
    }

    val focusIndex by remember(lastIndex) {
        derivedStateOf { position.value.roundToInt().coerceIn(0, lastIndex) }
    }

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
                                tween(TEMP_DRAG_FOLLOW_MS, easing = LinearEasing),
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
                                tween(TEMP_DRAG_FOLLOW_MS, easing = LinearEasing),
                            )
                        }
                    },
                )
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val h = size.height
            val inset = CHART_INSET.toPx()
            val w = size.width - inset * 2

            fun yOf(v: Float): Float {
                val t = ((v - lo) / span).coerceIn(0f, 1f)
                return h - t * h
            }

            // 点状网格
            val dotR = 0.7.dp.toPx()
            for (g in 1..3) {
                val y = h * g / 4f
                var x = inset + 4.dp.toPx()
                while (x < size.width - inset) {
                    drawCircle(color = faint.copy(alpha = 0.35f), radius = dotR, center = Offset(x, y))
                    x += 12.dp.toPx()
                }
            }

            val stepX = w / lastIndex
            val pts = values.mapIndexed { i, v -> Offset(inset + i * stepX, yOf(v)) }
            val path = Path()
            smoothPath(path, pts)
            // 曲线下方强调色渐变（与心率页同款）
            val area = Path()
            smoothPath(area, pts)
            area.lineTo(pts.last().x, h)
            area.lineTo(pts.first().x, h)
            area.close()
            drawPath(
                path = area,
                brush = Brush.verticalGradient(
                    colors = listOf(
                        colors.accentGlow.copy(alpha = colors.accentGlow.alpha * 0.75f),
                        colors.accentGlow.copy(alpha = 0f),
                    ),
                    startY = 0f,
                    endY = h,
                ),
            )
            drawPath(
                path = path,
                color = colors.ink.copy(alpha = 0.9f),
                style = Stroke(width = 1.3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
            )

            val ball = Offset(inset + w * (position.value / lastIndex), yOf(valuesAt(values, position.value)))
            if (focused) {
                dashVLine(x = ball.x, color = faint.copy(alpha = 0.4f), width = 1.dp.toPx())
                drawCircle(color = glow, radius = 9.dp.toPx(), center = ball)
                drawCircle(color = accent, radius = 4.dp.toPx(), center = ball)
                drawCircle(color = colors.canvas, radius = 1.4.dp.toPx(), center = ball)
            } else {
                drawCircle(color = accent, radius = 2.6.dp.toPx(), center = ball)
            }
        }

        // 气泡跟着小球走。offset{} 在布局阶段读位置，滑动时不触发重组。
        if (focused) {
            val bubbleShape = if (surface.hasPanels) RoundedCornerShape(10.dp) else RoundedCornerShape(4.dp)
            val bubbleW = 104.dp
            Box(
                Modifier
                    .offset {
                        val ball = pointAt(position.value)
                        val half = bubbleW.toPx() / 2f
                        val maxX = (chartSize.width - bubbleW.toPx()).coerceAtLeast(0f)
                        val above = ball.y - 52.dp.toPx()
                        val y = if (above >= 0f) above else ball.y + 16.dp.toPx()
                        IntOffset(
                            (ball.x - half).coerceIn(0f, maxX).roundToInt(),
                            y.roundToInt(),
                        )
                    }
                    .width(bubbleW)
                    .clip(bubbleShape)
                    .background(colors.canvasLift)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            ) {
                Column {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            text = fmt1(values[focusIndex]),
                            style = type.rowTitle,
                            color = colors.ink,
                        )
                        Spacer(Modifier.width(3.dp))
                        Text("°C", style = type.caption, color = colors.inkMuted)
                    }
                    Text(
                        text = hm(startedAt + (focusIndex * secPerPoint).toLong() * 1000L),
                        style = type.monoSmall,
                        color = colors.inkMuted,
                    )
                }
            }
        }
    }
}

/** 采样点之间的线性插值取值。 */
private fun valuesAt(values: List<Float>, f: Float): Float {
    if (values.isEmpty()) return 0f
    val last = values.lastIndex
    val ff = f.coerceIn(0f, last.toFloat())
    val i0 = floor(ff).toInt().coerceIn(0, last)
    val i1 = (i0 + 1).coerceAtMost(last)
    val t = ff - i0
    return values[i0] + (values[i1] - values[i0]) * t
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