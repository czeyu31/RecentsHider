package com.example.hiderecents.temp

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

/** 一次录制里某个热区的峰值。 */
data class TempZonePeak(val label: String, val sub: String, val peak: Float)

/** 一次录制里某个热区的完整曲线（已抽稀），回放页点热区就切到这一条。 */
data class TempZoneSeries(val label: String, val sub: String, val values: List<Float>) {
    val peak: Float get() = values.maxOrNull() ?: 0f
    val average: Float get() = if (values.isEmpty()) 0f else values.sum() / values.size
}

/**
 * 一次温度录制。
 *
 * values 是主曲线（录制开始时选中的那一路）；zoneSeries 是各热区各自的曲线，
 * 回放页点热区行就能切换；zonePeaks 是各热区峰值，历史列表用它显示电池温度。
 */
data class TempSession(
    val id: Long,
    val startedAt: Long,
    val durationSec: Int,
    val values: List<Float>,
    val zonePeaks: List<TempZonePeak> = emptyList(),
    val sampleIntervalMs: Long = 1000L,
    /** 这次录制跟随的热区名，回放页用来标注曲线属于谁 */
    val primaryLabel: String = "",
    /** 各热区各自的曲线，回放页可点选切换 */
    val zoneSeries: List<TempZoneSeries> = emptyList(),
) {
    val peak: Float get() = values.maxOrNull() ?: 0f
    val average: Float get() = if (values.isEmpty()) 0f else values.sum() / values.size
    val peakIndex: Int get() = if (values.isEmpty()) 0 else values.indices.maxBy { values[it] }

    /** 历史列表上显示的温度：优先电池，没有电池热区就退回主曲线峰值。 */
    val batteryPeak: Float
        get() = zonePeaks.firstOrNull { it.label == "电池" }?.peak ?: peak

    val hasBattery: Boolean get() = zonePeaks.any { it.label == "电池" }
}

/**
 * 录制落盘。JSON 存到 filesDir，最多保留最近 50 条，新的在前。
 *
 * 先写临时文件再改名，避免中途被杀留下半个文件。
 */
object TempStore {

    private const val FILE_NAME = "temp_sessions.json"
    private const val MAX_SESSIONS = 50

    fun load(context: Context): List<TempSession> {
        val file = File(context.filesDir, FILE_NAME)
        if (!file.exists()) return emptyList()
        return try {
            val root = JSONArray(file.readText())
            (0 until root.length()).mapNotNull { i ->
                val o = root.optJSONObject(i) ?: return@mapNotNull null
                val values = parseFloats(o.optString("v", ""))
                if (values.isEmpty()) return@mapNotNull null
                TempSession(
                    id = o.optLong("id", 0L),
                    startedAt = o.optLong("t", 0L),
                    durationSec = o.optInt("d", values.size),
                    values = values,
                    zonePeaks = parsePeaks(o.optJSONArray("z")),
                    sampleIntervalMs = o.optLong("s", 1000L),
                    primaryLabel = o.optString("l", ""),
                    zoneSeries = parseSeries(o.optJSONArray("zs")),
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun save(context: Context, sessions: List<TempSession>) {
        val root = JSONArray()
        sessions.take(MAX_SESSIONS).forEach { s ->
            val peaks = JSONArray()
            s.zonePeaks.forEach { p ->
                peaks.put(JSONObject().put("l", p.label).put("s", p.sub).put("p", p.peak.toDouble()))
            }
            val zs = JSONArray()
            s.zoneSeries.forEach { z ->
                zs.put(
                    JSONObject()
                        .put("l", z.label)
                        .put("s", z.sub)
                        .put("v", z.values.joinToString(",") { fmt(it) }),
                )
            }
            root.put(
                JSONObject()
                    .put("id", s.id)
                    .put("t", s.startedAt)
                    .put("d", s.durationSec)
                    .put("s", s.sampleIntervalMs)
                    .put("v", s.values.joinToString(",") { fmt(it) })
                    .put("l", s.primaryLabel)
                    .put("z", peaks)
                    .put("zs", zs),
            )
        }
        val file = File(context.filesDir, FILE_NAME)
        val tmp = File(context.filesDir, "$FILE_NAME.tmp")
        try {
            tmp.writeText(root.toString())
            if (file.exists()) file.delete()
            tmp.renameTo(file)
        } catch (_: Exception) {
        }
    }

    fun clear(context: Context) {
        try {
            File(context.filesDir, FILE_NAME).delete()
        } catch (_: Exception) {
        }
    }

    private fun fmt(v: Float): String = String.format(Locale.US, "%.1f", v)

    private fun parseFloats(raw: String): List<Float> {
        if (raw.isEmpty()) return emptyList()
        return raw.split(',').mapNotNull { it.trim().toFloatOrNull() }
    }

    private fun parsePeaks(arr: JSONArray?): List<TempZonePeak> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val label = o.optString("l", "")
            if (label.isEmpty()) return@mapNotNull null
            TempZonePeak(
                label = label,
                sub = o.optString("s", ""),
                peak = o.optDouble("p", 0.0).toFloat(),
            )
        }
    }

    private fun parseSeries(arr: JSONArray?): List<TempZoneSeries> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val label = o.optString("l", "")
            val values = parseFloats(o.optString("v", ""))
            if (label.isEmpty() || values.isEmpty()) return@mapNotNull null
            TempZoneSeries(label = label, sub = o.optString("s", ""), values = values)
        }
    }
}