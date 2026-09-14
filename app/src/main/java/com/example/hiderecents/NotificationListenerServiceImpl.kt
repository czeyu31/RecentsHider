package com.example.hiderecents

import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import org.json.JSONArray
import org.json.JSONObject

class NotificationListenerServiceImpl : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val notification = sbn.notification
        val extras = notification.extras
        val title = extras.getCharSequence("android.title")?.toString() ?: ""
        val text = extras.getCharSequence("android.text")?.toString() ?: ""
        val packageName = sbn.packageName

        if (title.isEmpty() && text.isEmpty()) return

        // 检查该应用是否被排除
        val prefs = getSharedPreferences("notification_history", Context.MODE_PRIVATE)
        val excludedApps = prefs.getStringSet("notif_excluded_apps", emptySet()) ?: emptySet()
        if (packageName in excludedApps) return

        saveNotification(packageName, title, text, sbn.postTime, prefs)
    }

    private fun saveNotification(packageName: String, title: String, text: String, time: Long, prefs: android.content.SharedPreferences) {
        val existing = prefs.getString("notifications", "[]") ?: "[]"
        val array = JSONArray(existing)

        val obj = JSONObject().apply {
            put("packageName", packageName)
            put("title", title)
            put("text", text)
            put("time", time)
        }

        array.put(obj)

        val trimmed = if (array.length() > 1000) {
            JSONArray().apply {
                for (i in array.length() - 1000 until array.length()) {
                    put(array[i])
                }
            }
        } else {
            array
        }

        prefs.edit().putString("notifications", trimmed.toString()).apply()
    }
}
