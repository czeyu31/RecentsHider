package com.example.hiderecents

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Environment
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.app.ActivityCompat
import androidx.core.content.FileProvider
import com.example.hiderecents.hr.HrLink
import com.example.hiderecents.hr.HrSession
import com.example.hiderecents.hr.HrDevice
import com.example.hiderecents.hr.HeartRateBle
import com.example.hiderecents.hr.HeartRateService
import com.example.hiderecents.hr.HeartRateStore
import com.example.hiderecents.hr.HrLiveState
import com.example.hiderecents.hr.analysisOf
import com.example.hiderecents.hr.clockOf
import com.example.hiderecents.hr.dayOf
import com.example.hiderecents.hr.downsample
import com.example.hiderecents.hr.hm
import com.example.hiderecents.hr.hms
import com.example.hiderecents.hr.hoursOf
import com.example.hiderecents.hr.sharePercents
import com.example.hiderecents.hr.zoneOfValue
import com.example.hiderecents.ui.screens.HeartRateHistoryScreen
import com.example.hiderecents.ui.screens.HeartRateReportScreen
import com.example.hiderecents.ui.screens.HeartRateScreen
import com.example.hiderecents.ui.screens.HrDeviceSheet
import com.example.hiderecents.ui.screens.HrSettingsSheet
import com.example.hiderecents.ui.screens.HrHistoryItem
import com.example.hiderecents.ui.screens.HrZoneSlice
import com.example.hiderecents.ui.theme.AppTheme
import com.example.hiderecents.ui.theme.Dsh
import com.example.hiderecents.ui.theme.DshTheme
import com.example.hiderecents.ui.theme.applyDshSystemBars
import kotlinx.coroutines.delay
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * 心率广播 · 正式版。
 *
 * 链路：扫描广播 0x180D 的设备 → 选一台 → GATT 连接（不配对）→ 订阅 0x2A37 → 实时心率。
 * 录制：按开始/停止，停止后立即生成会话报告，并写入 filesDir/hr_sessions.json（最多 50 条）。
 *
 * 主题跟随软件设置（hide_recents_prefs / ui_theme），页内不提供切换入口。
 * 录制期间保持屏幕常亮 —— 现在还没有做后台服务，熄屏会被系统限制扫描与连接。
 */
class HeartRateActivity : DshActivity() {

    private enum class Screen { LIVE, HISTORY, REPORT }

    private companion object {
        const val REQ_BLE = 2001
        /** 超过这个秒数没有新数据，就把大数字让回「--」。 */
        const val STALE_SEC = 20L
        /** 相邻两次心跳超过这个毫秒数，算一次断流。手环的推送间隔常在 5 秒上下，不能卡太紧。 */
        const val GAP_MS = 10000L
    }

    private val ble by lazy { HeartRateBle(this) }
    private var hr: HeartRateService? = null
    private var bound = false

    private var theme by mutableStateOf(AppTheme.PAPER)
    private var screen by mutableStateOf(Screen.LIVE)
    private var reportFromHistory by mutableStateOf(false)

    // 连接
    private var device by mutableStateOf<HrDevice?>(null)
    private var devices by mutableStateOf<List<HrDevice>>(emptyList())
    private var showPicker by mutableStateOf(false)
    private var link by mutableStateOf(HrLink.IDLE)
    private var linkText by mutableStateOf("")
    /** 连上设备的时刻：页面上的「会话时长」从这里起算。 */
    private var connectedAt = 0L

    // 设置
    private var showSettings by mutableStateOf(false)
    private var autoConnectOn by mutableStateOf(true)
    private var keepLinkOn by mutableStateOf(false)

    // 实时
    private var bpm by mutableStateOf(0)
    private var liveValues by mutableStateOf<List<Int>>(emptyList())
    private var packets by mutableStateOf(0)
    private var gaps by mutableStateOf(0)
    private var rssi by mutableStateOf(0)
    private var lastBeatAt by mutableStateOf(0L)
    private var nowTick by mutableStateOf(System.currentTimeMillis())

    // 录制
    private var recording by mutableStateOf(false)
    private var recStartAt = 0L
    private val recValues = ArrayList<Int>()
    private var recGapIndex = -1

    // 历史
    private var sessions by mutableStateOf<List<HrSession>>(emptyList())
    private var opened by mutableStateOf<HrSession?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences("hide_recents_prefs", Context.MODE_PRIVATE)
        theme = AppTheme.fromPref(prefs.getString("ui_theme", null))
        applyDshSystemBars(theme)

        sessions = HeartRateStore.load(this).filter { it.values.isNotEmpty() }
        autoConnectOn = HeartRateService.autoConnect(this)
        keepLinkOn = HeartRateService.keepLink(this)
        wireBle()
        bindHrService()

        setContent {
            DshTheme(theme) {
                LaunchedEffect(Unit) {
                    while (true) {
                        delay(1000)
                        nowTick = System.currentTimeMillis()
                    }
                }

                Box(Modifier.fillMaxSize()) {
                    // 三屏之间要有推进/退回的过渡，不能硬切
                    AnimatedContent(
                        targetState = screen to opened,
                        transitionSpec = {
                            val forward = when {
                                initialState.first == Screen.LIVE && targetState.first != Screen.LIVE -> true
                                initialState.first == Screen.HISTORY && targetState.first == Screen.REPORT -> true
                                else -> false
                            }
                            if (forward) {
                                (slideInHorizontally(tween(260, easing = FastOutSlowInEasing)) { it / 5 } +
                                    fadeIn(tween(200))) togetherWith
                                    (slideOutHorizontally(tween(260, easing = FastOutSlowInEasing)) { -it / 12 } +
                                        fadeOut(tween(160)))
                            } else {
                                (slideInHorizontally(tween(260, easing = FastOutSlowInEasing)) { -it / 5 } +
                                    fadeIn(tween(200))) togetherWith
                                    (slideOutHorizontally(tween(260, easing = FastOutSlowInEasing)) { it / 12 } +
                                        fadeOut(tween(160)))
                            }
                        },
                        label = "hrScreen",
                    ) { target ->
                        val s = target.second
                        when {
                            target.first == Screen.REPORT && s != null -> Report(s)
                            target.first == Screen.HISTORY -> History()
                            else -> Live()
                        }
                    }

                    if (showSettings) {
                        HrSettingsSheet(
                            autoConnect = autoConnectOn,
                            keepLink = keepLinkOn,
                            deviceName = HeartRateService.lastName(this@HeartRateActivity),
                            onAutoConnect = {
                                autoConnectOn = it
                                prefs().edit().putBoolean(HeartRateService.KEY_AUTO_CONNECT, it).apply()
                            },
                            onKeepLink = {
                                keepLinkOn = it
                                prefs().edit().putBoolean(HeartRateService.KEY_KEEP_LINK, it).apply()
                            },
                            onDismiss = { showSettings = false },
                        )
                    }

                    if (showPicker) {
                        HrDeviceSheet(
                            devices = devices,
                            scanning = link == HrLink.SCANNING,
                            statusText = if (link == HrLink.FAILED || link == HrLink.LOST) linkText else "",
                            connectedAddress = device?.address,
                            onDismiss = {
                                showPicker = false
                                ble.stopScan()
                            },
                            onRescan = {
                                if (link == HrLink.SCANNING) {
                                    ble.stopScan()
                                    link = HrLink.IDLE
                                } else {
                                    devices = emptyList()
                                    ble.startScan()
                                }
                            },
                            onPick = { picked ->
                                showPicker = false
                                connectTo(picked)
                            },
                            onDisconnect = {
                                disconnectDevice()
                                showPicker = false
                            },
                        )
                    }
                }
            }
        }

        // 上次连过、且开着「自动连接」就直接连；否则弹出设备列表省一次点击
        window.decorView.post { autoConnectOrPick() }
    }

    // ------------------------------------------------------------------ BLE

    /** 扫描留在页面里（只在选设备时用），连接和心率都交给前台服务。 */
    private fun wireBle() {
        ble.onDevices = { list -> runOnUiThread { devices = list } }
    }

    // ------------------------------------------------------------------ 服务

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val s = (binder as? HeartRateService.LocalBinder)?.service() ?: return
            hr = s
            bound = true
            s.listener = object : HeartRateService.Listener {
                override fun onState(state: HrLiveState) {
                    runOnUiThread { applyState(state) }
                }

                override fun onBeat(value: Int, at: Long) {
                    runOnUiThread { recordBeat(value, at) }
                }
            }
            applyState(s.currentState())
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            hr = null
            bound = false
        }
    }

    private fun bindHrService() {
        bindService(Intent(this, HeartRateService::class.java), serviceConnection, Context.BIND_AUTO_CREATE)
    }

    private fun applyState(st: HrLiveState) {
        val wasReady = link == HrLink.READY
        link = st.link
        linkText = st.linkText
        if (st.deviceMac.isNotEmpty()) {
            device = HrDevice(st.deviceMac, st.deviceName.ifEmpty { "心率设备" }, 0)
        }
        if (st.link == HrLink.READY) {
            // 会话起点以服务里的为准，页面重进也能接着计时
            connectedAt = if (st.connectedAt > 0L) st.connectedAt else {
                if (!wasReady) System.currentTimeMillis() else connectedAt
            }
            device?.let { d -> if (devices.none { it.address == d.address }) devices = devices + d }
        } else if (st.link == HrLink.IDLE || st.link == HrLink.LOST || st.link == HrLink.FAILED) {
            connectedAt = 0L
        }
        if (st.link == HrLink.FAILED) toast(st.linkText)
        if (st.bpm != bpm) bpm = st.bpm
        // 断线时不要把上一次的信号强度留在界面上
        rssi = if (st.link == HrLink.READY) st.rssi else 0
        if (st.packets != packets) packets = st.packets
        if (st.gaps != gaps) gaps = st.gaps
        if (st.lastBeatAt != lastBeatAt) lastBeatAt = st.lastBeatAt
        // 曲线以服务里的最近 60 个样本为准，这样从工具箱重新进来曲线不会空掉
        if (st.recent.isNotEmpty() && st.recent != liveValues) liveValues = st.recent
    }

    private fun recordBeat(value: Int, at: Long) {
        if (lastBeatAt > 0 && at - lastBeatAt > GAP_MS && recGapIndex < 0 && recording) {
            recGapIndex = recValues.size
        }
        // liveValues 由服务推送的 state.recent 统一维护，这里不再重复追加
        if (recording) recValues.add(value)
    }

    /** 上次连过、开着「自动连接」就直接连，连不上再退回选设备。 */
    private fun autoConnectOrPick() {
        // 服务里可能已经连着（用户从别的页面回来），这种情况直接沿用，不要重连：
        // 重连会让手环重新握手、会话时长也会被清零。
        val live = hr?.currentState()
        if (live != null && (live.link == HrLink.READY || live.link == HrLink.CONNECTING)) {
            applyState(live)
            if (live.link == HrLink.CONNECTING) toast("正在连接 " + live.deviceName)
            return
        }
        val mac = HeartRateService.lastMac(this)
        if (!autoConnectOn || mac.isEmpty() || !hasBlePermissions() || !ble.bluetoothOn) {
            openPicker()
            return
        }
        device = HrDevice(mac, HeartRateService.lastName(this).ifEmpty { "上次的设备" }, 0)
        HeartRateService.connect(this, mac, HeartRateService.lastName(this))
    }

    private fun connectTo(target: HrDevice) {
        device = target
        bpm = 0
        liveValues = emptyList()
        packets = 0
        gaps = 0
        lastBeatAt = 0L
        connectedAt = 0L
        HeartRateService.connect(this, target.address, target.name)
    }

    private fun disconnectDevice() {
        hr?.disconnectLink()
        connectedAt = 0L
        device = null
        bpm = 0
        liveValues = emptyList()
        lastBeatAt = 0L
    }

    private fun hasBlePermissions(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val scan = checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
        val conn = checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        return scan && conn
    }

    private fun openPicker() {
        if (!hasBlePermissions()) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT),
                REQ_BLE,
            )
            return
        }
        if (!ble.bluetoothOn) {
            toast("请先打开手机蓝牙")
            return
        }
        showPicker = true
        devices = emptyList()
        ble.startScan()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_BLE) return
        val granted = grantResults.isNotEmpty() &&
            grantResults.all { it == PackageManager.PERMISSION_GRANTED }
        if (granted) {
            openPicker()
        } else {
            toast("需要「附近的设备」权限才能扫描心率设备")
        }
    }

    // ------------------------------------------------------------------ 录制

    private fun toggleRecording() {
        if (recording) {
            stopRecording()
        } else {
            if (link != HrLink.READY) {
                toast("先连接一台心率设备")
                if (!showPicker) openPicker()
                return
            }
            recValues.clear()
            recGapIndex = -1
            recStartAt = System.currentTimeMillis()
            recording = true
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun stopRecording() {
        recording = false
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val elapsed = ((System.currentTimeMillis() - recStartAt) / 1000L).toInt()
        if (recValues.size < 5) {
            toast("只收到 " + recValues.size + " 个样本，这次没有保存")
            recValues.clear()
            return
        }
        // 注意：downsample 在样本不超上限时会原样返回同一个 List，先把录制缓冲拷一份
        val values = downsample(recValues.toList(), 600)
        val gap = if (recGapIndex < 0) -1 else
            (recGapIndex.toDouble() * values.size / recValues.size).toInt().coerceIn(0, values.lastIndex)
        val session = HrSession(
            id = System.currentTimeMillis(),
            startedAt = recStartAt,
            durationSec = elapsed.coerceAtLeast(values.size),
            values = values,
            gapIndex = gap,
            deviceName = device?.name.orEmpty(),
            deviceMac = device?.address.orEmpty(),
        )
        recValues.clear()
        sessions = (listOf(session) + sessions).take(50)
        HeartRateStore.save(this, sessions)
        opened = session
        reportFromHistory = false
        screen = Screen.REPORT
    }

    /** 会话时长从连上设备那一刻算起，跟录不录制无关；录制时长在右上角 REC 里。 */
    private fun sessionElapsed(): Int =
        if (connectedAt <= 0L) 0 else ((nowTick - connectedAt) / 1000L).toInt()

    private fun recElapsed(): Int =
        if (!recording) 0 else ((System.currentTimeMillis() - recStartAt) / 1000L).toInt()

    private fun ageText(): String {
        if (lastBeatAt == 0L) return if (link == HrLink.READY) "等待数据" else "未连接"
        val age = (nowTick - lastBeatAt) / 1000L
        return when {
            age <= 1 -> "刚刚"
            age < 60 -> "$age 秒前"
            else -> (age / 60).toString() + " 分钟前"
        }
    }

    private fun dropRateText(): String {
        val total = packets + gaps
        if (total <= 0) return "—"
        val pct = gaps * 100.0 / total
        return String.format(Locale.CHINA, "%.1f%%", pct)
    }

    // ------------------------------------------------------------------ 屏幕

    @Composable
    private fun Live() {
        val fresh = lastBeatAt > 0 && (nowTick - lastBeatAt) / 1000L <= STALE_SEC
        val showStatus = link == HrLink.CONNECTING || link == HrLink.FAILED || link == HrLink.LOST
        HeartRateScreen(
            bpm = if (fresh) bpm else 0,
            values = liveValues,
            deviceName = device?.name ?: "未连接设备",
            deviceMac = device?.address ?: "—",
            rssi = rssi,
            packets = packets,
            dropRate = dropRateText(),
            sessionText = if (connectedAt > 0L) clockOf(((nowTick - connectedAt) / 1000L).toInt()) else "—",
            ageText = ageText(),
            sourceLabel = "广播 0x180D",
            recording = recording,
            recTime = clockOf(recElapsed()),
            onBack = { dshGoBack() },
            onOpenHistory = { screen = Screen.HISTORY },
            onOpenSettings = { showSettings = true },
            onToggleRecording = { toggleRecording() },
            onChangeDevice = { openPicker() },
            statusLine = if (showStatus) linkText else "",
            statusIsError = link == HrLink.FAILED || link == HrLink.LOST,
        )
    }

    @Composable
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
                HeartRateStore.clear(this)
                toast("已清空录制历史")
            },
        )
    }

    @Composable
    private fun Report(s: HrSession) {
        // 空样本（旧版本写坏的记录）直接退回上一屏，不进报告页
        if (s.values.isEmpty()) {
            LaunchedEffect(s.id) {
                toast("这条记录没有样本，已跳过")
                opened = null
                screen = if (reportFromHistory) Screen.HISTORY else Screen.LIVE
            }
        } else {
            ReportBody(s)
        }
    }

    @Composable
    private fun ReportBody(s: HrSession) {
        val values = s.values
        val secPerPoint = (s.durationSec.toDouble() / values.size).coerceAtLeast(1.0)
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
        val completeness = if (s.gapIndex >= 0) {
            String.format(Locale.CHINA, "%.1f%%", 100.0 * values.size / (values.size + 1))
        } else {
            "100%"
        }
        // 短会话三段刻度都落在同一分钟里，会显示成 21:49 / 21:49 / 21:49，改用带秒的格式
        val shortSession = s.durationSec < 600
        val axisFmt: (Long) -> String = if (shortSession) ::hms else ::hm
        HeartRateReportScreen(
            periodText = dayOf(s.startedAt) + " " + hm(s.startedAt) + " – " + hm(end),
            durationText = clockOf(s.durationSec),
            avg = if (values.size >= 1) values.average().roundToInt() else 0,
            min = values.minOrNull() ?: 0,
            max = values.maxOrNull() ?: 0,
            samples = values.size,
            completeness = completeness,
            values = values,
            zones = zones,
            analysis = analysisOf(values, secPerPoint, s.gapIndex),
            gapIndex = s.gapIndex,
            axisStart = axisFmt(s.startedAt),
            axisMid = axisFmt(mid),
            axisEnd = axisFmt(end),
            pointLabel = { i -> axisFmt(s.startedAt + (i * secPerPoint * 1000).toLong()) },
            onBack = { screen = if (reportFromHistory) Screen.HISTORY else Screen.LIVE },
            onDelete = {
                sessions = sessions.filterNot { it.id == s.id }
                opened = null
                HeartRateStore.save(this, sessions)
                screen = if (reportFromHistory) Screen.HISTORY else Screen.LIVE
                toast("已删除这次会话")
            },
            onExportCsv = { exportCsv(s) },
            onShareChart = { shareChart(s) },
        )
    }

    // ------------------------------------------------------------------ 导出

    private fun exportCsv(s: HrSession) {
        try {
            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "系统工具",
            )
            dir.mkdirs()
            val stamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.CHINA).format(Date(s.startedAt))
            val file = File(dir, "心率_$stamp.csv")
            val step = s.durationSec.toDouble() / s.values.size.coerceAtLeast(1)
            val sb = StringBuilder()
            sb.append("设备,").append(s.deviceName.ifEmpty { "未知" }).append('\n')
            sb.append("时间,").append(dayOf(s.startedAt)).append(' ').append(hm(s.startedAt)).append('\n')
            sb.append("时长,").append(clockOf(s.durationSec)).append('\n')
            sb.append("序号,时间,心率(bpm)\n")
            s.values.forEachIndexed { i, v ->
                val at = s.startedAt + (i * step).roundToLong() * 1000L
                sb.append(i + 1).append(',')
                    .append(SimpleDateFormat("HH:mm:ss", Locale.CHINA).format(Date(at))).append(',')
                    .append(v).append('\n')
            }
            file.writeText(sb.toString())
            toast("已导出到 下载/系统工具/" + file.name)
        } catch (e: Exception) {
            toast("导出失败：" + (e.message ?: "无法写入下载目录"))
        }
    }

    private fun shareChart(s: HrSession) {
        try {
            val file = HrChartImage.render(this, s, theme)
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "分享心率曲线"))
        } catch (e: Exception) {
            toast("分享失败：" + (e.message ?: "未知原因"))
        }
    }

    // ------------------------------------------------------------------ 杂项

    override fun onDshBackPressed() {
        if (showSettings) {
            showSettings = false
            return
        }
        if (showPicker) {
            showPicker = false
            ble.stopScan()
            return
        }
        when (screen) {
            Screen.REPORT -> screen = if (reportFromHistory) Screen.HISTORY else Screen.LIVE
            Screen.HISTORY -> screen = Screen.LIVE
            Screen.LIVE -> super.onDshBackPressed()
        }
    }

    override fun onDestroy() {
        if (recording) stopRecording()
        ble.stopScan()
        hr?.listener = null
        // 设置里勾了「退出后保持连接」就留一个前台服务在后台继续收心率，否则彻底断开
        if (!keepLinkOn) {
            hr?.disconnectLink()
            stopService(Intent(this, HeartRateService::class.java))
        } else if (isFinishing && !isChangingConfigurations) {
            // 明确告诉用户连接没断，别看页面没了就以为断了
            runCatching { toast("连接保持中，通知栏可查看心率") }
        }
        if (bound) {
            unbindService(serviceConnection)
            bound = false
        }
        super.onDestroy()
    }

    private fun prefs() = getSharedPreferences(HeartRateService.PREF, Context.MODE_PRIVATE)

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }

    private fun HrSession.toItem(): HrHistoryItem {
        val end = startedAt + durationSec * 1000L
        return HrHistoryItem(
            id = id,
            day = dayOf(startedAt),
            timeRange = hm(startedAt) + " – " + hm(end),
            duration = clockOf(durationSec),
            samples = values.size,
            avg = if (values.size >= 1) values.average().roundToInt() else 0,
            min = values.minOrNull() ?: 0,
            max = values.maxOrNull() ?: 0,
            values = values,
        )
    }
}

private val ZONE_LABELS = listOf(
    "静息" to "< 60",
    "日常" to "60 – 100",
    "燃脂" to "100 +",
)
