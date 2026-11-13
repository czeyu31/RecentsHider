package com.example.hiderecents

import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.example.hiderecents.ui.screens.HeartRateHistoryScreen
import com.example.hiderecents.ui.screens.HeartRateReportScreen
import com.example.hiderecents.ui.screens.HeartRateScreen
import com.example.hiderecents.ui.screens.HrHistoryItem
import com.example.hiderecents.ui.screens.HrZoneSlice
import com.example.hiderecents.ui.theme.AppTheme
import com.example.hiderecents.ui.theme.DshTheme
import com.example.hiderecents.ui.theme.applyDshSystemBars
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 心率广播 · 页面预览（尚未接入工具箱）。
 *
 * 主题跟随软件自身的设置（hide_recents_prefs / ui_theme），页内不提供切换入口。
 * 三个屏：实时（录制） / 录制历史 / 会话报告。
 * 交互与最终形态一致：开始录制 -> 停止录制 -> 存入历史并打开报告；右上角「录制历史」可看以往每次。
 * 当前数据为预览假数据，未接入真实 BLE，也不落盘。
 *
 * adb 抓图可用 --ei preview_screen 1(历史) / 2(历史第一条的报告) 直达。
 */
class HeartRatePreviewActivity : DshActivity() {

    private enum class Screen { LIVE, HISTORY, REPORT }

    private var theme by mutableStateOf(AppTheme.PAPER)
    private var screen by mutableStateOf(Screen.LIVE)
    private var reportFromHistory by mutableStateOf(false)
    private var recording by mutableStateOf(false)
    private var elapsedSec by mutableStateOf(0)
    private var phase by mutableStateOf(0)
    private var recPoints by mutableStateOf(0)
    private var recStartedAt by mutableStateOf(0L)
    private var sessions by mutableStateOf<List<PreviewSession>>(emptyList())
    private var opened by mutableStateOf<PreviewSession?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences("hide_recents_prefs", Context.MODE_PRIVATE)
        theme = AppTheme.fromPref(prefs.getString("ui_theme", null))
        applyDshSystemBars(theme)

        sessions = sampleSessions()
        when (intent?.getIntExtra("preview_screen", 0) ?: 0) {
            1 -> screen = Screen.HISTORY
            2 -> {
                opened = sessions.firstOrNull()
                reportFromHistory = true
                screen = Screen.REPORT
            }
        }

        setContent {
            DshTheme(theme) {
                LaunchedEffect(recording) {
                    while (recording) {
                        delay(1000)
                        elapsedSec += 1
                        phase += 1
                        recPoints += 1
                    }
                }

                val session = opened
                when {
                    screen == Screen.REPORT && session != null -> Report(session)
                    screen == Screen.HISTORY -> History()
                    else -> Live()
                }
            }
        }
    }

    override fun onDshBackPressed() {
        when (screen) {
            Screen.REPORT -> screen = if (reportFromHistory) Screen.HISTORY else Screen.LIVE
            Screen.HISTORY -> screen = Screen.LIVE
            Screen.LIVE -> super.onDshBackPressed()
        }
    }

    @androidx.compose.runtime.Composable
    private fun Live() {
        val values = remember(phase) { rotateCurve(LIVE_CURVE, phase) }
        HeartRateScreen(
            bpm = values.last(),
            values = values,
            deviceName = "Xiaomi Smart Band 8",
            deviceMac = "C8:4B:D6:1A:2F:90",
            rssi = -62,
            packets = 142 + recPoints * 2,
            dropRate = "2.1%",
            sessionText = if (elapsedSec > 0) clockOf(elapsedSec) else "—",
            ageText = "3 秒前",
            sourceLabel = "广播 0x180D",
            recording = recording,
            recTime = clockOf(elapsedSec),
            onBack = { dshGoBack() },
            onOpenHistory = { screen = Screen.HISTORY },
            onToggleRecording = {
                if (recording) {
                    recording = false
                    // 预览：把这次录制存成一条（曲线用示例形态，时长用真实计时）
                    val dur = elapsedSec.coerceAtLeast(10)
                    val s = PreviewSession(
                        id = System.currentTimeMillis(),
                        startedAt = recStartedAt,
                        durationSec = dur,
                        values = SESSION_CURVE,
                        gapIndex = 24,
                    )
                    sessions = listOf(s) + sessions
                    opened = s
                    reportFromHistory = false
                    screen = Screen.REPORT
                } else {
                    elapsedSec = 0
                    recPoints = 0
                    recStartedAt = System.currentTimeMillis()
                    recording = true
                }
            },
            onChangeDevice = {},
            onOpenSettings = {},
        )
    }

    @androidx.compose.runtime.Composable
    private fun History() {
        val weekAgo = System.currentTimeMillis() - 7 * 86_400_000L
        val week = sessions.filter { it.startedAt >= weekAgo }
        val allValues = week.flatMap { it.values }
        HeartRateHistoryScreen(
            items = sessions.map { it.toItem() },
            weekCount = week.size,
            weekDuration = hoursOf(week.sumOf { it.durationSec }),
            weekAvg = if (allValues.isEmpty()) null else allValues.average().roundToInt(),
            onBack = { screen = Screen.LIVE },
            onOpen = { item ->
                opened = sessions.firstOrNull { it.id == item.id }
                reportFromHistory = true
                screen = Screen.REPORT
            },
            onClearAll = {
                sessions = emptyList()
                opened = null
            },
        )
    }

    @androidx.compose.runtime.Composable
    private fun Report(s: PreviewSession) {
        val values = s.values
        val secPerPoint = (s.durationSec.toDouble() / values.size.coerceAtLeast(1)).coerceAtLeast(1.0)
        val counts = listOf(0, 1, 2).map { z -> values.count { zoneOfValue(it) == z } }
        val percents = sharePercents(counts)
        val zones = listOf(0, 1, 2).map { z ->
            HrZoneSlice(
                label = ZONE_LABELS[z].first,
                range = ZONE_LABELS[z].second,
                percent = percents[z],
                duration = clockOf((counts[z] * secPerPoint).roundToInt()),
            )
        }
        val end = s.startedAt + s.durationSec * 1000L
        val mid = s.startedAt + s.durationSec * 500L
        HeartRateReportScreen(
            periodText = dayOf(s.startedAt) + " " + hm(s.startedAt) + " – " + hm(end),
            durationText = clockOf(s.durationSec),
            avg = values.average().roundToInt(),
            min = values.minOrNull() ?: 0,
            max = values.maxOrNull() ?: 0,
            samples = values.size,
            completeness = if (s.gapIndex >= 0) "97.9%" else "100%",
            values = values,
            zones = zones,
            analysis = analysisOf(s, secPerPoint),
            gapIndex = s.gapIndex,
            axisStart = hm(s.startedAt),
            axisMid = hm(mid),
            axisEnd = hm(end),
            onBack = { screen = if (reportFromHistory) Screen.HISTORY else Screen.LIVE },
            onDelete = {
                sessions = sessions.filterNot { it.id == s.id }
                opened = null
                screen = if (reportFromHistory) Screen.HISTORY else Screen.LIVE
                toast("已删除这次会话")
            },
            onExportCsv = { toast("预览版：CSV 导出在接入工具箱后启用") },
            onShareChart = { toast("预览版：曲线图分享在接入工具箱后启用") },
        )
    }

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }
}

// ---------------------------------------------------------------------------
// 预览数据
// ---------------------------------------------------------------------------

private data class PreviewSession(
    val id: Long,
    val startedAt: Long,
    val durationSec: Int,
    val values: List<Int>,
    val gapIndex: Int = -1,
)

private fun PreviewSession.toItem(): HrHistoryItem {
    val end = startedAt + durationSec * 1000L
    return HrHistoryItem(
        id = id,
        day = dayOf(startedAt),
        timeRange = hm(startedAt) + " – " + hm(end),
        duration = clockOf(durationSec),
        samples = values.size,
        avg = values.average().roundToInt(),
        min = values.minOrNull() ?: 0,
        max = values.maxOrNull() ?: 0,
        values = values,
    )
}

/** 几条不同形态的示例会话：日常带峰值 / 跑步 / 安静 / 骑行。 */
private fun sampleSessions(): List<PreviewSession> {
    fun at(daysAgo: Int, hour: Int, minute: Int): Long = Calendar.getInstance().apply {
        add(Calendar.DAY_OF_YEAR, -daysAgo)
        set(Calendar.HOUR_OF_DAY, hour)
        set(Calendar.MINUTE, minute)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    fun wave(n: Int, base: Double, amp: Double, period: Double, warm: Int = 0): List<Int> =
        (0 until n).map { i ->
            val ramp = if (warm > 0 && i < warm) (i.toDouble() / warm) else 1.0
            val v = 66 + (base - 66) * ramp + amp * sin(i / period) + ((i * 37) % 5 - 2)
            v.roundToInt().coerceIn(45, 190)
        }

    return listOf(
        PreviewSession(0L, at(0, 19, 2), 700, SESSION_CURVE, gapIndex = 24),
        PreviewSession(0L, at(0, 7, 18), 1920, wave(96, 142.0, 8.0, 9.0, warm = 14)),
        PreviewSession(0L, at(1, 22, 40), 480, wave(48, 62.0, 3.0, 6.0)),
        PreviewSession(0L, at(1, 12, 5), 900, wave(60, 82.0, 9.0, 7.0)),
        PreviewSession(0L, at(3, 18, 30), 3120, wave(104, 118.0, 14.0, 12.0, warm = 18)),
    ).map { it.copy(id = it.startedAt) }
}

private val LIVE_CURVE = listOf(
    61, 62, 64, 63, 62, 60, 61, 63, 66, 68,
    67, 65, 64, 66, 69, 72, 75, 79, 84, 88,
    91, 93, 92, 89, 85, 81, 78, 76, 74, 72,
    71, 70, 69, 70, 72, 71, 69, 68, 67, 66,
)

private val ZONE_LABELS = listOf(
    "静息" to "< 60",
    "日常" to "60 – 100",
    "燃脂" to "100 +",
)

private val SESSION_CURVE: List<Int> = run {
    val out = mutableListOf<Int>()
    out += 58
    out += 57
    out += 58
    out += 59
    val wobble = listOf(0, 1, 2, 3, 4, 3, 2, 1, 0, -1, -2, -1)
    for (i in 0 until 36) {
        out += (62 + wobble[i % wobble.size] + (i / 8)).coerceIn(58, 76)
    }
    out += listOf(84, 96, 112, 126, 134, 131, 120, 106, 98, 93, 90, 88, 86, 84)
    for (i in 0 until 16) out += 79 + (i % 4)
    out.toList()
}

/** 纯描述的分析：只说测到了什么，不做推断。 */
private fun analysisOf(s: PreviewSession, secPerPoint: Double): List<String> {
    val v = s.values
    if (v.size < 10) return listOf("样本不足 10 个，无法生成分析。")
    val avg = v.average().roundToInt()
    val max = v.maxOrNull() ?: 0
    val min = v.minOrNull() ?: 0
    val peakAt = v.indexOf(max)
    val back = v.drop(peakAt).indexOfFirst { it < 100 }
    val tail = v.takeLast((v.size / 6).coerceAtLeast(3))
    val lines = mutableListOf<String>()
    lines += "全程平均 $avg bpm，最低 $min，最高 $max。"
    val peakText = "最高 $max 出现在第 " + clockOf((peakAt * secPerPoint).roundToInt())
    lines += when {
        max < 100 -> peakText + "，全程未超过 100。"
        back > 0 -> peakText + "，约 " + (back * secPerPoint).roundToInt() + " 秒后回落到 100 以下。"
        else -> peakText + "，之后一直在 100 以上。"
    }
    lines += "末段 " + (tail.minOrNull() ?: 0) + "–" + (tail.maxOrNull() ?: 0) +
        " bpm" + if (s.gapIndex >= 0) "，中途断流一次（图中竖虚线）。" else "，全程无断流。"
    return lines
}

/** 三段占比：最大余数法，保证三项相加正好 100，且不给 0 计数的区间分配 1%。 */
private fun sharePercents(counts: List<Int>): List<Int> {
    val total = counts.sum().coerceAtLeast(1)
    val exact = counts.map { it * 100.0 / total }
    val out = exact.map { it.toInt() }.toMutableList()
    var rest = 100 - out.sum()
    val order = exact.indices.sortedByDescending { exact[it] - exact[it].toInt() }
    for (k in order) {
        if (rest <= 0) break
        if (counts[k] <= 0) continue
        out[k] += 1
        rest -= 1
    }
    return out
}

private fun zoneOfValue(bpm: Int): Int = when {
    bpm < 60 -> 0
    bpm < 100 -> 1
    else -> 2
}

private fun rotateCurve(src: List<Int>, n: Int): List<Int> {
    if (src.isEmpty()) return src
    val k = ((n % src.size) + src.size) % src.size
    return src.drop(k) + src.take(k)
}

private fun clockOf(sec: Int): String {
    val m = sec / 60
    val s = sec % 60
    val mm = if (m < 10) "0" + m else m.toString()
    val ss = if (s < 10) "0" + s else s.toString()
    return mm + ":" + ss
}

private fun hoursOf(sec: Int): String {
    val h = sec / 3600
    val m = (sec % 3600) / 60
    return if (h > 0) h.toString() + "h" + (if (m < 10) "0" else "") + m else m.toString() + "m"
}

private fun hm(ts: Long): String = SimpleDateFormat("HH:mm", Locale.CHINA).format(Date(ts))

private fun dayOf(ts: Long): String {
    val now = Calendar.getInstance()
    val then = Calendar.getInstance().apply { timeInMillis = ts }
    if (now.get(Calendar.YEAR) != then.get(Calendar.YEAR)) {
        return SimpleDateFormat("yyyy年M月d日", Locale.CHINA).format(Date(ts))
    }
    return when (now.get(Calendar.DAY_OF_YEAR) - then.get(Calendar.DAY_OF_YEAR)) {
        0 -> "今天"
        1 -> "昨天"
        else -> SimpleDateFormat("M月d日 E", Locale.CHINA).format(Date(ts))
    }
}
