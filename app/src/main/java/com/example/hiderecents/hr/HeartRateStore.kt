package com.example.hiderecents.hr

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** 一次录制。values 是每秒一个心率值；gapIndex 是中途断流在 values 里的位置，-1 表示没有断流。 */
data class HrSession(
    val id: Long,
    val startedAt: Long,
    val durationSec: Int,
    val values: List<Int>,
    val gapIndex: Int = -1,
    val deviceName: String = "",
    val deviceMac: String = "",
)

/**
 * 会话落盘。JSON 存到 filesDir，最多保留最近 50 条，新的在前。
 *
 * 数据量很小（一条几百个整数），所以直接整份读写，不做增量；
 * 写的时候先写临时文件再改名，避免中途被杀留下半个文件。
 */
object HeartRateStore {

    private const val FILE_NAME = "hr_sessions.json"
    private const val MAX_SESSIONS = 50

    fun load(context: Context): List<HrSession> {
        val file = File(context.filesDir, FILE_NAME)
        if (!file.exists()) return emptyList()
        return try {
            val root = JSONArray(file.readText())
            (0 until root.length()).mapNotNull { i ->
                val o = root.optJSONObject(i) ?: return@mapNotNull null
                val values = parseInts(o.optString("v", ""))
                if (values.isEmpty()) return@mapNotNull null
                HrSession(
                    id = o.optLong("id", 0L),
                    startedAt = o.optLong("t", 0L),
                    durationSec = o.optInt("d", values.size),
                    values = values,
                    gapIndex = o.optInt("g", -1),
                    deviceName = o.optString("n", ""),
                    deviceMac = o.optString("m", ""),
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun save(context: Context, sessions: List<HrSession>) {
        val trimmed = sessions.take(MAX_SESSIONS)
        val root = JSONArray()
        trimmed.forEach { s ->
            root.put(
                JSONObject()
                    .put("id", s.id)
                    .put("t", s.startedAt)
                    .put("d", s.durationSec)
                    .put("g", s.gapIndex)
                    .put("n", s.deviceName)
                    .put("m", s.deviceMac)
                    .put("v", s.values.joinToString(",")),
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

    private fun parseInts(raw: String): List<Int> {
        if (raw.isEmpty()) return emptyList()
        return raw.split(',').mapNotNull { it.trim().toIntOrNull() }
    }
}
