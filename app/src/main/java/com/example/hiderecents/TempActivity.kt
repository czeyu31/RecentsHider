package com.example.hiderecents

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.os.Environment
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.example.hiderecents.hr.clockOf
import com.example.hiderecents.hr.dayOf
import com.example.hiderecents.hr.hm
import com.example.hiderecents.temp.TempSampler
import com.example.hiderecents.temp.TempSession
import com.example.hiderecents.temp.TempStore
import com.example.hiderecents.ui.screens.TempHistoryItem
import com.example.hiderecents.ui.screens.TempZoneRow
import com.example.hiderecents.ui.screens.TemperatureHistoryScreen
import com.example.hiderecents.ui.screens.TemperatureReplayScreen
import com.example.hiderecents.ui.screens.TemperatureScreen
import com.example.hiderecents.ui.theme.AppTheme
import com.example.hiderecents.ui.theme.DshTheme
import com.example.hiderecents.ui.theme.applyDshSystemBars
import kotlinx.coroutines.delay
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 温度：实时 / 录制历史 / 回放三屏。
 *
 * 采样与录制都在 [TempSampler]（进程级单例）里跑：本页只负责渲染与操作，
 * 所以离开页面采样不断、录制继续，回来也不会从零开始。
 */
class TempActivity : DshActivity() {

    private enum class Screen { LIVE, HISTORY, REPLAY }

    private lateinit var prefs: SharedPreferences
    private var theme = AppTheme.SYSTEM

    private var screen by mutableStateOf(Screen.LIVE)
    private var sessions by mutableStateOf<List<TempSession>>(emptyList())
    private var opened by mutableStateOf<TempSession?>(null)
    private var nowTick by mutableStateOf(System.currentTimeMillis())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        prefs = getSharedPreferences("hide_recents_prefs", Context.MODE_PRIVATE)
        theme = AppTheme.fromPref(prefs.getString("ui_theme", null))
        applyDshSystemBars(theme)

        sessions = TempStore.load(this).filter { it.values.isNotEmpty() }
        TempSampler.ensureRunning(this)
        TempSampler.setInterval(prefs.getLong(KEY_INTERVAL, 1000L))

        setContent {
            DshTheme(theme) {
                // 录制中的秒表：只刷新 REC 时长
                LaunchedEffect(TempSampler.recording) {
                    while (TempSampler.recording) {
                        nowTick = System.currentTimeMillis()
                        delay(500)
                    }
                    nowTick = System.currentTimeMillis()
                }

                when (screen) {
                    Screen.LIVE -> {
                        val sel = TempSampler.selectedZone ?: TempSampler.headLabel
                        TemperatureScreen(
                            head = TempSampler.currentSeries(sel).lastOrNull(),
                            headLabel = sel,
                            zones = TempSampler.zones.map {
                                TempZoneRow(
                                    label = it.label,
                                    sub = it.sub,
                                    value = it.celsius,
                                    selected = it.label == TempSampler.selectedZone,
                                )
                            },
                            live = TempSampler.currentSeries(sel),
                            recording = TempSampler.recording,
                            recTime = clockOf(
                                ((nowTick - TempSampler.recStartedAt) / 1000L).toInt().coerceAtLeast(0),
                            ),
                            intervalLabel = (TempSampler.intervalMs / 1000L).toString() + "s",
                            sampleIntervalMs = TempSampler.intervalMs,
                            onBack = { dshGoBack() },
                            onOpenHistory = { screen = Screen.HISTORY },
                            onToggleRecording = { toggleRecording() },
                            onCycleInterval = { cycleInterval() },
                            onSelectZone = { label ->
                                TempSampler.selectedZone =
                                    if (TempSampler.selectedZone == label) null else label
                            },
                            statusLine = TempSampler.readingError,
                            statusIsError = TempSampler.recording && TempSampler.recSamples == 0,
                        )
                    }

                    Screen.HISTORY -> TemperatureHistoryScreen(
                        items = sessions.map { it.toItem() },
                        onBack = { screen = Screen.LIVE },
                        onOpen = { id ->
                            opened = sessions.firstOrNull { it.id == id }
                            screen = Screen.REPLAY
                        },
                        onClearAll = {
                            sessions = emptyList()
                            opened = null
                            TempStore.clear(this@TempActivity)
                            screen = Screen.LIVE
                        },
                        onExportAll = { exportAll() },
                    )

                    Screen.REPLAY -> {
                        val s = opened
                        if (s == null) {
                            LaunchedEffect(Unit) { screen = Screen.HISTORY }
                        } else {
                            TemperatureReplayScreen(
                                session = s,
                                onBack = { screen = Screen.HISTORY },
                                onExport = { exportSession(s) },
                                onDelete = { deleteSession(s.id) },
                            )
                        }
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ 录制

    private fun toggleRecording() {
        if (TempSampler.recording) {
            val session = TempSampler.stopRecording()
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            if (session == null) {
                toast("样本太少，这次没有保存")
                return
            }
            sessions = (listOf(session) + sessions).take(50)
            TempStore.save(this, sessions)
            opened = session
            screen = Screen.REPLAY
        } else {
            if (TempSampler.startRecording()) {
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            } else {
                toast("暂时读不到热区")
            }
        }
    }

    private fun cycleInterval() {
        val steps = TempSampler.intervalSteps
        val idx = steps.indexOf(TempSampler.intervalMs)
        val next = steps[(idx + 1).mod(steps.size)]
        TempSampler.setInterval(next)
        prefs.edit().putLong(KEY_INTERVAL, next).apply()
    }

    // ------------------------------------------------------------------ 历史

    private fun deleteSession(id: Long) {
        sessions = sessions.filterNot { it.id == id }
        opened = null
        TempStore.save(this, sessions)
        screen = Screen.HISTORY
        toast("已删除")
    }

    private fun exportSession(s: TempSession) {
        try {
            val dir = downloadDir()
            val stamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.CHINA).format(Date(s.startedAt))
            val file = File(dir, "温度_$stamp.csv")
            val step = s.durationSec.toDouble() / s.values.size.coerceAtLeast(1)
            val sb = StringBuilder()
            sb.append("时间,").append(dayOf(s.startedAt)).append(' ').append(hm(s.startedAt)).append('\n')
            sb.append("时长,").append(clockOf(s.durationSec)).append('\n')
            sb.append("间隔,").append(s.sampleIntervalMs).append("ms\n")
            sb.append("跟随热区,").append(s.primaryLabel.ifEmpty { "未知" }).append('\n')
            sb.append("序号,时间,").append(s.primaryLabel.ifEmpty { "温度" }).append("(°C)\n")
            s.values.forEachIndexed { i, v ->
                val at = s.startedAt + (i * step).toLong() * 1000L
                sb.append(i + 1).append(',')
                    .append(SimpleDateFormat("HH:mm:ss", Locale.CHINA).format(Date(at))).append(',')
                    .append(String.format(Locale.US, "%.1f", v)).append('\n')
            }
            if (s.zonePeaks.isNotEmpty()) {
                sb.append('\n').append("热区,峰值(°C)\n")
                s.zonePeaks.forEach {
                    sb.append(it.label).append(',')
                        .append(String.format(Locale.US, "%.1f", it.peak)).append('\n')
                }
            }
            file.writeText(sb.toString())
            toast("已导出到 下载/系统工具/" + file.name)
        } catch (e: Exception) {
            toast("导出失败：" + (e.message ?: "无法写入下载目录"))
        }
    }

    private fun exportAll() {
        if (sessions.isEmpty()) {
            toast("还没有录制记录")
            return
        }
        try {
            val dir = downloadDir()
            val stamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.CHINA).format(Date())
            val file = File(dir, "温度汇总_$stamp.csv")
            val sb = StringBuilder()
            sb.append("开始时间,时长,样本数,电池(°C),峰值(°C),平均(°C),跟随热区\n")
            sessions.sortedByDescending { it.startedAt }.forEach { s ->
                val at = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date(s.startedAt))
                sb.append(at).append(',')
                    .append(clockOf(s.durationSec)).append(',')
                    .append(s.values.size).append(',')
                    .append(String.format(Locale.US, "%.1f", s.batteryPeak)).append(',')
                    .append(String.format(Locale.US, "%.1f", s.peak)).append(',')
                    .append(String.format(Locale.US, "%.1f", s.average)).append(',')
                    .append(s.primaryLabel).append('\n')
            }
            file.writeText(sb.toString())
            toast("已导出到 下载/系统工具/" + file.name)
        } catch (e: Exception) {
            toast("导出失败：" + (e.message ?: "无法写入下载目录"))
        }
    }

    private fun downloadDir(): File {
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "系统工具",
        )
        dir.mkdirs()
        return dir
    }

    // ------------------------------------------------------------------ 杂项

    override fun onDshBackPressed() {
        when (screen) {
            Screen.REPLAY -> screen = Screen.HISTORY
            Screen.HISTORY -> screen = Screen.LIVE
            Screen.LIVE -> super.onDshBackPressed()
        }
    }

    override fun onDestroy() {
        // 只清自己的屏常亮；录制归 TempSampler，退出本页不影响它
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        super.onDestroy()
    }

    private fun toast(text: String) {
        runOnUiThread { Toast.makeText(this, text, Toast.LENGTH_SHORT).show() }
    }

    private fun TempSession.toItem(): TempHistoryItem = TempHistoryItem(
        id = id,
        day = dayOf(startedAt),
        time = hm(startedAt),
        duration = clockOf(durationSec),
        samples = values.size,
        peak = batteryPeak,
        average = average,
        isBattery = hasBattery,
        zoneLabel = primaryLabel,
    )

    private companion object {
        const val KEY_INTERVAL = "temp_interval_ms"
    }
}