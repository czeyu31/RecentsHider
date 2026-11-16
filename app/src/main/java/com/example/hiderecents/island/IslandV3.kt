package com.example.hiderecents.island

import android.content.Context
import android.graphics.drawable.Icon
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.example.hiderecents.R
import java.util.concurrent.atomic.AtomicInteger

/**
 * 澎湃 3（Android 15 底）的焦点通知 V3 通道。
 *
 * 与 [IslandPublish] 的 Live Updates 通道并存：那边靠系统「推广进行中通知」，
 * 这边直接给 SystemUI 的 FocusPlugin 喂小米私有协议（`miui.focus.param` 根键 param_v2
 * + `miui.focus.pics` 里的 Icon）。
 *
 * 几条硬约束（都是试错出来的，缺一条就只见日志不见岛）：
 *  · 每个「会话」必须用全新的通知 id，撤岛后永不复用；
 *  · 撤岛先发一帧 cancel:true 到同一 id，隔 80ms 再 cancel(id)；
 *  · 不能 setOnlyAlertOnce(true)，否则重投失效；
 *  · 系统只有一个岛位，孤儿岛会把位置占死 —— 所以退出前一定要走 cancel 帧；
 *  · 发岛应用自己在前台时系统不展示，要按 HOME 才看得到。
 */
object IslandV3 {

    private const val TAG = "IslandV3"
    private const val CHANNEL = "island_v3"
    /** 系统侧一定存在的图标资源名；我们的自绘图标也挂在它上面 */
    private const val PIC_KEY = "miui.focus.pic_liveupdate"

    /** 新会话 id：从当前时间粗粒度起步，保证进程重启后也不撞旧 id */
    private val idSeed = AtomicInteger((System.currentTimeMillis() / 1000L % 100000).toInt() * 10 + 1)
    private val main = Handler(Looper.getMainLooper())

    fun newSessionId(): Int = idSeed.incrementAndGet()

    private fun ensureChannel(context: Context) {
        val mgr = context.getSystemService(android.app.NotificationManager::class.java) ?: return
        if (mgr.getNotificationChannel(CHANNEL) != null) return
        mgr.createNotificationChannel(
            android.app.NotificationChannel(CHANNEL, "原生岛", android.app.NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "澎湃 3 焦点通知上岛"
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
            },
        )
    }

    /**
     * 发一帧岛。
     *
     * @param sessionId 本次会话固定的 id；更新同一会话时复用，换会话必须换新 id
     * @param picKey 胶囊左侧图标在 pics Bundle 里的键；null 表示只用文字段
     */
    fun publish(
        context: Context,
        sessionId: Int,
        title: String,
        content: String,
        leftText: String,
        rightText: String,
        heart: Boolean,
        picKey: String? = null,
        sequence: Int = 1,
        firstFrame: Boolean = false,
        order: Int = 0,
        priority: Int = 1,
        actions: List<Pair<String, android.app.PendingIntent>> = emptyList(),
        progress: Int = 50,
    ) {
        ensureChannel(context)
        val iconRes = if (heart) R.drawable.ic_heart else R.drawable.ic_thermo
        val builder = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(iconRes)
            .setContentTitle(title)
            .setContentText(content)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            // 注意：V3 通道不能 setOnlyAlertOnce，否则重投失效
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)

        val n = builder.build()
        n.extras.putString(
            "miui.focus.param",
            paramJson(sessionId, title, content, leftText, rightText, picKey, sequence, firstFrame, order, priority, actions, progress),
        )
        n.extras.putBundle("miui.focus.pics", picsBundle(context, iconRes, picKey))
        if (actions.isNotEmpty()) {
            val b = Bundle()
            actions.forEachIndexed { i, a -> b.putParcelable("miui.focus.action_" + i, a.second) }
            n.extras.putBundle("miui.focus.actions", b)
        }
        runCatching {
            NotificationManagerCompat.from(context).notify(sessionId, n)
            Log.d(TAG, "publish id=$sessionId left=$leftText right=$rightText")
        }
    }

    /** 撤岛：先 cancel 帧，再 80ms 后真正 cancel，位置才不会被孤儿岛占死 */
    fun dismiss(context: Context, sessionId: Int) {
        ensureChannel(context)
        val builder = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_thermo)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
        val n = builder.build()
        n.extras.putString("miui.focus.param", cancelJson(sessionId))
        runCatching { NotificationManagerCompat.from(context).notify(sessionId, n) }
        main.postDelayed({
            runCatching { NotificationManagerCompat.from(context).cancel(sessionId) }
            Log.d(TAG, "dismiss id=$sessionId")
        }, 80L)
    }

    private fun paramJson(
        sessionId: Int,
        title: String,
        content: String,
        left: String,
        right: String,
        picKey: String?,
        sequence: Int,
        firstFrame: Boolean,
        order: Int,
        priority: Int,
        actions: List<Pair<String, android.app.PendingIntent>>,
        progress: Int = 50,
    ): String = buildString {
        append("{\"param_v2\":{")
        append("\"protocol\":1,")
        append("\"business\":\"systemtool\",")
        append("\"updatable\":true,")
        // 首帧才允许浮出/展开：enableFloat=true 是「作为浮岛展示」的开关，
        // 置 false 整条岛都不出来；但每次更新都带 true 又会反复展开 —— 所以只给首帧。
        append("\"enableFloat\":").append(firstFrame).append(',')
        append("\"islandFirstFloat\":").append(firstFrame).append(',')
        append("\"reopen\":\"reopen\",")
        append("\"sequence\":").append(sequence).append(',')
        append("\"notifyId\":").append(sessionId).append(',')
        // 岛优先级：order 小的排前面，priority 大的优先占中间位
        append("\"islandOrder\":").append(order).append(',')
        append("\"islandPriority\":").append(priority).append(',')

        // 岛形态：左段图标+文字，右段数值
        append("\"param_island\":{")
        append("\"islandProperty\":2,")
        append("\"bigIslandArea\":{")
        // textInfo 是胶囊右段：只放数值，title+content 会被系统拼成一串
        append("\"textInfo\":{\"title\":\"").append(esc(right)).append("\"},")
        append("\"imageTextInfoLeft\":{\"type\":1,\"textInfo\":{\"title\":\"").append(esc(left)).append("\"}")
        if (picKey != null) {
            append(",\"picInfo\":{\"type\":3,\"pic\":\"").append(picKey).append("\"}")
        }
        append("}")
        append("},")
        append("\"smallIslandArea\":{")
        if (picKey != null) {
            append("\"picInfo\":{\"type\":3,\"pic\":\"").append(picKey).append("\"}")
        } else {
            append("\"picInfo\":{\"type\":3,\"pic\":\"miui.focus.pic_liveupdate\"}")
        }
        append("}")
        append("},")

        if (actions.isNotEmpty()) {
            append("\"actions\":[")
            actions.forEachIndexed { i, a ->
                if (i > 0) append(',')
                append("{\"actionTitle\":\"").append(esc(a.first)).append("\",")
                append("\"actionIntent\":\"miui.focus.action_").append(i).append("\",")
                append("\"actionIntentType\":2,\"type\":1}")
            }
            append("],")
        }

        append("\"progressInfo\":{\"progress\":").append(progress).append("},")
        append("\"baseInfo\":{")
        append("\"type\":1,")
        append("\"title\":\"").append(esc(title)).append("\",")
        append("\"content\":\"").append(esc(content)).append("\"")
        append("}")
        append("}}")
    }

    private fun cancelJson(sessionId: Int): String =
        "{\"param_v2\":{\"cancel\":true,\"notifyId\":" + sessionId + "}}"

    private fun picsBundle(context: Context, iconRes: Int, picKey: String?): Bundle = Bundle().apply {
        val key = picKey ?: PIC_KEY
        runCatching {
            putParcelable(key, Icon.createWithResource(context, iconRes))
        }
        // 同名再放一份到系统自带的可解析名上，避免「名字解析不到 → 整个左段被丢弃」
        runCatching {
            if (key != PIC_KEY) putParcelable(PIC_KEY, Icon.createWithResource(context, iconRes))
        }
    }

    private fun esc(s: String): String = s.replace("\\", "\\\\").replace("\"", "\\\"")
}