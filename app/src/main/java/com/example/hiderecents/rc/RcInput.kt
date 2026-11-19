package com.example.hiderecents.rc

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.example.hiderecents.AccessibilityKeepAliveService
import com.example.hiderecents.ShizukuShell
import com.example.hiderecents.TaskHideBridge
import java.nio.ByteBuffer

/**
 * 被控端的输入注入。
 *
 * 底座路径只有无障碍：`dispatchGesture` 是「一条预编排轨迹 + 时长」，按下之后改不了路径，
 * 所以这里只接受离散动作（点击、滑动、全局按键），不做逐帧跟手 —— 那是增强路径的事。
 *
 * 三个全局按键走 `performGlobalAction`，官方 API，不需要 root。
 * 但澎湃 / MIUI 有无障碍「超时自动关闭」、锁屏后关闭、部分全面屏手势下 RECENTS 被吞，
 * 所以调用失败要能被上层看见，不能静默。
 */
object RcInput {

    const val MIN_SWIPE_MS = 40L
    const val MAX_SWIPE_MS = 600L
    private const val TAG = "RcInput"

    private val main = Handler(Looper.getMainLooper())

    /** 由被控服务注入：Shizuku 注入路径下没有 AccessibilityService 可取屏幕尺寸。 */
    @Volatile var lastReason: String = ""

    @Volatile var screenWidth: Int = 0
    @Volatile var screenHeight: Int = 0

    private val service: AccessibilityService?
        get() = AccessibilityKeepAliveService.instance

    /**
     * Shizuku 兜底：无障碍服务在某些 ROM 上会被系统摘掉、装完包也未必重新绑定，
     * 而 Shizuku 在运行时可以直接用 shell 的 input 命令注入。
     * 每次多几十毫秒，但比「点了完全没反应」好。
     */
    private fun shizukuReady(): Boolean = runCatching {
        rikka.shizuku.Shizuku.pingBinder() &&
            rikka.shizuku.Shizuku.checkSelfPermission() ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    private fun shizuku(cmd: String): Boolean = runCatching {
        ShizukuShell.exec(TaskHideBridge.resolve(null), cmd).first
    }.getOrDefault(false)

    val ready: Boolean
        get() = Build.VERSION.SDK_INT >= 24 && service != null || shizukuReady()

    fun handle(payload: ByteArray): Boolean {
        if (Build.VERSION.SDK_INT < 24 || payload.isEmpty()) return false
        val buf = ByteBuffer.wrap(payload)
        return when (buf.get().toInt()) {
            TouchPacket.ACTION_TAP -> tap(buf.float, buf.float)
            TouchPacket.ACTION_SWIPE ->
                swipe(buf.float, buf.float, buf.float, buf.float, buf.int)

            TouchPacket.ACTION_GLOBAL -> global(buf.get().toInt())
            else -> false
        }
    }

    fun tap(nx: Float, ny: Float): Boolean {
        val (x, y) = toScreen(nx, ny)
        if (service != null) {
            val path = Path().apply { moveTo(x, y) }
            return dispatch(path, 0L, TAP_MS)
        }
        if (shizukuReady()) {
            val ok = shizuku("input tap " + x.toInt() + " " + y.toInt())
            lastReason = if (ok) "" else "Shizuku 执行 input tap 失败"
            return ok
        }
        lastReason = "无障碍实例为空，且 Shizuku 不可用"
        return false
    }

    fun swipe(nx1: Float, ny1: Float, nx2: Float, ny2: Float, durationMs: Int): Boolean {
        val (x1, y1) = toScreen(nx1, ny1)
        val (x2, y2) = toScreen(nx2, ny2)
        val duration = durationMs.toLong().coerceIn(MIN_SWIPE_MS, MAX_SWIPE_MS)
        if (service != null) {
            val path = Path().apply {
                moveTo(x1, y1)
                lineTo(x2, y2)
            }
            return dispatch(path, 0L, duration)
        }
        if (shizukuReady()) {
            return shizuku("input swipe " + x1.toInt() + " " + y1.toInt() + " " + x2.toInt() + " " + y2.toInt() + " " + duration)
        }
        return false
    }

    fun global(code: Int): Boolean {
        val svc = service
        if (svc == null) {
            val key = when (code) {
                TouchPacket.GLOBAL_BACK -> 4
                TouchPacket.GLOBAL_HOME -> 3
                TouchPacket.GLOBAL_RECENTS -> 187
                else -> return false
            }
            return shizukuReady() && shizuku("input keyevent " + key)
        }
        val action = when (code) {
            TouchPacket.GLOBAL_BACK -> AccessibilityService.GLOBAL_ACTION_BACK
            TouchPacket.GLOBAL_HOME -> AccessibilityService.GLOBAL_ACTION_HOME
            TouchPacket.GLOBAL_RECENTS -> AccessibilityService.GLOBAL_ACTION_RECENTS
            else -> return false
        }
        var ok = false
        main.post { ok = runCatching { svc.performGlobalAction(action) }.getOrDefault(false) }
        return ok
    }

    private fun dispatch(path: Path, startMs: Long, durationMs: Long): Boolean {
        val svc = service
        if (svc == null) {
            lastReason = "无障碍实例为空"
            return false
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, startMs, durationMs))
            .build()
        val posted = main.post {
            val caps = svc.serviceInfo?.capabilities ?: -1
            Log.i(TAG, "inject caps=$caps gesturesFlag=${caps and android.accessibilityservice.AccessibilityServiceInfo.CAPABILITY_CAN_PERFORM_GESTURES}")
            val accepted = runCatching {
                svc.dispatchGesture(
                    gesture,
                    object : AccessibilityService.GestureResultCallback() {
                        override fun onCompleted(g: GestureDescription?) {
                            Log.i(TAG, "gesture completed")
                        }

                        override fun onCancelled(g: GestureDescription?) {
                            Log.w(TAG, "gesture cancelled")
                        }
                    },
                    main,
                )
            }.onFailure { Log.w(TAG, "注入失败", it) }.getOrDefault(false)
            if (!accepted) {
                lastReason = "手势被系统拒绝"
                Log.w(TAG, "dispatchGesture 被拒绝")
            }
        }
        return posted
    }

    private fun toScreen(nx: Float, ny: Float): Pair<Float, Float> {
        val metrics = service?.resources?.displayMetrics
        val w = screenWidth.takeIf { it > 0 } ?: metrics?.widthPixels ?: 1080
        val h = screenHeight.takeIf { it > 0 } ?: metrics?.heightPixels ?: 2400
        return (nx.coerceIn(0f, 1f) * w) to (ny.coerceIn(0f, 1f) * h)
    }

    private const val TAP_MS = 40L
}