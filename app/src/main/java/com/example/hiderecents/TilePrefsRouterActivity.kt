package com.example.hiderecents

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log

/**
 * 快捷设置磁贴「长按」的落点。
 *
 * 关键点：系统发来的 QS_TILE_PREFERENCES 里，被长按的磁贴组件放在
 * android.intent.extra.COMPONENT_NAME 中，而且是一个 ComponentName 对象，
 * 不是字符串 —— 旧实现用 getStringExtra 取，永远拿到 null，
 * 于是所有长按都落到应用管理页（无障碍磁贴也跳错）。这里改成按 Parcelable 读。
 */
class TilePrefsRouterActivity : Activity() {

    companion object {
        private const val TAG = "TilePrefsRouter"
        private const val KEY_COMPONENT = "android.intent.extra.COMPONENT_NAME"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val i = intent
        var cn: ComponentName? = null
        if (i != null) {
            cn = if (Build.VERSION.SDK_INT >= 33) {
                i.getParcelableExtra(KEY_COMPONENT, ComponentName::class.java)
            } else {
                @Suppress("DEPRECATION")
                val old = i.getParcelableExtra(KEY_COMPONENT) as? ComponentName
                old
            }
        }
        val extra = cn?.flattenToString()
            ?: i?.getStringExtra(KEY_COMPONENT)
            ?: i?.getStringExtra("android.intent.extra.COMPONENT")
            ?: ""
        Log.d(TAG, "long-press tile=" + extra)

        val target = if (extra.contains("A11yTile", true)) {
            AccessibilityManageActivity::class.java
        } else {
            AppManageActivity::class.java
        }
        try {
            startActivity(Intent(this, target))
        } catch (_: Exception) {
        }
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }
}
