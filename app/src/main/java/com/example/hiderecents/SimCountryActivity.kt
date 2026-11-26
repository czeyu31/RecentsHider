package com.example.hiderecents

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.example.hiderecents.sim.CallRecord
import com.example.hiderecents.sim.CarrierConfig
import com.example.hiderecents.sim.CountryPreset
import com.example.hiderecents.sim.Presets
import com.example.hiderecents.sim.RecentCountry
import com.example.hiderecents.sim.SimHistory
import com.example.hiderecents.sim.SimInfo
import com.example.hiderecents.sim.SimSlot
import com.example.hiderecents.ui.components.DshAnimatedDialog
import com.example.hiderecents.ui.components.dshClickable
import com.example.hiderecents.ui.screens.SimCarrierPickerScreen
import com.example.hiderecents.ui.screens.SimConfirmSheet
import com.example.hiderecents.ui.screens.SimCountryPickerScreen
import com.example.hiderecents.ui.screens.SimCountryScreen
import com.example.hiderecents.ui.screens.SimSegment
import com.example.hiderecents.ui.screens.relativeTime
import com.example.hiderecents.ui.theme.AppTheme
import com.example.hiderecents.ui.theme.Dsh
import com.example.hiderecents.ui.theme.DshTheme
import com.example.hiderecents.ui.theme.applyDshSystemBars
import rikka.shizuku.Shizuku
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * SIM 国家码覆盖。
 *
 * 状态全部留在这一层，Compose 只管画：读卡信息走 [SimInfo]，
 * 配置的读写与兜底通道在 sim/ 包里。
 */
class SimCountryActivity : DshActivity() {

    private lateinit var prefs: SharedPreferences

    private var theme by mutableStateOf(AppTheme.SYSTEM)
    private var slots by mutableStateOf<List<SimSlot>>(emptyList())
    private var selectedIndex by mutableStateOf(0)
    private var target by mutableStateOf<CountryPreset?>(null)
    private var carrierName by mutableStateOf("")
    private var segment by mutableStateOf(SimSegment.COUNTRY)
    private var recent by mutableStateOf<List<RecentCountry>>(emptyList())
    private var calls by mutableStateOf<List<CallRecord>>(emptyList())
    private var todayCount by mutableStateOf(0)
    private var pickerOpen by mutableStateOf(false)
    private var carrierPickerOpen by mutableStateOf(false)
    private var confirmOpen by mutableStateOf(false)
    private var acknowledged by mutableStateOf(false)
    private var busy by mutableStateOf(false)
    private var message by mutableStateOf<String?>(null)
    private var shizukuLabel by mutableStateOf("未连接")
    private var channelLabel by mutableStateOf("检测中")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        prefs = getSharedPreferences("hide_recents_prefs", Context.MODE_PRIVATE)
        theme = AppTheme.fromPref(prefs.getString("ui_theme", null))
        applyDshSystemBars(theme)
        reloadHistory()

        setContent {
            DshTheme(theme) {
                Box(Modifier.fillMaxSize()) {
                    val slot = slots.getOrNull(selectedIndex)
                    if (pickerOpen) {
                        SimCountryPickerScreen(
                            currentIso = slot?.let { it.countryOverride ?: it.iso },
                            recent = recent,
                            onPick = {
                                pickerOpen = false
                                target = it
                                // 带上该地区的代表运营商名：部分应用认运营商名而不认国家码
                                carrierName = Presets.representativeCarrier(it.code).orEmpty()
                            },
                            onBack = { pickerOpen = false },
                        )
                    } else if (carrierPickerOpen) {
                        SimCarrierPickerScreen(
                            country = target,
                            current = carrierName,
                            onPick = {
                                carrierName = it
                                carrierPickerOpen = false
                            },
                            onBack = { carrierPickerOpen = false },
                        )
                    } else {
                        SimCountryScreen(
                            slots = slots,
                            selectedIndex = selectedIndex,
                            target = target,
                            carrierName = carrierName,
                            recent = recent,
                            calls = calls,
                            todayCount = todayCount,
                            shizukuVersion = shizukuLabel,
                            channelLabel = channelLabel,
                            uid = Process.myUid(),
                            segment = segment,
                            busy = busy,
                            message = message,
                            onSegment = { segment = it },
                            onSelectSlot = { selectedIndex = it },
                            onPickTarget = { pickerOpen = true },
                            onPickCarrier = { carrierPickerOpen = true },
                            onRecentPick = { item ->
                                // 点「最近使用」= 直接套用上次的配置，弹出确认抽屉。
                                // 运营商名必须按这条记录的国家重新取，否则会带着上一次的旧值。
                                val preset = Presets.byCode(item.code)
                                target = preset ?: CountryPreset(item.code, item.label, item.mcc)
                                carrierName = Presets.representativeCarrier(item.code).orEmpty()
                                acknowledged = false
                                confirmOpen = true
                            },
                            onApply = {
                                message = null
                                acknowledged = false
                                when {
                                    slots.getOrNull(selectedIndex) == null ->
                                        message = "未检测到 SIM 卡，无法写入运营商配置"

                                    target == null -> pickerOpen = true
                                    else -> confirmOpen = true
                                }
                            },
                            onReset = { reset() },
                            onExport = { exportLog() },
                            onBack = { dshGoBack() },
                        )
                    }

                    if (confirmOpen && slot != null && target != null) {
                        SimConfirmDialog(
                            slot = slot,
                            target = target!!,
                            carrierName = carrierName,
                            acknowledged = acknowledged,
                            busy = busy,
                            onAcknowledgedChange = { acknowledged = it },
                            onConfirm = { applyTarget(slot, target!!) },
                            onDismiss = { confirmOpen = false },
                        )
                    }
                }
            }
        }

        requestShizukuPermission()
        refresh(delayMillis = 0)
    }

    override fun onResume() {
        super.onResume()
        val prefTheme = AppTheme.fromPref(prefs.getString("ui_theme", null))
        if (prefTheme != theme) {
            theme = prefTheme
            applyDshSystemBars(prefTheme)
        }
        shizukuLabel = readShizukuLabel()
        reloadHistory()
        refresh(delayMillis = 0)
    }

    override fun onDshBackPressed() {
        if (confirmOpen) {
            confirmOpen = false
            return
        }
        if (pickerOpen) {
            pickerOpen = false
            return
        }
        super.onDshBackPressed()
    }

    // ---------------------------------------------------------------- 状态

    private fun reloadHistory() {
        recent = SimHistory.recent(this)
        calls = SimHistory.calls(this)
        todayCount = SimHistory.todayCalls(this).size
    }

    /** 用调用记录还原「上次结果」，这样重启或返回页面后提示不会凭空消失 */
    private fun restoredMessage(): String? {
        val slot = slots.getOrNull(selectedIndex) ?: return null
        val last = calls.firstOrNull { it.subId == slot.subId } ?: return null
        val whenText = relativeTime(last.timestamp)
        return when {
            !last.ok -> "上次（" + whenText + "）写入失败：" + last.note.ifEmpty { "未知错误" }
            last.action == "restore" -> "上次（" + whenText + "）已还原为运营商默认"
            else -> {
                val preset = Presets.byCode(last.countryCode)
                val label = preset?.let { it.label + " · " + it.mcc }
                    ?: last.countryCode.uppercase().ifEmpty { slot.countryOverride?.uppercase() ?: "国家码" }
                "上次（" + whenText + "）已写入 " + label +
                    if (last.note.contains("non-persistent")) "（非持久，重启后失效）" else ""
            }
        }
    }

    private fun refresh(delayMillis: Long) {
        Thread {
            if (delayMillis > 0) Thread.sleep(delayMillis)
            val loaded = SimInfo.slots(this)
            val reachable = CarrierConfig.channelReachable()
            runOnUiThread {
                slots = loaded
                if (selectedIndex >= loaded.size) selectedIndex = 0
                shizukuLabel = readShizukuLabel()
                channelLabel = if (reachable) "ICarrierConfigLoader 可达" else "不可达"
                reloadHistory()
                // 页面重建（进程被清、切走再回来）后，把上次结果从调用记录里回显出来
                if (message == null) message = restoredMessage()
            }
        }.start()
    }

    private fun requestShizukuPermission() {
        runCatching {
            if (Shizuku.pingBinder() &&
                Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED
            ) {
                Shizuku.requestPermission(2001)
            }
        }
    }

    private fun readShizukuLabel(): String = runCatching {
        when {
            !Shizuku.pingBinder() -> "未连接"
            Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED -> "未授权"
            else -> "已连接 · API " + Shizuku.getVersion()
        }
    }.getOrDefault("不可用")

    // ---------------------------------------------------------------- 写入

    private fun applyTarget(slot: SimSlot, country: CountryPreset) {
        confirmOpen = false
        busy = true
        message = null

        Thread {
            val started = SystemClock.elapsedRealtime()
            val outcome = CarrierConfig.setOverride(
                this,
                slot.subId,
                country.code,
                carrierName.trim().ifEmpty { null },
            )
            val millis = SystemClock.elapsedRealtime() - started

            SimHistory.addCall(
                this,
                CallRecord(
                    timestamp = System.currentTimeMillis(),
                    action = "overrideConfig",
                    subId = slot.subId,
                    ok = outcome.ok,
                    millis = millis,
                    note = noteOf(outcome),
                    countryCode = country.code,
                ),
            )
            if (outcome.ok) {
                SimHistory.remember(
                    this,
                    RecentCountry(
                        code = country.code,
                        label = country.label,
                        mcc = country.mcc,
                        operator = slot.carrierName,
                        timestamp = System.currentTimeMillis(),
                    ),
                )
            }

            runOnUiThread {
                busy = false
                message = if (outcome.ok) {
                    buildString {
                        append("已写入 ").append(country.label).append(" · ").append(country.mcc)
                        if (!outcome.persistent) {
                            append("。本次只写进内存：该 ROM 要求持久写入必须是系统应用，")
                            append("重启或重新插卡后会失效")
                        }
                    }
                } else {
                    "写入失败：" + (outcome.error ?: "未知错误")
                }
                reloadHistory()
                // 兜底通道的写入由另一个上下文下发，读回来的值会晚一点才变
                refresh(delayMillis = if (outcome.delayed) 900 else 250)
            }
        }.start()
    }

    private fun reset() {
        val slot = slots.getOrNull(selectedIndex)
        if (slot == null) {
            Toast.makeText(this, "没有可用的 SIM 卡", Toast.LENGTH_SHORT).show()
            return
        }
        busy = true
        message = null

        Thread {
            val started = SystemClock.elapsedRealtime()
            val outcome = CarrierConfig.reset(this, slot.subId)
            val millis = SystemClock.elapsedRealtime() - started

            SimHistory.addCall(
                this,
                CallRecord(
                    timestamp = System.currentTimeMillis(),
                    action = "restore",
                    subId = slot.subId,
                    ok = outcome.ok,
                    millis = millis,
                    note = noteOf(outcome),
                ),
            )

            runOnUiThread {
                busy = false
                message = if (outcome.ok) "已还原为运营商默认" else "还原失败：" + (outcome.error ?: "未知错误")
                reloadHistory()
                refresh(delayMillis = if (outcome.delayed) 900 else 250)
            }
        }.start()
    }

    // ---------------------------------------------------------------- 导出

    private fun noteOf(outcome: com.example.hiderecents.sim.OverrideOutcome): String = when {
        outcome.error != null -> outcome.error
        !outcome.persistent -> "non-persistent"
        outcome.delayed -> "instrumentation"
        else -> ""
    }

    private fun exportLog() {
        Thread {
            val dir = getExternalFilesDir(null) ?: filesDir
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            val file = File(dir, "sim-country-log-$stamp.txt")
            val body = buildString {
                appendLine("# System Tool · SIM 国家码调用记录（已脱敏）")
                appendLine("# 导出时间 " + SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()))
                appendLine("# 记录不含运营商名称、号码与卡标识")
                appendLine()
                SimHistory.calls(this@SimCountryActivity).forEach { record ->
                    val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(record.timestamp))
                    appendLine(
                        time + "  action=" + record.action +
                            "  subId=" + record.subId +
                            "  ok=" + record.ok +
                            "  ms=" + record.millis +
                            (if (record.note.isNotEmpty()) "  note=" + record.note else ""),
                    )
                }
            }
            val ok = runCatching {
                file.parentFile?.mkdirs()
                file.writeText(body)
            }.isSuccess

            runOnUiThread {
                Toast.makeText(
                    this,
                    if (ok) "已导出到 " + file.absolutePath else "导出失败",
                    Toast.LENGTH_LONG,
                ).show()
            }
        }.start()
    }
}

@androidx.compose.runtime.Composable
private fun SimConfirmDialog(
    slot: SimSlot,
    target: CountryPreset,
    carrierName: String,
    acknowledged: Boolean,
    busy: Boolean,
    onAcknowledgedChange: (Boolean) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = Dsh.colors
    // 抽屉真的能往下拖：拖动量超过阈值就关闭，没超过就弹回去
    var dragOffset by remember { mutableFloatStateOf(0f) }

    DshAnimatedDialog(
        onDismissRequest = onDismiss,
        // decorFitsSystemWindows = false：让遮罩铺到状态栏，否则顶部会留一条颜色不一的边
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) { close ->
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            // 点空白关闭；抽屉自己吃掉点击，避免穿透
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.45f))
                    .dshClickable(scaleOnPress = false) { close() },
            )
            Box(
                Modifier
                    .fillMaxWidth()
                    .offset { IntOffset(0, dragOffset.roundToInt()) }
                    .pointerInput(Unit) {
                        detectVerticalDragGestures(
                            onDragEnd = {
                                if (dragOffset > 140f) close() else dragOffset = 0f
                            },
                            onDragCancel = { dragOffset = 0f },
                            onVerticalDrag = { _, delta ->
                                dragOffset = (dragOffset + delta).coerceAtLeast(0f)
                            },
                        )
                    }
                    .background(colors.canvas)
                    .dshClickable(scaleOnPress = false) {}
                    .navigationBarsPadding(),
            ) {
                SimConfirmSheet(
                    slot = slot,
                    target = target,
                    carrierName = carrierName,
                    acknowledged = acknowledged,
                    busy = busy,
                    onAcknowledgedChange = onAcknowledgedChange,
                    onConfirm = onConfirm,
                    onDismiss = { close() },
                )
            }
        }
    }
}