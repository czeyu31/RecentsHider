package com.example.hiderecents

import android.content.ComponentName
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.*
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import rikka.shizuku.Shizuku

class AdbActivity : AppCompatActivity() {

    private var taskHideService: IBinder? = null
    private var serviceReady = false
    private lateinit var tvDeviceModel: TextView
    private lateinit var tvDeviceInfo: TextView
    private lateinit var etCommand: EditText
    private lateinit var tvOutput: TextView
    private lateinit var svOutput: ScrollView
    private val outputHistory = StringBuilder()

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_adb)

        tvDeviceModel = findViewById(R.id.tvDeviceModel)
        tvDeviceInfo = findViewById(R.id.tvDeviceInfo)
        etCommand = findViewById(R.id.etCommand)
        tvOutput = findViewById(R.id.tvOutput)
        svOutput = findViewById(R.id.svOutput)

        findViewById<ImageView>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<MaterialButton>(R.id.btnExecute).setOnClickListener { executeCommand() }
        findViewById<ImageView>(R.id.btnClear).setOnClickListener { clearOutput() }

        findViewById<MaterialButton>(R.id.btnListPackages).setOnClickListener {
            etCommand.setText("pm list packages")
            executeCommand()
        }
        findViewById<MaterialButton>(R.id.btnDeviceInfo).setOnClickListener {
            etCommand.setText("getprop ro.product.model")
            executeCommand()
        }
        findViewById<MaterialButton>(R.id.btnBattery).setOnClickListener {
            etCommand.setText("dumpsys battery")
            executeCommand()
        }

        if (Shizuku.pingBinder() && !Shizuku.isPreV11()) {
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                bindService()
            }
        }
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

    private fun loadDeviceInfo() {
        Thread {
            val model = executeCommandSync("getprop ro.product.model")
            val brand = executeCommandSync("getprop ro.product.brand")
            val androidVer = executeCommandSync("getprop ro.build.version.release")
            runOnUiThread {
                tvDeviceModel.text = "$brand $model"
                tvDeviceInfo.text = "Android $androidVer"
            }
        }.start()
    }

    private fun executeCommandSync(command: String): String {
        val binder = taskHideService ?: return ""
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(ITaskHideService.DESCRIPTOR)
            data.writeString(command)
            binder.transact(ITaskHideService.TRANSACTION_execCommand, data, reply, 0)
            reply.readException()
            reply.readInt()
            return reply.readString() ?: ""
        } catch (_: Exception) { return "" }
        finally { data.recycle(); reply.recycle() }
    }

    private fun executeCommand() {
        val command = etCommand.text?.toString()?.trim() ?: return
        if (command.isEmpty()) return
        if (!serviceReady) {
            Toast.makeText(this, "Shizuku 未就绪", Toast.LENGTH_SHORT).show()
            return
        }

        val ts = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
        outputHistory.append("\n[$ts] $command\n")
        tvOutput.text = outputHistory.toString()
        scrollOutput()

        Thread {
            val output = executeCommandSync(command)
            runOnUiThread {
                outputHistory.append("$output\n${"─".repeat(40)}\n")
                tvOutput.text = outputHistory.toString()
                scrollOutput()
            }
        }.start()
    }

    private fun clearOutput() {
        outputHistory.clear()
        tvOutput.text = "等待执行命令..."
    }

    private fun scrollOutput() {
        svOutput.post { svOutput.fullScroll(android.view.View.FOCUS_DOWN) }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            Shizuku.unbindUserService(
                Shizuku.UserServiceArgs(ComponentName(this, TaskHideService::class.java)),
                serviceConnection, true
            )
        } catch (_: Exception) {}
    }
}
