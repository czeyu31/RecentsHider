package com.example.hiderecents.temp

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

/**
 * 常驻采样器（进程级单例）。
 *
 * 以前采样与录制都挂在 TempActivity 上：退出页面曲线就没了，录制也断。
 * 现在采样归它管——页面只读状态，所以：
 *  · 进软件（MainActivity.onCreate）就开跑，切到后台继续采；
 *  · 退出温度页再进来，序列还在，不清零；
 *  · 录制中也一样，离开页面照样攒样本。
 */
object TempSampler {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val started = AtomicBoolean(false)
    private val rings = HashMap<String, MutableList<Float>>()

    var zones by mutableStateOf<List<TempZone>>(emptyList())
        private set
    var headLabel by mutableStateOf<String?>(null)
        private set
    /** 每个热区的 120 点环，键是 label */
    var series by mutableStateOf<Map<String, List<Float>>>(emptyMap())
        private set
    /** null = 跟随最高温区 */
    var selectedZone by mutableStateOf<String?>(null)
    var intervalMs by mutableStateOf(1000L)
    var readingError by mutableStateOf("")
        private set

    var recording by mutableStateOf(false)
        private set
    var recStartedAt by mutableStateOf(0L)
        private set
    var recSamples by mutableStateOf(0)
        private set
    var recZone by mutableStateOf<String?>(null)
        private set

    private val recValues = mutableListOf<Float>()
    private val recSeries = HashMap<String, MutableList<Float>>()
    private val recSubs = HashMap<String, String>()
    private val recPeaks = LinkedHashMap<String, TempZonePeak>()

    val intervalSteps = listOf(1000L, 2000L, 5000L, 10000L)

    fun currentSeries(label: String?): List<Float> = label?.let { series[it] } ?: emptyList()

    fun ensureRunning(context: Context) {
        if (!started.compareAndSet(false, true)) return
        val app = context.applicationContext
        scope.launch {
            while (isActive) {
                val t0 = System.currentTimeMillis()
                val z = TempSensor.keyZones(app)
                val head = TempSensor.headOf(z)
                    ?: TempSensor.readBattery(app)?.let { TempZone("bms", "电池", "bms", it) }
                sample(z, head)
                val cost = System.currentTimeMillis() - t0
                delay((intervalMs - cost).coerceAtLeast(200L))
            }
        }
    }

    private fun sample(z: List<TempZone>, head: TempZone?) {
        zones = z
        headLabel = head?.label
        if (z.isEmpty() && head == null) {
            readingError = "读不到热区读数"
            return
        }
        readingError = ""
        z.forEach { zone ->
            val ring = rings.getOrPut(zone.label) { ArrayList(120) }
            ring.add(round1(zone.celsius))
            if (ring.size > 120) ring.removeAt(0)
        }
        series = rings.mapValues { (_, ring) -> ring.toList() }
        if (recording) {
            val recName = recZone ?: head?.label
            recName?.let { name -> series[name]?.lastOrNull()?.let { recValues.add(it) } }
            z.forEach { zone ->
                recSubs[zone.label] = zone.sub
                val list = recSeries.getOrPut(zone.label) { ArrayList<Float>() }
                list.add(round1(zone.celsius))
                val prev = recPeaks[zone.label]
                if (prev == null || zone.celsius > prev.peak) {
                    recPeaks[zone.label] = TempZonePeak(zone.label, zone.sub, round1(zone.celsius))
                }
            }
            recSamples = recValues.size
        }
    }

    // ------------------------------------------------------------------ 录制

    fun startRecording(): Boolean {
        if (series.isEmpty()) return false
        recValues.clear()
        recSeries.clear()
        recSubs.clear()
        recPeaks.clear()
        recSamples = 0
        recZone = selectedZone ?: headLabel
        recStartedAt = System.currentTimeMillis()
        recording = true
        return true
    }

    /** 停止并返回这次录制；样本不足 5 个返回 null（已在内部清空）。 */
    fun stopRecording(): TempSession? {
        if (!recording) return null
        val started = recStartedAt
        val elapsed = ((System.currentTimeMillis() - started) / 1000L).toInt()
        val values = downsample(recValues.toList(), 600)
        val zoneSeries = recSeries.mapNotNull { (label, list) ->
            val v = downsample(list, 600)
            if (v.isEmpty()) null else TempZoneSeries(label, recSubs[label].orEmpty(), v)
        }
        val primary = recZone ?: headLabel ?: ""
        val peaks = recPeaks.values.toList()
        val enough = recValues.size >= 5

        recording = false
        recStartedAt = 0L
        recSamples = 0
        recValues.clear()
        recSeries.clear()
        recSubs.clear()
        recPeaks.clear()
        recZone = null

        if (!enough) return null
        return TempSession(
            id = System.currentTimeMillis(),
            startedAt = started,
            durationSec = if (values.isEmpty()) elapsed else elapsed.coerceAtLeast(values.size),
            values = values,
            zonePeaks = peaks,
            sampleIntervalMs = intervalMs,
            primaryLabel = primary,
            zoneSeries = zoneSeries,
        )
    }

    fun setInterval(ms: Long) {
        if (intervalSteps.contains(ms)) intervalMs = ms
    }

    private fun round1(v: Float): Float = (v * 10f).roundToInt() / 10f

    private fun downsample(values: List<Float>, max: Int): List<Float> {
        if (values.size <= max) return values.toList()
        val step = values.size.toDouble() / max
        return (0 until max).map { i -> values[(i * step).toInt().coerceAtMost(values.lastIndex)] }
    }
}