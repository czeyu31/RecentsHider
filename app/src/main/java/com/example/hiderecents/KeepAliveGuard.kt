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
    private const val KEY_MANUAL_POWER_POLICY = "keep_alive_power_policy_confirmed"

    /**
     * 是否是 MIUI / HyperOS / 澎湃这类有「应用级省电策略」的系统。
     *
     * 这一层是厂商私有的：应用管理 → 本应用 → 省电策略，默认「智能限制后台运行」，
     * 它的系统说明里写明了会限制「后台联网」。AOSP 没有任何接口能读或改它
     * （`dumpsys netpolicy`、`deviceidle whitelist`、`isIgnoringBatteryOptimizations` 都看不到），
     * 所以只能引导用户手点，再让用户自己确认。
     */
    fun hasVendorPowerPolicy(ctx: Context): Boolean {
        val m = runCatching { android.os.Build.MANUFACTURER.lowercase() }.getOrDefault("")
        if (m.contains("xiaomi") || m.contains("redmi") || m.contains("poco")) return true
        val miui = runCatching {
            val c = Class.forName("android.os.SystemProperties")
            val get = c.getMethod("get", String::class.java)
            (get.invoke(null, "ro.miui.ui.version.name") as? String).orEmpty()
        }.getOrNull().orEmpty()
        return miui.isNotEmpty()
    }

    fun manualPowerPolicy(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_MANUAL_POWER_POLICY, false)

    fun setManualPowerPolicy(ctx: Context, value: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_MANUAL_POWER_POLICY, value).apply()
    }

    /** 厂商省电策略：非 MIUI 系系统视为不需要；MIUI 系只能靠用户手动确认。 */
    fun powerPolicyOk(ctx: Context): Boolean =
        !hasVendorPowerPolicy(ctx) || manualPowerPolicy(ctx)

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

    fun allSatisfied(ctx: Context): Boolean =
        batteryOk(ctx) && autoStartOk(ctx) && powerPolicyOk(ctx)

    /**
     * 打开本应用的系统详情页（HyperOS/MIUI 上这里就有「省电策略」入口）。
     *
     * 没有公开的深链能直达那一页：`com.miui.powerkeeper` 的电池详情 Activity 各版本名字都不一样，
     * 乱猜会闪退，所以稳定跳到应用信息页，让用户点一下「省电策略」。
     */
    fun openPowerPolicySettings(ctx: Context) {
        val detail = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
        detail.data = Uri.parse("package:" + ctx.packageName)
        detail.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (runCatching { ctx.startActivity(detail) }.isSuccess) return
        val list = Intent(Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS)
        list.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { ctx.startActivity(list) }
            .onFailure { Log.w(TAG, "open power policy settings failed: " + it.message) }
    }

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
