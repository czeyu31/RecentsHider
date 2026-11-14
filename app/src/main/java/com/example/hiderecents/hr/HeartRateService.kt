package com.example.hiderecents.hr

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.hiderecents.HeartRateActivity
import com.example.hiderecents.R

/** 服务对外暴露的一帧状态，界面照着它画就行。 */
data class HrLiveState(
    val link: HrLink = HrLink.IDLE,
    val linkText: String = "",
    val bpm: Int = 0,
    val rssi: Int = 0,
    val packets: Int = 0,
    val gaps: Int = 0,
    val lastBeatAt: Long = 0L,
    val deviceName: String = "",
    val deviceMac: String = "",
    /** 这次连接建立的时间（毫秒）。放在服务里，进出页面时「会话时长」才能接着走。 */
    val connectedAt: Long = 0L,
    /** 最近 60 个心率样本。放服务里，页面退出去再进来曲线才不会空掉。 */
    val recent: List<Int> = emptyList(),
)

/**
 * 心率连接的前台服务。
 *
 * 为什么要有它：连接要能活得比页面久（用户设置的「退出页面后保持连接」），
 * 同时系统通知栏要能实时显示心率 —— 这两件事都要求 BLE 不在 Activity 里。
 *
 * 通知常驻、静音、只有一条：标题就是当前心率，副标题是设备名。
 */
class HeartRateService : Service() {

    companion object {
        const val ACTION_CONNECT = "com.example.hiderecents.hr.CONNECT"
        const val ACTION_DISCONNECT = "com.example.hiderecents.hr.DISCONNECT"
        const val EXTRA_MAC = "mac"
        const val EXTRA_NAME = "name"

        const val CHANNEL_ID = "hr_live"
        const val NOTIF_ID = 4210
        private const val MAX_RECONNECT = 5

        const val PREF = "hide_recents_prefs"
        const val KEY_MAC = "hr_last_mac"
        const val KEY_NAME = "hr_last_name"
        const val KEY_KEEP_LINK = "hr_keep_link"
        const val KEY_AUTO_CONNECT = "hr_auto_connect"

        /** 开始连接：会拉起前台服务，并在通知栏挂上实时心率。 */
        fun connect(context: Context, mac: String, name: String) {
            val intent = Intent(context, HeartRateService::class.java)
                .setAction(ACTION_CONNECT)
                .putExtra(EXTRA_MAC, mac)
                .putExtra(EXTRA_NAME, name)
            ContextCompat.startForegroundService(context, intent)
        }

        fun lastMac(context: Context): String =
            context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY_MAC, "").orEmpty()

        fun lastName(context: Context): String =
            context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY_NAME, "").orEmpty()

        fun keepLink(context: Context): Boolean =
            context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getBoolean(KEY_KEEP_LINK, false)

        fun autoConnect(context: Context): Boolean =
            context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getBoolean(KEY_AUTO_CONNECT, true)
    }

    inner class LocalBinder : Binder() {
        fun service(): HeartRateService = this@HeartRateService
    }

    /** 界面绑上来之后听这两件事：整帧状态、单次心跳。 */
    interface Listener {
        fun onState(state: HrLiveState)
        fun onBeat(bpm: Int, at: Long)
    }

    private val binder = LocalBinder()
    private val ble by lazy { HeartRateBle(this) }

    var listener: Listener? = null
        set(value) {
            field = value
            value?.onState(state)
        }

    private var state = HrLiveState()
    private var stopWanted = false
    private var wantLink = false
    private var retryCount = 0
    private val retryHandler = Handler(Looper.getMainLooper())
    private val retryTick = Runnable {
        val prefs = getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val mac = prefs.getString(KEY_MAC, "").orEmpty()
        val name = prefs.getString(KEY_NAME, "").orEmpty()
        if (mac.isNotEmpty() && wantLink) {
            update(state.copy(link = HrLink.CONNECTING, linkText = "重新连接中（第 $retryCount 次）"))
            ble.connect(HrDevice(mac, name, 0))
        }
    }
    private var notificationShown = false
    private var lastNotifiedAt = 0L
    private var lastNotifiedBpm = -1

    override fun onBind(intent: Intent?): IBinder = binder

    fun currentState(): HrLiveState = state

    // ------------------------------------------------------------------ 录制
    // 录制归服务管：界面退出（例如返回工具箱）后采样继续，回来还能看到 REC 在走。

    private val recValues = ArrayList<Int>()
    private var recStartAt = 0L
    private var recGapIndex = -1
    private var recActive = false

    fun isRecording(): Boolean = recActive

    fun recElapsedSec(): Int =
        if (!recActive) 0 else ((System.currentTimeMillis() - recStartAt) / 1000L).toInt()

    fun startRecording(): Boolean {
        if (state.link != HrLink.READY) return false
        recValues.clear()
        recGapIndex = -1
        recStartAt = System.currentTimeMillis()
        recActive = true
        return true
    }

    /** 停止并返回这次录制；样本不足 5 个返回 null（内部已清空）。 */
    fun stopRecording(): HrSession? {
        if (!recActive) return null
        recActive = false
        val elapsed = ((System.currentTimeMillis() - recStartAt) / 1000L).toInt()
        if (recValues.size < 5) {
            recValues.clear()
            return null
        }
        val values = downsample(recValues.toList(), 600)
        val gap = if (recGapIndex < 0) -1 else
            (recGapIndex.toDouble() * values.size / recValues.size).toInt().coerceIn(0, values.lastIndex)
        val session = HrSession(
            id = System.currentTimeMillis(),
            startedAt = recStartAt,
            durationSec = elapsed.coerceAtLeast(values.size),
            values = values,
            gapIndex = gap,
            deviceName = state.deviceName,
            deviceMac = state.deviceMac,
        )
        recValues.clear()
        recStartAt = 0L
        return session
    }

    /** 界面主动断开：断开连接、收掉通知，服务自己收工。 */
    fun disconnectLink() {
        stopWanted = true
        wantLink = false
        retryHandler.removeCallbacks(retryTick)
        ble.disconnect()
        stopForegroundCompat()
        stopSelf()
    }

    /** 重连退避：2s、4s、8s… 最多几次，成功一次就清零。 */
    private fun scheduleReconnect() {
        retryHandler.removeCallbacks(retryTick)
        val delayMs = (1200L shl (retryCount - 1).coerceIn(0, 4))
        retryHandler.postDelayed(retryTick, delayMs)
    }

    /**
     * 还有界面绑着时 stopSelf 不会真的销毁服务，要等解绑。
     * 这里补一刀：解绑发生时如果已经要求停止，就再 stopSelf 一次。
     */
    override fun onUnbind(intent: Intent?): Boolean {
        if (stopWanted) stopSelf()
        return super.onUnbind(intent)
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        ble.onLink = { link, text ->
            // 会话起点记在服务里：页面退出再进来，时长接着走而不是从 0 开始
            val stamp = when (link) {
                HrLink.READY -> if (state.connectedAt > 0L) state.connectedAt else System.currentTimeMillis()
                else -> if (link == HrLink.CONNECTING) state.connectedAt else 0L
            }
            update(state.copy(link = link, linkText = text, connectedAt = stamp))
            when (link) {
                HrLink.READY -> retryCount = 0
                HrLink.LOST, HrLink.FAILED -> {
                    // 手环走远、息屏省电都可能把连接丢掉，只要用户还要着就自己重连几次
                    if (wantLink && retryCount < MAX_RECONNECT) {
                        retryCount += 1
                        scheduleReconnect()
                    } else {
                        stopForegroundCompat()
                        stopSelf()
                    }
                }
                else -> Unit
            }
        }
        ble.onBpm = { value ->
            val now = System.currentTimeMillis()
            val gap = if (state.lastBeatAt > 0 && now - state.lastBeatAt > 10_000L) 1 else 0
            update(
                state.copy(
                    bpm = value,
                    packets = state.packets + 1,
                    gaps = state.gaps + gap,
                    lastBeatAt = now,
                    // 最近 60 秒曲线跟着服务走，页面退出再进来直接复用
                    recent = (state.recent + value).takeLast(60),
                ),
            )
            // 录制也归服务：页面退出了照样在攒样本
            if (recActive) {
                if (gap == 1 && recGapIndex < 0) recGapIndex = recValues.size
                recValues.add(value)
            }
            listener?.onBeat(value, now)
        }
        ble.onRssi = { value -> update(state.copy(rssi = value)) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT -> {
                val mac = intent.getStringExtra(EXTRA_MAC).orEmpty()
                val name = intent.getStringExtra(EXTRA_NAME).orEmpty()
                startForegroundCompat()
                if (mac.isEmpty()) {
                    stopForegroundCompat()
                    stopSelf()
                } else {
                    val prefs = getSharedPreferences(PREF, Context.MODE_PRIVATE)
                    prefs.edit().putString(KEY_MAC, mac).putString(KEY_NAME, name).apply()
                    wantLink = true
                    retryCount = 0
                    state = state.copy(
                        deviceMac = mac,
                        deviceName = name,
                        packets = 0,
                        gaps = 0,
                        bpm = 0,
                        lastBeatAt = 0L,
                        connectedAt = 0L,
                        recent = emptyList(),
                    )
                    listener?.onState(state)
                    ble.connect(HrDevice(mac, name, 0))
                }
            }
            ACTION_DISCONNECT -> {
                stopWanted = true
                wantLink = false
                retryHandler.removeCallbacks(retryTick)
                ble.disconnect()
                stopForegroundCompat()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        wantLink = false
        retryHandler.removeCallbacks(retryTick)
        ble.disconnect()
        listener = null
        super.onDestroy()
    }

    // ------------------------------------------------------------------ 通知

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = getSystemService(NotificationManager::class.java) ?: return
        if (mgr.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(CHANNEL_ID, "实时心率", NotificationManager.IMPORTANCE_LOW)
        channel.description = "连接心率设备后在通知栏显示当前心率"
        channel.setShowBadge(false)
        channel.enableVibration(false)
        channel.setSound(null, null)
        mgr.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val title = when {
            state.link == HrLink.CONNECTING -> "正在连接…"
            state.bpm > 0 -> state.bpm.toString() + " bpm"
            state.link == HrLink.READY -> "等待心率数据"
            else -> "心率广播"
        }
        val text = when {
            state.link == HrLink.CONNECTING || state.link == HrLink.IDLE -> state.linkText.ifEmpty { "广播 0x180D" }
            state.linkText.isNotEmpty() && state.bpm == 0 -> state.linkText
            else -> state.deviceName.ifEmpty { "广播 0x180D" }
        }

        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, HeartRateActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, HeartRateService::class.java).setAction(ACTION_DISCONNECT),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_heart)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .addAction(0, "断开", stop)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun startForegroundCompat() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIF_ID, notification)
        }
        notificationShown = true
    }

    private fun stopForegroundCompat() {
        if (notificationShown) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
            notificationShown = false
        }
    }

    // ------------------------------------------------------------------ 状态

    private fun update(next: HrLiveState) {
        val changed = next != state
        state = next
        if (changed) listener?.onState(state)
        refreshNotification()
    }

    /** 通知不需要每一下心跳都刷，1 秒一次、并且数值变了才刷。 */
    private fun refreshNotification() {
        if (!notificationShown) return
        val now = System.currentTimeMillis()
        if (state.bpm == lastNotifiedBpm && now - lastNotifiedAt < 1000L) return
        lastNotifiedBpm = state.bpm
        lastNotifiedAt = now
        try {
            val mgr = getSystemService(NotificationManager::class.java) ?: return
            mgr.notify(NOTIF_ID, buildNotification())
        } catch (_: SecurityException) {
            // 没给通知权限时后台照样跑，只是看不到通知
        }
    }
}
