package com.example.hiderecents.island

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 超级岛设置（两个岛各自独立）。
 *
 * 全部落 prefs；字段用 mutableStateOf 包着，设置页改一下，发布逻辑下一拍就能读到新值。
 */
object IslandSettings {

    private const val PREF = "hide_recents_prefs"

    // ---------------- 心率岛
    var heartEnabled by mutableStateOf(false)
    var heartRecordButton by mutableStateOf(true)
    var heartDropIslandOnLost by mutableStateOf(true)
    var heartShowDevice by mutableStateOf(true)
    var heartShowSignal by mutableStateOf(true)
    var heartOpenOnTap by mutableStateOf(true)

    // ---------------- 温度岛
    var tempEnabled by mutableStateOf(false)
    /** true = 常驻上岛；false = 只在超过阈值（或录制中）上岛 */
    var tempAlwaysOn by mutableStateOf(true)
    var tempRecordButton by mutableStateOf(true)
    var tempCloseButton by mutableStateOf(true)
    var tempChargingOnly by mutableStateOf(false)
    var tempOpenOnTap by mutableStateOf(true)
    var tempSuddenRise by mutableStateOf(true)
    var tempUpCelsius by mutableStateOf(45.0f)
    var tempDownCelsius by mutableStateOf(42.0f)
    var tempDownDelayMs by mutableStateOf(3_000L)
    /** 胶囊显示的热区 label；空 = 跟随最高温区 */
    var tempCapsuleZone by mutableStateOf<String?>(null)
    /** 上岛热区（多选） */
    var tempZones by mutableStateOf<Set<String>>(setOf("CPU", "GPU", "电池", "外壳"))

    // ---------------- 通用
    /** 上岛方式：auto（按系统能力）/ live（实时更新）/ v3（澎湃 3 焦点通知 V3） */
    var mode by mutableStateOf("auto")
    var updateIntervalMs by mutableStateOf(1_000L)
    var expandDurationMs by mutableStateOf(4_000L)
    var tempPriority by mutableStateOf(true)
    var keepNotificationWhenOff by mutableStateOf(false)

    private var loaded = false

    fun load(context: Context) {
        if (loaded) return
        loaded = true
        val p = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        heartEnabled = p.getBoolean("island_heart_on", false)
        heartRecordButton = p.getBoolean("island_heart_rec_btn", true)
        
        heartDropIslandOnLost = p.getBoolean("island_heart_drop_lost", true)
        heartShowDevice = p.getBoolean("island_heart_show_device", true)
        heartShowSignal = p.getBoolean("island_heart_show_signal", true)
        heartOpenOnTap = p.getBoolean("island_heart_open_tap", true)

        tempEnabled = p.getBoolean("island_temp_on", false)
        tempAlwaysOn = p.getBoolean("island_temp_always_on", true)
        tempRecordButton = p.getBoolean("island_temp_rec_btn", true)
        tempCloseButton = p.getBoolean("island_temp_close_btn", true)
        tempChargingOnly = p.getBoolean("island_temp_charging_only", false)
        tempOpenOnTap = p.getBoolean("island_temp_open_tap", true)
        tempSuddenRise = p.getBoolean("island_temp_rise", true)
        tempUpCelsius = p.getFloat("island_temp_up", 45f)
        tempDownCelsius = p.getFloat("island_temp_down", 42f)
        // 下岛不允许高于上岛，旧存档也顺手纠正
        if (tempDownCelsius > tempUpCelsius) tempDownCelsius = tempUpCelsius
        tempDownDelayMs = p.getLong("island_temp_delay", 3_000L)
        tempCapsuleZone = p.getString("island_temp_capsule", null)
        tempZones = p.getStringSet("island_temp_zones", setOf("CPU", "GPU", "电池", "外壳"))
            ?.toSet().orEmpty()

        mode = p.getString("island_mode", "auto") ?: "auto"
        updateIntervalMs = p.getLong("island_update_ms", 1_000L)
        expandDurationMs = p.getLong("island_expand_ms", 4_000L)
        tempPriority = p.getBoolean("island_temp_priority", true)
        keepNotificationWhenOff = p.getBoolean("island_keep_notif_off", false)
    }

    fun save(context: Context) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putBoolean("island_heart_on", heartEnabled)
            .putBoolean("island_heart_rec_btn", heartRecordButton)
            .putBoolean("island_heart_drop_lost", heartDropIslandOnLost)
            .putBoolean("island_heart_show_device", heartShowDevice)
            .putBoolean("island_heart_show_signal", heartShowSignal)
            .putBoolean("island_heart_open_tap", heartOpenOnTap)
            .putBoolean("island_temp_on", tempEnabled)
            .putBoolean("island_temp_always_on", tempAlwaysOn)
            .putBoolean("island_temp_rec_btn", tempRecordButton)
            .putBoolean("island_temp_close_btn", tempCloseButton)
            .putBoolean("island_temp_charging_only", tempChargingOnly)
            .putBoolean("island_temp_open_tap", tempOpenOnTap)
            .putBoolean("island_temp_rise", tempSuddenRise)
            .putFloat("island_temp_up", tempUpCelsius)
            .putFloat("island_temp_down", tempDownCelsius)
            .putLong("island_temp_delay", tempDownDelayMs)
            .putString("island_temp_capsule", tempCapsuleZone)
            .putStringSet("island_temp_zones", tempZones)
            .putString("island_mode", mode)
            .putLong("island_update_ms", updateIntervalMs)
            .putLong("island_expand_ms", expandDurationMs)
            .putBoolean("island_temp_priority", tempPriority)
            .putBoolean("island_keep_notif_off", keepNotificationWhenOff)
            .apply()
    }
}