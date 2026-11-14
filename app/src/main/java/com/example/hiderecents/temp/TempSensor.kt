package com.example.hiderecents.temp

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import com.example.hiderecents.ShizukuShell
import java.io.File
import java.util.Locale

/**
 * 一个热区读数。
 *
 * key 是 /sys/class/thermal 里的 type（如 cpu-1-4、battery），用来去重与排序；
 * label 是界面上显示的中文名；sub 是界面上的等宽小字，直接展示原始 type。
 */
data class TempZone(
    val key: String,
    val label: String,
    val sub: String,
    val celsius: Float,
)

/**
 * 温度读取。
 *
 * 主路径：一段 shell 一次性把全部热区的 type 与 temp 读出来（Shizuku 通道，无需 root）。
 * 兜底：电池温度走 ACTION_BATTERY_CHANGED 的 sticky 广播，应用进程本身就能读。
 *
 * 各机型热区命名差异很大（本机是 cpu-0-* / cpu-1-* / gpuss-* / battery，另有大量
 * 无意义的 -273000 与电压类节点），所以这里做三件事：过滤物理上不可能的读数、
 * 按「簇」归并 CPU、把噪声节点压成一行「其他」。
 */
object TempSensor {

    private const val CMD =
        "for z in /sys/class/thermal/thermal_zone*; do " +
            "t=\$(cat \"\$z/type\" 2>/dev/null); " +
            "v=\$(cat \"\$z/temp\" 2>/dev/null); " +
            "if [ -n \"\$t\" ] && [ -n \"\$v\" ]; then echo \"\$t|\$v\"; fi; done"

    /** 全量热区：已按「大核 → 小核 → 电池 → GPU → 集群 → 其他」排好序，同 label 只留最高温那条。 */
    fun readZones(): List<TempZone> {
        val direct = dedupeAndSort(readZonesDirect())
        if (direct.isNotEmpty()) return direct
        return dedupeAndSort(readZonesViaShell())
    }

    /**
     * 直接读文件。
     *
     * 实测这台 HyperOS 15 上，应用自身 UID 就能读 thermal_zone* 的 type 与 temp
     * （两者都是 root:root 0444 + sysfs_thermal，untrusted_app 可读），
     * 所以正常情况下根本不需要 Shizuku；/sys 对少数机型不可读时才退回 shell。
     */
    private fun readZonesDirect(): List<TempZone> {
        val dir = File("/sys/class/thermal")
        val zones = dir.listFiles { f -> f.isDirectory && f.name.startsWith("thermal_zone") } ?: return emptyList()
        val out = ArrayList<TempZone>(zones.size)
        zones.forEach { z ->
            val type = runCatching { File(z, "type").readText().trim() }.getOrDefault("")
            val raw = runCatching { File(z, "temp").readText().trim() }.getOrDefault("")
            if (type.isEmpty() || raw.isEmpty()) return@forEach
            val celsius = parseCelsius(raw) ?: return@forEach
            val t = type.lowercase(Locale.US)
            out += TempZone(key = t, label = labelOf(t), sub = t, celsius = celsius)
        }
        return out
    }

    /** Shizuku 兜底：一段 shell 一次性读出全部热区的 type 与 temp。 */
    private fun readZonesViaShell(): List<TempZone> {
        val (ok, out) = ShizukuShell.exec(CMD)
        if (!ok || out.isBlank()) return emptyList()
        return out.lineSequence().mapNotNull { line ->
            val idx = line.lastIndexOf('|')
            if (idx <= 0) return@mapNotNull null
            val type = line.substring(0, idx).trim()
            val celsius = parseCelsius(line.substring(idx + 1).trim()) ?: return@mapNotNull null
            if (type.isEmpty()) return@mapNotNull null
            val t = type.lowercase(Locale.US)
            TempZone(key = t, label = labelOf(t), sub = t, celsius = celsius)
        }.toList()
    }

    /** 毫度转摄氏度，并滤掉 -273000（无效）、0（未就绪）、vbat 电压这类噪声。 */
    private fun parseCelsius(raw: String): Float? {
        val v = raw.toFloatOrNull() ?: return null
        val celsius = if (v > 1000f) v / 1000f else v
        if (celsius < 5f || celsius > 120f) return null
        return celsius
    }

    /** 只读电池温度（BatteryManager sticky 广播），Shizuku 不可用时界面仍有一个数可显示。 */
    fun readBattery(context: Context): Float? {
        val intent = runCatching {
            context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }.getOrNull() ?: return null
        val tenths = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        return if (tenths > 0) tenths / 10f else null
    }

    /** 当前显示用的 4 个关键热区；不足 4 个就有几个给几个。 */
    fun keyZones(context: Context): List<TempZone> {
        val zones = readZones()
        if (zones.size >= 2) return zones.take(4)
        val battery = readBattery(context)
        if (battery != null && zones.none { it.label == "电池" }) {
            return zones + TempZone("bms", "电池", "bms", battery)
        }
        return zones
    }

    /** 主读数：所有热区里最高的那个（CPU/GPU 一般就是它）。 */
    fun headOf(zones: List<TempZone>): TempZone? = zones.maxByOrNull { it.celsius }


    /** 同一 label 的多簇 CPU / 多传感器只保留最高温那条，界面上不重复列。 */
    private fun dedupeAndSort(zones: List<TempZone>): List<TempZone> =
        zones.groupBy { it.label }
            .map { (_, list) -> list.maxByOrNull { it.celsius } ?: list.first() }
            .sortedWith(compareBy({ rankOf(it) }, { it.key }))

    /**
     * 机型命名不统一，这里按「能不能被用户理解」来归并：
     * cpu-1-* 是本机的大核簇（温度最高），cpu-0-* 是小核簇；其余 CPU 类节点
     * （cpuss-*、aoss-*、cpu_therm）并入「CPU 集群」，只在前面凑不满 4 行时才出现。
     */
    private fun labelOf(t: String): String = when {
        t.startsWith("cpu-1") || t.contains("gold") || t.contains("prime") -> "CPU 大核"
        t.startsWith("cpu-0") || t.contains("silver") -> "CPU 小核"
        t.contains("cpu") || t.contains("cpuss") || t.contains("aoss") -> "CPU 集群"
        t.contains("batt") || t.contains("bms") -> "电池"
        t.contains("gpu") || t.contains("kgsl") -> "GPU"
        t.contains("skin") || t.contains("shell") || t.contains("quiet") -> "外壳"
        else -> "其他"
    }

    private fun rankOf(z: TempZone): Int = when (z.label) {
        "CPU 大核" -> 1
        "CPU 小核" -> 2
        "电池" -> 3
        "GPU" -> 4
        "CPU 集群" -> 5
        "外壳" -> 6
        else -> 9
    }
}
