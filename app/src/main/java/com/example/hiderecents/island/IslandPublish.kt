package com.example.hiderecents.island

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.example.hiderecents.R
import java.util.Locale

/**
 * 把内容贴到澎湃原生岛。
 *
 * 走的是系统认可的焦点通知：常驻 + 进度 + 静音，参数放在 `miui.focus.param`
 * （template_v2 + param_island）。胶囊里的左右两段分别由
 * bigIslandArea.imageTextInfoLeft / imageTextInfoRight 决定，
 * 所以录制时可以「左边时长、右边数值」。
 */
object IslandPublish {

    /**
     * 岛位靠通知 id 决定：系统把 id 小的那条摆在左边主位（完整胶囊），
     * 另一条被压成纯图标。template_v2 里的 islandOrder / islandPriority
     * 不会被这套通道采纳，所以「心率优先 / 温度优先」只能靠 id 实现。
     *
     * 但已上岛的节点只会「换内容」不会「换位置」——把两条 id 对调，
     * 系统仍把原来的节点留在原位。所以切换优先级时换一整代新 id（见 [nextSlots]），
     * 让两个岛都是全新节点，系统才会重新按 id 排位。
     */
    private var slotBase = (System.currentTimeMillis() / 1000L % 50_000L).toInt() * 4

    /** 正在使用的 id，撤岛时连上一代一起清，避免孤儿岛占位 */
    private val usedSlots = HashSet<Int>()

    /** 心率岛该用哪个槽位：优先的那个用较小的 id */
    fun heartId(): Int = if (IslandSettings.tempPriority) slotBase + 1 else slotBase

    /** 温度岛该用哪个槽位：和心率岛互补 */
    fun tempId(): Int = if (IslandSettings.tempPriority) slotBase else slotBase + 1

    private fun remember(id: Int) {
        usedSlots += id
    }

    /** 切换优先级前调用：换一代新 id，两条岛才都会被系统当新岛重排 */
    fun nextSlots() {
        slotBase += 2
    }

    /** 把用过的槽位全撤掉：优先级切换后旧槽里的岛会一直占着左边主位 */
    fun cancelAll(context: Context) {
        usedSlots.forEach { cancel(context, it) }
        usedSlots.clear()
        cancel(context, slotBase)
        cancel(context, slotBase + 1)
    }

    /**
     * 胶囊图标在 `miui.focus.pics` 里的键，必须与 param 里 picInfo.pic 完全一致。
     *
     * 不能沿用系统内置名 `miui.focus.pic_liveupdate`：SystemUI 认这个名字，会直接拿自己的
     * 资源去解析，我们塞进 Bundle 的自绘图标被忽略；而它内置的那张又画不出来，于是左段
     * （图标 + 文案）整段消失。换成系统解析不到的自定义名，系统才会回落到 Bundle 里的 Icon。
     * 温度岛一直用 pic_thermo，所以它的图标能正常显示。
     */
    const val PIC_HEART = "miui.focus.pic_heart"
    const val PIC_TEMP = "miui.focus.pic_thermo"

    private const val CHANNEL = "island_ops"

    /**
     * 本机是否能走「系统实时更新」上岛。
     *
     * Android 16（SDK 36）起才有 POST_PROMOTED_NOTIFICATIONS / 推广进行中通知；
     * 澎湃 3（Android 15 底）没有这个机制，普通应用发不出岛，只能落在通知栏。
     */
    fun liveUpdatesSupported(): Boolean = android.os.Build.VERSION.SDK_INT >= 36

    fun ensureChannel(context: Context) {
        val mgr = context.getSystemService(NotificationManager::class.java) ?: return
        if (mgr.getNotificationChannel(CHANNEL) != null) return
        mgr.createNotificationChannel(
            NotificationChannel(CHANNEL, "原生岛", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "在澎湃原生岛上显示心率与温度"
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
            },
        )
    }

    /**
     * 发布/更新一条岛通知。
     *
     * @param leftText 胶囊左侧（录制中给时长，否则给图标侧文案）
     * @param rightText 胶囊右侧（心率 bpm / 温度值）
     * @param overThreshold 超阈值时用强调色
     */
    fun post(
        context: Context,
        id: Int,
        title: String,
        content: String,
        leftText: String,
        rightText: String,
        progressPct: Int,
        heart: Boolean,
        overThreshold: Boolean = false,
        contentIntent: PendingIntent? = null,
        actionLabel: String? = null,
        actionIntent: PendingIntent? = null,
        actions: List<Pair<String, PendingIntent>> = emptyList(),
        order: Int = 0,
        priority: Int = 1,
    ) {
        ensureChannel(context)
        remember(id)
        val builder = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(if (heart) R.drawable.ic_heart else R.drawable.ic_thermo)
            .setContentTitle(title)
            .setContentText(content)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setProgress(100, progressPct.coerceIn(0, 100), false)
        contentIntent?.let { builder.setContentIntent(it) }
        if (actionLabel != null && actionIntent != null) builder.addAction(0, actionLabel, actionIntent)
        actions.forEach { (label, pi) -> builder.addAction(0, label, pi) }

        val picKey = if (heart) PIC_HEART else PIC_TEMP
        val n = builder.build()
        n.extras.putBoolean("android.requestPromotedOngoing", true)
        n.extras.putCharSequence("android.shortCriticalText", rightText)
        n.extras.putString(
            "miui.focus.param",
            focusJson(title, content, leftText, rightText, progressPct, overThreshold, picKey, order, priority),
        )
        n.extras.putBundle(
            "miui.focus.pics",
            android.os.Bundle().apply {
                runCatching {
                    putParcelable(
                        picKey,
                        android.graphics.drawable.Icon.createWithResource(
                            context,
                            if (heart) R.drawable.ic_heart else R.drawable.ic_thermo,
                        ),
                    )
                }
            },
        )
        runCatching { NotificationManagerCompat.from(context).notify(id, n) }
    }

    fun cancel(context: Context, id: Int) {
        runCatching { NotificationManagerCompat.from(context).cancel(id) }
    }

    /**
     * template_v2 参数。
     *
     * param_island 决定胶囊形态：
     *  · imageTextInfoLeft.type = 1（带图标的文字段）→ 左侧时长
     *  · imageTextInfoRight.type = 2（纯文字段）→ 右侧数值
     *  · smallIslandArea 用系统自带的 liveupdate 图标，保证小胶囊有内容
     *
     * 标准通道（[IslandStandard]）在 [XiaomiFocusGate] 放行时也复用这份生成器，
     * 所以不再是 private；通过 live 通道发布的那条路径一行未动。
     */
    internal fun focusJson(
        title: String,
        content: String,
        left: String,
        right: String,
        pct: Int,
        hot: Boolean,
        picKey: String,
        order: Int,
        priority: Int,
    ): String {
        val accent = if (hot) "#E5482F" else "#FF6A3D"
        val escTitle = title.replace("\"", "\\\"")
        val escContent = content.replace("\"", "\\\"")
        val escLeft = left.replace("\"", "\\\"")
        val escRight = right.replace("\"", "\\\"")
        return buildString {
            append("{")
            append("\"scene\":\"template_v2\",")
            append("\"baseInfo\":{\"title\":\"").append(escTitle)
            append("\",\"content\":\"").append(escContent)
            append("\",\"colorTitle\":\"").append(accent).append("\"}")
            append(",\"progressInfo\":{\"progress\":").append(pct.coerceIn(0, 100))
            append(",\"colorProgress\":\"").append(accent)
            append("\",\"colorProgressEnd\":\"").append(accent).append("\"}")
            append(",\"param_island\":{")
            append("\"islandProperty\":2,")
            // 岛位顺序：order 小的排前面，priority 大的优先占中间显眼位。
            // V3 通道靠这两个字段分主次，template_v2 不带的话系统只按默认摆，
            // 设置里的「心率优先 / 温度优先」就成了空话。
            append("\"islandOrder\":").append(order).append(',')
            append("\"islandPriority\":").append(priority).append(',')
        append("\"bigIslandArea\":{")
        append("\"textInfo\":{\"title\":\"").append(escRight).append("\"},")
        append("\"imageTextInfoLeft\":{\"type\":1,")
        append("\"textInfo\":{\"title\":\"").append(escLeft).append("\"},")
        append("\"picInfo\":{\"type\":3,\"pic\":\"").append(picKey).append("\"}}")
            append("},")
            append("\"smallIslandArea\":{\"picInfo\":{\"type\":3,\"pic\":\"").append(picKey).append("\"}}")
            append("}}")
        }
    }

    /** 录制时长 mm:ss */
    fun clock(sec: Int): String {
        val m = sec / 60
        val s = sec % 60
        return String.format(Locale.US, "%02d:%02d", m, s)
    }

    fun intentFor(context: Context, cls: Class<*>, action: String? = null, requestCode: Int = 0): PendingIntent {
        val i = Intent(context, cls)
        if (action != null) {
            i.action = action
            i.putExtra("from_island", true)
        }
        // 只有 NEW_TASK 的话，系统会把已有任务整个提到前台、停在上次那一页；
        // 加 CLEAR_TOP 才会真的把目标页带到前台
        i.addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                Intent.FLAG_ACTIVITY_CLEAR_TOP,
        )
        return PendingIntent.getActivity(
            context,
            requestCode,
            i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    fun broadcastIntent(context: Context, action: String, requestCode: Int): PendingIntent {
        val i = Intent(context, IslandReceiver::class.java).setAction(action)
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}