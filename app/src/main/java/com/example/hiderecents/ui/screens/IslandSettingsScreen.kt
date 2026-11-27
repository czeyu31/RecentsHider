package com.example.hiderecents.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.hiderecents.island.IslandArt
import com.example.hiderecents.island.IslandPublish
import com.example.hiderecents.island.IslandSettings
import com.example.hiderecents.island.IslandStandard
import com.example.hiderecents.island.XiaomiFocusGate
import com.example.hiderecents.ui.components.DshDivider
import com.example.hiderecents.ui.components.DshIcon
import com.example.hiderecents.ui.components.DshIconKind
import com.example.hiderecents.ui.components.DshPanel
import com.example.hiderecents.ui.components.DshSwitch
import com.example.hiderecents.ui.components.dshCanvasBackground
import com.example.hiderecents.ui.components.dshClickable
import com.example.hiderecents.ui.components.paperHeaderRule
import com.example.hiderecents.ui.theme.Dsh
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.abs
import kotlin.math.round

/** 标尺的最小刻度：0.1℃ */
private const val TEMP_STEP = 0.1f

/** 温度区间上下限 */
private const val TEMP_MIN = 0f
private const val TEMP_MAX = 100f

/**
 * 原生岛设置。一个岛一栏，栏与栏之间跟手左右滑动。
 *
 * 三栏内容自己横移，不走 pager：pager 的拖拽会和标尺抢手势，
 * 自己平移既能跟手预览，也能把阈值那块整段排除在翻页之外。
 */
@Composable
fun IslandSettingsScreen(
    onBack: () -> Unit,
    onChange: () -> Unit,
    onTest: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Dsh.colors
    val dimens = Dsh.dimens
    val type = Dsh.type

    // 岛上按钮只在两条「走通知」的方式里可用（实时更新 / 标准实时活动）；焦点通知 V3 没有按钮
    val buttonSupported = IslandPublish.liveUpdatesSupported() || IslandSettings.mode == "std"

    // 分类栏：通用在最前，后面按岛排。加新岛就在这里加一项、再加一个分支
    val tabs = listOf("通用", "心率岛", "温度岛")
    val scope = rememberCoroutineScope()
    val settle = remember { Animatable(0f) }
    var page by remember { mutableStateOf(0) }
    var dragging by remember { mutableStateOf(false) }
    var dragX by remember { mutableStateOf(0f) }
    val widthPx = remember { floatArrayOf(1f) }
    // 阈值面板在屏幕上的纵向范围（top, bottom）；bottom < 0 表示还没量到
    val blockTop = remember { floatArrayOf(0f, -1f) }
    val contentTop = remember { floatArrayOf(0f) }
    val tempScroll = rememberScrollState()

    val fraction = if (widthPx[0] > 1f) {
        -((if (dragging) dragX else settle.value) / widthPx[0])
    } else {
        page.toFloat()
    }

    Box(modifier.fillMaxSize().dshCanvasBackground()) {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = dimens.screenPadding),
        ) {
            Spacer(Modifier.height(4.dp))
            Row(
                Modifier.fillMaxWidth().height(56.dp).paperHeaderRule(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(32.dp).clip(RoundedCornerShape(dimens.iconRadius))
                        .dshClickable(onClick = onBack),
                    contentAlignment = Alignment.Center,
                ) {
                    DshIcon(DshIconKind.BACK, iconSize = 15.dp, tint = colors.inkMuted)
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("原生岛", style = type.h2, color = colors.ink)
                    Spacer(Modifier.height(5.dp))
                    Text("按岛分类 · 左右滑动切换", style = type.label, color = colors.inkFaint)
                }
            }

            // 本机能力提示：澎湃 3（Android 15）没有实时更新机制，只能落通知栏
            if (!buttonSupported) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "本机系统未开放第三方上岛（Android " + android.os.Build.VERSION.RELEASE +
                        "）：岛上的样式会退化为普通常驻通知，设置仍会保存。",
                    style = type.caption,
                    color = colors.accent,
                )
                Spacer(Modifier.height(6.dp))
            }

            TabsRow(tabs, fraction) { index ->
                page = index
                dragging = false
                scope.launch { settle.animateTo(-index * widthPx[0], tween(260)) }
            }
            DshDivider()

            BoxWithConstraints(
                Modifier.weight(1f).fillMaxWidth().clipToBounds()
                    .onGloballyPositioned {
                        widthPx[0] = it.size.width.toFloat().coerceAtLeast(1f)
                        contentTop[0] = it.positionInRoot().y
                    }
                    .pointerInput(Unit) {
                        val slop = viewConfiguration.touchSlop
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val rootY = contentTop[0] + down.position.y
                            // 阈值块的位置 = 容器顶 + 块内局部坐标 - 当前滚动量；这样滚动后也不会算错
                            val blockTopY = blockTop[0] - tempScroll.value
                            val blockBottomY = blockTopY + blockTop[1]
                            val inBlock = blockTop[1] > 0f && rootY >= blockTopY && rootY <= blockBottomY
                            if (!inBlock) {
                                val min = -(tabs.size - 1) * widthPx[0]
                                var total = 0f
                                var started = false
                                var lastDx = 0f
                                dragX = settle.value
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                    if (!change.pressed) break
                                    if (change.isConsumed) continue
                                    val dx = change.positionChange().x
                                    total += dx
                                    if (!started && abs(total) > slop) {
                                        started = true
                                        dragging = true
                                    }
                                    if (started) {
                                        change.consume()
                                        lastDx = dx
                                        dragX = (dragX + dx).coerceIn(min, 0f)
                                    }
                                }
                                if (started) {
                                    // 甩得快就多走半栏，否则按松手时谁占得多
                                    val current = -dragX / widthPx[0]
                                    val fling = if (abs(lastDx) > 2f) -lastDx / abs(lastDx) * 0.35f else 0f
                                    val target = round(current + fling).toInt().coerceIn(0, tabs.size - 1)
                                    page = target
                                    scope.launch {
                                        settle.snapTo(dragX)
                                        dragging = false
                                        settle.animateTo(-target * widthPx[0], tween(260))
                                    }
                                }
                            }
                        }
                    },
            ) {
                val w = constraints.maxWidth.toFloat().coerceAtLeast(1f)
                widthPx[0] = w
                val shift = if (dragging) dragX else settle.value
                // 每页各自占满一屏，靠位移摆位置；不做超宽 Row，免得被父约束压扁
                PageLayer(offsetPx = shift) { GeneralTab(onChange, onTest) }
                PageLayer(offsetPx = w + shift) { HeartTab(onChange, buttonSupported) }
                PageLayer(offsetPx = 2 * w + shift) { TempTab(onChange, buttonSupported, blockTop, tempScroll) }
            }
        }
    }
}

/** 一页内容：整屏大小，用像素位移摆放（不参与 Row 测量，避免被父约束压成 0 宽） */
@Composable
private fun PageLayer(offsetPx: Float, content: @Composable () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer { translationX = offsetPx },
    ) {
        content()
    }
}

/** 分类栏：红线跟着当前页偏移连续平移 */
@Composable
private fun TabsRow(titles: List<String>, fraction: Float, onPick: (Int) -> Unit) {
    val colors = Dsh.colors
    val type = Dsh.type
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columnWidth = maxWidth / titles.size
        Row(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 12.dp)) {
            titles.forEachIndexed { index, title ->
                Box(
                    Modifier.weight(1f).dshClickable(scaleOnPress = false) { onPick(index) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        title,
                        style = type.rowTitle,
                        color = if (round(fraction).toInt() == index) colors.accent else colors.inkFaint,
                    )
                }
            }
        }
        val lineWidth = 18.dp
        Box(
            Modifier.align(Alignment.BottomStart)
                .offset(x = columnWidth * fraction + (columnWidth - lineWidth) / 2f)
                .width(lineWidth)
                .height(2.dp)
                .background(colors.accent),
        )
    }
}

@Composable
private fun GeneralTab(onChange: () -> Unit, onTest: () -> Unit) {
    val colors = Dsh.colors
    val type = Dsh.type
    val surface = Dsh.surface
    val s = IslandSettings
    val ctx = LocalContext.current

    fun set(block: () -> Unit) {
        block()
        onChange()
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = 16.dp),
    ) {
        Section("GENERAL · 通用", "所有岛共用")
        DshPanel(
            Modifier.fillMaxWidth(),
            contentPadding = if (surface.hasPanels) PaddingValues(horizontal = 14.dp) else PaddingValues(0.dp),
        ) {
            // 四条通道各占一行，点哪条走哪条。
            // 光看名字分不出差别，所以每项在选中时都说明本机能不能用。
            Column(Modifier.fillMaxWidth().padding(top = 14.dp)) {
                Text("上岛方式", style = type.rowTitle, color = colors.ink)
                Spacer(Modifier.height(3.dp))
                Text(
                    "四条通道，点哪条走哪条",
                    style = type.caption,
                    color = colors.inkFaint,
                )
            }
            ChoiceRow(
                "自动",
                if (IslandPublish.liveUpdatesSupported()) {
                    "本机走实时更新（Android 16 及以上）"
                } else {
                    "本机走焦点通知 V3"
                },
                selected = s.mode == "auto",
            ) { set { s.mode = "auto" } }
            DshDivider()
            ChoiceRow(
                "实时更新",
                "通知 + 小米模板，靠系统提升",
                selected = s.mode == "live",
            ) { set { s.mode = "live" } }
            if (s.mode == "live" && !IslandPublish.liveUpdatesSupported()) {
                Text(
                    "本机低于 Android 16，这条不会发通知",
                    style = type.caption,
                    color = colors.accent,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
            }
            DshDivider()
            ChoiceRow(
                "焦点通知 V3",
                "澎湃私有协议，不走系统提升",
                selected = s.mode == "v3",
            ) { set { s.mode = "v3" } }
            // V3 在澎湃上要靠焦点通知白名单：没放行时通知发得出去、岛不出来
            if (s.mode == "v3" && XiaomiFocusGate.isXiaomi()) {
                val focusOk = remember { XiaomiFocusGate.allowed(ctx) }
                Text(
                    if (focusOk) {
                        "系统已放行焦点通知"
                    } else {
                        "系统未放行本应用的焦点通知，这条在本机不会有岛"
                    },
                    style = type.caption,
                    color = if (focusOk) colors.inkFaint else colors.accent,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
            }
            DshDivider()
            ChoiceRow(
                "标准实时活动",
                "系统提升，HyperOS / ColorOS / MagicOS 通用",
                selected = s.mode == "std",
            ) { set { s.mode = "std" } }
            if (s.mode == "std") {
                val stdOk = remember { IslandStandard.supported(ctx) }
                Text(
                    if (stdOk) {
                        "本机可用：由系统决定画成状态栏胶囊还是岛"
                    } else {
                        "本机不支持（需 Android 16），这条不会发通知"
                    },
                    style = type.caption,
                    color = if (stdOk) colors.inkFaint else colors.accent,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
            }
            DshDivider()
            CycleRow("岛优先级", if (s.tempPriority) "温度优先" else "心率优先") {
                set { s.tempPriority = !s.tempPriority }
            }
            DshDivider()
            CycleRow("更新节流", "${s.updateIntervalMs / 1000}s") {
                set {
                    s.updateIntervalMs = when (s.updateIntervalMs) {
                        1000L -> 2000L
                        2000L -> 5000L
                        5000L -> 10_000L
                        else -> 1000L
                    }
                }
            }
            DshDivider()
            CycleRow("展开时长", "${s.expandDurationMs / 1000}s") {
                set {
                    s.expandDurationMs = when (s.expandDurationMs) {
                        3000L -> 4000L
                        4000L -> 6000L
                        else -> 3000L
                    }
                }
            }
            DshDivider()
            SwitchRow("关闭岛后保留通知", "", s.keepNotificationWhenOff) { set { s.keepNotificationWhenOff = it } }
            DshDivider()
            Row(
                Modifier.fillMaxWidth().padding(vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("同时显示上限", style = type.rowTitle, color = colors.ink)
                Spacer(Modifier.weight(1f))
                Text("2 个（心率 / 温度）", style = type.monoSmall, color = colors.inkFaint)
            }
            DshDivider()
            Row(
                Modifier.fillMaxWidth()
                    .dshClickable(scaleOnPress = false, onClick = onTest)
                    .padding(vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("发一条测试岛", style = type.rowTitle, color = colors.accent)
                Spacer(Modifier.weight(1f))
                Text("检查是否上岛", style = type.monoSmall, color = colors.inkFaint)
            }
        }

        Spacer(Modifier.height(14.dp))
        Text(
            "每个岛各自一套开关，互不覆盖；总开关关闭后，对应内容不再上岛。",
            style = type.caption,
            color = colors.inkFaint,
        )
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun HeartTab(onChange: () -> Unit, buttonSupported: Boolean) {
    val colors = Dsh.colors
    val type = Dsh.type
    val surface = Dsh.surface
    val s = IslandSettings

    fun set(block: () -> Unit) {
        block()
        onChange()
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = 16.dp),
    ) {
        Section("ISLAND LOOK · 外观", "图案与岛色")
        DshPanel(
            Modifier.fillMaxWidth(),
            contentPadding = if (surface.hasPanels) PaddingValues(horizontal = 14.dp) else PaddingValues(0.dp),
        ) {
            Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
                Text("岛上的图案", style = type.rowTitle, color = colors.ink)
                Spacer(Modifier.height(10.dp))
                ArtPicker(s.heartArt, s.heartTint) { set { s.heartArt = it } }
            }
            DshDivider()
            Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
                Text("岛色", style = type.rowTitle, color = colors.ink)
                Spacer(Modifier.height(10.dp))
                TintPicker(s.heartTint) { set { s.heartTint = it } }
            }
            DshDivider()
            SwitchRow("显示岛上进度条", "关掉只留文字与计时", s.heartProgress) {
                set { s.heartProgress = it }
            }
        }

        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Pill("72", "bpm", fill = colors.ink)
            Pill("01:23", "REC", fill = colors.accent)
        }
        Spacer(Modifier.height(16.dp))

        Section("HEART ISLAND · 心率广播", "独立岛")
        DshPanel(
            Modifier.fillMaxWidth(),
            contentPadding = if (surface.hasPanels) PaddingValues(horizontal = 14.dp) else PaddingValues(0.dp),
        ) {
            SwitchRow("启用心率岛", "", s.heartEnabled) { set { s.heartEnabled = it } }
            DshDivider()
            SwitchRow(
                "岛上录制按钮",
                if (buttonSupported) "开始 / 停止" else "本机不支持岛上按钮",
                s.heartRecordButton,
                enabled = buttonSupported,
            ) { set { s.heartRecordButton = it } }
            DshDivider()
            SwitchRow("断连自动下岛", "10 秒无数据", s.heartDropIslandOnLost) { set { s.heartDropIslandOnLost = it } }
            DshDivider()
            SwitchRow("展开态显示设备名", "", s.heartShowDevice) { set { s.heartShowDevice = it } }
            DshDivider()
            SwitchRow("展开显示信号强度", "弱信号显示 ··", s.heartShowSignal) { set { s.heartShowSignal = it } }
            DshDivider()
            SwitchRow("点岛打开心率页", "", s.heartOpenOnTap) { set { s.heartOpenOnTap = it } }
        }

        Spacer(Modifier.height(14.dp))
        Text(
            "胶囊左段「❤ 心率」，右段「78 bpm」；录制中左段换成红色时长。",
            style = type.caption,
            color = colors.inkFaint,
        )
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun TempTab(
    onChange: () -> Unit,
    buttonSupported: Boolean,
    blockTop: FloatArray,
    tempScroll: androidx.compose.foundation.ScrollState,
) {
    val colors = Dsh.colors
    val type = Dsh.type
    val surface = Dsh.surface
    val s = IslandSettings

    fun set(block: () -> Unit) {
        block()
        onChange()
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(tempScroll).padding(top = 16.dp),
    ) {
        Section("ISLAND LOOK · 外观", "图案与岛色")
        DshPanel(
            Modifier.fillMaxWidth(),
            contentPadding = if (surface.hasPanels) PaddingValues(horizontal = 14.dp) else PaddingValues(0.dp),
        ) {
            Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
                Text("岛上的图案", style = type.rowTitle, color = colors.ink)
                Spacer(Modifier.height(10.dp))
                ArtPicker(s.tempArt, s.tempTint) { set { s.tempArt = it } }
            }
            DshDivider()
            Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
                Text("岛色", style = type.rowTitle, color = colors.ink)
                Spacer(Modifier.height(10.dp))
                TintPicker(s.tempTint) { set { s.tempTint = it } }
            }
            DshDivider()
            SwitchRow("显示岛上进度条", "关掉只留文字与计时", s.tempProgress) {
                set { s.tempProgress = it }
            }
        }

        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Pill("45.2", "℃", fill = colors.ink)
            Pill("01:23", "REC", fill = colors.accent)
        }
        Spacer(Modifier.height(16.dp))

        Section("THERMAL ISLAND · 温度", "独立岛")
        DshPanel(
            Modifier.fillMaxWidth(),
            contentPadding = if (surface.hasPanels) PaddingValues(horizontal = 14.dp) else PaddingValues(0.dp),
        ) {
            SwitchRow("启用温度岛", "", s.tempEnabled) { set { s.tempEnabled = it } }
            DshDivider()
            SwitchRow(
                "常驻上岛",
                if (s.tempAlwaysOn) "一直显示在岛上" else "仅超阈值 / 录制中上岛",
                s.tempAlwaysOn,
            ) { set { s.tempAlwaysOn = it } }
            DshDivider()
            SwitchRow(
                "岛上录制按钮",
                if (buttonSupported) "" else "本机不支持岛上按钮",
                s.tempRecordButton,
                enabled = buttonSupported,
            ) { set { s.tempRecordButton = it } }
            DshDivider()
            SwitchRow(
                "岛上关闭按钮",
                if (buttonSupported) "一键下岛" else "本机不支持岛上按钮",
                s.tempCloseButton,
                enabled = buttonSupported,
            ) { set { s.tempCloseButton = it } }
            DshDivider()
            SwitchRow("仅在充电时上岛", "平时不占岛", s.tempChargingOnly) { set { s.tempChargingOnly = it } }
            DshDivider()
            SwitchRow("点岛打开温度页", "", s.tempOpenOnTap) { set { s.tempOpenOnTap = it } }
        }

        Spacer(Modifier.height(14.dp))
        Section("上岛热区", "可多选")
        DshPanel(
            Modifier.fillMaxWidth(),
            contentPadding = if (surface.hasPanels) PaddingValues(horizontal = 14.dp) else PaddingValues(0.dp),
        ) {
            val zones = listOf("CPU", "GPU", "电池", "外壳")
            zones.forEachIndexed { index, label ->
                SwitchRow(label, "", label in s.tempZones) { on ->
                    set { s.tempZones = if (on) s.tempZones + label else s.tempZones - label }
                }
                if (index != zones.lastIndex) DshDivider()
            }
        }

        Spacer(Modifier.height(14.dp))
        Section("胶囊显示", "单选 · 未展开时")
        DshPanel(
            Modifier.fillMaxWidth(),
            contentPadding = if (surface.hasPanels) PaddingValues(horizontal = 14.dp) else PaddingValues(0.dp),
        ) {
            val zones = listOf("CPU", "GPU", "电池", "外壳")
            zones.forEachIndexed { index, label ->
                val selected = (s.tempCapsuleZone ?: "CPU") == label
                Row(
                    Modifier.fillMaxWidth()
                        .dshClickable(scaleOnPress = false) { set { s.tempCapsuleZone = label } }
                        .padding(vertical = 13.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier.size(5.dp).clip(RoundedCornerShape(1.dp))
                            .background(if (selected) colors.accent else androidx.compose.ui.graphics.Color.Transparent),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(label, style = type.rowTitle, color = if (selected) colors.accent else colors.ink)
                    Spacer(Modifier.weight(1f))
                    if (selected) Text("显示", style = type.label, color = colors.inkFaint)
                }
                if (index != zones.lastIndex) DshDivider()
            }
        }

        Spacer(Modifier.height(14.dp))
        Section("THRESHOLD · 自动上/下岛", "滑动 0 – 100℃")
        DshPanel(
            Modifier.fillMaxWidth().onGloballyPositioned {
                blockTop[0] = it.positionInRoot().y + tempScroll.value
                blockTop[1] = it.size.height.toFloat()
            },
            contentPadding = if (surface.hasPanels) PaddingValues(horizontal = 14.dp) else PaddingValues(0.dp),
        ) {
            // 下岛不能高于上岛：拖哪边都按对方夹一下，标尺跟着停在夹紧后的位置
            RulerRow("上岛", "高于", s.tempUpCelsius) { v ->
                val up = v.coerceAtLeast(s.tempDownCelsius)
                set { s.tempUpCelsius = up }
                up
            }
            DshDivider()
            RulerRow("下岛", "低于", s.tempDownCelsius) { v ->
                val down = v.coerceIn(TEMP_MIN, s.tempUpCelsius)
                set { s.tempDownCelsius = down }
                down
            }
            DshDivider()
            SwitchRow("骤升提醒", "1 分钟升 5°C", s.tempSuddenRise) { set { s.tempSuddenRise = it } }
        }

        Spacer(Modifier.height(24.dp))
    }
}

/** 一行「上岛 / 下岛」+ 横向标尺；拖动过程中数字实时跟着变，松手才落盘 */
@Composable
private fun RulerRow(title: String, sub: String, value: Float, onCommit: (Float) -> Float) {
    val colors = Dsh.colors
    val type = Dsh.type
    var live by remember { mutableStateOf(value) }
    LaunchedEffect(value) {
        if (abs(live - value) > 0.05f) live = value
    }

    Column(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = type.rowTitle, color = colors.ink)
            Spacer(Modifier.width(8.dp))
            Text(sub, style = type.monoSmall, color = colors.inkFaint)
            Spacer(Modifier.weight(1f))
            Text(
                String.format(Locale.US, "%.1f℃", live),
                style = type.rowTitle.copy(fontWeight = FontWeight.Medium),
                color = colors.accent,
            )
        }
        Spacer(Modifier.height(6.dp))
        TempRuler(
            initial = value,
            onChanging = { v -> live = v },
            onCommit = { v ->
                val accepted = onCommit(v)
                live = accepted
                accepted
            },
        )
    }
}

/**
 * 标尺：指针固定在中间，拖动刻度，松手带惯性滑一段再吸附到 0.1℃；拖到端点会冲出去再弹回。
 */
@Composable
private fun TempRuler(
    initial: Float,
    onChanging: (Float) -> Unit,
    onCommit: (Float) -> Float,
) {
    val colors = Dsh.colors
    val density = LocalDensity.current
    val stepPx = with(density) { 7.dp.toPx() }
    val anim = remember { Animatable(initial) }
    var dragging by remember { mutableStateOf(false) }
    val tracker = remember { VelocityTracker() }
    val scope = rememberCoroutineScope()
    // 甩完的落点：低刚度低阻尼，甩得越快滑得越远
    val settle = remember { spring<Float>(dampingRatio = 0.55f, stiffness = Spring.StiffnessLow) }

    fun snap(v: Float): Float = (round(v / TEMP_STEP) * TEMP_STEP).coerceIn(TEMP_MIN, TEMP_MAX)

    // 刻度每动 0.1 就把数字往回带一次，标题那行跟着变
    LaunchedEffect(Unit) {
        snapshotFlow { round(anim.value / TEMP_STEP) }.collect { onChanging(anim.value) }
    }

    Box(
        Modifier.fillMaxWidth().height(46.dp)
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = {
                        dragging = true
                        tracker.resetTracking()
                    },
                    onDragEnd = {
                        dragging = false
                        val valuePerSec = -tracker.calculateVelocity().x / stepPx * TEMP_STEP
                        scope.launch {
                            if (abs(valuePerSec) > 0.8f) {
                                val glide = anim.value + valuePerSec * 0.28f
                                anim.animateTo(glide.coerceIn(TEMP_MIN - 2f, TEMP_MAX + 2f), settle, valuePerSec)
                            }
                            val target = snap(anim.value)
                            val accepted = onCommit(target)
                            anim.animateTo(accepted, tween(110))
                        }
                    },
                    onDragCancel = {
                        dragging = false
                        scope.launch {
                            val target = snap(anim.value)
                            val accepted = onCommit(target)
                            anim.animateTo(accepted, tween(110))
                        }
                    },
                    onHorizontalDrag = { change, delta ->
                        change.consume()
                        tracker.addPosition(change.uptimeMillis, change.position)
                        scope.launch { anim.snapTo(overshoot(anim.value - delta / stepPx * TEMP_STEP)) }
                    },
                )
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val centerX = size.width / 2f
            val center = anim.value / TEMP_STEP
            val halfTicks = (size.width / 2f / stepPx).toInt() + 1
            val baseIndex = round(center).toInt()
            val majorHeight = 18.dp.toPx()
            val midHeight = 12.dp.toPx()
            val minorHeight = 6.dp.toPx()
            for (i in -halfTicks..halfTicks) {
                val index = baseIndex + i
                if (index < 0 || index > (TEMP_MAX / TEMP_STEP).toInt()) continue
                val x = centerX + (index - center) * stepPx
                if (x < 0f || x > size.width) continue
                val major = index % 10 == 0
                val height = when {
                    major -> majorHeight
                    index % 5 == 0 -> midHeight
                    else -> minorHeight
                }
                drawLine(
                    color = if (major) colors.inkMuted else colors.hairline,
                    start = Offset(x, size.height - height),
                    end = Offset(x, size.height),
                    strokeWidth = 1.dp.toPx(),
                )
            }
        }
        // 固定指针
        Box(
            Modifier.align(Alignment.BottomCenter).width(2.dp).height(22.dp).background(colors.accent),
        )
    }
}

/** 越界后按比例衰减：拖到端点还能再拽出一点，松手弹回 */
private fun overshoot(v: Float): Float = when {
    v < TEMP_MIN -> TEMP_MIN - (TEMP_MIN - v) * 0.35f
    v > TEMP_MAX -> TEMP_MAX + (v - TEMP_MAX) * 0.35f
    else -> v
}

/** 分组标题：左侧名称，右侧一句说明 */
@Composable
private fun Section(title: String, trailing: String) {
    val colors = Dsh.colors
    val type = Dsh.type
    Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = type.label, color = colors.inkFaint)
        Spacer(Modifier.weight(1f))
        Text(trailing, style = type.label, color = colors.inkFaint.copy(alpha = 0.6f))
    }
}

@Composable
private fun Pill(value: String, unit: String, fill: androidx.compose.ui.graphics.Color) {
    val type = Dsh.type
    Box(
        Modifier.height(30.dp).clip(RoundedCornerShape(999.dp)).background(fill)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(value, style = type.monoSmall.copy(fontSize = 12.sp), color = androidx.compose.ui.graphics.Color(0xFFF2F4F3))
            Spacer(Modifier.width(5.dp))
            Text(unit, style = type.label, color = androidx.compose.ui.graphics.Color(0xFFF2F4F3).copy(alpha = 0.65f))
        }
    }
}

@Composable
private fun SwitchRow(
    title: String,
    sub: String,
    checked: Boolean,
    enabled: Boolean = true,
    onToggle: (Boolean) -> Unit,
) {
    val colors = Dsh.colors
    val type = Dsh.type
    Row(
        Modifier.fillMaxWidth().padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = type.rowTitle, color = if (enabled) colors.ink else colors.inkFaint)
        if (sub.isNotEmpty()) {
            Spacer(Modifier.width(8.dp))
            Text(sub, style = type.monoSmall, color = colors.inkFaint)
        }
        Spacer(Modifier.weight(1f))
        DshSwitch(
            checked = checked,
            onCheckedChange = if (enabled) onToggle else null,
        )
    }
}

@Composable
private fun CycleRow(title: String, value: String, onClick: () -> Unit) {
    val colors = Dsh.colors
    val type = Dsh.type
    Row(
        Modifier.fillMaxWidth().dshClickable(scaleOnPress = false, onClick = onClick)
            .padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = type.rowTitle, color = colors.ink)
        Spacer(Modifier.weight(1f))
        Text(value, style = type.monoSmall, color = colors.inkMuted)
    }
}

/**
 * 单选项：标题 + 一句说明，右侧方框选中时填强调色。
 *
 * 上岛方式只有两项，用单选比循环切换清楚：不用点好几下才轮到下一项，
 * 也看得出现在选的是哪一个。
 */
@Composable
private fun ChoiceRow(
    title: String,
    sub: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = Dsh.colors
    val type = Dsh.type
    Row(
        Modifier.fillMaxWidth().dshClickable(scaleOnPress = false, onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = type.rowTitle, color = colors.ink)
            if (sub.isNotEmpty()) {
                Spacer(Modifier.height(3.dp))
                Text(sub, style = type.caption, color = colors.inkFaint)
            }
        }
        Spacer(Modifier.width(12.dp))
        Box(
            Modifier
                .size(15.dp)
                .border(
                    width = 1.dp,
                    color = if (selected) colors.accent else colors.hairlineStrong,
                    shape = RoundedCornerShape(3.dp),
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                Box(Modifier.size(7.dp).background(colors.accent, RoundedCornerShape(2.dp)))
            }
        }
    }
}
/**
 * 图案选择：横向一排缩略图，选中的加一圈强调色边框。
 *
 * 缩略图直接照字符网格现画，和岛上用的是同一份数据，所以看到的就是实际效果。
 */
@Composable
private fun ArtPicker(
    selectedId: String,
    tintId: String,
    onPick: (String) -> Unit,
) {
    val colors = Dsh.colors
    val type = Dsh.type
    val ctx = LocalContext.current
    val tint = remember(tintId) { IslandArt.tintColor(ctx, tintId) }
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        IslandArt.ARTS.forEach { art ->
            val picked = art.id == selectedId
            Column(
                Modifier.width(64.dp).dshClickable(scaleOnPress = false) { onPick(art.id) },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(10.dp))
                        // 缩略图用深色底：岛就是深色背景，浅底会让图案里的白色糊成一片
                        .background(Color(0xFF1A1D20))
                        .border(
                            width = if (picked) 1.5.dp else 1.dp,
                            color = if (picked) colors.accent else colors.hairline,
                            shape = RoundedCornerShape(10.dp),
                        )
                        .padding(6.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    ArtThumb(art, tint, Modifier.fillMaxSize())
                }
                Spacer(Modifier.height(5.dp))
                Text(
                    art.label,
                    style = type.caption,
                    color = if (picked) colors.accent else colors.inkFaint,
                )
            }
        }
    }
}

/** 把一块图案按字符网格画进给定区域，保持格子正方形、整体居中 */
@Composable
private fun ArtThumb(art: IslandArt.Art, tint: Int, modifier: Modifier = Modifier) {
    val palette = remember(art, tint) {
        art.palette ?: mapOf('#' to tint, '+' to 0xFFF2F0EA.toInt())
    }
    Canvas(modifier) {
        val rows = art.pattern.size
        val cols = art.pattern.maxOf { it.length }
        val cell = minOf(size.width / cols, size.height / rows)
        val ox = (size.width - cols * cell) / 2f
        val oy = (size.height - rows * cell) / 2f
        art.pattern.forEachIndexed { r, line ->
            line.forEachIndexed { c, ch ->
                val color = palette[ch] ?: return@forEachIndexed
                drawRect(
                    color = Color(color),
                    topLeft = Offset(ox + c * cell, oy + r * cell),
                    // 多描一点，避免格子之间露出底色细缝
                    size = Size(cell + 0.5f, cell + 0.5f),
                )
            }
        }
    }
}

/**
 * 岛色：一排圆点，选中的外套一圈同色细环、中间留一道缝。
 * "跟随主题"那一项用当前主题的强调色显示。
 */
@Composable
private fun TintPicker(selectedId: String, onPick: (String) -> Unit) {
    val ctx = LocalContext.current
    val themeTint = remember { IslandArt.tintColor(ctx, "theme") }
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        IslandArt.TINT_OPTIONS.forEach { tint ->
            val color = tint.color ?: themeTint
            val picked = tint.id == selectedId
            // 跟随主题不是某个固定色（纸本下它接近白），画成空心环，免得跟"白"那颗撞脸
            val auto = tint.color == null
            Box(
                Modifier.size(38.dp).dshClickable(scaleOnPress = false) { onPick(tint.id) },
                contentAlignment = Alignment.Center,
            ) {
                if (picked) {
                    Box(
                        Modifier
                            .size(38.dp)
                            .border(2.dp, Color(color), RoundedCornerShape(50)),
                    )
                }
                if (auto) {
                    Box(
                        Modifier
                            .size(24.dp)
                            .border(2.dp, Color(color), RoundedCornerShape(50)),
                    )
                } else {
                    Box(
                        Modifier
                            .size(28.dp)
                            .clip(RoundedCornerShape(50))
                            .background(Color(color)),
                    )
                }
            }
        }
    }
}
