package com.example.hiderecents

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
        private const val BOOT_CHECK_ACTION = "com.example.hiderecents.BOOT_A11Y_CHECK"
        private const val BOOT_DELAY = 30_000L // 30 seconds after boot
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == "android.intent.action.QUICKBOOT_POWERON" ||
            intent.action == "com.htc.intent.action.QUICKBOOT_POWERON") {

            Log.d(TAG, "Boot completed, scheduling accessibility restore")

            val prefs = context.getSharedPreferences("hide_recents_prefs", Context.MODE_PRIVATE)
            val autoProtect = prefs.getBoolean("a11y_auto_protect", false)
            if (!autoProtect) {
                Log.d(TAG, "Auto-protect disabled, skipping")
                return
            }

            val protectedServices = prefs.getStringSet("protected_a11y_services", emptySet()) ?: emptySet()
            if (protectedServices.isEmpty()) {
                Log.d(TAG, "No protected services, skipping")
                return
            }

            // Use AlarmManager to schedule the check — much more reliable than Thread.sleep
            // AlarmManager alarms fire even if the app process isn't running yet
            scheduleBootCheck(context)
        }

        // Handle our own boot check alarm
        if (intent.action == BOOT_CHECK_ACTION) {
            Log.d(TAG, "Boot check alarm fired")
            // The AccessibilityKeepAliveService's alarm-based check will handle the actual restoration.
            // Here we just make sure the service gets re-enabled so it can start its own alarm cycle.
            ensureKeepAliveEnabled(context)
        }
    }

    private fun scheduleBootCheck(context: Context) {
        try {
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val checkIntent = Intent(context, BootReceiver::class.java).apply {
                action = BOOT_CHECK_ACTION
            }
            val pending = PendingIntent.getBroadcast(
                context, 1, checkIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val triggerAt = System.currentTimeMillis() + BOOT_DELAY

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val showIntent = PendingIntent.getActivity(
                    context, 2,
                    Intent(context, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE
                )
                am.setAlarmClock(AlarmManager.AlarmClockInfo(triggerAt, showIntent), pending)
            } else {
                am.set(AlarmManager.RTC_WAKEUP, triggerAt, pending)
            }
            Log.d(TAG, "Boot check alarm scheduled for ${BOOT_DELAY / 1000}s later")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to schedule boot check alarm", e)
        }
    }

    private fun ensureKeepAliveEnabled(context: Context) {
        val prefs = context.getSharedPreferences("hide_recents_prefs", Context.MODE_PRIVATE)
        val autoProtect = prefs.getBoolean("a11y_auto_protect", false)
        if (!autoProtect) return

        val protectedServices = prefs.getStringSet("protected_a11y_services", emptySet()) ?: emptySet()
        val userDisabled = prefs.getStringSet("user_disabled_a11y", emptySet()) ?: emptySet()
        if (protectedServices.isEmpty()) return

        try {
            val current = android.provider.Settings.Secure.getString(
                context.contentResolver,
                android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: ""

            val currentSet = current.split(':').filter { it.isNotBlank() }.toSet()
            val needRestore = protectedServices.filter { it !in currentSet && it !in userDisabled }

            val myCn = "${context.packageName}/${AccessibilityKeepAliveService::class.java.name}"
            val needKeepAlive = !current.contains(myCn)

            if (needRestore.isNotEmpty() || needKeepAlive) {
                val services = current.split(':').filter { it.isNotBlank() }.toMutableList()
                if (needKeepAlive) services.add(myCn)
                services.addAll(needRestore)
                val newValue = services.joinToString(":")

                try {
                    android.provider.Settings.Secure.putString(
                        context.contentResolver,
                        android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                        newValue
                    )
                    android.provider.Settings.Secure.putString(
                        context.contentResolver,
                        "accessibility_enabled",
                        "1"
                    )
                    Log.d(TAG, "Restored ${needRestore.size} services + keepalive via direct write")
                } catch (e: SecurityException) {
                    Log.e(TAG, "Cannot write settings directly (no WRITE_SECURE_SETTINGS). " +
                            "AccessibilityKeepAliveService will handle restoration via Shizuku once it starts.", e)
                }
            } else {
                Log.d(TAG, "All services already enabled, no restore needed")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Boot restore check failed", e)
        }
    }
}
