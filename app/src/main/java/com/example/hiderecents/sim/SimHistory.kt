package com.example.hiderecents.sim

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

/** 最近用过的目标国家，供主页「最近使用」和选择器「最近」分栏使用 */
data class RecentCountry(
    val code: String,
    val label: String,
    val mcc: String,
    val operator: String,
    val timestamp: Long,
)

/** 一次调用的流水，诊断页的「调用记录」直接用这个 */
data class CallRecord(
    val timestamp: Long,
    val action: String,
    val subId: Int,
    val ok: Boolean,
    val millis: Long,
    val note: String,
    /** 这次写的是哪个国家（restore 时为空），用于页面重开后回显上次结果 */
    val countryCode: String = "",
)

/**
 * SIM 国家码的记录存储。
 *
 * 只有几十条、结构扁平，用 SharedPreferences + JSON 足够，不引入数据库。
 * 与项目里通知历史的存法保持一致。
 */
object SimHistory {

    private const val PREFS = "hide_recents_prefs"
    private const val KEY_RECENT = "sim_recent_countries"
    private const val KEY_CALLS = "sim_call_log"

    private const val MAX_RECENT = 5
    private const val MAX_CALLS = 60

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun recent(context: Context): List<RecentCountry> {
        val raw = prefs(context).getString(KEY_RECENT, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                val code = item.optString("code").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                RecentCountry(
                    code = code,
                    label = item.optString("label", code),
                    mcc = item.optString("mcc"),
                    operator = item.optString("operator"),
                    timestamp = item.optLong("ts"),
                )
            }
        }.getOrDefault(emptyList())
    }

    fun remember(context: Context, entry: RecentCountry) {
        val kept = recent(context).filterNot { it.code.equals(entry.code, ignoreCase = true) }
        val merged = (listOf(entry) + kept).take(MAX_RECENT)
        val array = JSONArray()
        merged.forEach { item ->
            array.put(
                JSONObject().apply {
                    put("code", item.code)
                    put("label", item.label)
                    put("mcc", item.mcc)
                    put("operator", item.operator)
                    put("ts", item.timestamp)
                },
            )
        }
        prefs(context).edit().putString(KEY_RECENT, array.toString()).apply()
    }

    fun calls(context: Context): List<CallRecord> {
        val raw = prefs(context).getString(KEY_CALLS, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                CallRecord(
                    timestamp = item.optLong("ts"),
                    action = item.optString("action"),
                    subId = item.optInt("subId", -1),
                    ok = item.optBoolean("ok", false),
                    millis = item.optLong("ms"),
                    note = item.optString("note"),
                    countryCode = item.optString("country"),
                )
            }
        }.getOrDefault(emptyList())
    }

    fun addCall(context: Context, record: CallRecord) {
        val merged = (listOf(record) + calls(context)).take(MAX_CALLS)
        val array = JSONArray()
        merged.forEach { item ->
            array.put(
                JSONObject().apply {
                    put("ts", item.timestamp)
                    put("action", item.action)
                    put("subId", item.subId)
                    put("ok", item.ok)
                    put("ms", item.millis)
                    put("note", item.note)
                    put("country", item.countryCode)
                },
            )
        }
        prefs(context).edit().putString(KEY_CALLS, array.toString()).apply()
    }

    fun todayCalls(context: Context): List<CallRecord> {
        val start = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        return calls(context).filter { it.timestamp >= start }
    }

    fun lastCall(context: Context): CallRecord? = calls(context).firstOrNull()

    fun clear(context: Context) {
        prefs(context).edit().remove(KEY_RECENT).remove(KEY_CALLS).apply()
    }
}