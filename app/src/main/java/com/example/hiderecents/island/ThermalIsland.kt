package com.example.hiderecents.island

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import com.example.hiderecents.TempActivity
import com.example.hiderecents.temp.TempSampler
import com.example.hiderecents.temp.TempZone

/**
 * 温度岛的状态机。
 *
 * 采样在 TempSampler 里跑，这里只负责「该不该在岛上」：
 *  · 总开关关掉 → 撤岛
 *  · 只在充电时上岛 → 未充电且没在录制 → 撤岛
 *  · 阈值滞回：≥ 上岛阈值 置位，≤ 下岛阈值 并保持 N 秒 才撤；两者之间维持原状
 *  · 骤升提醒：1 分钟内升幅 ≥ 5℃ 直接上岛
 *  · 录制中始终在岛上，胶囊左侧显示时长、右侧显示数值
 */
object ThermalIsland {

    private var armed = false
    private var lastPublishAt = 0L
    private var published = false
    /** V3 通道的会话 id：每次上岛新生成，撤岛后不复用 */
    private var v3SessionId: Int? = null
    private var v3Sequence = 0
    private var belowSince = 0L
    private var lastRiseCheckAt = 0L
    private var oneMinuteAgoValue = 0f

    fun onSample(
        context: Context,
        zones: List<TempZone>,
        series: Map<String, List<Float>>,
        recording: Boolean,
        recSeconds: Int,
    ) {
        val s = IslandSettings
        s.load(context)
        if (!s.tempEnabled) {
            if (published) {
                IslandPublish.cancel(context, IslandPublish.tempId())
                published = false
            }
            dismissV3(context)
            armed = false
            return
        }

        // 「上岛热区」决定在哪些热区里挑：挑不到就不上岛；录制中是显式动作，不受限制
        val enabledZones = if (recording || s.tempZones.isEmpty()) {
            zones
        } else {
            zones.filter { islandKey(it.label) in s.tempZones }
        }
        if (enabledZones.isEmpty()) {
            dropIsland(context)
            return
        }

        // 胶囊指定的热区没被启用时，退回到启用热区里最热的那个，而不是整条岛下掉
        val capsuleKey = s.tempCapsuleZone
        val zone = (if (capsuleKey != null) {
            enabledZones.firstOrNull { islandKey(it.label) == capsuleKey }
        } else {
            null
        }) ?: enabledZones.maxByOrNull { it.celsius }
        if (zone == null) {
            dropIsland(context)
            return
        }

        val charging = isCharging(context)
        if (s.tempChargingOnly && !charging && !recording) {
            dropIsland(context)
            armed = false
            return
        }

        val value = zone.celsius
        val now = System.currentTimeMillis()

        // 阈值滞回
        if (value >= s.tempUpCelsius) {
            armed = true
            belowSince = 0L
        } else if (value <= s.tempDownCelsius) {
            if (belowSince == 0L) belowSince = now
            if (armed && now - belowSince >= s.tempDownDelayMs) armed = false
        } else {
            belowSince = 0L
        }

        // 骤升：拿一分钟前的值比较，用采样序列最后 60 个点（1s 采样）
        if (s.tempSuddenRise) {
            if (now - lastRiseCheckAt >= 10_000L) {
                lastRiseCheckAt = now
                val seq = series[zone.label].orEmpty()
                if (seq.size >= 60) {
                    oneMinuteAgoValue = seq[seq.size - 60]
                    if (value - oneMinuteAgoValue >= 5f) armed = true
                }
            }
        }

        val shouldShow = recording || s.tempAlwaysOn || armed
        if (!shouldShow) {
            dropIsland(context)
            return
        }

        if (now - lastPublishAt < s.updateIntervalMs && published) return
        lastPublishAt = now

        val pct = (((value - 30f) / 40f) * 100f).toInt().coerceIn(0, 100)
        val left = if (recording) "● " + IslandPublish.clock(recSeconds) else islandKey(zone.label)
        val right = String.format(java.util.Locale.US, "%.1f℃", value)
        // 岛上按钮：录制 + 关闭（关闭等于把温度岛总开关关掉）
        val acts = ArrayList<Pair<String, android.app.PendingIntent>>()
        if (s.tempRecordButton) {
            acts += (if (recording) "停止录制" else "开始录制") to
                IslandPublish.broadcastIntent(context, IslandReceiver.ACTION_TEMP_REC, 21)
        }
        if (s.tempCloseButton) {
            acts += "关闭" to IslandPublish.broadcastIntent(context, IslandReceiver.ACTION_TEMP_CLOSE, 22)
        }

        if (useV3()) {
            val first = v3SessionId == null
            v3Sequence += 1
            val sid = v3SessionId ?: IslandV3.newSessionId().also { v3SessionId = it }
            IslandV3.publish(
                context = context,
                sessionId = sid,
                // 胶囊右段只要数值，热区名已经在左段；重复会变成「CPU CPU 49.7℃」
                title = right,
                // 左段已经显示热区名了，这里再放一次胶囊上会重复成「电池 电池」
                content = if (recording) "温度监控 · 录制中" else "温度监控",
                leftText = left,
                rightText = right,
                heart = false,
                picKey = IslandPublish.PIC_TEMP,
                sequence = v3Sequence,
            )
            v3Sequence = v3Sequence
            lastPublishAt = now
            published = true
            return
        }

        IslandPublish.post(
            context = context,
            id = IslandPublish.tempId(),
            title = zone.label + " " + right,
            content = if (recording) "温度监控 · 录制中" else "温度监控",
            leftText = left,
            rightText = right,
            progressPct = pct,
            heart = false,
            contentIntent = if (s.tempOpenOnTap) IslandPublish.intentFor(context, TempActivity::class.java, requestCode = 20) else null,
            actions = acts,
        )
        published = true
    }

    /** 优先级切换时用：彻底撤掉并复位，下一步采样会按新局面重新上岛 */
    fun restart(context: Context) {
        dropIsland(context)
        v3Sequence = 0
        lastPublishAt = 0L
        armed = false
        belowSince = 0L
    }

    /** 岛上的「关闭」：等于把温度岛总开关关掉，并立刻撤岛 */
    fun closeFromIsland(context: Context) {
        IslandSettings.tempEnabled = false
        IslandSettings.save(context)
        IslandPublish.cancel(context, IslandPublish.tempId())
        dismissV3(context)
        published = false
        armed = false
        belowSince = 0L
    }

    /** 岛上按钮按过之后立刻重算一次（不等下一拍采样） */
    fun refreshNow(context: Context) {
        val zones = TempSampler.zones
        val label = IslandSettings.tempCapsuleZone ?: zones.maxByOrNull { it.celsius }?.label
        onSample(
            context = context,
            zones = zones,
            series = mapOf(),
            recording = TempSampler.recording,
            recSeconds = if (TempSampler.recording) {
                ((System.currentTimeMillis() - TempSampler.recStartedAt) / 1000L).toInt()
            } else {
                0
            },
        )
    }

    /** 当前该走哪条通道：auto 时按系统版本选，live 强制实时更新，v3 强制焦点通知 */
    private fun useV3(): Boolean = when (IslandSettings.mode) {
        "v3" -> true
        "live" -> false
        else -> !IslandPublish.liveUpdatesSupported()
    }

    private fun dismissV3(context: Context) {
        v3SessionId?.let { IslandV3.dismiss(context, it) }
        v3SessionId = null
        v3Sequence = 0
    }

    /** 屏上显示的热区名 → 设置页用的归一化名字 */
    fun islandKey(label: String): String = when {
        label.startsWith("CPU") -> "CPU"
        label == "GPU" -> "GPU"
        label == "电池" -> "电池"
        label == "外壳" -> "外壳"
        else -> "其他"
    }

    private fun dropIsland(context: Context) {
        IslandPublish.cancel(context, IslandPublish.tempId())
        dismissV3(context)
        published = false
    }

    /** 充电状态：用 BatteryManager 直接问，避免 sticky broadcast 在各机型上取到旧值 */
    private fun isCharging(context: Context): Boolean {
        val bm = context.getSystemService(BatteryManager::class.java) ?: return false
        return bm.isCharging
    }
}