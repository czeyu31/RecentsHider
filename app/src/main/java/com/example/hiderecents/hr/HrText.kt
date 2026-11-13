package com.example.hiderecents.hr

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/** 心率模块共用的格式化与统计：时间、时长、区间划分、占比、分析文案。 */

fun clockOf(sec: Int): String {
    val m = sec / 60
    val s = sec % 60
    val mm = if (m < 10) "0$m" else m.toString()
    val ss = if (s < 10) "0$s" else s.toString()
    return "$mm:$ss"
}

fun hoursOf(sec: Int): String {
    val h = sec / 3600
    val m = (sec % 3600) / 60
    return if (h > 0) h.toString() + "h" + (if (m < 10) "0" else "") + m else m.toString() + "m"
}

fun hm(ts: Long): String = SimpleDateFormat("HH:mm", Locale.CHINA).format(Date(ts))

/** 带秒的时钟：短会话的曲线轴用它，不然三段刻度的分钟数会重复。 */
fun hms(ts: Long): String = SimpleDateFormat("HH:mm:ss", Locale.CHINA).format(Date(ts))

/** 列表里的日期分组标题：今天 / 昨天 / 11月10日 周二。 */
fun dayOf(ts: Long): String {
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

/** 0 = 静息，1 = 日常，2 = 燃脂。两条分界线是 60 和 100。 */
fun zoneOfValue(bpm: Int): Int = when {
    bpm < 60 -> 0
    bpm < 100 -> 1
    else -> 2
}

/** 三段占比：最大余数法，保证三项相加正好 100，且不给 0 计数的区间分配 1%。 */
fun sharePercents(counts: List<Int>): List<Int> {
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

/** 抽稀到最多 max 个点，保留曲线形态（等间隔取样）。 */
fun downsample(values: List<Int>, max: Int): List<Int> {
    if (values.size <= max) return values.toList()
    val step = values.size.toDouble() / max
    return (0 until max).map { i -> values[(i * step).toInt().coerceAtMost(values.lastIndex)] }
}

/** 纯描述的分析：只说测到了什么，不做推断。 */
fun analysisOf(values: List<Int>, secPerPoint: Double, gapIndex: Int): List<String> {
    if (values.size < 10) return listOf("样本不足 10 个，无法生成分析。")
    val avg = values.average().roundToInt()
    val max = values.maxOrNull() ?: 0
    val min = values.minOrNull() ?: 0
    val peakAt = values.indexOf(max)
    val back = values.drop(peakAt).indexOfFirst { it < 100 }
    val tail = values.takeLast((values.size / 6).coerceAtLeast(3))
    val lines = mutableListOf<String>()
    lines += "全程平均 $avg bpm，最低 $min，最高 $max。"
    val peakText = "最高 $max 出现在第 " + clockOf((peakAt * secPerPoint).roundToInt())
    lines += when {
        max < 100 -> "$peakText，全程未超过 100。"
        back > 0 -> "$peakText，约 " + (back * secPerPoint).roundToInt() + " 秒后回落到 100 以下。"
        else -> "$peakText，之后一直在 100 以上。"
    }
    val tailLo = tail.minOrNull() ?: 0
    val tailHi = tail.maxOrNull() ?: 0
    val tailText = if (tailLo == tailHi) "末段 $tailLo bpm" else "末段 $tailLo–$tailHi bpm"
    lines += tailText + if (gapIndex >= 0) "，中途断流一次（图中竖虚线）。" else "，全程无断流。"
    return lines
}
