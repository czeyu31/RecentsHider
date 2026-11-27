package com.example.hiderecents.island

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import com.example.hiderecents.R
import java.util.concurrent.ConcurrentHashMap

/**
 * 标准实时活动通道：只发一条普通常驻通知，请求 Android 16 自己把它提升成实时活动。
 *
 * 与另外两条通道并列，互不影响：
 *  · [IslandV3] 硬喂澎湃 SystemUI 的私有协议，只在小米上有意义；
 *  · [IslandPublish] 发通知 + 小米 template_v2，能不能进岛看系统心情；
 *  · 这一条不发任何厂商私有字段，靠系统提升（`android.requestPromotedOngoing`），
 *    HyperOS 3.1+ / ColorOS 16 / MagicOS 10 会把提升后的通知画进各自的岛，
 *    Pixel 显示成状态栏胶囊。小米焦点模板只在 [XiaomiFocusGate] 放行时附加。
 *
 * 几条硬约束（都是搬过来的经验，缺一条就会留垃圾通知或看不到更新）：
 *  · 系统既不会提升、小米又不放行时，什么都不发 —— 否则只是多一条没人认领的常驻通知；
 *  · 同一个 id 两次发布至少隔 [MIN_GAP_MS]，NotificationManager 会把连发丢掉；
 *  · 内容没变不重发，但每 [REFRESH_MS] 续一次，否则 [TIMEOUT_MS] 到点自己消失；
 *  · 进程被杀后最多留 [TIMEOUT_MS] 的残留，不会一直挂着「监控中」。
 */
object IslandStandard {

    private const val TAG = "IslandStandard"

    /** 独立渠道：与 live 的 island_ops、V3 的 island_v3 分开，切通道时通知栏里分得清 */
    private const val CHANNEL = "island_std"

    /**
     * 通知 id 与 [IslandPublish] 的槽位空间错开（那边 slotBase ≤ 200001），
     * 这样切通道时两条通道的通知不会互相覆盖，各自撤各自的。
     */
    const val ID_HEART = 1_000_001
    const val ID_TEMP = 1_000_002

    /** 两次发布的最小间隔下限；实际间隔取设置里的「更新节流」 */
    private const val MIN_GAP_MS = 600L

    /** 续期间隔：小于 [TIMEOUT_MS]，让岛在活跃期间不会到点消失 */
    private const val REFRESH_MS = 4 * 60_000L

    /** 没有后续更新时，通知自己消失的时间。留得比续期长得多，正常使用中不会走到 */
    private const val TIMEOUT_MS = 30 * 60_000L

    /** `canPostPromotedNotifications` 是系统调用，一分钟内不重复问 */
    private const val PROBE_TTL_MS = 60_000L

    /** 胶囊短文本的显示上限：Android 只完整显示到大约七个字 */
    private const val CHIP_MAX = 12

    /** 右侧像素图案的边长（dp）；岛上会按密度放大 */
    private const val ART_DP = 64

    /** Android 16（SDK 36）起才有推广进行中通知；常量到 36.1 才公开，这里写字面量 */
    private const val SDK_PROMOTED = 36

    private const val EXTRA_REQUEST_PROMOTED = "android.requestPromotedOngoing"
    private const val EXTRA_SHORT_CRITICAL = "android.shortCriticalText"

    private val handler: Handler by lazy {
        Handler(HandlerThread("island-std").apply { start() }.looper)
    }

    /** 每个 id 一份发布状态，只在 [handler] 线程上改 */
    private class Slot {
        var shown: String? = null
        var posted = false
        var at = 0L
        var refresher: Runnable? = null
        /** 上一次发布的内容，退到后台时照它重发一帧 */
        var lastReq: Req? = null
    }

    private val slots = ConcurrentHashMap<Int, Slot>()

    @Volatile private var probedAt = 0L
    @Volatile private var promoteOk = false

    // ---------------------------------------------------------------- 能力探测

    /**
     * 本机现在能不能走这条路：系统会把通知提升成实时活动。
     *
     * 同步做一次系统调用（含 binder），设置页打开调一次没问题，别放进每秒循环里 ——
     * 发布路径问的是带缓存的 [probe]。
     */
    fun supported(context: Context): Boolean = probe(context)

    private fun probe(context: Context): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (probedAt != 0L && now - probedAt < PROBE_TTL_MS) return promoteOk
        val ctx = context.applicationContext
        val ok = Build.VERSION.SDK_INT >= SDK_PROMOTED && runCatching {
            nm(ctx).canPostPromotedNotifications()
        }.getOrDefault(false)
        promoteOk = ok
        probedAt = now
        return ok
    }

    // ---------------------------------------------------------------- 发布

    private class Req(
        val id: Int,
        val title: String,
        val content: String,
        val leftText: String,
        val rightText: String,
        val chip: String,
        val progressPct: Int,
        val heart: Boolean,
        val sinceMs: Long?,
        val overThreshold: Boolean,
        val contentIntent: PendingIntent?,
        val actions: List<Pair<String, PendingIntent>>,
        val order: Int,
        val priority: Int,
        /** 这个岛选中的图案与岛色 */
        val artId: String,
        val tintId: String,
        /** 岛上是否显示进度条 */
        val showProgress: Boolean,
    ) {
        /** 可见内容的指纹：一样就不重发（省电，也避免系统把连发丢掉） */
        val fingerprint: String = buildString {
            append(id).append('\u0001')
            append(title).append('\u0001')
            append(content).append('\u0001')
            append(leftText).append('\u0001')
            append(rightText).append('\u0001')
            append(chip).append('\u0001')
            append(progressPct).append('\u0001')
            append(heart).append('\u0001')
            append(sinceMs).append('\u0001')
            append(overThreshold).append('\u0001')
            append(order).append('\u0001')
            append(priority).append('\u0001')
            append(artId).append('\u0001')
            append(tintId).append('\u0001')
            append(showProgress).append('\u0001')
            actions.forEach { append(it.first).append('\u0002') }
        }
    }

    /**
     * 发布/更新一条标准实时活动。
     *
     * @param chip 胶囊右段的短文本（心率给 bpm，温度给℃）；超过 [CHIP_MAX] 会截断
     * @param progressPct 0–100 放进度条；-1 不放（心率这类没有总量的指标）
     * @param sinceMs 计时起点：非空时系统会显示走动的计时器（录制中用）
     */
    fun post(
        context: Context,
        id: Int,
        title: String,
        content: String,
        leftText: String,
        rightText: String,
        chip: String,
        progressPct: Int,
        heart: Boolean,
        sinceMs: Long? = null,
        overThreshold: Boolean = false,
        contentIntent: PendingIntent? = null,
        actions: List<Pair<String, PendingIntent>> = emptyList(),
        order: Int = 0,
        priority: Int = 1,
    ) {
        val ctx = context.applicationContext
        // 图案和岛色跟着每个岛各自的设置走；换图案时指纹会变，所以会重发一次
        val req = Req(
            id = id,
            title = title,
            content = content,
            leftText = leftText,
            rightText = rightText,
            chip = chip.take(CHIP_MAX),
            progressPct = progressPct,
            heart = heart,
            sinceMs = sinceMs,
            overThreshold = overThreshold,
            contentIntent = contentIntent,
            actions = actions,
            order = order,
            priority = priority,
            artId = if (heart) IslandSettings.heartArt else IslandSettings.tempArt,
            tintId = if (heart) IslandSettings.heartTint else IslandSettings.tempTint,
            showProgress = if (heart) IslandSettings.heartProgress else IslandSettings.tempProgress,
        )
        handler.post { apply(ctx, req) }
    }

    /** 撤岛。没发过东西的 id 直接返回，所以每秒调也不吵 */
    fun cancel(context: Context, id: Int) {
        if (!slots.containsKey(id)) return
        val ctx = context.applicationContext
        handler.post {
            slots.remove(id)?.let { slot ->
                slot.refresher?.let { handler.removeCallbacks(it) }
                runCatching { nm(ctx).cancel(id) }
            }
        }
    }

    /**
     * 照着上一次的内容再发一遍。
     *
     * 应用在前台时系统会把实时活动收起来，回到桌面后不会自己恢复 —— 要有一次新的发布才重新展示。
     * 所以退到后台时补这一帧，不然只能等下一次内容变化或 4 分钟续期才看得到。
     */
    fun repost(context: Context, id: Int) {
        val ctx = context.applicationContext
        // 等半拍：onStop 那一刻窗口还没完全退回后台，立刻发仍可能被当作前台而收起来
        handler.postDelayed({
            val slot = slots[id] ?: return@postDelayed
            val req = slot.lastReq ?: return@postDelayed
            slot.shown = null
            apply(ctx, req)
        }, 400L)
    }

    /** 退到后台时补帧；只有标准通道需要（另外两条通道系统不抑制） */
    fun repostAllIfStd(context: Context) {
        if (IslandSettings.mode != "std") return
        repost(context, ID_HEART)
        repost(context, ID_TEMP)
    }

    private fun apply(ctx: Context, req: Req) {
        val slot = slots.getOrPut(req.id) { Slot() }
        val promote = probe(ctx)
        val xiaomi = XiaomiFocusGate.allowed(ctx)
        if (!promote && !xiaomi) {
            // 这条路在本机不通：撤掉，别留一条系统永远不会提升的常驻通知
            slot.refresher?.let { handler.removeCallbacks(it) }
            slot.refresher = null
            if (slot.posted) runCatching { nm(ctx).cancel(req.id) }
            slot.posted = false
            slot.shown = null
            return
        }
        if (slot.posted && slot.shown == req.fingerprint) return

        // 重发频率跟随设置里的「更新节流」：每重发一次，系统都要重新走一遍提升，
        // 频率高了在锁屏上就是一闪一闪的
        val gap = IslandSettings.updateIntervalMs.coerceIn(MIN_GAP_MS, 30_000L)
        val wait = (slot.at + gap - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
        if (wait > 0L) {
            handler.postDelayed({ apply(ctx, req) }, wait)
            return
        }

        val ok = runCatching { postNow(ctx, req, xiaomi) }
            .onFailure { Log.w(TAG, "notify failed id=" + req.id + " " + it.message) }
            .isSuccess
        if (!ok) return

        slot.posted = true
        slot.shown = req.fingerprint
        slot.at = SystemClock.elapsedRealtime()
        slot.lastReq = req

        slot.refresher?.let { handler.removeCallbacks(it) }
        val refresher = Runnable {
            // 续一次：内容没变也重发，把 setTimeoutAfter 往后推，活着的岛不会到点消失
            slots[req.id]?.shown = null
            apply(ctx, req)
        }
        slot.refresher = refresher
        handler.postDelayed(refresher, REFRESH_MS)
    }

    /**
     * 真正发出去的那一条。
     *
     * 用平台 Builder 而不是 NotificationCompat：`ProgressStyle` 和 `setShortCriticalText`
     * 只有平台 API 有，而 Compat 的 `setStyle` 收的是 `NotificationCompat.Style`，塞不进去。
     *
     * 外观三件事都走标准 API：`setColor` 给图标和进度条着色（跟随主题强调色）、
     * `setLargeIcon` 放右侧像素图案、`ProgressStyle` 给三段滑动进度条。
     * 小米私有字段仍然只在 [XiaomiFocusGate] 放行时附加。
     */
    @Suppress("NewApi")
    private fun postNow(ctx: Context, req: Req, xiaomi: Boolean) {
        ensureChannel(ctx)
        val iconRes = if (req.heart) R.drawable.ic_heart else R.drawable.ic_thermo
        val picKey = if (req.heart) IslandPublish.PIC_HEART else IslandPublish.PIC_TEMP
        val accent = IslandArt.tintColor(ctx, req.tintId)
        val art = IslandArt.bitmap(ctx, req.artId, artPx(ctx), req.tintId)

        val builder = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(iconRes)
            .setColor(accent)
            .setLargeIcon(art)
            .setContentTitle(req.title)
            .setContentText(req.content)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setTimeoutAfter(TIMEOUT_MS)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion(ctx, req, iconRes, accent))
        req.contentIntent?.let(builder::setContentIntent)
        req.actions.forEach { (label, pi) ->
            builder.addAction(Notification.Action.Builder(null, label, pi).build())
        }
        if (req.sinceMs != null) {
            builder.setWhen(req.sinceMs).setShowWhen(true).setUsesChronometer(true)
        }

        val segments = listOf(Notification.ProgressStyle.Segment(1).setColor(accent))
        if (Build.VERSION.SDK_INT >= SDK_PROMOTED) {
            // 关掉进度条时干脆不设 ProgressStyle：岛上只留图标、文字和计时
            if (req.showProgress) {
                // 岛上用一段来回滑的动画（不确定进度），心率温度都一样。
                // 按百分比填充是通知栏那种静态样式，别混到岛上来。
                builder.setStyle(
                    Notification.ProgressStyle()
                        .setProgressSegments(segments)
                        .setProgressIndeterminate(true),
                )
            }
            builder.setShortCriticalText(req.chip)
        } else if (req.showProgress && req.progressPct >= 0) {
            builder.setProgress(100, req.progressPct.coerceIn(0, 100), false)
        }

        val n = builder.build()
        n.extras.putBoolean(EXTRA_REQUEST_PROMOTED, true)
        n.extras.putCharSequence(EXTRA_SHORT_CRITICAL, req.chip)
        if (xiaomi) {
            n.extras.putString(
                "miui.focus.param",
                IslandPublish.focusJson(
                    title = req.title,
                    content = req.content,
                    left = req.leftText,
                    right = req.rightText,
                    pct = req.progressPct.coerceIn(0, 100),
                    hot = req.overThreshold,
                    picKey = picKey,
                    order = req.order,
                    priority = req.priority,
                ),
            )
            // 岛上的图用同一张像素图案，跟标准通道看起来一致
            n.extras.putBundle(
                "miui.focus.pics",
                Bundle().apply { runCatching { putParcelable(picKey, Icon.createWithBitmap(art)) } },
            )
        }
        nm(ctx).notify(req.id, n)
    }

    private fun artPx(ctx: Context): Int =
        (ART_DP * ctx.resources.displayMetrics.density).toInt().coerceIn(96, 256)

    /** 锁屏隐藏敏感内容时的版本：只留应用名和一句泛化说明 */
    private fun publicVersion(ctx: Context, req: Req, iconRes: Int, accent: Int): Notification =
        Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(iconRes)
            .setColor(accent)
            .setContentTitle(ctx.getString(R.string.app_name))
            .setContentText(if (req.heart) "心率监控中" else "温度监控中")
            .setOngoing(true)
            .setShowWhen(false)
            .build()

    private fun ensureChannel(ctx: Context) {
        val mgr = nm(ctx)
        if (mgr.getNotificationChannel(CHANNEL) != null) return
        mgr.createNotificationChannel(
            NotificationChannel(CHANNEL, "标准实时活动", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "由系统提升为状态栏胶囊或灵动岛"
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            },
        )
    }

    private fun nm(ctx: Context) =
        ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
}