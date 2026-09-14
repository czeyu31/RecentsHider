package com.example.hiderecents

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == "android.intent.action.QUICKBOOT_POWERON" ||
            intent.action == "com.htc.intent.action.QUICKBOOT_POWERON") {

            Log.d("BootReceiver", "Boot completed, restoring accessibility services")
            val prefs = context.getSharedPreferences("hide_recents_prefs", Context.MODE_PRIVATE)
            val autoProtect = prefs.getBoolean("a11y_auto_protect", false)
            if (!autoProtect) return

            val protectedServices = prefs.getStringSet("protected_a11y_services", emptySet()) ?: emptySet()
            if (protectedServices.isEmpty()) return

            // 开机后延迟恢复，等系统服务就绪
            Thread {
                try {
                    Thread.sleep(10_000)
                    val current = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
                    val userDisabled = prefs.getStringSet("user_disabled_a11y", emptySet()) ?: emptySet()
                    val currentSet = current.split(':').filter { it.isNotBlank() }.toSet()
                    val needRestore = protectedServices.filter { it !in currentSet && it !in userDisabled }

                    // 同时恢复守护服务
                    val myCn = "${context.packageName}/${AccessibilityKeepAliveService::class.java.name}"
                    val needKeepAlive = !current.contains(myCn)

                    if (needRestore.isNotEmpty() || needKeepAlive) {
                        val services = current.split(':').filter { it.isNotBlank() }.toMutableList()
                        if (needKeepAlive) services.add(myCn)
                        services.addAll(needRestore)
                        val newValue = services.joinToString(":")
                        // 通过 shizuku 设置需要已启动，这里只能尝试直接写入
                        try {
                            Settings.Secure.putString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, newValue)
                            Settings.Secure.putString(context.contentResolver, "accessibility_enabled", "1")
                        } catch (e: SecurityException) {
                            Log.e("BootReceiver", "Cannot write settings directly", e)
                        }
                    }
                } catch (e: Exception) {
                    Log.e("BootReceiver", "Boot restore failed", e)
                }
            }.start()
        }
    }
}
