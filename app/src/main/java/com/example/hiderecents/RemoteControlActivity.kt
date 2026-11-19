package com.example.hiderecents

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.os.SystemClock
import android.view.Surface
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.example.hiderecents.rc.RcHostService
import com.example.hiderecents.rc.RcInput
import com.example.hiderecents.rc.RcProtocol
import com.example.hiderecents.rc.RcViewer
import com.example.hiderecents.ui.screens.RcMode
import com.example.hiderecents.ui.screens.RcUiState
import com.example.hiderecents.ui.screens.RemoteControlScreen
import com.example.hiderecents.ui.theme.AppTheme
import com.example.hiderecents.ui.theme.DshTheme
import com.example.hiderecents.ui.theme.applyDshSystemBars
import kotlinx.coroutines.delay
import kotlin.math.max

/**
 * 手机远控 · M1。
 *
 * 被控端走 [RcHostService]（前台服务持 MediaProjection），控制端在本页解码直渲并回传触控。
 * 两条路都不碰 CPU 拷贝：采集侧 VirtualDisplay 直接喂编码器 Surface，控制端直接渲到 SurfaceView。
 */
class RemoteControlActivity : DshActivity() {

    private var mode by mutableStateOf(RcMode.PICK)
    private var ip by mutableStateOf("")
    private var status by mutableStateOf("")
    private var detail by mutableStateOf("")
    private var fps by mutableStateOf(0)
    private var kbps by mutableStateOf(0)
    private var bitrateMbps by mutableStateOf(0)
    private var longSide by mutableStateOf(1080)
    private var fpsTarget by mutableStateOf(60)
    private var connected by mutableStateOf(false)
    private var fullscreen by mutableStateOf(false)
    private var videoW by mutableStateOf(0)
    private var videoH by mutableStateOf(0)
    private var peerName by mutableStateOf("")
    private var pip by mutableStateOf(false)
    private var viewerAudio by mutableStateOf("未连接")
    private var adbTarget by mutableStateOf("")
    private var adbCode by mutableStateOf("")
    private var adbStatus by mutableStateOf("")
    private val devices = androidx.compose.runtime.mutableStateListOf<Pair<String, String>>()
    private val seenAt = mutableMapOf<String, Long>()

    private var viewer: RcViewer? = null
    private var surface: Surface? = null
    private var pendingConnect = false
    /** 用户手动选过分辨率/帧率后，不再自动按视口对齐。 */
    private var autoProfile = true
    /** 画质面板里选中的那一档：0 表示原生（按视口）。 */
    private var profileChoice by mutableStateOf(0)
    private var lastTapAt = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences("hide_recents_prefs", Context.MODE_PRIVATE)
        val theme = AppTheme.fromPref(prefs.getString("ui_theme", null))
        applyDshSystemBars(theme)
        ip = prefs.getString(KEY_LAST_IP, "") ?: ""

        viewer = RcViewer(
            onState = { text -> status = text },
            onConnected = { value -> runOnUiThread { connected = value } },
            onStats = { f, k ->
                fps = f
                kbps = k
            },
            onVideoSize = { w, h ->
                videoW = w
                videoH = h
                // 画面尺寸来自被控端时，把编码尺寸对齐到本机视口。
                // 但不能一路顶到物理长边：2560 这种尺寸下码率跟不上，动态画面糊成块，
                // 手机上肉眼看不出相比 1080p 的提升。
                if (autoProfile) applyAutoProfile()
            },
        )

        setContent {
            DshTheme(theme) {

                LaunchedEffect(fullscreen) { applyImmersive(fullscreen) }

                // 全屏时跟随画面方向：横屏素材在竖屏里永远铺不满。
                // 退出全屏立刻交还给系统，避免把方向锁在横屏上。
                LaunchedEffect(fullscreen, videoW, videoH) {
                    requestedOrientation = if (!fullscreen) {
                        ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                    } else if (videoW in 1..videoH) {
                        ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                    } else if (videoW > videoH) {
                        ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                    } else {
                        ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                    }
                }



                // 方向完全交还给系统：之前这里按画面比例强制 requestedOrientation，
                // 会盖掉用户自己的方向锁定，属于越权，已移除。旋转只由用户点「旋转」触发。
                // 持续重扫：被控端可能比我们先打开页面；进了控制页但还没连上时也继续扫。
                LaunchedEffect(mode, connected) {
                    val scanning = mode == RcMode.PICK || (mode == RcMode.VIEWER && !connected)
                    if (!scanning) return@LaunchedEffect
                    while (true) {
                        com.example.hiderecents.rc.RcDiscovery.scan(1500) { f ->
                            val addr = f.address + ":" + f.port
                            seenAt[addr] = SystemClock.elapsedRealtime()
                            if (devices.none { it.second == addr }) {
                                devices.add(f.name to addr)
                            }
                            if (ip.isBlank()) ip = addr
                            peerName = f.name
                            status = "已发现 " + f.name
                        }
                        // 连续几轮都没应答的设备从列表里去掉，避免列表越攒越多死条目
                        val now = SystemClock.elapsedRealtime()
                        devices.removeAll { item -> (now - (seenAt[item.second] ?: 0L)) > 6000 }
                        delay(600)
                    }
                }

                // 被控端：一进页面就答复探测，不必等录屏授权
                LaunchedEffect(mode) {
                    if (mode != RcMode.HOST) return@LaunchedEffect
                    val stopAdvertise = com.example.hiderecents.rc.RcDiscovery.advertise(
                        RcHostService.port,
                        android.os.Build.MODEL ?: "Android",
                    )
                    try {
                        while (true) {
                            detail = formatAddress()
                            status = when {
                                RcHostService.lastError != null -> RcHostService.lastError ?: ""
                                RcHostService.connected ->
                                    "已连接 ${RcHostService.clientName}" +
                                        " · " + (if (com.example.hiderecents.rc.RcInput.ready) "可注入" else "无注入权限，需开启无障碍") +
                                        " · 编码器最高 ${RcHostService.encoderMaxFps}fps" +
                                        " · 控制通道" + (if (RcHostService.ctrlConnected) "已连" else "未连") +
                                        " · 音频" + (if (RcHostService.audioConnected) "已连" else "未连") +
                                        " · 触控 收${RcHostService.touchRx}/成${RcHostService.touchOk}" +
                                        (com.example.hiderecents.rc.RcInput.lastReason
                                            .takeIf { it.isNotEmpty() }?.let { " · $it" } ?: "")

                                RcHostService.isRunning -> "等待控制端连接"
                                else -> "尚未开始共享"
                            }
                            delay(500)
                        }
                    } finally {
                        stopAdvertise()
                    }
                }

                RemoteControlScreen(
                    state = RcUiState(
                        mode = mode,
                        ip = ip,
                        status = status,
                        detail = detail,
                        fps = fps,
                        // 画面尺寸来自解码器 config
                        kbps = kbps,
                        bitrateMbps = bitrateMbps,
                        longSide = longSide,
                        profileChoice = profileChoice,
                        fpsTarget = fpsTarget,
                        connected = connected,
                        devices = devices.toList(),
                        peerName = peerName,
                        videoWidth = videoW,
                        videoHeight = videoH,
                        audioState = viewerAudio,
                        adbTarget = adbTarget,
                        adbCode = adbCode,
                        adbStatus = adbStatus,
                    ),
                    onBack = { onDshBackPressed() },
                    onPickHost = {
                        mode = RcMode.HOST
                        ensureInjection()
                    },
                    onPickViewer = { mode = RcMode.VIEWER },
                    onRequestShare = {
                        requestCapture()
                    },
                    onAdbTargetChange = { adbTarget = it },
                    onAdbCodeChange = { adbCode = it },
                    onAdbPair = {
                        val target = adbTarget.trim()
                        val code = adbCode.trim()
                        if (target.isEmpty() || code.isEmpty()) {
                            adbStatus = "先填配对地址和六位码"
                        } else {
                            adbStatus = "配对中…"
                            Thread {
                                val host = target.substringBefore(':')
                                val port = target.substringAfter(':', "0").toIntOrNull() ?: 0
                                val r = runCatching {
                                    kotlinx.coroutines.runBlocking {
                                        com.example.hiderecents.rc.AdbBridge.pair(
                                            this@RemoteControlActivity, host, port, code,
                                        )
                                    }
                                }
                                runOnUiThread {
                                    adbStatus = "配对 " + if (r.isSuccess) "成功" else {
                                        "失败：" + (r.exceptionOrNull()?.message ?: "")
                                    }
                                }
                            }.start()
                        }
                    },
                    onAdbConnect = {
                        val target = adbTarget.trim()
                        if (target.isEmpty()) {
                            adbStatus = "先填被控端地址"
                        } else {
                            adbStatus = "连接中…"
                            Thread {
                                val host = target.substringBefore(':')
                                val port = target.substringAfter(':', "0").toIntOrNull() ?: 0
                                val r = runCatching {
                                    kotlinx.coroutines.runBlocking {
                                        com.example.hiderecents.rc.AdbBridge.connect(
                                            this@RemoteControlActivity, host, port,
                                        )
                                    }
                                }
                                runOnUiThread {
                                    adbStatus = "连接 " + if (r.isSuccess) {
                                        "成功：" + (r.getOrNull() ?: "")
                                    } else {
                                        "失败：" + (r.exceptionOrNull()?.message ?: "")
                                    }
                                }
                            }.start()
                        }
                    },
                    onTestScrcpy = {
                        status = "正在探测 scrcpy…"
                        Thread {
                            val binder = TaskHideBridge.resolve(null)
                            val jar = com.example.hiderecents.rc.ScrcpyHost.ensureServer(
                                this@RemoteControlActivity,
                                binder ?: return@Thread run {
                                    runOnUiThread { status = "scrcpy：没有 shell 通道（Shizuku 未就绪）" }
                                },
                            )
                            // 必须先让 shell 侧进入监听，服务端启动后是主动来连的
                            var pfd: android.os.ParcelFileDescriptor? = null
                            val listener = Thread {
                                pfd = com.example.hiderecents.rc.ScrcpyHost.openSocket(binder)
                            }
                            listener.start()
                            Thread.sleep(600)
                            val started = com.example.hiderecents.rc.ScrcpyHost.startServer(
                                binder,
                                maxSize = 1080,
                                maxFps = 30,
                                videoBitRate = 20_000_000,
                                withAudio = false,
                            )
                            runCatching { listener.join(8000) }
                            val head = pfd?.let { com.example.hiderecents.rc.ScrcpyHost.probeHeader(it) }
                            val log = com.example.hiderecents.rc.ScrcpyHost.readServerLog(binder)
                            val msg = "scrcpy jar=" + jar + " start=" + started +
                                " sock=" + (pfd != null) + " head=" + (head?.size ?: 0) +
                                " log=" + log.replace("\n", " | ")
                            android.util.Log.i("ScrcpyProbe", msg)
                            runOnUiThread { status = msg }
                        }.start()
                    },
                    onTestInject = {
                        val ready = com.example.hiderecents.rc.RcInput.ready
                        if (ready) {
                            com.example.hiderecents.rc.RcInput.tap(0.5f, 0.5f)
                            status = "已向被控屏中央注入一次点击"
                        } else {
                            status = "注入未就绪：无障碍服务没开"
                        }
                    },
                    onOpenAccessibility = {
                        if (!ensureInjection()) {
                            runCatching {
                                startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
                            }
                        }
                    },
                    onStopHost = {
                        RcHostService.stop(this@RemoteControlActivity)
                        status = "已停止"
                    },
                    onIpChange = { ip = it },
                    onPasteAddress = {
                        val clip = getSystemService(android.content.ClipboardManager::class.java)
                            ?.primaryClip
                        val text = runCatching {
                            if (clip != null && clip.itemCount > 0) {
                                clip.getItemAt(0).text?.toString().orEmpty()
                            } else {
                                ""
                            }
                        }.getOrDefault("").trim()
                        if (text.isEmpty()) {
                            status = "剪贴板里没有地址"
                        } else {
                            ip = text
                            status = "已从剪贴板填入地址"
                        }
                    },
                    onConnect = { connectViewer() },
                    onDisconnect = { disconnectViewer() },
                    onSurface = { s ->
                        surface = s
                        viewer?.attach(s)
                        if (s != null && pendingConnect) {
                            pendingConnect = false
                            startViewerConnection()
                        }
                    },
                    onTap = { x, y -> viewer?.sendTap(x, y) },
                    onSwipe = { x1, y1, x2, y2, dur -> viewer?.sendSwipe(x1, y1, x2, y2, dur) },
                    onGlobal = { code -> viewer?.sendGlobal(code) },
                    onBitrate = { mbps ->
                        bitrateMbps = mbps
                        pushOutput()
                        status = if (mbps == 0) "码率改为自动" else "码率已切到 $mbps Mbps"
                    },
                    onResolution = { side ->
                        profileChoice = side
                        if (side <= 0) {
                            autoProfile = true
                            applyAutoProfile()
                            status = "分辨率改回按设备原生"
                        } else {
                            autoProfile = false
                            longSide = side
                            pushOutput()
                            status = "分辨率切到 ${side}p，编码器重建一次"
                        }
                    },
                    onFrameRate = { fps ->
                        fpsTarget = fps
                        pushOutput()
                        status = "帧率切到 ${fps}fps，编码器重建一次"
                    },
                    onScreenshot = { status = "截图暂未接入" },
                    pipMode = pip,
                    onRetry = {
                        mode = RcMode.PICK
                        connectViewer()
                    },
                    fullscreen = fullscreen,
                    onToggleFullscreen = { fullscreen = !fullscreen },
                    onRotate = {
                        status = "方向由系统控制，请用系统快捷开关旋转"
                    },
                    videoWidth = videoW,
                    videoHeight = videoH,
                )
            }
        }
    }

    // ------------------------------------------------------------------ 被控端

    /**
     * 自动开启自己的无障碍服务。
     *
     * 普通权限下做不到（WRITE_SECURE_SETTINGS 是 signature 级），但 Shizuku 在运行时
     * 可以以 shell 身份写 secure 设置 —— 项目里本来就有这条通道，远控之前没接。
     */
    private fun ensureInjection(): Boolean {
        if (RcInput.ready) return true
        if (!rikka.shizuku.Shizuku.pingBinder()) {
            status = "无障碍未开，且 Shizuku 未运行，需要手动开启"
            return false
        }
        val mine = android.content.ComponentName(
            this,
            AccessibilityKeepAliveService::class.java,
        ).flattenToString()
        val current = runCatching {
            android.provider.Settings.Secure.getString(
                contentResolver,
                android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            )
        }.getOrNull().orEmpty()

        if (!current.contains(mine)) {
            val next = if (current.isBlank()) mine else "$current:$mine"
            val bridge = TaskHideBridge.resolve(null)
            ShizukuShell.exec(bridge, "settings put secure enabled_accessibility_services '$next'")
            ShizukuShell.exec(bridge, "settings put secure accessibility_enabled 1")
        }
        status = "已通过 Shizuku 启用无障碍，稍等一秒生效"
        return true
    }


    private fun requestCapture() {
        val mgr = getSystemService(MediaProjectionManager::class.java)
        if (mgr == null) {
            status = "本机不支持录屏"
            return
        }
        status = "等待录屏授权"
        @Suppress("DEPRECATION")
        startActivityForResult(mgr.createScreenCaptureIntent(), REQUEST_CAPTURE)
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_CAPTURE) return
        if (resultCode != RESULT_OK || data == null) {
            status = "已取消授权"
            return
        }
        val (width, height) = captureSize()
        RcHostService.start(
            this,
            resultCode,
            data,
            width,
            height,
            resources.displayMetrics.densityDpi,
        )
        detail = formatAddress()
        status = "已开始采集，等待控制端连接"
    }

    /** 采集尺寸取物理分辨率不缩放：VirtualDisplay 与源同尺寸时走 GPU 直通。 */
    private fun captureSize(): Pair<Int, Int> {
        val dm = resources.displayMetrics
        var w = dm.widthPixels
        var h = dm.heightPixels
        val longSide = max(w, h)
        if (longSide > MAX_LONG_SIDE) {
            val k = MAX_LONG_SIDE.toFloat() / longSide
            w = (w * k).toInt()
            h = (h * k).toInt()
        }
        w -= w % 2
        h -= h % 2
        return max(2, w) to max(2, h)
    }

    private fun formatAddress(): String {
        val host = RcHostService.lanAddress
        return if (host.isEmpty()) {
            "未拿到局域网地址，请确认已连 Wi-Fi"
        } else {
            "$host:${RcHostService.port}"
        }
    }

    // ------------------------------------------------------------------ 控制端

    private fun connectViewer() {
        if (ip.isBlank()) {
            status = "没搜到设备，先填地址或点「粘贴」"
            return
        }
        getSharedPreferences("hide_recents_prefs", Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LAST_IP, ip.trim())
            .apply()

        // 从设备列表点进来也要切到画面页，否则连上了还停在选设备页
        mode = RcMode.VIEWER
        startViewerConnection()
    }

    private fun startViewerConnection() {
        val target = ip.trim()
        val host = target.substringBefore(':').ifEmpty { target }
        val port = if (target.contains(':')) {
            target.substringAfter(':').toIntOrNull() ?: RcProtocol.PORT
        } else {
            RcProtocol.PORT
        }
        viewer?.stop()
        viewer?.attach(surface)
        viewer?.connect(host, port)
        status = "正在连接 $host:$port"
        lastTapAt = SystemClock.elapsedRealtime()
    }

    private fun disconnectViewer() {
        viewer?.stop()
        connected = false
        pendingConnect = false
        status = ""
    }

    /** 画质三项一起发：被控端自己判断哪些可以直接生效、哪些要重建编码器。 */
    private fun pushOutput() {
        viewer?.sendOutput(resolveBitrate(), fpsTarget, longSide)
    }

    /** 按本机视口对齐编码长边。只能在用户没手动选分辨率时用。 */
    private fun applyAutoProfile() {
        if (!connected || pip) return
        if (!fullscreen) fullscreen = true
        // 必须用设备真实分辨率：resources.displayMetrics 在小窗/分屏下会缩水，
        // 曾经把编码尺寸带成 464×464，画面直接糊死。
        val real = android.util.DisplayMetrics()
        val display = getSystemService(android.hardware.display.DisplayManager::class.java)
            ?.getDisplay(android.view.Display.DEFAULT_DISPLAY)
        display?.getRealMetrics(real)
        val viewportLong = if (real.widthPixels > 0 && real.heightPixels > 0) {
            max(real.widthPixels, real.heightPixels)
        } else {
            max(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels)
        }
        if (viewportLong != longSide) {
            longSide = viewportLong
            viewer?.sendOutput(resolveBitrate(), fpsTarget, viewportLong)
        }
    }

    /**
     * 0 表示自动：按真实画面尺寸现算。
     * 固定 16 Mbps 在 1250×2560 这种高分屏上，动态场景必然糊。
     */

    /**
     * 0 表示自动：按真实画面尺寸现算。
     * 固定 16 Mbps 在 1250×2560 这种高分屏上，动态场景必然糊。
     * 走中继时还要再压：服务器只有 30 Mbps，编码器按内容能冲到 30+，打满带宽就整帧丢。
     */
    private fun resolveBitrate(): Int {
        if (bitrateMbps > 0) return bitrateMbps * 1_000_000
        val ratio = if (videoW > 0 && videoH > 0) videoH.toFloat() / videoW.toFloat() else 1f
        val side = longSide.coerceAtLeast(480)
        val w = if (ratio >= 1f) (side / ratio).toInt() else side
        val h = if (ratio >= 1f) side else (side * ratio).toInt()
        val auto = (w.toLong() * h * fpsTarget * 20L / 100L).toInt()
        return auto.coerceIn(4_000_000, 80_000_000)
    }

    private fun applyImmersive(on: Boolean) {
        // 始终 edge-to-edge：一旦切回 decorFits=true，状态栏与刘海区就变成系统色，
        // 页面背景不再延伸上去，标题也会整体下移，和其它页面不一致。
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        if (on) {
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    /** 旋转的是控制端自己，画面按新方向重新等比 FIT，不改被控端朝向。 */


    // ------------------------------------------------------------------ 生命周期

    override fun onResume() {
        super.onResume()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun onPause() {
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        super.onPause()
    }

    /** 在子页面按返回先回到「远程控制」，不要直接退出去。 */
    override fun onDshBackPressed() {
        if (mode != RcMode.PICK) {
            // 被控端返回上一页不停服务：共享是前台服务，属于会话而不是页面
            if (mode == RcMode.VIEWER) disconnectViewer()
            fullscreen = false
            mode = RcMode.PICK
            return
        }
        super.onDshBackPressed()
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (mode == RcMode.VIEWER && connected) enterPip()
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration,
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        pip = isInPictureInPictureMode
    }

    /** 离开控制页不断开：缩成小窗继续推流，点小窗回到全屏。 */
    private fun enterPip() {
        if (android.os.Build.VERSION.SDK_INT < 26) return
        runCatching {
            val w = videoW.coerceAtLeast(1)
            val h = videoH.coerceAtLeast(1)
            val params = android.app.PictureInPictureParams.Builder()
                .setAspectRatio(android.util.Rational(w, h))
                .build()
            enterPictureInPictureMode(params)
        }
    }

    override fun onDestroy() {
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        // 被控端服务不随页面销毁：系统弹窗、切应用都会走到这里，
        // 共享必须活到用户主动点「立刻断开」为止。

        viewer?.stop()
        viewer = null
        super.onDestroy()
    }

    private companion object {
        const val REQUEST_CAPTURE = 4201
        const val KEY_LAST_IP = "rc_last_ip"
        /** 中继带宽只有 30 Mbps，留出余量给突发和重传。 */
        const val MAX_LONG_SIDE = 2560
    }
}
