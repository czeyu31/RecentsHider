package com.example.hiderecents

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.IBinder
import android.os.Parcel
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import com.example.hiderecents.ui.components.AppIconTile
import com.example.hiderecents.ui.components.DshDivider
import com.example.hiderecents.ui.components.DshGhostButton
import com.example.hiderecents.ui.components.DshIcon
import com.example.hiderecents.ui.components.DshIconKind
import com.example.hiderecents.ft.FtOnline
import com.example.hiderecents.ft.FtOnlineService
import com.example.hiderecents.ui.components.DshSwitch
import com.example.hiderecents.ui.components.dshClickable
import com.example.hiderecents.ui.screens.FileServerState
import com.example.hiderecents.ui.screens.FileTransferScreen
import com.example.hiderecents.ui.screens.NearbyDeviceItem
import com.example.hiderecents.ui.screens.ShareAction
import com.example.hiderecents.ui.screens.SharedFileItem
import com.example.hiderecents.ui.screens.TaskStatus
import com.example.hiderecents.ui.screens.TransferTaskItem
import com.example.hiderecents.ui.theme.AppTheme
import com.example.hiderecents.ui.theme.applyDshSystemBars
import com.example.hiderecents.ui.theme.Dsh
import com.example.hiderecents.ui.theme.DshTheme
import fi.iki.elonen.NanoHTTPD
import org.json.JSONObject
import rikka.shizuku.Shizuku
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 文件传输（Compose 重写版）。
 *
 * 逻辑与原 FileTransferActivity 逐条对齐：
 * - NanoHTTPD 随机端口服务，GET / 出文件列表页，GET /<id> 下载，POST 上传落盘
 * - UDP 53317 广播发现 + 常驻监听 + /24 网段探测 + 蓝牙已配对设备
 * - 六条分享入口：文件 / 媒体 / 剪贴板 / 文本 / 文件夹 / 应用
 * - URL 接收（超时、文件名清洗、速度采样、MD5、APK 自动安装）
 * - 设备直传 multipart POST
 * - 传输任务五态、共享文件移除、下载位置与自动安装设置
 */
class FileTransferActivity : DshActivity() {

    private companion object {
        const val REQ_PICK_FILE = 1001
        const val REQ_PICK_DIR = 1002
        const val REQ_PICK_FOLDER = 1003
        const val TAG = "FileTransfer"
    }

    private lateinit var prefs: SharedPreferences
    private lateinit var downloadDir: File
    private var autoInstallApk by mutableStateOf(false)

    private var httpServer: SimpleHttpServer? = null
    private var udpListener: java.net.DatagramSocket? = null
    private var udpThread: Thread? = null
    private var serverPort = 0

    private val sharedFiles = linkedMapOf<String, File>()
    private val discoveredDevices = ConcurrentHashMap<String, DeviceInfo>()
    private var discoveryRunning = false
    private val tasks = mutableListOf<TransferTask>()
    private var totalTransferBytes = 0L

    private var shizukuBound = false
    private var taskHideService: IBinder? = null

    // ---------------- Compose 状态 ----------------
    private var serverState by mutableStateOf(FileServerState("", 0, false))
    private var shareUrl by mutableStateOf<String?>(null)
    private var receiveUrl by mutableStateOf("")
    private var devicesState by mutableStateOf(listOf<NearbyDeviceItem>())
    private var scanning by mutableStateOf(false)
    private var tasksState by mutableStateOf(listOf<TransferTaskItem>())
    private var sharedState by mutableStateOf(listOf<SharedFileItem>())
    private var availableSpace by mutableStateOf("—")
    private var totalTransferred by mutableStateOf("0 B")

    private var showSettings by mutableStateOf(false)
    /** 「在线接收」：关掉本页后仍保持接收端在线，其他设备随时能发文件过来。 */
    private var onlineReceive by mutableStateOf(false)
    /** 「系统安装页兜底」：静默安装失败时改用系统安装页，由用户手动点「继续安装」。 */
    private var installPageFallback by mutableStateOf(true)
    private var showTextDialog by mutableStateOf(false)
    private var showAppPicker by mutableStateOf(false)
    private var appQuery by mutableStateOf("")
    private var appOptions by mutableStateOf(listOf<Pair<String, String>>())
    /** 「选择应用」里是否显示系统应用（默认不显示，避免列表被系统包淹没）。 */
    private var showSystemApps by mutableStateOf(false)

    /** 每个设备一条发送状态：缺省 idle / running 圆环 / done 勾 / fail 叉。 */
    private var deviceSendStates by mutableStateOf(mapOf<String, String>())

    /** 疑似被系统省电策略掐了后台联网（播报发不出去）。 */
    private var ftBlocked by mutableStateOf(false)

    data class DeviceInfo(
        val ip: String,
        val alias: String,
        val port: Int,
        val deviceModel: String,
        val deviceType: String,
    )

    data class TransferTask(
        val fileName: String,
        val target: String,
        val isSend: Boolean,
        val totalSize: Long,
        var transferred: Long = 0,
        var speed: Long = 0,
        var status: String = "waiting",
        var filePath: String = "",
        val id: String = UUID.randomUUID().toString().take(8),
    )

    private val shizukuConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            taskHideService = service
            shizukuBound = true
            autoGrantMediaPermission()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            taskHideService = null
            shizukuBound = false
        }
    }

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener { checkShizuku() }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        shizukuBound = false
        taskHideService = null
    }

    private val permissionListener =
        Shizuku.OnRequestPermissionResultListener { _, grantResult ->
            if (grantResult == android.content.pm.PackageManager.PERMISSION_GRANTED) bindShizuku()
        }

    // ------------------------------------------------------------------ 生命周期

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        prefs = getSharedPreferences("file_transfer_settings", Context.MODE_PRIVATE)
        autoInstallApk = prefs.getBoolean("auto_install_apk", false)
        showSystemApps = prefs.getBoolean("show_system_apps", false)
        prefs.edit().remove("download_path").apply()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            try {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                intent.data = Uri.parse("package:$packageName")
                startActivity(intent)
            } catch (_: Exception) {
                try {
                    startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                } catch (_: Exception) {
                }
            }
        }

        downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (!downloadDir.exists()) downloadDir.mkdirs()

        val themePrefs = getSharedPreferences("hide_recents_prefs", Context.MODE_PRIVATE)
        val theme = AppTheme.fromPref(themePrefs.getString("ui_theme", null))
        installPageFallback = themePrefs.getBoolean("install_page_fallback", true)
        onlineReceive = FtOnline.isOn(this)
        FtOnline.setOnEgressBlocked { blocked ->
            runOnUiThread { ftBlocked = blocked }
        }
        applySystemBars(theme)

        setContent {
            DshTheme(theme = theme) {
                FileTransferScreen(
                    server = serverState,
                    shareUrl = shareUrl,
                    actions = buildActions(),
                    receiveUrl = receiveUrl,
                    onReceiveUrlChange = { receiveUrl = it },
                    onReceive = { receiveFromUrl() },
                    devices = devicesState,
                    scanning = scanning,
                    tasks = tasksState,
                    totalTransferredLabel = totalTransferred,
                    sharedFiles = sharedState,
                    availableSpaceLabel = availableSpace,
                    onBack = { dshGoBack() },
                    onScan = { startDiscovery() },
                    deviceSendStates = deviceSendStates,
                    onOpenSettings = { showSettings = true },
                    onCopyUrl = { copyShareUrl() },
                    onRemoveSharedFile = { id ->
                        sharedFiles.remove(id)
                        FtOnline.shared.remove(id)
                        publishShared()
                        if (sharedFiles.isEmpty()) shareUrl = null
                    },
                    onSendToDevice = { device ->
                        discoveredDevices[device.key]?.let { target ->
                            deviceSendStates = deviceSendStates + (device.key to "running")
                            sendFileToDevice(target, device.key)
                        }
                    },
                    onOpenTaskFile = { task -> openTaskFile(task.id) },
                    netBlocked = ftBlocked,
                    onFixBlocked = {
                        runCatching { KeepAliveGuard.openPowerPolicySettings(this) }
                            .onFailure {
                                Toast.makeText(this, "请到「应用信息 → 省电策略」里改成「无限制」", Toast.LENGTH_LONG).show()
                            }
                    },
                )

                if (showSettings) {
                    SettingsSheet(
                        downloadPath = downloadDir.absolutePath,
                        autoInstall = autoInstallApk,
                        onToggleAutoInstall = { autoInstallApk = it },
                        installPageFallback = installPageFallback,
                        onlineReceive = onlineReceive,
                        onToggleOnlineReceive = { on ->
                            onlineReceive = on
                            FtOnline.setOn(this, on)
                            if (on) {
                                FtOnlineService.start(this)
                                Toast.makeText(this, "在线接收已开启，离开这一页也能收文件", Toast.LENGTH_SHORT).show()
                            } else {
                                FtOnline.onReceived = null
                                FtOnlineService.stop(this)
                                FtOnline.stop()
                                Toast.makeText(this, "在线接收已关闭", Toast.LENGTH_SHORT).show()
                            }
                            restartServer()
                        },
                        onToggleInstallPageFallback = {
                            installPageFallback = it
                            themePrefs.edit().putBoolean("install_page_fallback", it).apply()
                            Toast.makeText(
                                this,
                                if (it) "静默安装失败时会用系统安装页兜底" else "纯静默安装：失败只提示，不弹安装页",
                                Toast.LENGTH_SHORT,
                            ).show()
                        },
                        onChangePath = {
                            showSettings = false
                            pickDownloadDir()
                        },
                        onDismiss = { showSettings = false },
                        onSave = {
                            prefs.edit()
                                .putBoolean("auto_install_apk", autoInstallApk)
                                .putString("download_path", downloadDir.absolutePath)
                                .apply()
                            showSettings = false
                            Toast.makeText(this, "设置已保存", Toast.LENGTH_SHORT).show()
                        },
                    )
                }

                if (showTextDialog) {
                    TextInputDialog(
                        onDismiss = { showTextDialog = false },
                        onConfirm = { text ->
                            showTextDialog = false
                            shareText(text)
                        },
                    )
                }

                if (showAppPicker) {
                    AppPickerSheet(
                        query = appQuery,
                        showSystemApps = showSystemApps,
                        onToggleShowSystemApps = { value ->
                            showSystemApps = value
                            prefs.edit().putBoolean("show_system_apps", value).apply()
                            appOptions = filterApps(appQuery)
                        },
                        onQueryChange = { query ->
                            appQuery = query
                            appOptions = filterApps(query)
                        },
                        options = appOptions,
                        onDismiss = { showAppPicker = false },
                        onPick = { pkg ->
                            showAppPicker = false
                            shareApp(pkg)
                        },
                    )
                }
            }
        }

        startServer()
        updateAvailableSpace()
        setupShizuku()
        startDiscovery()
        publishServer()
    }

    override fun onResume() {
        super.onResume()
        publishServer()
        updateAvailableSpace()
        if (!discoveryRunning) startDiscovery()
    }

    override fun onPause() {
        super.onPause()
        discoveryRunning = false
    }

    override fun onDestroy() {
        super.onDestroy()
        // 在线接收交给服务托管，页面退出时不碰它
        FtOnline.setOnEgressBlocked(null)
        if (!onlineReceive) {
            httpServer?.stop()
            FtOnline.onReceived = null
        }
        try {
            udpThread?.interrupt()
            udpListener?.close()
            udpListener = null
        } catch (_: Exception) {
        }
        Shizuku.removeBinderReceivedListener(binderReceivedListener)
        Shizuku.removeBinderDeadListener(binderDeadListener)
        Shizuku.removeRequestPermissionResultListener(permissionListener)
        try {
            Shizuku.unbindUserService(
                Shizuku.UserServiceArgs(ComponentName(this, TaskHideService::class.java)),
                shizukuConnection,
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

    private fun setupShizuku() {
        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
        Shizuku.addRequestPermissionResultListener(permissionListener)
        checkShizuku()
    }

    private fun checkShizuku() {
        if (!Shizuku.pingBinder() || Shizuku.isPreV11()) return
        if (Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            bindShizuku()
        } else {
            Shizuku.requestPermission(1002)
        }
    }

    private fun bindShizuku() {
        try {
            Shizuku.bindUserService(
                Shizuku.UserServiceArgs(ComponentName(this, TaskHideService::class.java))
                    .daemon(false).processNameSuffix("taskhide").debuggable(true).version(2),
                shizukuConnection,
            )
        } catch (_: Exception) {
        }
    }

    private fun autoGrantMediaPermission() {
        Thread {
            try {
                val binder = TaskHideBridge.resolve(taskHideService)
                val cmds = listOf(
                    "appops set $packageName READ_MEDIA_IMAGES allow",
                    "appops set $packageName READ_MEDIA_VIDEO allow",
                    "appops set $packageName READ_MEDIA_AUDIO allow",
                    "appops set $packageName READ_EXTERNAL_STORAGE allow",
                    "appops set $packageName WRITE_EXTERNAL_STORAGE allow",
                )
                for (cmd in cmds) {
                    ShizukuShell.exec(binder, cmd)
                }
                AppLogger.i("autoGrant: media permissions done")
            } catch (e: Exception) {
                AppLogger.e("autoGrant failed", e)
            }
        }.start()
    }

    private fun execCommand(cmd: String): Pair<Boolean, String> {
        // 本页自己 bind 的用户服务常常还没就绪，优先复用进程内已连好的那个；
        // 都连不上时 ShizukuShell 会走服务端 newProcess。
        return ShizukuShell.exec(TaskHideBridge.resolve(taskHideService), cmd)
    }

    // ------------------------------------------------------------------ 发布状态

    private fun publishServer() {
        serverState = FileServerState(
            ip = getDeviceIpAddress(),
            port = serverPort,
            running = if (onlineReceive) FtOnline.port > 0 else httpServer?.wasStarted() == true,
        )
    }

    private fun publishShared() {
        sharedState = sharedFiles.map { (id, file) ->
            SharedFileItem(
                id = id,
                name = file.name,
                sizeLabel = formatSize(file.length()),
                isApk = file.name.endsWith(".apk", ignoreCase = true),
            )
        }
    }

    private fun publishTasks() {
        tasksState = tasks.map { task ->
            val fraction = if (task.totalSize > 0) {
                (task.transferred.toFloat() / task.totalSize.toFloat()).coerceIn(0f, 1f)
            } else {
                0f
            }
            val status = when (task.status) {
                "completed" -> TaskStatus.COMPLETED
                "installed" -> TaskStatus.INSTALLED
                "installing" -> TaskStatus.INSTALLING
                "transferring" -> TaskStatus.TRANSFERRING
                else -> TaskStatus.WAITING
            }
            TransferTaskItem(
                id = task.id,
                fileName = task.fileName,
                target = task.target,
                isSend = task.isSend,
                sizeLabel = formatSize(task.totalSize),
                progressLabel = formatSize(task.transferred) + " / " + formatSize(task.totalSize),
                speedLabel = when (status) {
                    TaskStatus.TRANSFERRING -> formatSize(task.speed) + "/s"
                    TaskStatus.WAITING -> "队列中"
                    TaskStatus.INSTALLING -> "安装中"
                    TaskStatus.INSTALLED -> "已安装"
                    TaskStatus.COMPLETED -> "已完成"
                },
                fraction = fraction,
                status = status,
                filePath = task.filePath,
            )
        }
        totalTransferred = formatSize(totalTransferBytes)
    }

    private fun publishDevices() {
        // 没有端口的条目根本收不了文件，不往列表里放
        devicesState = discoveredDevices.mapNotNull { (key, device) ->
            if (device.port <= 0) return@mapNotNull null
            NearbyDeviceItem(
                key = key,
                alias = device.alias,
                endpoint = device.ip + ":" + device.port,
                isBluetooth = false,
            )
        }
    }

    // ------------------------------------------------------------------ 服务端

    /** 切换「在线接收」后要换一套接收端，先把旧的收掉再起新的。 */
    private fun restartServer() {
        runCatching { httpServer?.stop() }
        httpServer = null
        runCatching {
            udpThread?.interrupt()
            udpListener?.close()
        }
        udpThread = null
        udpListener = null
        if (!onlineReceive) FtOnline.stop()
        startServer()
        publishServer()
        publishShared()
    }

    private fun startServer() {
        // 在线接收打开时，接收端由前台服务托管（关掉本页也还在），本页不再自己占端口
        if (onlineReceive) {
            val p = FtOnline.start(this)
            if (p > 0) {
                serverPort = p
                httpServer = null
                FtOnline.onReceived = { file, from ->
                    runOnUiThread {
                        addTask(
                            file.name,
                            from.ifEmpty { "其他设备" },
                            false,
                            file.length(),
                            file.absolutePath,
                        )
                    }
                }
                AppLogger.i("Online receive server on port $p")
                publishServer()
                return
            }
            AppLogger.e("Online receive server failed, falling back to local server")
        }

        try {
            val socket = java.net.ServerSocket(0)
            serverPort = socket.localPort
            socket.close()
            httpServer = SimpleHttpServer(serverPort)
            httpServer?.start()
            AppLogger.i("HTTP server started on port $serverPort")
        } catch (e: Exception) {
            AppLogger.e("HTTP server failed", e)
        }

        if (udpListener == null) {
            try {
                udpListener = java.nio.channels.DatagramChannel.open(java.net.StandardProtocolFamily.INET)
                    .socket()
                    .apply {
                        reuseAddress = true
                        bind(java.net.InetSocketAddress(java.net.InetAddress.getByName("0.0.0.0"), 53317))
                    }
                udpThread = Thread {
                    try {
                        val udpSocket = udpListener ?: return@Thread
                        udpSocket.soTimeout = 0
                        while (!Thread.currentThread().isInterrupted && !udpSocket.isClosed) {
                            try {
                                val buf = ByteArray(8192)
                                val packet = java.net.DatagramPacket(buf, buf.size)
                                udpSocket.receive(packet)
                                val msg = String(packet.data, 0, packet.length)
                                val senderIp = packet.address.hostAddress ?: continue

                                val response = JSONObject().apply {
                                    put("alias", Build.MODEL)
                                    put("port", serverPort)
                                    put("protocol", "http")
                                    put("version", "2.1")
                                    put("deviceModel", Build.MODEL)
                                    put("deviceType", "mobile")
                                    put("ip", getDeviceIpAddress())
                                }.toString()
                                val responseData = response.toByteArray()
                                udpSocket.send(
                                    java.net.DatagramPacket(
                                        responseData,
                                        responseData.size,
                                        packet.address,
                                        packet.port,
                                    ),
                                )

                                try {
                                    val json = JSONObject(msg)
                                    val port = json.optInt("port", 53317)
                                    val alias = json.optString("alias", "Device")
                                    val model = json.optString("deviceModel", "")
                                    val myIps = getAllIpAddresses()
                                    if (!myIps.contains(senderIp) && !discoveredDevices.containsKey(senderIp)) {
                                        discoveredDevices[senderIp] = DeviceInfo(senderIp, alias, port, model, "mobile")
                                        runOnUiThread { publishDevices() }
                                    }
                                } catch (_: Exception) {
                                }
                            } catch (_: java.net.SocketTimeoutException) {
                            }
                        }
                    } catch (e: Exception) {
                        AppLogger.e("UDP listener error", e)
                    }
                }
                udpThread?.isDaemon = true
                udpThread?.start()
            } catch (e: Exception) {
                AppLogger.e("UDP listener failed to start", e)
            }
        }
    }

    private fun getDeviceIpAddress(): String {
        // 用 FtOnline.lanIp()：会跳过 ccmni*/rmnet* 这类蜂窝接口，否则双卡手机插着移动数据时
        // 会拿到 10.x 的 /32，广播和同网段扫描全跑偏。
        return FtOnline.lanIp() ?: "未连接WiFi"
    }

    private fun getAllIpAddresses(): List<String> {
        val ips = mutableListOf<String>()
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val intf = interfaces.nextElement()
                val addrs = intf.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    ips.add(addr.hostAddress ?: "")
                }
            }
        } catch (_: Exception) {
        }
        return ips
    }

    // ------------------------------------------------------------------ 发现

    private fun startDiscovery() {
        if (discoveryRunning) return
        discoveryRunning = true
        scanning = true
        discoveredDevices.clear()
        publishDevices()

        // 这里只认「能在局域网上收文件」的设备：先 UDP 广播，再扫同网段。
        // 以前顺手把系统已配对的蓝牙设备也塞进这个列表，手环、桌搭这些根本收不了文件，
        // 看起来就像心率广播扫描波及到了文件传输，已经去掉。

        Thread {
            val ip = getDeviceIpAddress()
            val myIps = getAllIpAddresses()
            if (ip == "未连接WiFi") {
                runOnUiThread {
                    scanning = false
                    discoveryRunning = false
                }
                return@Thread
            }

            try {
                // 扫描 socket 也必须是 IPv4：双栈 socket 发 IPv4 广播在部分 ROM 上会被丢掉，
                // 表现就是「这台能扫到那台、那台扫不到这台」。
                val socket = java.nio.channels.DatagramChannel.open(java.net.StandardProtocolFamily.INET)
                    .socket()
                socket.broadcast = true
                socket.soTimeout = 3000
                socket.bind(
                    java.net.InetSocketAddress(java.net.InetAddress.getByName("0.0.0.0"), 0),
                )

                val announce = JSONObject().apply {
                    put("alias", Build.MODEL)
                    put("port", serverPort)
                    put("protocol", "http")
                    put("version", "2.1")
                    put("deviceModel", Build.MODEL)
                    put("deviceType", "mobile")
                }.toString()

                val data = announce.toByteArray()
                val targets = listOf(
                    java.net.InetAddress.getByName("255.255.255.255"),
                    // 有些路由器不转发受限广播，定向子网广播更靠谱
                    java.net.InetAddress.getByName(ip.substringBeforeLast('.') + ".255"),
                )
                val packets = targets.map { java.net.DatagramPacket(data, data.size, it, 53317) }

                val buf = ByteArray(8192)
                val endTime = System.currentTimeMillis() + 4000
                var nextAnnounce = 0L
                while (System.currentTimeMillis() < endTime) {
                    // 广播要反复发：对方可能刚从省电模式醒来，第一轮广播是收不到的
                    val now = System.currentTimeMillis()
                    if (now >= nextAnnounce) {
                        packets.forEach { runCatching { socket.send(it) } }
                        nextAnnounce = now + 700
                    }
                    try {
                        val recvPacket = java.net.DatagramPacket(buf, buf.size)
                        socket.receive(recvPacket)
                        val msg = String(recvPacket.data, 0, recvPacket.length)
                        val senderIp = recvPacket.address.hostAddress ?: continue
                        if (senderIp == ip) continue
                        try {
                            val json = JSONObject(msg)
                            val deviceIp = json.optString("ip", senderIp)
                            val port = json.optInt("port", 53317)
                            val alias = json.optString("alias", "Device")
                            val model = json.optString("deviceModel", "")
                            synchronized(discoveredDevices) {
                                discoveredDevices[deviceIp] = DeviceInfo(deviceIp, alias, port, model, "mobile")
                            }
                            runOnUiThread { publishDevices() }
                        } catch (_: Exception) {
                        }
                    } catch (_: java.net.SocketTimeoutException) {
                    }
                }
                socket.close()
            } catch (e: Exception) {
                AppLogger.e("Discovery UDP failed", e)
            }

            val subnet = ip.substringBeforeLast(".")
            val ports = intArrayOf(serverPort, 53318, 53317, 8080)
            val threads = (1..254).map { i ->
                Thread {
                    try {
                        val addr = "$subnet.$i"
                        if (myIps.contains(addr)) return@Thread
                        for (port in ports) {
                            if (discoveredDevices.containsKey(addr)) break
                            try {
                                val socket = java.net.Socket()
                                socket.connect(java.net.InetSocketAddress(addr, port), 800)
                                socket.close()
                                try {
                                    // 用 /ping 认设备：既确认是 System Tool，又直接拿到设备名和真实端口
                                    val conn = URL("http://$addr:$port/ping").openConnection() as HttpURLConnection
                                    conn.connectTimeout = 800
                                    conn.readTimeout = 800
                                    if (conn.responseCode == 200) {
                                        val body = conn.inputStream.bufferedReader().readText()
                                        val json = runCatching { JSONObject(body) }.getOrNull()
                                        if (json != null && json.optBoolean("online", false)) {
                                            val realPort = json.optInt("port", port)
                                            val model = json.optString("deviceModel", "")
                                            val alias = json.optString("alias", "")
                                                .ifEmpty { model }
                                                .ifEmpty { "System Tool" }
                                            synchronized(discoveredDevices) {
                                                discoveredDevices[addr] =
                                                    DeviceInfo(addr, alias, realPort, model, "mobile")
                                            }
                                            runOnUiThread { publishDevices() }
                                        }
                                    }
                                } catch (_: Exception) {
                                }
                            } catch (_: Exception) {
                            }
                        }
                    } catch (_: Exception) {
                    }
                }
            }
            threads.forEach { it.start() }
            threads.forEach { it.join(3000) }

            runOnUiThread {
                scanning = false
                discoveryRunning = false
                publishDevices()
            }
        }.start()
    }

    // ------------------------------------------------------------------ 分享入口

    private fun buildActions(): List<ShareAction> = listOf(
        ShareAction("文件", DshIconKind.FILE) { pickFile() },
        ShareAction("媒体", DshIconKind.IMAGE) { pickMedia() },
        ShareAction("剪贴板", DshIconKind.CLIPBOARD) { shareClipboard() },
        ShareAction("文本", DshIconKind.TERMINAL) { showTextDialog = true },
        ShareAction("文件夹", DshIconKind.FOLDER) { pickFolder() },
        ShareAction("应用", DshIconKind.PACKAGE) {
            appQuery = ""
            appOptions = filterApps("")
            showAppPicker = true
        },
    )

    @Suppress("DEPRECATION")
    private fun pickFile() {
        val intent = Intent(Intent.ACTION_GET_CONTENT)
        intent.type = "*/*"
        intent.addCategory(Intent.CATEGORY_OPENABLE)
        startActivityForResult(intent, REQ_PICK_FILE)
    }

    @Suppress("DEPRECATION")
    private fun pickMedia() {
        val intent = Intent(Intent.ACTION_GET_CONTENT)
        intent.type = "*/*"
        intent.putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/*", "video/*", "audio/*"))
        intent.addCategory(Intent.CATEGORY_OPENABLE)
        startActivityForResult(intent, REQ_PICK_FILE)
    }

    @Suppress("DEPRECATION")
    private fun pickFolder() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivityForResult(intent, REQ_PICK_FOLDER)
    }

    @Suppress("DEPRECATION")
    private fun pickDownloadDir() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        startActivityForResult(intent, REQ_PICK_DIR)
    }

    private fun shareClipboard() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = clipboard.primaryClip
        if (clip != null && clip.itemCount > 0) {
            val text = clip.getItemAt(0).text?.toString()
            if (!text.isNullOrEmpty()) {
                val file = File(cacheDir, "clipboard_" + System.currentTimeMillis() + ".txt")
                file.writeText(text)
                addSharedFile(file)
                Toast.makeText(this, "剪贴板内容已添加", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "剪贴板为空", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(this, "剪贴板为空", Toast.LENGTH_SHORT).show()
        }
    }

    private fun shareText(text: String) {
        if (text.isEmpty()) return
        val file = File(cacheDir, "text_" + System.currentTimeMillis() + ".txt")
        file.writeText(text)
        addSharedFile(file)
    }

    /**
     * 枚举可选应用。
     *
     * 为什么不用单一 getInstalledApplications：这台 ROM 上它只返回本应用自己，
     * 导致「选择应用」列表只有一条；MAIN/LAUNCHER 查询能拿到完整可启动应用集合，
     * 两路合并去重后即可覆盖全部。
     */
    private fun collectAppInfos(pm: android.content.pm.PackageManager): List<android.content.pm.ApplicationInfo> {
        val merged = LinkedHashMap<String, android.content.pm.ApplicationInfo>()
        runCatching { pm.getInstalledApplications(0) }.getOrDefault(emptyList()).forEach { merged[it.packageName] = it }
        runCatching {
            val launch = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            for (ri in pm.queryIntentActivities(launch, 0)) {
                val pkg = ri.activityInfo?.packageName ?: continue
                if (merged.containsKey(pkg)) continue
                val info = runCatching { pm.getApplicationInfo(pkg, 0) }.getOrNull() ?: continue
                merged[pkg] = info
            }
        }
        return merged.values.toList()
    }

    private fun filterApps(query: String): List<Pair<String, String>> {
        val pm = packageManager
        // 这台 ROM 上 getInstalledApplications 只返回本应用自己（实测选择应用列表只有 1 条），
        // 所以改走 collectAppInfos()：直连枚举 + MAIN/LAUNCHER 查询两路合并去重。
        val all = collectAppInfos(pm)
        AppLogger.i("filterApps: candidates=" + all.size + " showSystemApps=" + showSystemApps)
        val needle = query.trim().lowercase()
        return all.mapNotNull { info ->
            // 「显示系统应用」关闭时（默认）不列出系统应用：系统包几百个，会把用户应用淹没。
            if (!showSystemApps &&
                (info.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
            ) {
                return@mapNotNull null
            }
            val label = try {
                pm.getApplicationLabel(info).toString()
            } catch (_: Exception) {
                info.packageName
            }
            if (needle.isNotEmpty() &&
                !label.lowercase().contains(needle) &&
                !info.packageName.lowercase().contains(needle)
            ) {
                null
            } else {
                Pair(label, info.packageName)
            }
        }.sortedBy { it.first.lowercase() }
    }

    private fun shareApp(packageName: String) {
        try {
            val pm = packageManager
            val info = pm.getApplicationInfo(packageName, 0)
            val src = File(info.sourceDir)
            val label = try {
                pm.getApplicationLabel(info).toString()
            } catch (_: Exception) {
                src.name
            }
            val cached = File(cacheDir, "$label.apk")
            try {
                src.inputStream().use { input ->
                    cached.outputStream().use { output -> input.copyTo(output) }
                }
            } catch (_: Exception) {
            }
            addSharedFile(if (cached.exists()) cached else src)
        } catch (e: Exception) {
            Toast.makeText(this, "无法读取应用: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun addSharedFile(file: File) {
        val fileId = UUID.randomUUID().toString().take(8)
        sharedFiles[fileId] = file
        FtOnline.shared[fileId] = file
        addTask(file.name, "分享文件", true, file.length())
        showShareLink(fileId)
    }

    private fun showShareLink(fileId: String) {
        val ip = getDeviceIpAddress()
        shareUrl = "http://$ip:$serverPort/$fileId"
        publishShared()
        publishServer()
    }

    private fun copyShareUrl() {
        val url = shareUrl ?: return
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("url", url))
        Toast.makeText(this, "链接已复制", Toast.LENGTH_SHORT).show()
    }

    // ------------------------------------------------------------------ Activity 结果

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return

        when (requestCode) {
            REQ_PICK_FILE -> {
                val uri = data?.data ?: return
                try {
                    var fileName = "file_" + System.currentTimeMillis()
                    contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                            if (idx >= 0) fileName = cursor.getString(idx) ?: fileName
                        }
                    }
                    val cacheFile = File(cacheDir, fileName)
                    contentResolver.openInputStream(uri)?.use { input ->
                        cacheFile.outputStream().use { output -> input.copyTo(output) }
                    }
                    addSharedFile(cacheFile)
                } catch (e: Exception) {
                    Toast.makeText(this, "添加失败: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }

            REQ_PICK_DIR -> {
                val uri = data?.data ?: return
                try {
                    val path = uri.path?.replace("/tree/primary:", "/storage/emulated/0/") ?: ""
                    if (path.isNotEmpty()) {
                        val dir = File(path)
                        if (!dir.exists()) dir.mkdirs()
                        downloadDir = dir
                        updateAvailableSpace()
                        Toast.makeText(this, "下载位置已改为 ${dir.absolutePath}", Toast.LENGTH_SHORT).show()
                    }
                } catch (_: Exception) {
                }
            }

            REQ_PICK_FOLDER -> {
                val uri = data?.data ?: return
                try {
                    val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
                        uri,
                        DocumentsContract.getTreeDocumentId(uri),
                    )
                    val cursor = contentResolver.query(
                        childrenUri,
                        arrayOf(
                            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                        ),
                        null,
                        null,
                        null,
                    )
                    var count = 0
                    cursor?.use {
                        while (it.moveToNext()) {
                            val docId = it.getString(0)
                            val name = it.getString(1)
                            val fileUri = DocumentsContract.buildDocumentUriUsingTree(uri, docId)
                            val cacheFile = File(cacheDir, name)
                            try {
                                contentResolver.openInputStream(fileUri)?.use { input ->
                                    cacheFile.outputStream().use { output -> input.copyTo(output) }
                                }
                                val fileId = UUID.randomUUID().toString().take(8)
                                sharedFiles[fileId] = cacheFile
                                FtOnline.shared[fileId] = cacheFile
                                count++
                            } catch (_: Exception) {
                            }
                        }
                    }
                    if (count > 0) {
                        Toast.makeText(this, "已添加 $count 个文件", Toast.LENGTH_SHORT).show()
                        sharedFiles.keys.lastOrNull()?.let { showShareLink(it) }
                    } else {
                        Toast.makeText(this, "文件夹为空", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    Toast.makeText(this, "添加失败: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // ------------------------------------------------------------------ URL 接收

    private fun receiveFromUrl() {
        var url = receiveUrl.trim()
        if (url.isEmpty()) {
            Toast.makeText(this, "请输入链接", Toast.LENGTH_SHORT).show()
            return
        }
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "http://$url"
        }
        Toast.makeText(this, "开始接收...", Toast.LENGTH_SHORT).show()

        Thread {
            try {
                val urlObj = URL(url)
                val connection = urlObj.openConnection() as HttpURLConnection
                connection.connectTimeout = 30000
                connection.readTimeout = 120000
                connection.instanceFollowRedirects = true
                connection.setRequestProperty("User-Agent", "SystemTool/1.0")
                connection.setRequestProperty("Connection", "keep-alive")
                connection.setRequestProperty("Accept-Encoding", "identity")
                connection.connect()

                val responseCode = connection.responseCode
                if (responseCode != 200) {
                    runOnUiThread {
                        Toast.makeText(this, "服务器返回错误: $responseCode", Toast.LENGTH_SHORT).show()
                    }
                    return@Thread
                }

                val contentDisposition = connection.getHeaderField("Content-Disposition")
                var fileName = "received_" + System.currentTimeMillis()
                if (contentDisposition != null) {
                    val match = Regex("filename=\"?([^\"]+)\"?").find(contentDisposition)
                    if (match != null) fileName = match.groupValues[1]
                } else {
                    val path = urlObj.path
                    if (path.isNotEmpty() && path != "/") {
                        fileName = path.substringAfterLast("/").substringBefore("?").ifEmpty { fileName }
                    }
                }
                fileName = fileName.replace(Regex("[^a-zA-Z0-9._\\-\\x{4e00}-\\x{9fff}]"), "_")
                if (fileName.length > 200) fileName = fileName.take(200)

                val totalSize = connection.contentLength.toLong()
                if (!downloadDir.exists()) downloadDir.mkdirs()
                val outputFile = File(downloadDir, fileName)

                val task = TransferTask(fileName, urlObj.host ?: "", false, totalSize)
                task.status = "transferring"
                runOnUiThread {
                    tasks.add(task)
                    publishTasks()
                }

                val inputStream = BufferedInputStream(connection.inputStream)
                val outputStream = FileOutputStream(outputFile)
                val buffer = ByteArray(32768)
                var bytesRead: Int
                var totalRead = 0L
                var lastTime = System.currentTimeMillis()
                var lastRead = 0L

                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    outputStream.write(buffer, 0, bytesRead)
                    totalRead += bytesRead
                    val now = System.currentTimeMillis()
                    if (now - lastTime >= 300) {
                        val speed = (totalRead - lastRead) * 1000 / (now - lastTime)
                        task.transferred = totalRead
                        task.speed = speed
                        lastRead = totalRead
                        lastTime = now
                        runOnUiThread { publishTasks() }
                    }
                }

                outputStream.flush()
                outputStream.close()
                inputStream.close()

                val downloadedMd5 = outputFile.inputStream().use { stream ->
                    val md = java.security.MessageDigest.getInstance("MD5")
                    val buf = ByteArray(32768)
                    var read: Int
                    while (stream.read(buf).also { r -> read = r } != -1) md.update(buf, 0, read)
                    md.digest().joinToString("") { "%02x".format(it) }
                }
                AppLogger.i("FileTransfer downloaded MD5=$downloadedMd5, size=${outputFile.length()}")

                totalTransferBytes += totalRead

                if (autoInstallApk && fileName.endsWith(".apk", ignoreCase = true)) {
                    task.status = "installing"
                    runOnUiThread { publishTasks() }
                    val installed = installApkViaShizuku(outputFile)
                    task.status = if (installed) "installed" else "completed"
                } else {
                    task.status = "completed"
                }

                task.transferred = totalRead
                task.speed = 0
                task.filePath = outputFile.absolutePath
                runOnUiThread {
                    publishTasks()
                    updateAvailableSpace()
                    Toast.makeText(this, "已保存到: ${outputFile.name}", Toast.LENGTH_LONG).show()
                }
            } catch (e: java.net.ConnectException) {
                runOnUiThread { Toast.makeText(this, "连接被拒绝，请确认发送方在线", Toast.LENGTH_SHORT).show() }
            } catch (e: java.net.SocketTimeoutException) {
                runOnUiThread { Toast.makeText(this, "连接超时，请检查网络", Toast.LENGTH_SHORT).show() }
            } catch (e: java.net.UnknownHostException) {
                runOnUiThread { Toast.makeText(this, "无法解析地址，请检查链接", Toast.LENGTH_SHORT).show() }
            } catch (e: Exception) {
                runOnUiThread { Toast.makeText(this, "接收失败: ${e.message}", Toast.LENGTH_SHORT).show() }
            }
        }.start()
    }

    // ------------------------------------------------------------------ 安装 APK

    private fun installApkViaShizuku(apkFile: File): Boolean {
        try {
            // 优先复用进程内已连好的 binder：本页自己 bind 的用户服务常常还没就绪。
            var waited = 0
            if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
                while (TaskHideBridge.resolve(taskHideService) == null && waited < 3000) {
                    Thread.sleep(100)
                    waited += 100
                }
            }
            // 与「APK 安装器」页共用同一份静默安装逻辑，全程后台，不打开任何页面。
            val res = SilentApkInstaller.install(apkFile, taskHideService)
            AppLogger.i("installApk: waited=${waited}ms ok=${res.first} output=${res.second.take(240)}")
            // 系统安装页兜底已收进 SilentApkInstaller.install（受偏好 install_page_fallback 控制）
            if (res.first) return true
            runOnUiThread {
                Toast.makeText(this, "静默安装失败：" + res.second.take(60), Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            AppLogger.e("installApk: Shizuku failed", e)
        }
        return false
    }

    // ------------------------------------------------------------------ 任务

    private fun addTask(fileName: String, target: String, isSend: Boolean, size: Long, filePath: String = "") {
        val task = TransferTask(fileName, target, isSend, size)
        task.status = "completed"
        task.transferred = size
        task.filePath = filePath
        tasks.add(task)
        totalTransferBytes += size
        publishTasks()
        updateAvailableSpace()
    }

    private fun openTaskFile(taskId: String) {
        val task = tasks.firstOrNull { it.id == taskId } ?: return
        if (task.filePath.isEmpty()) return
        val file = File(task.filePath)
        if (!file.exists()) {
            Toast.makeText(this, "文件不存在", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            val mime = android.webkit.MimeTypeMap.getSingleton()
                .getMimeTypeFromExtension(file.extension) ?: "*/*"
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "无法打开: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateAvailableSpace() {
        try {
            val stat = android.os.StatFs(downloadDir.absolutePath)
            availableSpace = formatSize(stat.availableBlocksLong * stat.blockSizeLong)
        } catch (_: Exception) {
            availableSpace = "未知"
        }
    }

    // ------------------------------------------------------------------ 设备直传

    private fun sendFileToDevice(device: DeviceInfo, deviceKey: String) {
        if (sharedFiles.isEmpty()) {
            Toast.makeText(this, "请先添加文件", Toast.LENGTH_SHORT).show()
            deviceSendStates = deviceSendStates - deviceKey
            return
        }
        Thread {
            var anyFailed = false
            for ((_, file) in sharedFiles) {
                try {
                    val boundary = "----SystemTool" + System.currentTimeMillis()
                    val conn = URL("http://${device.ip}:${device.port}/")
                        .openConnection() as HttpURLConnection
                    conn.requestMethod = "POST"
                    conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
                    conn.setRequestProperty("X-File-Name", URLEncoder.encode(file.name, "UTF-8"))
                    conn.setRequestProperty("X-File-Size", file.length().toString())
                    conn.doOutput = true
                    conn.connectTimeout = 10000
                    conn.readTimeout = 30000

                    val outputStream = conn.outputStream
                    val writer = outputStream.bufferedWriter()
                    writer.write("--$boundary\r\n")
                    writer.write("Content-Disposition: form-data; name=\"file\"; filename=\"${file.name}\"\r\n")
                    writer.write("Content-Type: application/octet-stream\r\n\r\n")
                    writer.flush()

                    file.inputStream().use { input ->
                        val buffer = ByteArray(32768)
                        var read: Int
                        while (input.read(buffer).also { read = it } != -1) {
                            outputStream.write(buffer, 0, read)
                        }
                    }

                    writer.write("\r\n--$boundary--\r\n")
                    writer.flush()
                    AppLogger.i("Send to ${device.ip}: response=${conn.responseCode}")
                    outputStream.close()
                    conn.disconnect()
                } catch (e: Exception) {
                    anyFailed = true
                    AppLogger.e("Send failed to ${device.ip}", e)
                }
            }
            val names = sharedFiles.values.joinToString(", ") { it.name }
            val total = sharedFiles.values.sumOf { it.length() }
            val failed = anyFailed
            runOnUiThread {
                Toast.makeText(this, if (failed) "发送失败" else "发送完成", Toast.LENGTH_SHORT).show()
                deviceSendStates = deviceSendStates + (deviceKey to if (failed) "fail" else "done")
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    deviceSendStates = deviceSendStates - deviceKey
                }, 3000L)
                if (!failed) addTask(names, device.alias, true, total)
            }
        }.start()
    }

    private fun formatSize(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> String.format("%.1f KB", bytes / 1024.0)
        bytes < 1024L * 1024 * 1024 -> String.format("%.1f MB", bytes / 1048576.0)
        else -> String.format("%.2f GB", bytes / 1073741824.0)
    }

    // ------------------------------------------------------------------ HTTP 服务

    inner class SimpleHttpServer(port: Int) : NanoHTTPD("0.0.0.0", port) {
        override fun serve(session: IHTTPSession): Response {
            val uri = session.uri.removePrefix("/")

            if (session.method == Method.POST) {
                try {
                    var fileName = "received_" + System.currentTimeMillis()
                    val encodedName = session.headers["x-file-name"]
                    if (!encodedName.isNullOrEmpty()) {
                        fileName = URLDecoder.decode(encodedName, "UTF-8")
                    }
                    val bodyFiles = HashMap<String, String>()
                    session.parseBody(bodyFiles)
                    val tempFile = bodyFiles["file"]
                    if (tempFile != null) {
                        val destFile = File(downloadDir, fileName)
                        File(tempFile).copyTo(destFile, overwrite = true)
                        File(tempFile).delete()
                        runOnUiThread {
                            addTask(
                                fileName,
                                session.remoteIpAddress ?: "device",
                                false,
                                destFile.length(),
                                destFile.absolutePath,
                            )
                            Toast.makeText(this@FileTransferActivity, "收到文件: $fileName", Toast.LENGTH_SHORT).show()
                        }
                        if (autoInstallApk && fileName.endsWith(".apk", ignoreCase = true)) {
                            Thread {
                                Thread.sleep(500)
                                installApkViaShizuku(destFile)
                            }.start()
                        }
                        return newFixedLengthResponse(
                            Response.Status.OK,
                            "application/json",
                            "{\"status\":\"ok\",\"file\":\"$fileName\"}",
                        )
                    }
                } catch (e: Exception) {
                    AppLogger.e("HTTP upload error", e)
                }
                return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Upload failed")
            }

            if (uri.isEmpty()) {
                val paper = false
                val bg = if (paper) "#F1EDE3" else "#0F1214"
                val fg = if (paper) "#151410" else "#E9EDEC"
                val muted = if (paper) "#8A8577" else "#68716F"
                val accent = if (paper) "#AE4327" else "#8FB3A4"
                val card = if (paper) "rgba(21,20,16,.04)" else "rgba(255,255,255,.05)"
                val line = if (paper) "rgba(21,20,16,.14)" else "rgba(255,255,255,.10)"

                val html = buildString {
                    append("<!DOCTYPE html><html><head><meta charset='utf-8'>")
                    append("<meta name='viewport' content='width=device-width,initial-scale=1'>")
                    append("<title>System Tool</title><style>")
                    append("body{background:$bg;color:$fg;font-family:-apple-system,'Segoe UI',system-ui,sans-serif;")
                    append("padding:28px 20px;max-width:620px;margin:0 auto;line-height:1.55}")
                    append("h1{font-size:19px;font-weight:600;letter-spacing:-.02em;margin:0 0 6px}")
                    append(".sub{color:$muted;font-size:12px;margin-bottom:22px}")
                    append(".lbl{font-size:10px;letter-spacing:.18em;text-transform:uppercase;color:$muted;margin:0 0 8px}")
                    append("a{color:$fg;text-decoration:none;display:block;padding:14px 16px;background:$card;")
                    append("border:1px solid $line;border-radius:12px;margin:0 0 8px;font-size:14px}")
                    append("a span{float:right;color:$muted;font-size:12px}")
                    append(".empty{color:$muted;font-size:13px;padding:18px 0}")
                    append(".foot{color:$muted;font-size:11px;margin-top:22px;border-top:1px solid $line;padding-top:14px}")
                    append("a.accent{color:$accent;border-color:$line}")
                    append("</style></head><body>")
                    append("<h1>System Tool</h1><div class='sub'>文件中转 · 在同一 Wi-Fi 下直接收发</div>")
                    append("<div class='lbl'>共享文件</div>")
                    if (sharedFiles.isEmpty()) {
                        append("<div class='empty'>还没有共享文件。在手机上点六个入口中的任意一个添加。</div>")
                    } else {
                        for ((id, file) in sharedFiles) {
                            append("<a href='/$id'>${file.name}<span>${formatSize(file.length())}</span></a>")
                        }
                    }
                    append("<div class='foot'>上传：把文件拖到本页任意位置，或使用 POST multipart 上传。</div>")
                    append("</body></html>")
                }
                return newFixedLengthResponse(Response.Status.OK, "text/html", html)
            }

            val file = sharedFiles[uri]
            if (file != null && file.exists()) {
                val mime = when {
                    file.name.endsWith(".apk") -> "application/vnd.android.package-archive"
                    file.name.endsWith(".zip") -> "application/zip"
                    file.name.endsWith(".pdf") -> "application/pdf"
                    file.name.endsWith(".jpg") || file.name.endsWith(".jpeg") -> "image/jpeg"
                    file.name.endsWith(".png") -> "image/png"
                    file.name.endsWith(".mp4") -> "video/mp4"
                    file.name.endsWith(".mp3") -> "audio/mpeg"
                    file.name.endsWith(".txt") -> "text/plain"
                    else -> "application/octet-stream"
                }
                val bytes = file.readBytes()
                val resp = newFixedLengthResponse(
                    Response.Status.OK,
                    mime,
                    ByteArrayInputStream(bytes),
                    bytes.size.toLong(),
                )
                resp.addHeader("Content-Disposition", "attachment; filename=\"${file.name}\"")
                resp.addHeader("Content-Length", bytes.size.toString())
                return resp
            }

            return newFixedLengthResponse(
                Response.Status.NOT_FOUND,
                "text/html",
                "<html><body style='background:#0F1214;color:#E9EDEC;padding:24px;font-family:system-ui'>" +
                    "<h2 style='font-size:16px'>文件不存在</h2>" +
                    "<a href='/' style='color:#8FB3A4;font-size:13px'>返回列表</a></body></html>",
            )
        }
    }
}

// ---------------------------------------------------------------------------
// 设置面板
// ---------------------------------------------------------------------------

@Composable
private fun SettingsSheet(
    downloadPath: String,
    autoInstall: Boolean,
    onToggleAutoInstall: (Boolean) -> Unit,
    installPageFallback: Boolean,
    onToggleInstallPageFallback: (Boolean) -> Unit,
    onlineReceive: Boolean,
    onToggleOnlineReceive: (Boolean) -> Unit,
    onChangePath: () -> Unit,
    onDismiss: () -> Unit,
    onSave: () -> Unit,
) {
    val colors = Dsh.colors
    val type = Dsh.type
    val dimens = Dsh.dimens

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            Box(
                Modifier
                    .fillMaxSize()
                    .dshClickable(scaleOnPress = false, haptic = false) { onDismiss() },
            )
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 28.dp)
                    .clip(RoundedCornerShape(dimens.panelRadius))
                    .background(color = colors.canvas)
                    .border(1.dp, colors.hairlineStrong, RoundedCornerShape(dimens.panelRadius))
                    .padding(18.dp),
            ) {
                Text("文件中转设置", style = type.h2, color = colors.ink)
                Spacer(Modifier.height(16.dp))

                Text("下载位置", style = type.label, color = colors.inkFaint)
                Spacer(Modifier.height(8.dp))
                Text(
                    text = downloadPath,
                    style = type.monoSmall,
                    color = colors.inkMuted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(8.dp))
                Box(Modifier.dshClickable(scaleOnPress = false, onClick = onChangePath).padding(vertical = 4.dp)) {
                    Text("更改下载位置", style = type.rowTitle, color = colors.accent)
                }

                Spacer(Modifier.height(16.dp))
                DshDivider()
                Spacer(Modifier.height(14.dp))

                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("接收 APK 自动安装", style = type.rowTitle, color = colors.ink)
                        Spacer(Modifier.height(5.dp))
                        Text(
                            "通过 Shizuku 在后台静默安装",
                            style = type.caption,
                            color = colors.inkFaint,
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    DshSwitch(checked = autoInstall, onCheckedChange = onToggleAutoInstall)
                }

                Spacer(Modifier.height(14.dp))
                DshDivider()
                Spacer(Modifier.height(14.dp))

                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("系统安装页兜底", style = type.rowTitle, color = colors.ink)
                        Spacer(Modifier.height(5.dp))
                        Text(
                            "静默安装被系统拒绝时，改用系统安装页继续安装（需要你手动点确认）",
                            style = type.caption,
                            color = colors.inkFaint,
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    DshSwitch(checked = installPageFallback, onCheckedChange = onToggleInstallPageFallback)
                }

                Spacer(Modifier.height(14.dp))
                DshDivider()
                Spacer(Modifier.height(14.dp))

                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("在线接收", style = type.rowTitle, color = colors.ink)
                        Spacer(Modifier.height(5.dp))
                        Text(
                            if (onlineReceive) {
                                "已开启 · 离开这一页也能收，收到文件会在屏幕上方弹通知"
                            } else {
                                "打开后其他设备随时能扫到这台手机并发文件，不用先打开这一页"
                            },
                            style = type.caption,
                            color = colors.inkFaint,
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    DshSwitch(checked = onlineReceive, onCheckedChange = onToggleOnlineReceive)
                }

                Spacer(Modifier.height(20.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.weight(1f)) {
                        DshGhostButton("取消", onClick = onDismiss, modifier = Modifier.fillMaxWidth())
                    }
                    Box(Modifier.weight(1f)) {
                        DshGhostButton("保存", onClick = onSave, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 文本分享弹层
// ---------------------------------------------------------------------------

@Composable
private fun TextInputDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    val colors = Dsh.colors
    val type = Dsh.type
    val dimens = Dsh.dimens
    var text by remember { mutableStateOf("") }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(dimens.panelRadius))
                .background(color = colors.canvas)
                .border(1.dp, colors.hairlineStrong, RoundedCornerShape(dimens.panelRadius))
                .padding(18.dp),
        ) {
            Text("分享文本", style = type.h2, color = colors.ink)
            Spacer(Modifier.height(6.dp))
            Text("文本会写成一个 .txt 文件，通过链接分享", style = type.caption, color = colors.inkFaint)
            Spacer(Modifier.height(14.dp))

            Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 96.dp, max = 200.dp)
                    .clip(RoundedCornerShape(dimens.controlRadius))
                    .background(color = colors.panelTop.copy(alpha = 0.5f))
                    .border(1.dp, colors.hairline, RoundedCornerShape(dimens.controlRadius))
                    .padding(12.dp),
            ) {
                BasicTextField(
                    value = text,
                    onValueChange = { text = it },
                    textStyle = type.body.copy(color = colors.ink),
                    cursorBrush = SolidColor(colors.accent),
                    modifier = Modifier.fillMaxWidth(),
                    decorationBox = { inner ->
                        Box {
                            if (text.isEmpty()) {
                                Text("输入要分享的文本", style = type.body, color = colors.inkFaint)
                            }
                            inner()
                        }
                    },
                )
            }

            Spacer(Modifier.height(18.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.weight(1f)) {
                    DshGhostButton("取消", onClick = onDismiss, modifier = Modifier.fillMaxWidth())
                }
                Box(Modifier.weight(1f)) {
                    DshGhostButton("分享", onClick = { onConfirm(text) }, modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 应用选择弹层
// ---------------------------------------------------------------------------

@Composable
private fun AppPickerSheet(
    query: String,
    showSystemApps: Boolean,
    onToggleShowSystemApps: (Boolean) -> Unit,
    onQueryChange: (String) -> Unit,
    options: List<Pair<String, String>>,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
) {
    val colors = Dsh.colors
    val type = Dsh.type
    val dimens = Dsh.dimens

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            Box(
                Modifier
                    .fillMaxSize()
                    .dshClickable(scaleOnPress = false, haptic = false) { onDismiss() },
            )
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 28.dp)
                    .clip(RoundedCornerShape(dimens.panelRadius))
                    .background(color = colors.canvas)
                    .border(1.dp, colors.hairlineStrong, RoundedCornerShape(dimens.panelRadius))
                    .padding(18.dp),
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("选择应用", style = type.h2, color = colors.ink)
                    Spacer(Modifier.weight(1f))
                    // 标题右侧空白处：是否显示系统应用
                    Text(
                        text = "显示系统应用",
                        style = type.caption,
                        color = colors.inkFaint,
                    )
                    Spacer(Modifier.width(8.dp))
                    DshSwitch(checked = showSystemApps, onCheckedChange = onToggleShowSystemApps)
                }
                Spacer(Modifier.height(6.dp))
                Text("选中的应用会导出为 APK 分享", style = type.caption, color = colors.inkFaint)
                Spacer(Modifier.height(14.dp))

                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(42.dp)
                        .clip(RoundedCornerShape(dimens.controlRadius))
                        .background(color = colors.panelTop.copy(alpha = 0.5f))
                        .border(1.dp, colors.hairline, RoundedCornerShape(dimens.controlRadius))
                        .padding(horizontal = 12.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    BasicTextField(
                        value = query,
                        onValueChange = onQueryChange,
                        singleLine = true,
                        textStyle = type.rowTitle.copy(color = colors.ink),
                        cursorBrush = SolidColor(colors.accent),
                        modifier = Modifier.fillMaxWidth(),
                        decorationBox = { inner ->
                            Box {
                                if (query.isEmpty()) {
                                    Text("搜索应用", style = type.rowTitle, color = colors.inkFaint)
                                }
                                inner()
                            }
                        },
                    )
                }

                Spacer(Modifier.height(10.dp))

                Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    if (options.isEmpty()) {
                        Text("没有匹配的应用", style = type.caption, color = colors.inkFaint)
                    } else {
                        options.forEachIndexed { index, option ->
                            if (index > 0) DshDivider()
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .dshClickable(scaleOnPress = false) { onPick(option.second) }
                                    .padding(vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                AppIconTile(
                                    label = option.first,
                                    seed = option.second.hashCode(),
                                    tileSize = 30.dp,
                                )
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        text = option.first,
                                        style = type.rowTitle,
                                        color = colors.ink,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        text = option.second,
                                        style = type.monoSmall,
                                        color = colors.inkFaint,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(14.dp))
                DshGhostButton("取消", onClick = onDismiss, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}
