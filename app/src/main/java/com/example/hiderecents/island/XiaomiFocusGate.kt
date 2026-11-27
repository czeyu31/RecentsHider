package com.example.hiderecents.island

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings

/**
 * 小米焦点通知的放行探测。
 *
 * HyperOS 的超级岛对第三方是白名单制：通知发得出去、内容也能被 SystemUI 解析
 * （日志里能看到 onInflateSuccess），但没有名单就没有 onAuthSuccess，岛永远不展示。
 * 唯一可靠的答复是 SystemUI 的 canShowFocus，猜版本号不行。
 *
 * 两个通道都要问它：V3 决定发出来的东西系统要不要，标准通道决定要不要顺带附小米模板。
 */
object XiaomiFocusGate {

    private const val PROTOCOL_SETTING = "notification_focus_protocol"
    private const val PROVIDER = "content://miui.statusbar.notification.public"

    /** 里面是 binder 调用，一分钟内不重复问 */
    private const val TTL_MS = 60_000L

    @Volatile private var checkedAt = 0L
    @Volatile private var allowed = false

    /** 这台机器会不会把本应用的焦点通知放进岛 */
    fun allowed(context: Context): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (checkedAt != 0L && now - checkedAt < TTL_MS) return allowed
        val ok = runCatching { query(context.applicationContext) }.getOrDefault(false)
        allowed = ok
        checkedAt = now
        return ok
    }

    /** 1 / 2 / 3 是 HyperOS 的焦点协议代次，0 表示不是小米或没有这个设置 */
    fun protocol(context: Context): Int {
        if (!isXiaomi()) return 0
        return runCatching {
            Settings.System.getInt(context.contentResolver, PROTOCOL_SETTING, 0)
        }.getOrDefault(0)
    }

    fun isXiaomi(): Boolean {
        val maker = (Build.MANUFACTURER ?: "").lowercase()
        val brand = (Build.BRAND ?: "").lowercase()
        return maker.contains("xiaomi") ||
            brand.contains("xiaomi") ||
            brand.contains("redmi") ||
            brand.contains("poco")
    }

    private fun query(ctx: Context): Boolean {
        if (protocol(ctx) < 1) return false
        val args = Bundle().apply { putString("package", ctx.packageName) }
        return ctx.contentResolver.call(Uri.parse(PROVIDER), "canShowFocus", null, args)
            ?.getBoolean("canShowFocus", false) == true
    }
}