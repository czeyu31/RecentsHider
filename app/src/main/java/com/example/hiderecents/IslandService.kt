package com.example.hiderecents

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

class IslandService : Service() {

    companion object {
        const val CHANNEL_ID = "island_monitor"
        const val NOTIFICATION_ID = 2001
        var instance: IslandService? = null
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForeground(NOTIFICATION_ID, buildNotification("监控服务运行中"))
        }
        startMonitoring()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        instance = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "系统监控", NotificationManager.IMPORTANCE_LOW)
            channel.description = "实时显示电池、温度、心率信息"
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("System Tool 监控")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_settings)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun startMonitoring() {
        Thread {
            while (instance != null) {
                try {
                    val batteryInfo = getBatteryInfo()
                    val heartRate = getHeartRate()
                    updateNotification("$batteryInfo | 心率: ${heartRate}bpm")
                    Thread.sleep(1000)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }.start()
    }

    private fun getBatteryInfo(): String {
        try {
            val bm = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            val batteryIntent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val temp = batteryIntent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
            val voltage = batteryIntent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0
            val currentNow = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
            val currentMA = if (currentNow != Int.MIN_VALUE) currentNow / 1000 else 0
            val absCurrent = kotlin.math.abs(currentMA)
            val powerW = absCurrent * voltage / 1000000.0

            return "电量:$level% 温度:${temp / 10}°C 功率:${String.format("%.1f", powerW)}W"
        } catch (e: Exception) {
            return "电量:未知"
        }
    }

    private fun getHeartRate(): Int {
        // 从HeartRateActivity获取心率数据
        return HeartRateActivity.currentHeartRate
    }
}