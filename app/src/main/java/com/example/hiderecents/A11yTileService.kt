package com.example.hiderecents

import android.content.ComponentName
import android.content.Intent
import android.content.Context
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
import rikka.shizuku.Shizuku

/**
 * 快捷设置磁贴：无障碍（全部开 / 全部关）。
 *
 * 作用集合是软件里已经选好的保护列表 protected_a11y_services：
 *  开 = 全部写进 enabled_accessibility_services；
 *  关 = 全部从 enabled_accessibility_services 移除。
 * 写入走 Shizuku 的 shell 通道（settings put secure），不需要额外权限。
 * 长按交给 TilePrefsRouterActivity，跳到无障碍管理页。
 */
class A11yTileService : TileService() {

    companion object {
        private const val PREFS = "hide_recents_prefs"
        private const val KEY_PROTECTED = "protected_a11y_services"
        private const val KEY_USER_DISABLED = "user_disabled_a11y"
    }

    private var binder: IBinder? = null
    private var ready = false
    private var prefs: SharedPreferences? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            binder = service
            ready = true
            TaskHideBridge.binder = service
        }

        override fun onServiceDisconnected(name: ComponentName) {
            binder = null
            ready = false
        }
    }

    private fun prefsOrInit(): SharedPreferences {
        val p = prefs ?: getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        return p
    }

    override fun onStartListening() {
        super.onStartListening()
        prefsOrInit()
        updateTileState()
    }

    override fun onStopListening() {
        super.onStopListening()
        try {
            Shizuku.unbindUserService(
                Shizuku.UserServiceArgs(ComponentName(this, TaskHideService::class.java)),
                connection,
                true,
            )
        } catch (_: Exception) {
        }
        binder = null
        ready = false
    }

    override fun onClick() {
        super.onClick()
        val p = prefsOrInit()
        val targets = p.getStringSet(KEY_PROTECTED, emptySet()) ?: emptySet()
        if (targets.isEmpty()) {
            toast("先在软件里选好要保护的无障碍服务")
            return
        }
        if (!Shizuku.pingBinder() || Shizuku.isPreV11()) {
            toast("Shizuku 未运行")
            return
        }
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            toast("请先授权 Shizuku")
            return
        }
        // 优先复用进程内已连好的 binder（应用本体 / 守护服务通常早就连上了），
        // 磁贴自己 bind 首次拉起用户服务进程常常来不及。
        if (!ready) {
            val shared = TaskHideBridge.binder
            if (shared != null) {
                binder = shared
                ready = true
            }
        }
        if (!ready) bindService()
        val turningOff = targets.all { enabledServices().contains(it) }
        Thread {
            var waited = 0
            while (!ready && waited < 6000) {
                Thread.sleep(100)
                waited += 100
            }
            if (!ready) {
                // 用户服务进程首次拉起可能超过 6 秒，再 bind 一次并多等 4 秒
                bindService()
                var extra = 0
                while (!ready && extra < 4000) {
                    Thread.sleep(100)
                    extra += 100
                }
            }
            val b = binder ?: TaskHideBridge.binder
            if (b == null) {
                openAccessibilityPage()
                return@Thread
            }
            val current = enabledServices().toMutableSet()
            val userDisabled = (p.getStringSet(KEY_USER_DISABLED, emptySet()) ?: emptySet()).toMutableSet()
            if (turningOff) {
                current.removeAll(targets)
                userDisabled.addAll(targets)
            } else {
                current.addAll(targets)
                userDisabled.removeAll(targets)
            }
            val value = current.joinToString(":")
            execCommand(b, "settings put secure enabled_accessibility_services " + value)
            execCommand(b, "settings put secure accessibility_enabled " + (if (current.isEmpty()) "0" else "1"))
            p.edit().putStringSet(KEY_USER_DISABLED, userDisabled).apply()
            toastOnUi(
                if (turningOff) {
                    "已关闭无障碍 " + targets.size + " 项"
                } else {
                    "已开启无障碍 " + targets.size + " 项"
                },
            )
            updateTileState()
        }.start()
    }

    private fun bindService() {
        try {
            Shizuku.bindUserService(
                Shizuku.UserServiceArgs(ComponentName(this, TaskHideService::class.java))
                    .daemon(false).processNameSuffix("taskhide").debuggable(true).version(2),
                connection,
            )
        } catch (_: Exception) {
        }
    }

    private fun execCommand(b: IBinder, command: String): Boolean {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(ITaskHideService.DESCRIPTOR)
            data.writeString(command)
            b.transact(ITaskHideService.TRANSACTION_execCommand, data, reply, 0)
            reply.readException()
            reply.readInt() != 0
        } catch (_: Exception) {
            false
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    private fun enabledServices(): Set<String> {
        val flat = try {
            Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        } catch (_: Exception) {
            null
        }
        val result = mutableSetOf<String>()
        (flat ?: "").split(":").forEach { if (it.isNotBlank()) result.add(it.trim()) }
        return result
    }

    private fun updateTileState() {
        val tile = qsTile ?: return
        val p = prefs ?: return
        val targets = p.getStringSet(KEY_PROTECTED, emptySet()) ?: emptySet()
        if (targets.isEmpty()) {
            tile.state = Tile.STATE_UNAVAILABLE
            tile.label = "无障碍"
            tile.icon = Icon.createWithResource(this, R.drawable.ic_qs_accessibility)
            tile.updateTile()
            return
        }
        val enabled = enabledServices()
        val allOn = targets.all { enabled.contains(it) }
        tile.state = if (allOn) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = if (allOn) "无障碍 · 开" else "无障碍 · 关"
        tile.icon = Icon.createWithResource(this, R.drawable.ic_qs_accessibility)
        tile.updateTile()
    }

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }

    private fun toastOnUi(text: String) {
        Handler(Looper.getMainLooper()).post { toast(text) }
    }

    /**
     * Shizuku 用户服务还没连上时的兜底：不弹「服务连接失败」，
     * 直接把目标页面打开，让用户在里面看到真实状态并操作；
     * 磁贴之后会随状态自动同步。
     */
    private fun openAccessibilityPage() {
        try {
            val intent = Intent(this, AccessibilityManageActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivityAndCollapse(intent)
        } catch (_: Exception) {
            toastOnUi("Shizuku 服务未就绪，请打开软件后重试")
        }
    }
}
