package com.example.hiderecents

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.Parcel
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
import rikka.shizuku.Shizuku

class QuickSettingsService : TileService() {

    private var taskHideService: IBinder? = null
    private var serviceReady = false
    private lateinit var prefs: SharedPreferences

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            taskHideService = service
            serviceReady = true
        }
        override fun onServiceDisconnected(name: ComponentName) {
            taskHideService = null
            serviceReady = false
        }
    }

    override fun onStartListening() {
        super.onStartListening()
        prefs = getSharedPreferences("hide_recents_prefs", Context.MODE_PRIVATE)
        updateTileState()
    }

    override fun onStopListening() {
        super.onStopListening()
        try {
            Shizuku.unbindUserService(
                Shizuku.UserServiceArgs(ComponentName(this, TaskHideService::class.java)),
                serviceConnection, true
            )
        } catch (_: Exception) {}
        taskHideService = null
        serviceReady = false
    }

    override fun onClick() {
        super.onClick()

        if (!Shizuku.pingBinder() || Shizuku.isPreV11()) {
            Toast.makeText(this, "Shizuku 未运行", Toast.LENGTH_SHORT).show()
            return
        }

        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "请先授权 Shizuku", Toast.LENGTH_SHORT).show()
            return
        }

        if (!serviceReady) {
            bindService()
        }

        val hiddenApps = prefs.getStringSet("hidden_apps", emptySet()) ?: emptySet()

        if (hiddenApps.isEmpty()) {
            Toast.makeText(this, "没有已标记的应用", Toast.LENGTH_SHORT).show()
            return
        }

        Thread {
            if (!serviceReady) {
                Thread.sleep(500)
            }

            val binder = taskHideService
            if (binder == null) {
                runOnUi { Toast.makeText(this, "服务连接失败", Toast.LENGTH_SHORT).show() }
                return@Thread
            }

            val allHidden = prefs.getBoolean("all_hidden", false)

            if (allHidden) {
                for (pkg in hiddenApps) {
                    callSetInvisible(binder, pkg, false)
                }
                prefs.edit().putBoolean("all_hidden", false).apply()
                runOnUi { Toast.makeText(this, "已恢复 ${hiddenApps.size} 个应用", Toast.LENGTH_SHORT).show() }
            } else {
                for (pkg in hiddenApps) {
                    callSetInvisible(binder, pkg, true)
                }
                prefs.edit().putBoolean("all_hidden", true).apply()
                runOnUi { Toast.makeText(this, "已隐藏 ${hiddenApps.size} 个应用", Toast.LENGTH_SHORT).show() }
            }

            updateTileState()
        }.start()
    }

    private fun bindService() {
        try {
            Shizuku.bindUserService(
                Shizuku.UserServiceArgs(ComponentName(this, TaskHideService::class.java))
                    .daemon(false).processNameSuffix("taskhide").debuggable(true).version(1),
                serviceConnection
            )
        } catch (_: Exception) {}
    }

    private fun callSetInvisible(binder: IBinder, packageName: String, invisible: Boolean): Boolean {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(ITaskHideService.DESCRIPTOR)
            data.writeString(packageName)
            data.writeByte(if (invisible) 1 else 0)
            binder.transact(ITaskHideService.TRANSACTION_setTaskInvisible, data, reply, 0)
            reply.readException()
            return reply.readInt() != 0
        } catch (_: Exception) {
            return false
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    private fun updateTileState() {
        val tile = qsTile ?: return
        val hiddenApps = prefs.getStringSet("hidden_apps", emptySet()) ?: emptySet()
        val allHidden = prefs.getBoolean("all_hidden", false)

        if (hiddenApps.isEmpty()) {
            tile.state = Tile.STATE_UNAVAILABLE
            tile.label = "隐藏最近"
            tile.icon = android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_qs_visibility_off)
        } else if (allHidden) {
            tile.state = Tile.STATE_ACTIVE
            tile.label = "已隐藏"
            tile.icon = android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_qs_visibility)
        } else {
            tile.state = Tile.STATE_INACTIVE
            tile.label = "隐藏最近"
            tile.icon = android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_qs_visibility_off)
        }

        tile.updateTile()
    }

    private fun runOnUi(action: () -> Unit) {
        android.os.Handler(android.os.Looper.getMainLooper()).post(action)
    }
}
