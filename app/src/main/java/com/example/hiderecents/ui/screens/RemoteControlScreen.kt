package com.example.hiderecents.ui.screens

import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import androidx.compose.ui.viewinterop.AndroidView
import com.example.hiderecents.ui.components.DshIcon
import com.example.hiderecents.ui.components.DshIconKind
import com.example.hiderecents.ui.components.DshScreenEnter
import com.example.hiderecents.ui.components.StaggeredEnter
import com.example.hiderecents.ui.components.dshCanvasBackground
import com.example.hiderecents.ui.components.dshClickable
import com.example.hiderecents.ui.components.paperHeaderRule
import com.example.hiderecents.ui.theme.Dsh
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.min

enum class RcMode { PICK, HOST, VIEWER, FAILED }

data class RcUiState(
    val mode: RcMode = RcMode.PICK,
    val ip: String = "",
    val status: String = "",
    val detail: String = "",
    val fps: Int = 0,
    val kbps: Int = 0,
    val bitrateMbps: Int = 16,
    val longSide: Int = 1080,
    /** 画质面板里选中的分辨率档：0 = 原生（按视口对齐）。 */
    val profileChoice: Int = 0,
    val fpsTarget: Int = 60,
    val connected: Boolean = false,
    val devices: List<Pair<String, String>> = emptyList(),
    val audioState: String = "未连接",
    val adbTarget: String = "",
    val adbCode: String = "",
    val adbStatus: String = "",
    val peerName: String = "",
    val videoWidth: Int = 0,
    val videoHeight: Int = 0,
    val failTitle: String = "",
    val failHint: String = "",
    val failAction: String = "",
)

@Composable
fun RemoteControlScreen(
    state: RcUiState,
    onBack: () -> Unit,
    onPickHost: () -> Unit,
    onPickViewer: () -> Unit,
    onRequestShare: () -> Unit,
    onStopHost: () -> Unit,
    onOpenAccessibility: () -> Unit,
    onTestInject: () -> Unit,
    onTestScrcpy: () -> Unit,
    onAdbTargetChange: (String) -> Unit,
    onAdbCodeChange: (String) -> Unit,
    onAdbPair: () -> Unit,
    onAdbConnect: () -> Unit,
    onIpChange: (String) -> Unit,
    onPasteAddress: () -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onSurface: (Surface?) -> Unit,
    onTap: (Float, Float) -> Unit,
    onSwipe: (Float, Float, Float, Float, Int) -> Unit,
    onGlobal: (Int) -> Unit,
    onBitrate: (Int) -> Unit,
    onResolution: (Int) -> Unit,
    onFrameRate: (Int) -> Unit,
    onRotate: () -> Unit,
    onScreenshot: () -> Unit,
    fullscreen: Boolean,
    onToggleFullscreen: () -> Unit,
    videoWidth: Int,
    videoHeight: Int,
    pipMode: Boolean = false,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Dsh.colors
    val dimens = Dsh.dimens
    val type = Dsh.type
    var drawer by remember { mutableStateOf(false) }

    Box(modifier.fillMaxSize().dshCanvasBackground()) {
        Column(
            Modifier
                .fillMaxSize()
                .then(
                    if (fullscreen) Modifier
                    else Modifier.safeDrawingPadding().padding(horizontal = dimens.screenPadding),
                ),
        ) {
            if (!fullscreen) {
                Spacer(Modifier.height(4.dp))
                Row(
                    Modifier.fillMaxWidth().height(56.dp).paperHeaderRule(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier
                            .size(32.dp)
                            .clip(RoundedCornerShape(dimens.iconRadius))
                            .dshClickable(onClick = onBack),
                        contentAlignment = Alignment.Center,
                    ) {
                        DshIcon(DshIconKind.BACK, iconSize = 15.dp, tint = colors.inkMuted)
                    }
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = when (state.mode) {
                                RcMode.PICK -> "远程控制"
                                RcMode.HOST -> "正在被控制"
                                RcMode.VIEWER -> state.peerName.ifEmpty { "远程画面" }
                                RcMode.FAILED -> "连接状态"
                            },
                            style = type.h2,
                            color = colors.ink,
                        )
                        Spacer(Modifier.height(5.dp))
                        Text(
                            text = when (state.mode) {
                                RcMode.PICK -> "控制端 · " + (if (state.connected) "1 台已连接" else "未连接")
                                RcMode.HOST -> "来自 " + state.peerName.ifEmpty { "未知设备" }
                                RcMode.VIEWER -> if (state.connected) {
                                    "已连接 · " + state.videoWidth + "×" + state.videoHeight
                                } else {
                                    "未连接"
                                }

                                RcMode.FAILED -> "4 种失败态 · 各自出口"
                            },
                            style = type.label,
                            color = colors.inkFaint,
                        )
                    }
                }
                Spacer(Modifier.height(dimens.gapM))
            }

            DshScreenEnter(screenKey = state.mode) {
            Column(Modifier.fillMaxSize()) {
            when (state.mode) {
                RcMode.PICK -> PickPane(
                    state = state,
                    onConnect = onConnect,
                    onIpChange = onIpChange,
                    onPasteAddress = onPasteAddress,
                    onPickHost = onPickHost,
                )

                RcMode.HOST -> HostPane(
                    state = state,
                    onRequestShare = onRequestShare,
                    onStopHost = onStopHost,
                    onOpenAccessibility = onOpenAccessibility,
                    onTestInject = onTestInject,
                    onTestScrcpy = onTestScrcpy,
                    onAdbTargetChange = onAdbTargetChange,
                    onAdbCodeChange = onAdbCodeChange,
                    onAdbPair = onAdbPair,
                    onAdbConnect = onAdbConnect,
                )

                RcMode.FAILED -> FailPane(state = state, onRetry = onRetry, onBack = onBack)

                RcMode.VIEWER -> Column(Modifier.fillMaxSize()) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .clip(
                                if (fullscreen) RoundedCornerShape(0.dp)
                                else RoundedCornerShape(dimens.panelRadius),
                            )
                            .background(Color.Black),
                    ) {
                        RemoteSurface(
                            videoWidth = videoWidth,
                            videoHeight = videoHeight,
                            onSurface = onSurface,
                            onTap = onTap,
                            onSwipe = onSwipe,
                        )
                        if (!state.connected) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text("选择下方设备开始控制", style = type.caption, color = colors.inkFaint)
                            }
                        }
                    }

                    if (!fullscreen && !pipMode) {
                        Spacer(Modifier.height(dimens.gapS))
                        if (state.connected) {
                            TelemetryRow(state)
                            Spacer(Modifier.height(dimens.gapS))
                            ToolRow(
                                onScreenshot = onScreenshot,
                                onFullscreen = onToggleFullscreen,
                                onQuality = { drawer = !drawer },
                                onDisconnect = onDisconnect,
                                qualityOpen = drawer,
                            )
                            Spacer(Modifier.height(dimens.gapS))
                            NavRow(onGlobal)
                            if (drawer) {
                                Spacer(Modifier.height(dimens.gapS))
                                QualityDrawer(state, onResolution, onFrameRate, onBitrate)
                            }
                            Spacer(Modifier.height(dimens.gapS))
                            RcButton("断开", primary = true, onClick = onDisconnect)
                            Spacer(Modifier.height(dimens.gapS))
                            IpRow(state.ip, onIpChange)
                        } else {
                            // 未连接时以「扫到的设备」为主，地址输入退到次要位置
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                SectionLabel(
                                    when {
                                        state.status.startsWith("正在连接") -> state.status
                                        state.status.startsWith("视频连接失败") -> state.status
                                        state.status == "连接已断开" -> "已断开"
                                        state.devices.isEmpty() -> "正在搜索同一 WI-FI 下的设备"
                                        else -> "发现 " + state.devices.size + " 台设备"
                                    },
                                )
                                Spacer(Modifier.weight(1f))
                                Text("点击即连", style = type.label, color = colors.inkFaint)
                            }
                            Spacer(Modifier.height(8.dp))
                            state.devices.forEach { (name, address) ->
                                DeviceRow(
                                    name = name,
                                    meta = address,
                                    badge = "连接",
                                    online = true,
                                    onClick = {
                                        onIpChange(address)
                                        onConnect()
                                    },
                                )
                                Spacer(Modifier.height(6.dp))
                            }
                            if (state.devices.isEmpty()) {
                                Text(
                                    text = "没搜到就确认两台设备在同一 Wi-Fi、对方已打开「共享本机」；" +
                                        "也可以直接粘贴或手填地址。",
                                    style = type.caption,
                                    color = colors.inkFaint,
                                )
                                Spacer(Modifier.height(8.dp))
                            }
                            IpRow(state.ip, onIpChange, onPasteAddress)
                            Spacer(Modifier.height(dimens.gapS))
                            RcButton("连接", primary = true, onClick = onConnect)
                        }
                        Spacer(Modifier.height(6.dp))
                    }
                }
            }
            }
            }
        }

        if (fullscreen && !pipMode && state.mode == RcMode.VIEWER) {
            FullscreenChrome(
                state = state,
                qualityOpen = drawer,
                onGlobal = onGlobal,
                onQuality = { drawer = !drawer },
                onResolution = onResolution,
                onFrameRate = onFrameRate,
                onBitrate = onBitrate,
                onExit = onToggleFullscreen,
                onDisconnect = onDisconnect,
            )
        }
    }
}

// ------------------------------------------------------------------ 远程控制

@Composable
private fun ColumnScope.PickPane(
    state: RcUiState,
    onConnect: () -> Unit,
    onIpChange: (String) -> Unit,
    onPasteAddress: () -> Unit,
    onPickHost: () -> Unit,
) {
    val colors = Dsh.colors
    val dimens = Dsh.dimens
    val type = Dsh.type

    SectionLabel("连接状态")
    Spacer(Modifier.height(10.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = if (state.connected) "已连接" else "未连接",
            style = type.h1,
            color = if (state.connected) colors.accent else colors.ink,
        )
        Spacer(Modifier.weight(1f))
        Column(horizontalAlignment = Alignment.End) {
            Text("本机", style = type.label, color = colors.inkFaint)
            Spacer(Modifier.height(3.dp))
            Text(
                text = state.status.ifEmpty { "等待开始" },
                style = type.caption,
                color = colors.inkMuted,
            )
        }
    }
    Spacer(Modifier.height(8.dp))
    Text(
        text = "两种方式：同一网络直接连，或经中继按房间码配对。",
        style = type.caption,
        color = colors.inkFaint,
    )

    Spacer(Modifier.height(dimens.gapL))
    SectionLabel("局域网控制 · 同一 WI-FI")
    Spacer(Modifier.height(6.dp))
    if (state.devices.isEmpty()) {
        StaggeredEnter(index = 0) {
            DeviceRow(
                name = if (state.connected) state.peerName.ifEmpty { "已连接设备" } else "等待发现",
                meta = if (state.connected) "局域网直连 · 已授权" else "同一 WI-FI 下可被发现",
                badge = if (state.connected) "在线" else "待连接",
                online = state.connected,
                onClick = { if (!state.connected) onConnect() },
            )
        }
    } else {
        state.devices.forEachIndexed { index, (name, address) ->
            StaggeredEnter(index = index) {
                DeviceRow(
                    name = name,
                    meta = address,
                    badge = if (state.connected) "已连接" else "在线",
                    online = true,
                    onClick = {
                        if (!state.connected) {
                            onIpChange(address)
                            onConnect()
                        }
                    },
                )
            }
            Spacer(Modifier.height(6.dp))
        }
    }

    Spacer(Modifier.height(dimens.gapM))
    IpRow(state.ip, onIpChange, onPasteAddress)
    Spacer(Modifier.height(dimens.gapS))
    RcButton("本机共享屏幕（局域网）", primary = false, onClick = onPickHost)
    Spacer(Modifier.height(14.dp))
}

@Composable
private fun DeviceRow(
    name: String,
    meta: String,
    badge: String,
    online: Boolean,
    onClick: () -> Unit,
) {
    val colors = Dsh.colors
    val dimens = Dsh.dimens
    val type = Dsh.type
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(dimens.tileRadius))
            .border(1.dp, colors.hairline, RoundedCornerShape(dimens.tileRadius))
            .dshClickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(dimens.iconRadius))
                .background(colors.accentDim),
            contentAlignment = Alignment.Center,
        ) {
            DshIcon(DshIconKind.ISLAND, iconSize = 16.dp, tint = colors.accent)
        }
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f)) {
            Text(name, style = type.rowTitle, color = colors.ink)
            Spacer(Modifier.height(3.dp))
            Text(meta, style = type.label, color = colors.inkFaint)
        }
        Text(
            text = badge,
            style = type.label,
            color = if (online) colors.accent else colors.inkFaint,
        )
    }
}

// ------------------------------------------------------------------ 被控端

@Composable
private fun ColumnScope.HostPane(
    state: RcUiState,
    onRequestShare: () -> Unit,
    onStopHost: () -> Unit,
    onOpenAccessibility: () -> Unit,
    onTestInject: () -> Unit,
    onTestScrcpy: () -> Unit,
    onAdbTargetChange: (String) -> Unit,
    onAdbCodeChange: (String) -> Unit,
    onAdbPair: () -> Unit,
    onAdbConnect: () -> Unit,
) {
    val colors = Dsh.colors
    val dimens = Dsh.dimens
    val type = Dsh.type


    SectionLabel("控制方")
    Spacer(Modifier.height(10.dp))
    Text(
        text = state.peerName.ifEmpty { "等待接入" },
        style = type.h1,
        color = colors.ink,
    )
    Spacer(Modifier.height(4.dp))
    Text(
        text = state.detail.ifEmpty { "127.0.0.1:27183" },
        style = type.mono,
        color = colors.inkFaint,
    )
    Spacer(Modifier.height(10.dp))
    Text(
        text = if (state.connected) "已连接 · 可随时断开" else "尚未接入",
        style = type.caption,
        color = if (state.connected) colors.accent else colors.inkMuted,
    )
    if (state.status.isNotEmpty()) {
        Spacer(Modifier.height(6.dp))
        Text(
            text = state.status,
            style = type.label,
            color = colors.inkFaint,
        )
    }

    Spacer(Modifier.height(dimens.gapL))
    SectionLabel("正在共享")
    Spacer(Modifier.height(6.dp))
    ShareRow("屏幕镜像", if (state.connected) "对方可看到并操作本机屏幕" else "等待开始", true)
    ShareRow("剪贴板", "未共享（M2）", false)
    ShareRow("通知内容", "未共享（M2）", false)

    Spacer(Modifier.height(dimens.gapM))
    Row(horizontalArrangement = Arrangement.spacedBy(dimens.gapS)) {
        Box(Modifier.weight(1f)) {
            RcButton(
                if (state.connected) "测试注入" else "测试注入",
                primary = false,
                onClick = onTestInject,
            )
        }
        Box(Modifier.weight(1f)) {
            RcButton("无障碍设置", primary = false, onClick = onOpenAccessibility)
        }
    }

    Spacer(Modifier.height(dimens.gapS))
    RcButton("增强模式探测（scrcpy）", primary = false, onClick = onTestScrcpy)

    Spacer(Modifier.height(dimens.gapM))
    SectionLabel("ADB 授权（无线调试）")
    Spacer(Modifier.height(6.dp))
    FieldRow("配对地址", state.adbTarget.ifEmpty { "192.168.31.123:37xxx" }, onAdbTargetChange)
    Spacer(Modifier.height(6.dp))
    FieldRow("六位码", state.adbCode.ifEmpty { "配对弹窗里的 6 位数字" }, onAdbCodeChange)
    Spacer(Modifier.height(6.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(dimens.gapS)) {
        Box(Modifier.weight(1f)) {
            RcButton("配对", primary = false, onClick = onAdbPair)
        }
        Box(Modifier.weight(1f)) {
            RcButton("连接", primary = false, onClick = onAdbConnect)
        }
    }
    if (state.adbStatus.isNotEmpty()) {
        Spacer(Modifier.height(6.dp))
        Text(state.adbStatus, style = type.label, color = colors.inkFaint)
    }

    Spacer(Modifier.weight(1f))

    Spacer(Modifier.weight(1f))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(dimens.gapS)) {
        Box(Modifier.weight(1f)) {
            RcButton("开始共享", primary = false, onClick = onRequestShare)
        }
        Box(Modifier.weight(1f)) {
            RcButton("立刻断开", primary = true, onClick = onStopHost)
        }
    }
    Spacer(Modifier.height(6.dp))
    Text(
        text = "断开后对方需重新配对。共享期间本机会有一条常驻通知。",
        style = type.label,
        color = colors.inkFaint,
    )
    Spacer(Modifier.height(12.dp))
}

@Composable
private fun ShareRow(title: String, sub: String, on: Boolean) {
    val colors = Dsh.colors
    val type = Dsh.type
    Row(
        Modifier.fillMaxWidth().padding(vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = type.rowTitle, color = if (on) colors.ink else colors.inkFaint)
            Spacer(Modifier.height(3.dp))
            Text(sub, style = type.label, color = colors.inkFaint)
        }
        Box(
            Modifier
                .size(width = 34.dp, height = 19.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(if (on) colors.accent else colors.track),
            contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart,
        ) {
            Box(
                Modifier
                    .padding(horizontal = 4.dp)
                    .size(11.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (on) colors.accentInk else colors.knob),
            )
        }
    }
}

// ------------------------------------------------------------------ 失败态

@Composable
private fun ColumnScope.FailPane(state: RcUiState, onRetry: () -> Unit, onBack: () -> Unit) {
    val colors = Dsh.colors
    val dimens = Dsh.dimens
    val type = Dsh.type

    Spacer(Modifier.height(dimens.gapM))
    Text(
        text = state.failTitle.ifEmpty { "连接超时" },
        style = type.h1,
        color = colors.ink,
    )
    Spacer(Modifier.height(8.dp))
    Text(
        text = state.failHint.ifEmpty { "30 秒内未完成握手，对方可能不在同一网段。" },
        style = type.caption,
        color = colors.inkMuted,
    )
    Spacer(Modifier.weight(1f))
    RcButton(state.failAction.ifEmpty { "重试" }, primary = true, onClick = onRetry)
    Spacer(Modifier.height(dimens.gapS))
    RcButton("返回设备列表", primary = false, onClick = onBack)
    Spacer(Modifier.height(12.dp))
}

// ------------------------------------------------------------------ 画面页组件

@Composable
private fun RemoteSurface(
    videoWidth: Int,
    videoHeight: Int,
    onSurface: (Surface?) -> Unit,
    onTap: (Float, Float) -> Unit,
    onSwipe: (Float, Float, Float, Float, Int) -> Unit,
) {
    val hasVideo = videoWidth > 0 && videoHeight > 0
    val vw = if (hasVideo) videoWidth else 1
    val vh = if (hasVideo) videoHeight else 1
    val density = LocalDensity.current
    var tapMark by remember { mutableStateOf<Offset?>(null) }
    LaunchedEffect(tapMark) {
        if (tapMark != null) {
            delay(450)
            tapMark = null
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val cw = constraints.maxWidth.toFloat()
        val ch = constraints.maxHeight.toFloat()
        val scale = if (hasVideo) min(cw / vw, ch / vh) else 1f
        val dw = if (hasVideo) vw * scale else cw
        val dh = if (hasVideo) vh * scale else ch
        val offsetX = (cw - dw) / 2f
        val offsetY = (ch - dh) / 2f

        Box(
            Modifier
                .size(with(density) { dw.toDp() }, with(density) { dh.toDp() })
                .align(Alignment.Center),
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    SurfaceView(ctx).also { view ->
                        view.holder.addCallback(
                            object : SurfaceHolder.Callback {
                                override fun surfaceCreated(holder: SurfaceHolder) = onSurface(holder.surface)
                                override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, hh: Int) = Unit
                                override fun surfaceDestroyed(holder: SurfaceHolder) = onSurface(null)
                            },
                        )
                    }
                },
            )
        }

        tapMark?.let { p ->
            Box(
                Modifier
                    .offset { IntOffset((p.x - 16f).roundToInt(), (p.y - 16f).roundToInt()) }
                    .size(32.dp)
                    .border(1.5.dp, Color(0xCCFFFFFF), CircleShape),
            )
        }

        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(vw, vh, dw, dh, offsetX, offsetY) {
                    detectTapGestures { p ->
                        val nx = (p.x - offsetX) / dw
                        val ny = (p.y - offsetY) / dh
                        tapMark = p
                        if (nx in 0f..1f && ny in 0f..1f) onTap(nx, ny)
                    }
                }
                .pointerInput(vw, vh, dw, dh, offsetX, offsetY) {
                    // 拖动过程中每 50ms 发一段：抬手才发一段的话，
                    // 被控端只能把整段轨迹播成动画，手感和画面是断开的。
                    var lastX = 0f
                    var lastY = 0f
                    var lastAt = 0L
                    var active = false
                    detectDragGestures(
                        onDragStart = { p ->
                            lastX = (p.x - offsetX) / dw
                            lastY = (p.y - offsetY) / dh
                            lastAt = android.os.SystemClock.uptimeMillis()
                            active = lastX in 0f..1f && lastY in 0f..1f
                        },
                        onDrag = { change, _ ->
                            if (!active) return@detectDragGestures
                            val nx = (change.position.x - offsetX) / dw
                            val ny = (change.position.y - offsetY) / dh
                            val now = android.os.SystemClock.uptimeMillis()
                            val moved = kotlin.math.abs(nx - lastX) + kotlin.math.abs(ny - lastY) > 0.004f
                            if (moved && now - lastAt >= 50 && nx in 0f..1f && ny in 0f..1f) {
                                onSwipe(lastX, lastY, nx, ny, 60)
                                lastX = nx
                                lastY = ny
                                lastAt = now
                            }
                        },
                        onDragEnd = {
                            // 收尾不要再发「原地」段：零长度轨迹会被对端识别成点击，
                            // 于是「划一下」变成了「点一下」。
                            active = false
                        },
                        onDragCancel = { active = false },
                    )
                },
        )
    }
}

@Composable
private fun TelemetryRow(state: RcUiState) {
    val colors = Dsh.colors
    val type = Dsh.type
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("码率 ${state.kbps / 1000f} Mbps", style = type.label, color = colors.inkFaint)
        Text("${state.fps} fps", style = type.label, color = colors.inkFaint)
        Text("音频 " + state.audioState, style = type.label, color = colors.inkFaint)
    }
}

@Composable
private fun ToolRow(
    onScreenshot: () -> Unit,
    onFullscreen: () -> Unit,
    onQuality: () -> Unit,
    onDisconnect: () -> Unit,
    qualityOpen: Boolean,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        ToolCell("截图", false, onScreenshot, Modifier.weight(1f))
        ToolCell("全屏", false, onFullscreen, Modifier.weight(1f))
        ToolCell("画质", qualityOpen, onQuality, Modifier.weight(1f))
        ToolCell("断开", false, onDisconnect, Modifier.weight(1f))
    }
}

@Composable
private fun ToolCell(text: String, active: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val colors = Dsh.colors
    val dimens = Dsh.dimens
    val type = Dsh.type
    Box(
        modifier
            .clip(RoundedCornerShape(dimens.controlRadius))
            .border(
                1.dp,
                if (active) colors.accent else colors.hairline,
                RoundedCornerShape(dimens.controlRadius),
            )
            .dshClickable(scaleOnPress = false, onClick = onClick)
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = type.caption, color = if (active) colors.accent else colors.inkMuted)
    }
}

@Composable
private fun NavRow(onGlobal: (Int) -> Unit) {
    val colors = Dsh.colors
    val dimens = Dsh.dimens
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        KeyCell("返回", { onGlobal(0) }, Modifier.weight(1f))
        KeyCell("主页", { onGlobal(1) }, Modifier.weight(1f))
        KeyCell("最近", { onGlobal(2) }, Modifier.weight(1f))
    }
    Text(
        text = "全局按键走无障碍：恢复 / 主页 / 最近无需 root；澎湃 OS 请关闭无障碍超时并加入自启动白名单。",
        style = Dsh.type.label,
        color = colors.inkFaint,
        modifier = Modifier.padding(top = 6.dp),
    )
}

@Composable
private fun KeyCell(text: String, onClick: () -> Unit, modifier: Modifier) {
    val colors = Dsh.colors
    val dimens = Dsh.dimens
    val type = Dsh.type
    Box(
        modifier
            .clip(RoundedCornerShape(dimens.controlRadius))
            .border(1.dp, colors.hairlineStrong, RoundedCornerShape(dimens.controlRadius))
            .dshClickable(scaleOnPress = false, onClick = onClick)
            .padding(vertical = 11.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = type.caption, color = colors.inkMuted)
    }
}

@Composable
private fun QualityDrawer(
    state: RcUiState,
    onResolution: (Int) -> Unit,
    onFrameRate: (Int) -> Unit,
    onBitrate: (Int) -> Unit,
) {
    val colors = Dsh.colors
    val dimens = Dsh.dimens
    val type = Dsh.type

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(dimens.panelRadius))
            .border(1.dp, colors.hairline, RoundedCornerShape(dimens.panelRadius))
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        ChipRow(
            "分辨率",
            listOf("原生" to 0, "1440" to 1440, "1080" to 1080, "720" to 720),
            state.profileChoice,
            onResolution,
        )
        Spacer(Modifier.height(8.dp))
        ChipRow("帧率", listOf("30" to 30, "60" to 60, "120" to 120), state.fpsTarget, onFrameRate)
        Spacer(Modifier.height(8.dp))
        ChipRow("码率", listOf("自动" to 0, "16" to 16, "32" to 32), state.bitrateMbps, onBitrate)
        Spacer(Modifier.height(8.dp))
        Text(
            text = "码率在线生效，画面不断。帧率与分辨率要重建一次编码器，黑屏约半秒，会话不中断。",
            style = type.label,
            color = colors.inkFaint,
        )
        Spacer(Modifier.height(5.dp))
        Text(
            text = "帧率上限由编码器与链路共同决定，运行时探测，不支持自动回落。",
            style = type.label,
            color = colors.inkFaint,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "中文输入需无障碍「读取窗口内容」，本轮未接入。",
            style = type.label,
            color = colors.inkFaint,
        )
    }
}

@Composable
private fun ChipRow(label: String, options: List<Pair<String, Int>>, current: Int, onPick: (Int) -> Unit) {
    val colors = Dsh.colors
    val dimens = Dsh.dimens
    val type = Dsh.type
    Text(label, style = type.label, color = colors.inkFaint)
    Spacer(Modifier.height(6.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { (text, value) ->
            val on = current == value
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(dimens.controlRadius))
                    .border(
                        1.dp,
                        if (on) colors.accent else colors.hairline,
                        RoundedCornerShape(dimens.controlRadius),
                    )
                    .dshClickable(scaleOnPress = false, onClick = { onPick(value) })
                    .padding(vertical = 9.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(text, style = type.caption, color = if (on) colors.accent else colors.inkMuted)
            }
        }
    }
}

@Composable
private fun FullscreenChrome(
    state: RcUiState,
    qualityOpen: Boolean,
    onGlobal: (Int) -> Unit,
    onQuality: () -> Unit,
    onResolution: (Int) -> Unit,
    onFrameRate: (Int) -> Unit,
    onBitrate: (Int) -> Unit,
    onExit: () -> Unit,
    onDisconnect: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    // 悬浮球与底部按键条都能拖，位置各自记住
    var ballX by remember { mutableStateOf(0f) }
    var ballY by remember { mutableStateOf(0f) }
    var barX by remember { mutableStateOf(0f) }
    var barY by remember { mutableStateOf(0f) }
    val type = Dsh.type

    Box(Modifier.fillMaxSize()) {
        if (open) {
            Column(
                Modifier
                    .align(Alignment.CenterEnd)
                    .offset { IntOffset((ballX - 56.dp.toPx()).roundToInt(), ballY.roundToInt()) }
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0xE6121618))
                    .border(1.dp, Color(0x24FFFFFF), RoundedCornerShape(14.dp))
                    .padding(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                ChromeCell("退出远控") { onDisconnect() }
                ChromeCell("离开全屏") { onExit() }
                ChromeCell("画质", qualityOpen) { onQuality() }
            }
        }

        // 全屏下点「画质」必须就地出档位，否则按钮等于没反应
        if (open && qualityOpen) {
            Column(
                Modifier
                    .align(Alignment.Center)
                    .padding(horizontal = 24.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xF2121618))
                    .border(1.dp, Color(0x2AFFFFFF), RoundedCornerShape(16.dp))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                DarkChipRow(
            "分辨率",
            listOf("原生" to 0, "1440" to 1440, "1080" to 1080, "720" to 720),
            state.profileChoice,
            onResolution,
        )
                DarkChipRow("帧率", listOf("30" to 30, "60" to 60, "120" to 120), state.fpsTarget, onFrameRate)
                DarkChipRow("码率", listOf("自动" to 0, "16" to 16, "32" to 32), state.bitrateMbps, onBitrate)
            }
        }

        Row(
            Modifier
                .align(Alignment.BottomCenter)
                .offset { IntOffset(barX.roundToInt(), barY.roundToInt()) }
                .pointerInput(Unit) {
                    detectDragGestures { change, drag ->
                        change.consume()
                        barX += drag.x
                        barY += drag.y
                    }
                }
                .clip(RoundedCornerShape(14.dp))
                .background(Color(0xE6121618))
                .border(1.dp, Color(0x24FFFFFF), RoundedCornerShape(14.dp))
                .padding(5.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            ChromeCell("返回") { onGlobal(0) }
            ChromeCell("主页") { onGlobal(1) }
            ChromeCell("最近") { onGlobal(2) }
        }

        Box(
            Modifier
                .align(Alignment.CenterEnd)
                .offset { IntOffset(ballX.roundToInt(), ballY.roundToInt()) }
                .size(48.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(Color(0xE6121618))
                .border(1.dp, Color(0x2AFFFFFF), RoundedCornerShape(24.dp))
                .pointerInput(Unit) {
                    detectDragGestures { change, drag ->
                        change.consume()
                        ballX += drag.x
                        ballY += drag.y
                    }
                }
                .dshClickable(scaleOnPress = false, onClick = { open = !open }),
            contentAlignment = Alignment.Center,
        ) {
            Text(if (open) "×" else "≡", style = type.title, color = Color(0xE6FFFFFF))
        }
    }
}

@Composable
private fun DarkChipRow(
    label: String,
    options: List<Pair<String, Int>>,
    current: Int,
    onPick: (Int) -> Unit,
) {
    val type = Dsh.type
    Text(label, style = type.label, color = Color(0x99FFFFFF))
    Spacer(Modifier.height(5.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { (text, value) ->
            val on = current == value
            Box(
                Modifier
                    .clip(RoundedCornerShape(9.dp))
                    .background(if (on) Color(0xE6E9EDEC) else Color(0x1FFFFFFF))
                    .border(
                        1.dp,
                        if (on) Color(0x66FFFFFF) else Color(0x24FFFFFF),
                        RoundedCornerShape(9.dp),
                    )
                    .dshClickable(scaleOnPress = false, onClick = { onPick(value) })
                    .padding(horizontal = 14.dp, vertical = 9.dp),
            ) {
                Text(
                    text,
                    style = type.caption,
                    color = if (on) Color(0xFF121618) else Color(0xE6FFFFFF),
                )
            }
        }
    }
}

@Composable
private fun ChromeCell(text: String, highlight: Boolean = false, onClick: () -> Unit) {
    val type = Dsh.type
    Box(
        Modifier
            .clip(RoundedCornerShape(9.dp))
            .background(if (highlight) Color(0x33FFFFFF) else Color(0x14FFFFFF))
            .dshClickable(scaleOnPress = false, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Text(text, style = type.caption, color = Color(0xEEFFFFFF))
    }
}

// ------------------------------------------------------------------ 公共

@Composable
private fun SectionLabel(text: String) {
    Text(text, style = Dsh.type.label, color = Dsh.colors.inkFaint)
}

@Composable
private fun IpRow(ip: String, onIpChange: (String) -> Unit, onPaste: (() -> Unit)? = null) {
    val colors = Dsh.colors
    val dimens = Dsh.dimens
    val type = Dsh.type
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("地址", style = type.label, color = colors.inkFaint)
        Spacer(Modifier.width(10.dp))
        Box(
            Modifier
                .weight(1f)
                .clip(RoundedCornerShape(dimens.controlRadius))
                .background(colors.canvasShade)
                .padding(horizontal = 10.dp, vertical = 9.dp),
        ) {
            androidx.compose.foundation.text.BasicTextField(
                value = ip,
                onValueChange = onIpChange,
                singleLine = true,
                textStyle = type.mono.copy(color = colors.ink),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(colors.accent),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (onPaste != null) {
            Spacer(Modifier.width(8.dp))
            Text(
                text = "粘贴",
                style = type.caption,
                color = colors.accent,
                modifier = Modifier
                    .dshClickable(scaleOnPress = false, onClick = onPaste)
                    .padding(horizontal = 4.dp, vertical = 6.dp),
            )
        }
    }
}

/** 纸本的文字型主操作：朱红文字 + 下方一条短红线，不用方块按钮。 */
@Composable
private fun TextAction(text: String, onClick: () -> Unit) {
    val colors = Dsh.colors
    val type = Dsh.type
    Column(
        Modifier
            .dshClickable(onClick = onClick)
            .padding(vertical = 10.dp),
    ) {
        Text(text, style = type.title, color = colors.accent)
        Spacer(Modifier.height(6.dp))
        Box(Modifier.width(56.dp).height(1.5.dp).background(colors.accent))
    }
}

@Composable
private fun OutlineAction(text: String, onClick: () -> Unit) {
    val colors = Dsh.colors
    val dimens = Dsh.dimens
    val type = Dsh.type
    Box(
        Modifier
            .heightIn(min = 44.dp)
            .clip(RoundedCornerShape(dimens.tileRadius))
            .border(1.dp, colors.hairlineStrong, RoundedCornerShape(dimens.tileRadius))
            .dshClickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 11.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = type.title, color = colors.ink)
    }
}

/** 受控输入行：值由外部持有，可选右侧动作（例如「随机」）。 */
@Composable
private fun ValueRow(
    label: String,
    value: String,
    hint: String,
    onChange: (String) -> Unit,
    actionText: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val colors = Dsh.colors
    val dimens = Dsh.dimens
    val type = Dsh.type
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = type.label, color = colors.inkFaint)
        Spacer(Modifier.width(10.dp))
        Box(
            Modifier
                .weight(1f)
                .clip(RoundedCornerShape(dimens.controlRadius))
                .background(colors.canvasShade)
                .padding(horizontal = 10.dp, vertical = 9.dp),
        ) {
            androidx.compose.foundation.text.BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = true,
                textStyle = type.mono.copy(color = colors.ink),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(colors.accent),
                modifier = Modifier.fillMaxWidth(),
            )
            if (value.isEmpty()) {
                Text(hint, style = type.label, color = colors.inkFaint)
            }
        }
        if (actionText != null && onAction != null) {
            Spacer(Modifier.width(8.dp))
            Text(
                text = actionText,
                style = type.caption,
                color = colors.accent,
                modifier = Modifier
                    .dshClickable(scaleOnPress = false, onClick = onAction)
                    .padding(horizontal = 4.dp, vertical = 6.dp),
            )
        }
    }
}

@Composable
private fun FieldRow(label: String, hint: String, onChange: (String) -> Unit) {
    val colors = Dsh.colors
    val dimens = Dsh.dimens
    val type = Dsh.type
    var value by remember { mutableStateOf("") }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = type.label, color = colors.inkFaint)
        Spacer(Modifier.width(10.dp))
        Box(
            Modifier
                .weight(1f)
                .clip(RoundedCornerShape(dimens.controlRadius))
                .background(colors.canvasShade)
                .padding(horizontal = 10.dp, vertical = 8.dp),
        ) {
            androidx.compose.foundation.text.BasicTextField(
                value = value,
                onValueChange = {
                    value = it
                    onChange(it)
                },
                singleLine = true,
                textStyle = type.mono.copy(color = colors.ink),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(colors.accent),
                modifier = Modifier.fillMaxWidth(),
            )
            if (value.isEmpty()) {
                Text(hint, style = type.label, color = colors.inkFaint)
            }
        }
    }
}

@Composable
private fun RcButton(text: String, primary: Boolean, onClick: () -> Unit) {
    val colors = Dsh.colors
    val dimens = Dsh.dimens
    val type = Dsh.type
    val shape = RoundedCornerShape(dimens.controlRadius)
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 46.dp)
            .clip(shape)
            .background(if (primary) colors.accent else colors.canvas)
            .border(1.dp, if (primary) colors.accent else colors.accentBorder, shape)
            .dshClickable(onClick = onClick)
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = type.title, color = if (primary) colors.accentInk else colors.ink)
    }
}