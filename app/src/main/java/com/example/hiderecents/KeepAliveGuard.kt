package com.example.hiderecents

import android.app.AppOpsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.util.Log

/**
 * 后台保活的前置条件检测。
 *
 * 为什么需要：旧实现点一下「后台保活」就立刻写 keep_alive_enabled=true 并弹引导，
 * 而引导卡片里「电池优化 / 自启动 / 我知道了」三个按钮都会把卡片关掉 ——
 * 用户只点其中一个（甚至只点「我知道了」），开关就已经是开着的，
 * 但系统里两项设置都没做，保活其实不生效。
 * 现在改成：两项条件都满足才允许把开关打开。
 */
object KeepAliveGuard {

    private const val TAG = "KeepAliveGuard"
    private const val PREFS = "hide_recents_prefs"
    private const val KEY_MANUAL_AUTOSTART = "keep_alive_autostart_confirmed"

    fun batteryOk(ctx: Context): Boolean {
        return runCatching {
            val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
            pm.isIgnoringBatteryOptimizations(ctx.packageName)
        }.getOrDefault(false)
    }

    /** 自启动状态：读得到返回 true/false，读不到返回 null（各 ROM 的 op 名不一样）。 */
    private fun probeAutoStart(ctx: Context): Boolean? {
        val am = runCatching {
            ctx.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        }.getOrNull() ?: return null
        val uid = ctx.applicationInfo.uid
        val names = listOf("android:auto_start", "AUTO_START", "auto_start")
        var known = false
        for (n in names) {
            val mode = runCatching { am.checkOpNoThrow(n, uid, ctx.packageName) }.getOrNull() ?: continue
            known = true
            if (mode == AppOpsManager.MODE_ALLOWED) return true
            if (mode == AppOpsManager.MODE_IGNORED || mode == AppOpsManager.MODE_ERRORED) return false
        }
        return if (known) false else null
    }

    fun autoStartUnknown(ctx: Context): Boolean = probeAutoStart(ctx) == null

    fun manualAutoStart(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_MANUAL_AUTOSTART, false)

    fun setManualAutoStart(ctx: Context, value: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_MANUAL_AUTOSTART, value).apply()
    }

    fun autoStartOk(ctx: Context): Boolean = probeAutoStart(ctx) == true || manualAutoStart(ctx)

    fun allSatisfied(ctx: Context): Boolean = batteryOk(ctx) && autoStartOk(ctx)

    fun openBatterySettings(ctx: Context) {
        val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
        direct.data = Uri.parse("package:" + ctx.packageName)
        direct.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (runCatching { ctx.startActivity(direct) }.isSuccess) return
        val list = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        list.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { ctx.startActivity(list) }
            .onFailure { Log.w(TAG, "open battery settings failed: " + it.message) }
    }

    fun openAutoStartSettings(ctx: Context) {
        val candidates = listOf(
            ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
            ComponentName("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"),
            ComponentName("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"),
            ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
            ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
            ComponentName("com.hihonor.systemmanager", "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
        )
        for (cn in candidates) {
            val i = Intent()
            i.component = cn
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (runCatching { ctx.startActivity(i) }.isSuccess) return
        }
        val detail = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
        detail.data = Uri.parse("package:" + ctx.packageName)
        detail.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { ctx.startActivity(detail) }
            .onFailure { Log.w(TAG, "open autostart settings failed: " + it.message) }
    }
}
