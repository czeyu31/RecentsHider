package com.example.hiderecents

import android.content.*
import android.os.Bundle
import android.os.Environment
import android.provider.OpenableColumns
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import fi.iki.elonen.NanoHTTPD
import org.json.JSONObject
import java.io.*
import java.net.*
import java.util.*
import java.util.concurrent.ConcurrentHashMap

class FileTransferActivity : AppCompatActivity() {

    private lateinit var tvUploadText: TextView
    private lateinit var tvUrl: TextView
    private lateinit var tvDeviceStatus: TextView
    private lateinit var tvDeviceCount: TextView
    private lateinit var taskListContainer: LinearLayout
    private lateinit var tvTodayTransfer: TextView
    private lateinit var tvAvailableSpace: TextView
    private lateinit var deviceListContainer: LinearLayout
    private lateinit var fileListContainer: LinearLayout

    private fun log(msg: String) {
        AppLogger.i("FileTransfer: $msg")
    }

    private var httpServer: SimpleHttpServer? = null
    private var udpListener: java.net.DatagramSocket? = null
    private var udpThread: Thread? = null
    private var serverPort = 0
    private val sharedFiles = mutableMapOf<String, File>()
    private val discoveredDevices = ConcurrentHashMap<String, DeviceInfo>()
    private var discoveryRunning = false
    private val tasks = mutableListOf<TransferTask>()
    private var totalTransferBytes = 0L

    private var shizukuBound = false
    private var taskHideService: android.os.IBinder? = null

    private val shizukuConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: android.os.IBinder) {
            taskHideService = service
            shizukuBound = true
            autoGrantMediaPermission()
        }
        override fun onServiceDisconnected(name: ComponentName) {
            taskHideService = null
            shizukuBound = false
        }
    }

    private val binderReceivedListener = rikka.shizuku.Shizuku.OnBinderReceivedListener {
        checkShizuku()
    }
    private val binderDeadListener = rikka.shizuku.Shizuku.OnBinderDeadListener {
        shizukuBound = false
        taskHideService = null
    }
    private val permissionListener = rikka.shizuku.Shizuku.OnRequestPermissionResultListener { _, grantResult ->
        if (grantResult == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            bindShizuku()
        }
    }

    private lateinit var prefs: SharedPreferences
    private lateinit var downloadDir: File
    private var autoInstallApk = false

    data class DeviceInfo(val ip: String, val alias: String, val port: Int, val deviceModel: String, val deviceType: String)
    data class TransferTask(
        val fileName: String,
        val target: String,
        val isSend: Boolean,
        val totalSize: Long,
        var transferred: Long = 0,
        var speed: Long = 0,
        var status: String = "waiting",
        var filePath: String = ""
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_file_transfer)

        prefs = getSharedPreferences("file_transfer_settings", MODE_PRIVATE)
        autoInstallApk = prefs.getBoolean("auto_install_apk", false)
        // Clear any old saved path
        prefs.edit().remove("download_path").apply()

        // Check and request storage permission
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            if (!android.os.Environment.isExternalStorageManager()) {
                AppLogger.i("Requesting MANAGE_EXTERNAL_STORAGE permission")
                try {
                    val intent = Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                    intent.data = android.net.Uri.parse("package:$packageName")
                    startActivity(intent)
                } catch (_: Exception) {
                    val intent = Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                    startActivity(intent)
                }
            }
        }

        // Use Download directory
        downloadDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
        if (!downloadDir.exists()) downloadDir.mkdirs()

        AppLogger.i("FileTransfer downloadDir=${downloadDir.absolutePath}, exists=${downloadDir.exists()}, canWrite=${downloadDir.canWrite()}")
        AppLogger.i("FileTransfer isExternalStorageManager=${android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R || android.os.Environment.isExternalStorageManager()}")

        tvUploadText = findViewById(R.id.tvUploadText)
        tvUrl = findViewById(R.id.tvUrl)
        tvDeviceStatus = findViewById(R.id.tvDeviceStatus)
        tvDeviceCount = findViewById(R.id.tvDeviceCount)
        taskListContainer = findViewById(R.id.taskListContainer)
        tvTodayTransfer = findViewById(R.id.tvTodayTransfer)
        tvAvailableSpace = findViewById(R.id.tvAvailableSpace)
        deviceListContainer = findViewById(R.id.deviceListContainer)
        fileListContainer = findViewById(R.id.fileListContainer)

        findViewById<ImageView>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<ImageView>(R.id.btnScan).setOnClickListener { startDiscovery() }
        findViewById<ImageView>(R.id.btnSettings).setOnClickListener { showSettings() }
        findViewById<FrameLayout>(R.id.uploadArea).setOnClickListener { pickFile() }
        findViewById<TextView>(R.id.btnQuickConnect).setOnClickListener { startDiscovery() }

        findViewById<LinearLayout>(R.id.btnFile).setOnClickListener { pickFile() }
        findViewById<LinearLayout>(R.id.btnMedia).setOnClickListener { pickMedia() }
        findViewById<LinearLayout>(R.id.btnClipboard).setOnClickListener { shareClipboard() }
        findViewById<LinearLayout>(R.id.btnText).setOnClickListener { shareText() }
        findViewById<LinearLayout>(R.id.btnFolder).setOnClickListener { pickFolder() }
        findViewById<LinearLayout>(R.id.btnApp).setOnClickListener { shareApp() }
        findViewById<TextView>(R.id.btnReceive).setOnClickListener { receiveFromUrl() }

        startServer()
        updateAvailableSpace()
        setupShizuku()
        startDiscovery()
    }

    override fun onResume() {
        super.onResume()
        if (!discoveryRunning) startDiscovery()
    }

    override fun onPause() {
        super.onPause()
        discoveryRunning = false
    }

    private fun setupShizuku() {
        rikka.shizuku.Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
        rikka.shizuku.Shizuku.addBinderDeadListener(binderDeadListener)
        rikka.shizuku.Shizuku.addRequestPermissionResultListener(permissionListener)
        checkShizuku()
    }

    private fun checkShizuku() {
        if (!rikka.shizuku.Shizuku.pingBinder() || rikka.shizuku.Shizuku.isPreV11()) return
        if (rikka.shizuku.Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            bindShizuku()
        } else {
            rikka.shizuku.Shizuku.requestPermission(1002)
        }
    }

    private fun bindShizuku() {
        try {
            rikka.shizuku.Shizuku.bindUserService(
                rikka.shizuku.Shizuku.UserServiceArgs(ComponentName(this, TaskHideService::class.java))
                    .daemon(false).processNameSuffix("taskhide").debuggable(true).version(1),
                shizukuConnection
            )
        } catch (_: Exception) {}
    }

    private fun autoGrantMediaPermission() {
        Thread {
            try {
                val binder = taskHideService ?: return@Thread
                val cmds = listOf(
                    "appops set $packageName READ_MEDIA_IMAGES allow",
                    "appops set $packageName READ_MEDIA_VIDEO allow",
                    "appops set $packageName READ_MEDIA_AUDIO allow",
                    "appops set $packageName READ_EXTERNAL_STORAGE allow",
                    "appops set $packageName WRITE_EXTERNAL_STORAGE allow"
                )
                for (cmd in cmds) {
                    val data = android.os.Parcel.obtain()
                    val reply = android.os.Parcel.obtain()
                    try {
                        data.writeInterfaceToken(ITaskHideService.DESCRIPTOR)
                        data.writeString(cmd)
                        binder.transact(ITaskHideService.TRANSACTION_execCommand, data, reply, 0)
                        reply.readException()
                        reply.readInt()
                        AppLogger.i("autoGrant: $cmd")
                    } finally {
                        data.recycle()
                        reply.recycle()
                    }
                }
                AppLogger.i("autoGrant: media permissions done")
            } catch (e: Exception) {
                AppLogger.e("autoGrant failed", e)
            }
        }.start()
    }

    private fun showSettings() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(64, 32, 64, 16)
        }

        val tvPath = TextView(this).apply {
            text = "下载位置: ${downloadDir.absolutePath}"
            textSize = 13f
            setTextColor(getColor(R.color.on_surface))
        }
        layout.addView(tvPath)

        val btnChangePath = TextView(this).apply {
            text = "更改下载位置"
            textSize = 14f
            setTextColor(getColor(R.color.primary))
            setPadding(0, 24, 0, 24)
        }
        layout.addView(btnChangePath)

        val switchAutoInstall = Switch(this).apply {
            text = "接收APK自动安装"
            textSize = 14f
            setTextColor(getColor(R.color.on_surface))
            isChecked = autoInstallApk
        }
        layout.addView(switchAutoInstall)

        val tvPathInfo = TextView(this).apply {
            text = "使用Shizuku在后台静默安装APK"
            textSize = 11f
            setTextColor(getColor(R.color.on_surface_variant))
            setPadding(0, 4, 0, 0)
        }
        layout.addView(tvPathInfo)

        btnChangePath.setOnClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
            startActivityForResult(intent, 1002)
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("文件中转设置")
            .setView(layout)
            .setPositiveButton("保存") { _, _ ->
                autoInstallApk = switchAutoInstall.isChecked
                prefs.edit()
                    .putBoolean("auto_install_apk", autoInstallApk)
                    .putString("download_path", downloadDir.absolutePath)
                    .apply()
                Toast.makeText(this, "设置已保存", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun getDeviceIpAddress(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val intf = interfaces.nextElement()
                if (intf.isLoopback || !intf.isUp) continue
                val addrs = intf.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        return addr.hostAddress ?: "未连接WiFi"
                    }
                }
            }
        } catch (_: Exception) {}
        return "未连接WiFi"
    }

    private fun startServer() {
        try {
            val socket = java.net.ServerSocket(0)
            serverPort = socket.localPort
            socket.close()
            httpServer = SimpleHttpServer(serverPort)
            httpServer?.start()
            AppLogger.i("HTTP server started on port $serverPort")
        } catch (_: Exception) {}

        // Start UDP discovery listener (only if not already running)
        if (udpListener == null) {
            try {
                udpListener = java.net.DatagramSocket(53317)
                udpThread = Thread {
                    try {
                        val udpSocket = udpListener ?: return@Thread
                        udpSocket.soTimeout = 0
                        AppLogger.i("UDP listener started on port 53317")
                        while (!Thread.currentThread().isInterrupted && !udpSocket.isClosed) {
                            try {
                                val buf = ByteArray(8192)
                                val packet = java.net.DatagramPacket(buf, buf.size)
                                udpSocket.receive(packet)
                                val msg = String(packet.data, 0, packet.length)
                                val senderIp = packet.address.hostAddress ?: continue

                                // Respond with our info
                                val response = JSONObject().apply {
                                    put("alias", android.os.Build.MODEL)
                                    put("port", serverPort)
                                    put("protocol", "http")
                                    put("version", "2.1")
                                    put("deviceModel", android.os.Build.MODEL)
                                    put("deviceType", "mobile")
                                    put("ip", getDeviceIpAddress())
                                }.toString()
                                val responseData = response.toByteArray()
                                val responsePacket = java.net.DatagramPacket(responseData, responseData.size, packet.address, packet.port)
                                udpSocket.send(responsePacket)

                                // Add sender as discovered device (filter out self)
                                try {
                                    val json = JSONObject(msg)
                                    val deviceIp = senderIp
                                    val port = json.optInt("port", 53317)
                                    val alias = json.optString("alias", "Device")
                                    val model = json.optString("deviceModel", "")
                                    val myIps = getAllIpAddresses()
                                    if (!myIps.contains(deviceIp) && !discoveredDevices.containsKey(deviceIp)) {
                                        discoveredDevices[deviceIp] = DeviceInfo(deviceIp, alias, port, model, "mobile")
                                        AppLogger.i("UDP discovered: $alias at $deviceIp:$port")
                                        runOnUiThread { updateDeviceList() }
                                    }
                                } catch (_: Exception) {}
                            } catch (_: java.net.SocketTimeoutException) {}
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

    private fun getAllIpAddresses(): List<String> {
        val ips = mutableListOf<String>()
        try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val intf = interfaces.nextElement()
                val addrs = intf.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    ips.add(addr.hostAddress ?: "")
                }
            }
        } catch (_: Exception) {}
        return ips
    }

    private fun pickFile() {
        val intent = Intent(Intent.ACTION_GET_CONTENT)
        intent.type = "*/*"
        intent.addCategory(Intent.CATEGORY_OPENABLE)
        startActivityForResult(intent, 1001)
    }

    private fun pickMedia() {
        val intent = Intent(Intent.ACTION_GET_CONTENT)
        intent.type = "*/*"
        intent.putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/*", "video/*", "audio/*"))
        intent.addCategory(Intent.CATEGORY_OPENABLE)
        startActivityForResult(intent, 1001)
    }

    private fun pickFolder() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivityForResult(intent, 1003)
    }

    private fun shareClipboard() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = clipboard.primaryClip
        if (clip != null && clip.itemCount > 0) {
            val text = clip.getItemAt(0).text?.toString()
            if (!text.isNullOrEmpty()) {
                val file = File(cacheDir, "clipboard_${System.currentTimeMillis()}.txt")
                file.writeText(text)
                val fileId = UUID.randomUUID().toString().take(8)
                sharedFiles[fileId] = file
                addTask(file.name, "剪贴板内容", true, file.length())
                showShareLink(fileId)
                Toast.makeText(this, "剪贴板内容已添加", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "剪贴板为空", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun shareText() {
        val input = EditText(this)
        input.hint = "输入要分享的文本"
        input.setPadding(48, 32, 48, 32)
        MaterialAlertDialogBuilder(this)
            .setTitle("分享文本")
            .setView(input)
            .setPositiveButton("分享") { _, _ ->
                val text = input.text.toString()
                if (text.isNotEmpty()) {
                    val file = File(cacheDir, "text_${System.currentTimeMillis()}.txt")
                    file.writeText(text)
                    val fileId = UUID.randomUUID().toString().take(8)
                    sharedFiles[fileId] = file
                    addTask(file.name, "文本内容", true, file.length())
                    showShareLink(fileId)
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun shareApp() {
        val pm = packageManager
        val packages = pm.getInstalledApplications(0).toMutableList()
        val allAppNames = packages.map { pm.getApplicationLabel(it).toString() }.toMutableList()

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 16, 48, 0)
        }

        val searchInput = EditText(this).apply {
            hint = "搜索应用..."
            textSize = 14f
            setPadding(32, 24, 32, 24)
            setBackgroundResource(R.drawable.bg_rounded_input)
        }
        layout.addView(searchInput)

        val listView = ListView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1200)
        }
        layout.addView(listView)

        var filteredPackages = packages.toList()
        var filteredNames = allAppNames.toList()

        fun updateFilter(query: String) {
            if (query.isEmpty()) {
                filteredPackages = packages.toList()
                filteredNames = allAppNames.toList()
            } else {
                val lower = query.lowercase()
                filteredPackages = packages.filterIndexed { index, _ ->
                    allAppNames[index].lowercase().contains(lower) || packages[index].packageName.lowercase().contains(lower)
                }
                filteredNames = filteredPackages.map { pm.getApplicationLabel(it).toString() }
            }
            listView.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, filteredNames)
        }

        updateFilter("")

        searchInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) { updateFilter(s?.toString() ?: "") }
        })

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("选择应用")
            .setView(layout)
            .setNegativeButton("取消", null)
            .create()

        listView.setOnItemClickListener { _, _, position, _ ->
            val appInfo = filteredPackages[position]
            val apkFile = File(appInfo.sourceDir)

            // Copy APK to cache to ensure it's readable
            val cachedApk = File(cacheDir, "${pm.getApplicationLabel(appInfo)}.apk")
            try {
                apkFile.inputStream().use { input ->
                    cachedApk.outputStream().use { output -> input.copyTo(output) }
                }
            } catch (_: Exception) {}

            val fileId = UUID.randomUUID().toString().take(8)
            sharedFiles[fileId] = if (cachedApk.exists()) cachedApk else apkFile
            addTask(apkFile.name, "应用APK", true, cachedApk.length())
            showShareLink(fileId)
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun showShareLink(fileId: String) {
        val ip = getDeviceIpAddress()
        val url = "http://$ip:$serverPort/$fileId"
        tvUrl.text = url
        tvUrl.visibility = View.VISIBLE
        tvUploadText.text = "文件已添加"
        tvUrl.setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("url", url))
            Toast.makeText(this, "链接已复制", Toast.LENGTH_SHORT).show()
        }
        updateFileList()
    }

    private fun updateFileList() {
        fileListContainer.removeAllViews()
        for ((id, file) in sharedFiles) {
            val row = layoutInflater.inflate(R.layout.item_shared_file, fileListContainer, false)
            row.findViewById<TextView>(R.id.tvFileName).text = file.name
            row.findViewById<TextView>(R.id.tvFileSize).text = formatSize(file.length())

            // Load APK icon
            val ivIcon = row.findViewById<ImageView>(R.id.ivFileIcon)
            if (file.name.endsWith(".apk", ignoreCase = true)) {
                try {
                    val pm = packageManager
                    val packageInfo = pm.getPackageArchiveInfo(file.absolutePath, android.content.pm.PackageManager.GET_META_DATA)
                    if (packageInfo != null) {
                        packageInfo.applicationInfo.sourceDir = file.absolutePath
                        packageInfo.applicationInfo.publicSourceDir = file.absolutePath
                        val icon = packageInfo.applicationInfo.loadIcon(pm)
                        if (icon != null) {
                            ivIcon.setImageDrawable(icon)
                            ivIcon.imageTintList = null
                            ivIcon.clearColorFilter()
                        }
                    }
                } catch (_: Exception) {}
            }

            row.findViewById<ImageView>(R.id.btnRemove).setOnClickListener {
                sharedFiles.remove(id)
                updateFileList()
                if (sharedFiles.isEmpty()) tvUrl.visibility = View.GONE
            }
            fileListContainer.addView(row)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 1001 && resultCode == RESULT_OK) {
            data?.data?.let { uri ->
                try {
                    val cursor = contentResolver.query(uri, null, null, null, null)
                    var fileName = "file_${System.currentTimeMillis()}"
                    cursor?.use {
                        if (it.moveToFirst()) {
                            val nameIndex = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                            if (nameIndex >= 0) fileName = it.getString(nameIndex)
                        }
                    }

                    val cacheFile = File(cacheDir, fileName)
                    contentResolver.openInputStream(uri)?.use { input ->
                        cacheFile.outputStream().use { output -> input.copyTo(output) }
                    }

                    val fileId = UUID.randomUUID().toString().take(8)
                    sharedFiles[fileId] = cacheFile
                    addTask(fileName, "文件分享", true, cacheFile.length())
                    showShareLink(fileId)
                } catch (e: Exception) {
                    Toast.makeText(this, "添加失败: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
        if (requestCode == 1002 && resultCode == RESULT_OK) {
            data?.data?.let { uri ->
                try {
                    val path = uri.path?.replace("/tree/primary:", "/storage/emulated/0/") ?: ""
                    if (path.isNotEmpty()) {
                        downloadDir = File(path)
                        if (!downloadDir.exists()) downloadDir.mkdirs()
                    }
                } catch (_: Exception) {}
            }
        }
        if (requestCode == 1003 && resultCode == RESULT_OK) {
            data?.data?.let { uri ->
                try {
                    val docUri = android.provider.DocumentsContract.buildDocumentUriUsingTree(
                        uri, android.provider.DocumentsContract.getTreeDocumentId(uri)
                    )
                    val childrenUri = android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(
                        uri, android.provider.DocumentsContract.getTreeDocumentId(uri)
                    )
                    val cursor = contentResolver.query(childrenUri, arrayOf(
                        android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                        android.provider.DocumentsContract.Document.COLUMN_SIZE
                    ), null, null, null)

                    var count = 0
                    cursor?.use {
                        while (it.moveToNext()) {
                            val docId = it.getString(0)
                            val name = it.getString(1)
                            val fileUri = android.provider.DocumentsContract.buildDocumentUriUsingTree(uri, docId)
                            val cacheFile = File(cacheDir, name)
                            try {
                                contentResolver.openInputStream(fileUri)?.use { input ->
                                    cacheFile.outputStream().use { output -> input.copyTo(output) }
                                }
                                val fileId = UUID.randomUUID().toString().take(8)
                                sharedFiles[fileId] = cacheFile
                                count++
                            } catch (_: Exception) {}
                        }
                    }

                    if (count > 0) {
                        Toast.makeText(this, "已添加 $count 个文件", Toast.LENGTH_SHORT).show()
                        showShareLink(sharedFiles.keys.last())
                    } else {
                        Toast.makeText(this, "文件夹为空", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    Toast.makeText(this, "添加失败: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun receiveFromUrl() {
        val etUrl = findViewById<EditText>(R.id.etReceiveUrl)
        var url = etUrl.text.toString().trim()
        if (url.isEmpty()) {
            Toast.makeText(this, "请输入链接", Toast.LENGTH_SHORT).show()
            return
        }

        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "http://$url"
        }

        log("开始接收: $url")
        Toast.makeText(this, "开始接收...", Toast.LENGTH_SHORT).show()

        Thread {
            try {
                log("正在连接...")
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
                log("响应码: $responseCode")
                if (responseCode != 200) {
                    runOnUiThread {
                        Toast.makeText(this, "服务器返回错误: $responseCode", Toast.LENGTH_SHORT).show()
                    }
                    return@Thread
                }

                val contentDisposition = connection.getHeaderField("Content-Disposition")
                var fileName = "received_${System.currentTimeMillis()}"
                if (contentDisposition != null) {
                    val match = Regex("filename=\"?([^\"]+)\"?").find(contentDisposition)
                    if (match != null) fileName = match.groupValues[1]
                } else {
                    val path = urlObj.path
                    if (path.isNotEmpty() && path != "/") {
                        fileName = path.substringAfterLast("/").substringBefore("?").ifEmpty { fileName }
                    }
                }
                // Sanitize filename - remove invalid characters
                fileName = fileName.replace(Regex("[^a-zA-Z0-9._\\-\\x{4e00}-\\x{9fff}]"), "_")
                if (fileName.length > 200) fileName = fileName.take(200)

                val totalSize = connection.contentLength.toLong()
                log("文件名: $fileName, 大小: ${formatSize(totalSize)}")
                if (!downloadDir.exists()) downloadDir.mkdirs()
                val outputFile = File(downloadDir, fileName)

                val task = TransferTask(fileName, urlObj.host, false, totalSize)
                task.status = "transferring"
                runOnUiThread { tasks.add(task); updateTaskList() }

                log("开始下载...")
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
                        runOnUiThread { updateTaskList() }
                    }
                }

                outputStream.flush()
                outputStream.close()
                inputStream.close()

                // Verify file integrity
                val downloadedMd5 = outputFile.inputStream().use { 
                    val md = java.security.MessageDigest.getInstance("MD5")
                    val buffer = ByteArray(32768)
                    var read: Int
                    while (it.read(buffer).also { r -> read = r } != -1) md.update(buffer, 0, read)
                    md.digest().joinToString("") { "%02x".format(it) }
                }
                log("下载完成: ${formatSize(totalRead)}, MD5=$downloadedMd5")
                AppLogger.i("FileTransfer downloaded MD5=$downloadedMd5, size=${outputFile.length()}")

                totalTransferBytes += totalRead

                if (autoInstallApk && fileName.endsWith(".apk", ignoreCase = true)) {
                    log("正在安装APK...")
                    task.status = "installing"
                    runOnUiThread { updateTaskList() }
                    val installed = installApkViaShizuku(outputFile)
                    task.status = if (installed) "installed" else "completed"
                    log(if (installed) "APK安装成功" else "APK安装失败")
                } else {
                    task.status = "completed"
                }

                task.transferred = totalRead
                task.speed = 0
                runOnUiThread {
                    updateTaskList()
                    updateStats()
                    Toast.makeText(this, "已保存到: ${outputFile.name}", Toast.LENGTH_LONG).show()
                }
            } catch (e: java.net.ConnectException) {
                log("连接被拒绝: ${e.message}")
                runOnUiThread { Toast.makeText(this, "连接被拒绝，请确认发送方在线", Toast.LENGTH_SHORT).show() }
            } catch (e: java.net.SocketTimeoutException) {
                log("连接超时: ${e.message}")
                runOnUiThread { Toast.makeText(this, "连接超时，请检查网络", Toast.LENGTH_SHORT).show() }
            } catch (e: java.net.UnknownHostException) {
                log("无法解析地址: ${e.message}")
                runOnUiThread { Toast.makeText(this, "无法解析地址，请检查链接", Toast.LENGTH_SHORT).show() }
            } catch (e: Exception) {
                log("接收失败: ${e.message}")
                runOnUiThread { Toast.makeText(this, "接收失败: ${e.message}", Toast.LENGTH_SHORT).show() }
            }
        }.start()
    }

    private fun installApkViaShizuku(apkFile: File): Boolean {
        AppLogger.i("installApk: file=${apkFile.absolutePath}, exists=${apkFile.exists()}, size=${apkFile.length()}")
        AppLogger.i("installApk: shizukuBound=$shizukuBound, taskHideService=${taskHideService != null}")

        // Method 1: cat pipe (most reliable with Shizuku)
        try {
            val binder = taskHideService
            if (binder != null) {
                val data = android.os.Parcel.obtain()
                val reply = android.os.Parcel.obtain()
                try {
                    val cmd = "cat ${apkFile.absolutePath} | pm install -S ${apkFile.length()}"
                    AppLogger.i("installApk: $cmd")
                    data.writeInterfaceToken(ITaskHideService.DESCRIPTOR)
                    data.writeString(cmd)
                    binder.transact(ITaskHideService.TRANSACTION_execCommand, data, reply, 0)
                    reply.readException()
                    val success = reply.readInt() != 0
                    val output = reply.readString() ?: ""
                    AppLogger.i("installApk: success=$success, output=$output")
                    if (success || output.contains("Success")) return true
                } finally {
                    data.recycle()
                    reply.recycle()
                }
            } else {
                AppLogger.e("installApk: taskHideService is null, Shizuku not bound")
            }
        } catch (e: Exception) {
            AppLogger.e("installApk: Shizuku failed", e)
        }

        // Method 2: System installer fallback
        AppLogger.i("installApk: falling back to system installer")
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                if (!packageManager.canRequestPackageInstalls()) {
                    val intent = Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                    intent.data = android.net.Uri.parse("package:$packageName")
                    startActivity(intent)
                    return false
                }
            }
            val uri = androidx.core.content.FileProvider.getUriForFile(this, "${packageName}.fileprovider", apkFile)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
            return true
        } catch (e: Exception) {
            AppLogger.e("installApk: system installer failed", e)
        }

        return false
    }

    private fun addTask(fileName: String, target: String, isSend: Boolean, size: Long, filePath: String = "") {
        val task = TransferTask(fileName, target, isSend, size)
        task.status = "completed"
        task.transferred = size
        task.filePath = filePath
        tasks.add(task)
        totalTransferBytes += size
        updateTaskList()
        updateStats()
    }

    private fun updateTaskList() {
        taskListContainer.removeAllViews()
        for (task in tasks.reversed()) {
            val row = layoutInflater.inflate(R.layout.item_transfer_task, taskListContainer, false)
            val tvName = row.findViewById<TextView>(R.id.tvTaskName)
            val tvTarget = row.findViewById<TextView>(R.id.tvTaskTarget)
            val tvSpeed = row.findViewById<TextView>(R.id.tvTaskSpeed)
            val progressBar = row.findViewById<View>(R.id.progressBar)
            val progressContainer = row.findViewById<View>(R.id.progressContainer)
            val tvProgressSize = row.findViewById<TextView>(R.id.tvProgressSize)
            val tvProgressTime = row.findViewById<TextView>(R.id.tvProgressTime)

            tvName.text = task.fileName
            tvTarget.text = if (task.isSend) "发送到: ${task.target}" else "来自: ${task.target}"

            // Load APK icon for received files
            val ivTaskIcon = row.findViewById<ImageView>(R.id.ivTaskIcon)
            if (task.filePath.isNotEmpty() && task.fileName.endsWith(".apk", ignoreCase = true)) {
                try {
                    val file = File(task.filePath)
                    AppLogger.i("Loading APK icon: ${task.filePath}, exists=${file.exists()}, size=${file.length()}")
                    val pm = packageManager
                    val packageInfo = pm.getPackageArchiveInfo(task.filePath, android.content.pm.PackageManager.GET_META_DATA)
                    if (packageInfo != null) {
                        AppLogger.i("APK package: ${packageInfo.packageName}")
                        packageInfo.applicationInfo.sourceDir = task.filePath
                        packageInfo.applicationInfo.publicSourceDir = task.filePath
                        val icon = packageInfo.applicationInfo.loadIcon(pm)
                        if (icon != null) {
                            ivTaskIcon.setImageDrawable(icon)
                            ivTaskIcon.background = null
                            ivTaskIcon.setPadding(0, 0, 0, 0)
                            ivTaskIcon.imageTintList = null
                            ivTaskIcon.clearColorFilter()
                            AppLogger.i("APK icon loaded successfully")
                        } else {
                            AppLogger.e("APK icon is null")
                        }
                    } else {
                        AppLogger.e("getPackageArchiveInfo returned null")
                    }
                } catch (e: Exception) {
                    AppLogger.e("APK icon load failed", e)
                }
            }

            // Click to open file
            if (task.filePath.isNotEmpty() && task.status in listOf("completed", "installed")) {
                row.setOnClickListener {
                    try {
                        val file = File(task.filePath)
                        if (file.exists()) {
                            val uri = androidx.core.content.FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
                            val mime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension) ?: "*/*"
                            val intent = Intent(Intent.ACTION_VIEW).apply {
                                setDataAndType(uri, mime)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            startActivity(intent)
                        } else {
                            Toast.makeText(this, "文件不存在", Toast.LENGTH_SHORT).show()
                        }
                    } catch (e: Exception) {
                        Toast.makeText(this, "无法打开: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
                row.isClickable = true
            }

            when (task.status) {
                "completed" -> {
                    tvSpeed.text = "已完成"
                    tvSpeed.setTextColor(getColor(R.color.on_surface_variant))
                    progressContainer.visibility = View.GONE
                }
                "installed" -> {
                    tvSpeed.text = "已安装"
                    tvSpeed.setTextColor(getColor(R.color.primary))
                    progressContainer.visibility = View.GONE
                }
                "installing" -> {
                    tvSpeed.text = "安装中..."
                    tvSpeed.setTextColor(getColor(R.color.tertiary))
                    progressContainer.visibility = View.GONE
                }
                "transferring" -> {
                    tvSpeed.text = "${formatSize(task.speed)}/s"
                    tvSpeed.setTextColor(getColor(R.color.primary))
                    progressContainer.visibility = View.VISIBLE
                    val percent = if (task.totalSize > 0) (task.transferred * 100 / task.totalSize).toInt() else 0
                    progressBar.layoutParams = progressBar.layoutParams.apply {
                        width = (progressBar.parent as View).width * percent / 100
                    }
                    tvProgressSize.text = "${formatSize(task.transferred)} / ${formatSize(task.totalSize)}"
                    tvProgressTime.text = "${percent}%"
                }
                else -> {
                    tvSpeed.text = "等待中"
                    tvSpeed.setTextColor(getColor(R.color.on_surface_variant))
                    progressContainer.visibility = View.VISIBLE
                    tvProgressSize.text = "0 / ${formatSize(task.totalSize)}"
                    tvProgressTime.text = "队列中"
                }
            }
            taskListContainer.addView(row)
        }
    }

    private fun updateStats() {
        tvTodayTransfer.text = formatSize(totalTransferBytes)
    }

    private fun updateAvailableSpace() {
        try {
            val stat = android.os.StatFs(downloadDir.absolutePath)
            val available = stat.availableBlocksLong * stat.blockSizeLong
            tvAvailableSpace.text = formatSize(available)
        } catch (_: Exception) {
            tvAvailableSpace.text = "未知"
        }
    }

    private fun startDiscovery() {
        if (discoveryRunning) return
        discoveryRunning = true
        discoveredDevices.clear()
        tvDeviceStatus.text = "扫描中..."
        tvDeviceCount.text = "正在搜索 (WiFi + 蓝牙)"

        // Bluetooth discovery
        try {
            val btAdapter = android.bluetooth.BluetoothAdapter.getDefaultAdapter()
            if (btAdapter != null && btAdapter.isEnabled) {
                // Get paired devices
                if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S || checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    val pairedDevices = btAdapter.bondedDevices
                    for (device in pairedDevices) {
                        val name = device.name ?: "Bluetooth Device"
                        val address = device.address
                        if (!discoveredDevices.containsKey(address)) {
                            discoveredDevices[address] = DeviceInfo(address, name, 0, "bluetooth", "bluetooth")
                            AppLogger.i("BT paired: $name ($address)")
                        }
                    }
                    runOnUiThread { updateDeviceList() }
                }
            }
        } catch (e: Exception) {
            AppLogger.e("Bluetooth discovery failed", e)
        }

        Thread {
            val ip = getDeviceIpAddress()
            val myIps = getAllIpAddresses()
            if (ip == "未连接WiFi") {
                runOnUiThread { tvDeviceStatus.text = "未连接WiFi"; tvDeviceCount.text = "请连接WiFi"; discoveryRunning = false }
                return@Thread
            }

            // Method 1: UDP broadcast on LocalSend port 53317
            try {
                val socket = java.net.DatagramSocket(null)
                socket.broadcast = true
                socket.soTimeout = 3000

                val announce = JSONObject().apply {
                    put("alias", android.os.Build.MODEL)
                    put("port", serverPort)
                    put("protocol", "http")
                    put("version", "2.1")
                    put("deviceModel", android.os.Build.MODEL)
                    put("deviceType", "mobile")
                }.toString()

                val data = announce.toByteArray()
                val group = java.net.InetAddress.getByName("255.255.255.255")
                val packet = java.net.DatagramPacket(data, data.size, group, 53317)
                // Send multiple broadcasts for reliability
                for (attempt in 1..3) {
                    socket.send(packet)
                    Thread.sleep(200)
                }
                AppLogger.i("Discovery: sent UDP broadcast on port 53317")

                // Listen for responses
                val buf = ByteArray(8192)
                val endTime = System.currentTimeMillis() + 3000
                while (System.currentTimeMillis() < endTime) {
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
                        // Only add if not already discovered
                        if (!discoveredDevices.containsKey(deviceIp)) {
                            synchronized(discoveredDevices) {
                                discoveredDevices[deviceIp] = DeviceInfo(deviceIp, alias, port, model, "mobile")
                            }
                            AppLogger.i("Discovery: found $alias at $deviceIp:$port")
                        }
                        } catch (_: Exception) {}
                    } catch (_: java.net.SocketTimeoutException) {}
                }
                socket.close()
            } catch (e: Exception) {
                AppLogger.e("Discovery UDP failed", e)
            }

            // Method 2: Direct HTTP probe on common ports
            val subnet = ip.substringBeforeLast(".")
            val ports = intArrayOf(serverPort, 53317, 8080)
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
                                // Check if it's our app
                                try {
                                    val conn = URL("http://$addr:$port/").openConnection() as HttpURLConnection
                                    conn.connectTimeout = 1000
                                    conn.readTimeout = 1000
                                    if (conn.responseCode == 200) {
                                        val body = conn.inputStream.bufferedReader().readText()
                                        if (body.contains("System Tool") || body.contains("localsend") || body.contains("文件中转")) {
                                            synchronized(discoveredDevices) {
                                                discoveredDevices[addr] = DeviceInfo(addr, "设备 $addr", port, "", "mobile")
                                            }
                                        }
                                    }
                                } catch (_: Exception) {}
                            } catch (_: Exception) {}
                        }
                    } catch (_: Exception) {}
                }
            }
            threads.forEach { it.start() }
            threads.forEach { it.join(3000) }

            runOnUiThread {
                discoveryRunning = false
                if (discoveredDevices.isEmpty()) {
                    tvDeviceStatus.text = "未发现设备"
                    tvDeviceCount.text = "请确认设备在同一WiFi"
                } else {
                    tvDeviceStatus.text = "发现设备"
                    tvDeviceCount.text = "${discoveredDevices.size} 个设备在线"
                }
                updateDeviceList()
            }
        }.start()
    }

    private fun updateDeviceList() {
        deviceListContainer.removeAllViews()
        if (discoveredDevices.isEmpty()) return

        for ((addr, device) in discoveredDevices) {
            val row = layoutInflater.inflate(R.layout.item_nearby_device, deviceListContainer, false)
            row.findViewById<TextView>(R.id.tvDeviceName).text = device.alias
            row.findViewById<TextView>(R.id.tvDeviceInfo).text = "${device.ip}:${device.port}"
            row.setOnClickListener { sendFileToDevice(device) }
            deviceListContainer.addView(row)
        }
    }

    private fun sendFileToDevice(device: DeviceInfo) {
        if (sharedFiles.isEmpty()) {
            Toast.makeText(this, "请先添加文件", Toast.LENGTH_SHORT).show()
            return
        }

        val fileNames = sharedFiles.values.joinToString(", ") { it.name }
        MaterialAlertDialogBuilder(this)
            .setTitle("发送到 ${device.alias}")
            .setMessage("发送 $fileNames？")
            .setPositiveButton("发送") { _, _ ->
                Thread {
                    for ((id, file) in sharedFiles) {
                        try {
                            val url = URL("http://${device.ip}:${device.port}/")
                            // Upload file via multipart POST
                            val boundary = "----SystemTool${System.currentTimeMillis()}"
                            val conn = url.openConnection() as HttpURLConnection
                            conn.requestMethod = "POST"
                            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
                            conn.setRequestProperty("X-File-Name", java.net.URLEncoder.encode(file.name, "UTF-8"))
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

                            val responseCode = conn.responseCode
                            AppLogger.i("Send to ${device.ip}: response=$responseCode")

                            outputStream.close()
                            conn.disconnect()
                        } catch (e: Exception) {
                            AppLogger.e("Send failed to ${device.ip}", e)
                        }
                    }
                    runOnUiThread {
                        Toast.makeText(this, "发送完成", Toast.LENGTH_SHORT).show()
                        addTask(fileNames, device.alias, true, sharedFiles.values.sumOf { it.length() })
                    }
                }.start()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun formatSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> String.format("%.1f KB", bytes / 1024.0)
            bytes < 1024 * 1024 * 1024 -> String.format("%.1f MB", bytes / 1048576.0)
            else -> String.format("%.2f GB", bytes / 1073741824.0)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        httpServer?.stop()
        try {
            udpThread?.interrupt()
            udpListener?.close()
            udpListener = null
        } catch (_: Exception) {}
        rikka.shizuku.Shizuku.removeBinderReceivedListener(binderReceivedListener)
        rikka.shizuku.Shizuku.removeBinderDeadListener(binderDeadListener)
        rikka.shizuku.Shizuku.removeRequestPermissionResultListener(permissionListener)
        try {
            rikka.shizuku.Shizuku.unbindUserService(
                rikka.shizuku.Shizuku.UserServiceArgs(ComponentName(this, TaskHideService::class.java)),
                shizukuConnection, true
            )
        } catch (_: Exception) {}
    }

    inner class SimpleHttpServer(port: Int) : NanoHTTPD("0.0.0.0", port) {
        override fun serve(session: IHTTPSession): Response {
            val uri = session.uri.removePrefix("/")
            AppLogger.i("HTTP ${session.method} $uri from ${session.remoteIpAddress}")

            // Handle file upload via POST
            if (session.method == Method.POST) {
                try {
                    // Get filename from custom header
                    var fileName = "received_${System.currentTimeMillis()}"
                    val encodedName = session.headers["x-file-name"]
                    if (!encodedName.isNullOrEmpty()) {
                        fileName = java.net.URLDecoder.decode(encodedName, "UTF-8")
                    }

                    val bodyFiles = HashMap<String, String>()
                    session.parseBody(bodyFiles)
                    val tempFile = bodyFiles["file"]
                    if (tempFile != null) {
                        val destFile = File(downloadDir, fileName)
                        File(tempFile).copyTo(destFile, overwrite = true)
                        File(tempFile).delete()

                        AppLogger.i("HTTP received: $fileName, size=${destFile.length()}")
                        log("收到文件: $fileName")

                        runOnUiThread {
                            addTask(fileName, session.remoteIpAddress ?: "device", false, destFile.length(), destFile.absolutePath)
                            Toast.makeText(this@FileTransferActivity, "收到文件: $fileName", Toast.LENGTH_SHORT).show()
                        }

                        if (autoInstallApk && fileName.endsWith(".apk", ignoreCase = true)) {
                            Thread { Thread.sleep(500); installApkViaShizuku(destFile) }.start()
                        }

                        return newFixedLengthResponse(Response.Status.OK, "application/json", "{\"status\":\"ok\",\"file\":\"$fileName\"}")
                    }
                } catch (e: Exception) {
                    AppLogger.e("HTTP upload error", e)
                }
                return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Upload failed")
            }

            // Handle file download via GET
            if (uri.isEmpty()) {
                val html = buildString {
                    append("<!DOCTYPE html><html><head><meta charset='utf-8'><meta name='viewport' content='width=device-width,initial-scale=1'>")
                    append("<title>System Tool</title><style>body{background:#131313;color:#e5e2e1;font-family:system-ui;padding:20px;max-width:600px;margin:0 auto}")
                    append("h1{color:#b0c6ff;font-size:20px}a{color:#b0c6ff;text-decoration:none;display:block;padding:12px;background:#2a2a2a;border-radius:8px;margin:8px 0}")
                    append("</style></head><body><h1>System Tool - 文件中转</h1>")
                    if (sharedFiles.isEmpty()) append("<p>暂无共享文件</p>")
                    else for ((id, file) in sharedFiles) append("<a href='/$id'>${file.name} (${formatSize(file.length())})</a>")
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
                AppLogger.i("HTTP serving: ${file.name}, size=${file.length()}")
                val bytes = file.readBytes()
                val resp = newFixedLengthResponse(Response.Status.OK, mime, ByteArrayInputStream(bytes), bytes.size.toLong())
                resp.addHeader("Content-Disposition", "attachment; filename=\"${file.name}\"")
                resp.addHeader("Content-Length", bytes.size.toString())
                return resp
            }

            return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/html", "<html><body style='background:#131313;color:#fff;padding:20px'><h2>文件不存在</h2><a href='/' style='color:#b0c6ff'>返回</a></body></html>")
        }
    }
}
