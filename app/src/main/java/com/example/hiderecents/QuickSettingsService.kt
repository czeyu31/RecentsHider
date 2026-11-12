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
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
import rikka.shizuku.Shizuku

/**
 * 快捷设置磁贴：隐藏应用（全部开 / 全部关）。
 *
 * 语义：
 *  - 「全部开 / 全部关」作用在软件里已经选好的隐藏列表（hidden_apps）上，
 *    切换不会清空这个列表，只是整批启用 / 停用；
 *  - 状态存在 hide_all_enabled，HiddenGuardService 也读这个键，
 *    关掉之后不再周期清扫，避免「刚关掉又被自动隐藏」；
 *  - 长按交给 TilePrefsRouterActivity，跳到应用管理页。
 */
class QuickSettingsService : TileService() {

    companion object {
        private const val PREFS = "hide_recents_prefs"
        private const val KEY_ENABLED = "hide_all_enabled"
    }

    private var taskHideService: IBinder? = null
    private var serviceReady = false
    private var prefs: SharedPreferences? = null

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            taskHideService = service
            serviceReady = true
            TaskHideBridge.binder = service
        }

        override fun onServiceDisconnected(name: ComponentName) {
            taskHideService = null
            serviceReady = false
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
                serviceConnection,
                true,
            )
        } catch (_: Exception) {
        }
        taskHideService = null
        serviceReady = false
    }

    override fun onClick() {
        super.onClick()
        val p = prefsOrInit()
        val hidden = p.getStringSet("hidden_apps", emptySet()) ?: emptySet()
        if (hidden.isEmpty()) {
            toast("还没有选好的隐藏应用")
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
        if (!serviceReady) {
            val shared = TaskHideBridge.binder
            if (shared != null) {
                taskHideService = shared
                serviceReady = true
            }
        }
        if (!serviceReady) bindService()

        val turningOff = p.getBoolean(KEY_ENABLED, true)
        Thread {
            var waited = 0
            while (!serviceReady && waited < 6000) {
                Thread.sleep(100)
                waited += 100
            }
            if (!serviceReady) {
                bindService()
                var extra = 0
                while (!serviceReady && extra < 4000) {
                    Thread.sleep(100)
                    extra += 100
                }
            }
            val binder = taskHideService ?: TaskHideBridge.binder
            if (binder == null) {
                openAppManagePage()
                return@Thread
            }
            var ok = 0
            for (pkg in hidden) {
                if (callSetInvisible(binder, pkg, !turningOff)) ok++
            }
            p.edit().putBoolean(KEY_ENABLED, !turningOff).apply()
            // 关掉「隐藏」时把目标重新拉起一次：removeTask 已经销毁了它们的任务，
            // 不重新拉起的话它们在最近任务里永远不会回来（用户看到的就是「关掉了但后台没有」）。
            // 关闭隐藏时不再把应用拉回前台：一批应用同时被拉起会连续跳前台。
            // 取消隐藏只负责清标记，应用是否回前台由用户在软件内的「恢复时回到前台」决定。
            HiddenGuardService.sync(this)
            toastOnUi(
                if (turningOff) {
                    "已关闭隐藏 " + ok + "/" + hidden.size
                } else {
                    "已开启隐藏 " + ok + "/" + hidden.size
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
                serviceConnection,
            )
        } catch (_: Exception) {
        }
    }

    private fun callSetInvisible(binder: IBinder, packageName: String, invisible: Boolean): Boolean {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(ITaskHideService.DESCRIPTOR)
            data.writeString(packageName)
            data.writeByte(if (invisible) 1 else 0)
            binder.transact(ITaskHideService.TRANSACTION_setTaskInvisible, data, reply, 0)
            reply.readException()
            reply.readInt() != 0
        } catch (_: Exception) {
            false
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    private fun execCommand(binder: IBinder, command: String): Boolean {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(ITaskHideService.DESCRIPTOR)
            data.writeString(command)
            binder.transact(ITaskHideService.TRANSACTION_execCommand, data, reply, 0)
            reply.readException()
            reply.readInt() != 0
        } catch (_: Exception) {
            false
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    private fun updateTileState() {
        val tile = qsTile ?: return
        val p = prefs ?: return
        val hidden = p.getStringSet("hidden_apps", emptySet()) ?: emptySet()
        val enabled = p.getBoolean(KEY_ENABLED, true)
        if (hidden.isEmpty()) {
            tile.state = Tile.STATE_UNAVAILABLE
            tile.label = "隐藏应用"
            tile.icon = Icon.createWithResource(this, R.drawable.ic_qs_visibility_off)
        } else if (enabled) {
            tile.state = Tile.STATE_ACTIVE
            tile.label = "隐藏应用 · 开"
            tile.icon = Icon.createWithResource(this, R.drawable.ic_qs_visibility)
        } else {
            tile.state = Tile.STATE_INACTIVE
            tile.label = "隐藏应用 · 关"
            tile.icon = Icon.createWithResource(this, R.drawable.ic_qs_visibility_off)
        }
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
    private fun openAppManagePage() {
        try {
            val intent = Intent(this, AppManageActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivityAndCollapse(intent)
        } catch (_: Exception) {
            toastOnUi("Shizuku 服务未就绪，请打开软件后重试")
        }
    }
}
