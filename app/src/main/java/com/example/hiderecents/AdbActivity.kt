package com.example.hiderecents

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.view.WindowCompat
import com.example.hiderecents.ui.screens.AdbLineKind
import com.example.hiderecents.ui.screens.AdbOutputLine
import com.example.hiderecents.ui.screens.AdbScreen
import com.example.hiderecents.ui.theme.AppTheme
import com.example.hiderecents.ui.theme.applyDshSystemBars
import com.example.hiderecents.ui.theme.DshTheme
import rikka.shizuku.Shizuku
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * ADB 命令（Compose 重写版）。
 *
 * 功能与原 AdbActivity 逐条对齐：设备信息（brand/model/Android 版本）、快捷命令、
 * 命令输入与执行、控制台输出（带时间戳与分隔线）、清空。
 * 所有命令通过 Shizuku 的 execCommand Parcel 通道以 shell 身份执行。
 */
class AdbActivity : DshActivity() {

    companion object {
        private const val MAX_LINES = 400
    }

    private lateinit var prefs: SharedPreferences
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    private var taskHideService: IBinder? = null
    private var serviceReady by mutableStateOf(false)

    private var deviceModel by mutableStateOf("")
    private var deviceInfo by mutableStateOf("")
    private var command by mutableStateOf("")
    private var output by mutableStateOf(listOf<AdbOutputLine>())
    private var running by mutableStateOf(false)

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            taskHideService = service
            serviceReady = true
            loadDeviceInfo()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            taskHideService = null
            serviceReady = false
        }
    }

    private val shizukuPermissionResultListener =
        Shizuku.OnRequestPermissionResultListener { _, grantResult ->
            if (grantResult == PackageManager.PERMISSION_GRANTED) bindService()
        }

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener { checkShizukuPermission() }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        taskHideService = null
        serviceReady = false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        prefs = getSharedPreferences("hide_recents_prefs", Context.MODE_PRIVATE)
        val theme = AppTheme.fromPref(prefs.getString("ui_theme", null))
        applySystemBars(theme)

        setContent {
            DshTheme(theme = theme) {
                AdbScreen(
                    deviceModel = deviceModel,
                    deviceInfo = deviceInfo,
                    command = command,
                    onCommandChange = { command = it },
                    output = output,
                    running = running,
                    ready = serviceReady,
                    onBack = { dshGoBack() },
                    onClear = { clearOutput() },
                    onExecute = { executeCommand() },
                )
            }
        }

        Shizuku.addRequestPermissionResultListener(shizukuPermissionResultListener)
        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
        checkShizukuPermission()
    }

    override fun onDestroy() {
        super.onDestroy()
        Shizuku.removeRequestPermissionResultListener(shizukuPermissionResultListener)
        Shizuku.removeBinderReceivedListener(binderReceivedListener)
        Shizuku.removeBinderDeadListener(binderDeadListener)
        try {
            Shizuku.unbindUserService(
                Shizuku.UserServiceArgs(ComponentName(this, TaskHideService::class.java)),
                serviceConnection,
                true,
            )
        } catch (_: Exception) {
        }
    }

    // ------------------------------------------------------------------ 主题

    private fun applySystemBars(theme: AppTheme) {
        applyDshSystemBars(theme)
    }

    // ------------------------------------------------------------------ Shizuku

    private fun checkShizukuPermission() {
        if (!Shizuku.pingBinder() || Shizuku.isPreV11()) return
        if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
            bindService()
        } else {
            Shizuku.requestPermission(1001)
        }
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

    private fun loadDeviceInfo() {
        Thread {
            val model = executeCommandSync("getprop ro.product.model")
            val brand = executeCommandSync("getprop ro.product.brand")
            val androidVer = executeCommandSync("getprop ro.build.version.release")
            runOnUiThread {
                deviceModel = (brand.trim() + " " + model.trim()).trim().ifEmpty { "未知设备" }
                deviceInfo = if (androidVer.isBlank()) "" else "Android " + androidVer.trim()
            }
        }.start()
    }

    // ------------------------------------------------------------------ 执行

    private fun executeCommand() {
        val cmd = command.trim()
        if (cmd.isEmpty()) return

        if (!serviceReady) {
            appendLine("Shizuku 未就绪，无法执行命令", AdbLineKind.OUTPUT)
            Toast.makeText(this, "Shizuku 未就绪", Toast.LENGTH_SHORT).show()
            return
        }

        val ts = timeFormat.format(Date())
        appendLine("[$ts] $cmd", AdbLineKind.COMMAND)
        running = true

        Thread {
            val result = executeCommandSync(cmd)
            runOnUiThread {
                running = false
                val trimmed = result.trimEnd()
                if (trimmed.isEmpty()) {
                    appendLine("（无输出）", AdbLineKind.OUTPUT)
                } else {
                    trimmed.lines().forEach { appendLine(it, AdbLineKind.OUTPUT) }
                }
                appendLine("", AdbLineKind.DIVIDER)
            }
        }.start()
    }

    private fun appendLine(text: String, kind: AdbLineKind) {
        val next = output + AdbOutputLine(text, kind)
        output = if (next.size > MAX_LINES) next.takeLast(MAX_LINES) else next
    }

    private fun clearOutput() {
        output = emptyList()
    }

    private fun executeCommandSync(command: String): String {
        val binder = TaskHideBridge.resolve(taskHideService) ?: return ""
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(ITaskHideService.DESCRIPTOR)
            data.writeString(command)
            binder.transact(ITaskHideService.TRANSACTION_execCommand, data, reply, 0)
            reply.readException()
            reply.readInt()
            return reply.readString() ?: ""
        } catch (_: Exception) {
            return ""
        } finally {
            data.recycle()
            reply.recycle()
        }
    }
}
