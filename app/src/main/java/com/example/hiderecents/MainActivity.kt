package com.example.hiderecents

import android.animation.ObjectAnimator
import android.content.*
import android.content.pm.PackageManager
import android.net.TrafficStats
import android.os.*
import android.util.Log
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.RandomAccessFile

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "SystemTool"
    }

    private lateinit var prefs: SharedPreferences
    private lateinit var tvCpuPercent: TextView
    private lateinit var tvCpuFreq: TextView
    private lateinit var tvMemUsed: TextView
    private lateinit var tvMemTotal: TextView
    private lateinit var tvMemSystem: TextView
    private lateinit var tvMemApps: TextView
    private lateinit var tvBatteryPercent: TextView
    private lateinit var tvBatteryTemp: TextView
    private lateinit var tvBatteryStatus: TextView
    private lateinit var tvStorageAvail: TextView
    private lateinit var tvStorageApps: TextView
    private lateinit var tvStorageMedia: TextView
    private lateinit var tvStorageSystem: TextView
    private lateinit var pbStorage: ProgressBar
    private lateinit var tvNetSpeed: TextView
    private lateinit var tvNetType: TextView
    private lateinit var tvUptime: TextView
    private lateinit var tvProcesses: TextView
    private lateinit var tvShizukuStatus: TextView
    private lateinit var tvHealthStatus: TextView
    private lateinit var tvHiddenStatus: TextView
    private lateinit var pbMemSystem: ProgressBar
    private lateinit var pbMemApps: ProgressBar
    private lateinit var cpuProgress: ProgressBar
    private lateinit var topAppsContainer: LinearLayout
    private lateinit var topAppsScroll: ScrollView
    private lateinit var rootLayout: View
    private lateinit var topBar: LinearLayout
    private lateinit var tvCpuModel: TextView
    private lateinit var tvCpuBigPercent: TextView
    private val coreTextViews = arrayOfNulls<TextView>(8)
        private val coreProgressBars = arrayOfNulls<ProgressBar>(8)
    private val prevCoreCpuIdle = LongArray(8)
    private val prevCoreCpuTotal = LongArray(8)
    private val coreInitialized = BooleanArray(8)

    private val handler = Handler(Looper.getMainLooper())
    private val refreshInterval = 3000L

    // Mix of vibrant and dark theme colors

    private var prevTotalCpuIdle: Long = 0
    private var prevTotalCpuTotal: Long = 0
    private var cpuInitialized = false
    private var topCpuUsage = -1
    private var topRunning = false
    private var prevRxBytes: Long = 0
    private var prevTxBytes: Long = 0
    private var prevNetTime: Long = 0

    private var shizukuPermissionGranted = false
    private var taskHideService: IBinder? = null
    private var serviceReady = false
    private var refreshCounter = 0

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            taskHideService = service
            serviceReady = true
            Log.d(TAG, "Shizuku service connected")
            updateShizukuStatus()
            loadTopApps()
            autoGrantAllPermissions()
        }
        override fun onServiceDisconnected(name: ComponentName) {
            taskHideService = null
            serviceReady = false
            updateShizukuStatus()
        }
    }

    private val shizukuPermissionResultListener =
        Shizuku.OnRequestPermissionResultListener { _, grantResult ->
            shizukuPermissionGranted = grantResult == PackageManager.PERMISSION_GRANTED
            if (shizukuPermissionGranted) {
                bindShizukuService()
            }
            updateShizukuStatus()
        }

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        checkShizukuPermission()
    }
    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        shizukuPermissionGranted = false
        taskHideService = null
        serviceReady = false
        updateShizukuStatus()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences("hide_recents_prefs", Context.MODE_PRIVATE)

        initViews()
        setupButtons()

        window.statusBarColor = 0xFF131313.toInt()
        window.navigationBarColor = 0xFF131313.toInt()

        // First immediate update
        updateAll()
        detectCpuModel()

        // All updates every 1s
        handler.post(object : Runnable {
            override fun run() {
                updateAll()
                handler.postDelayed(this, refreshInterval)
            }
        })

        setupShizuku()
    }

    private fun updateAll() {
        try { updateCpuInfo() } catch (e: Exception) { Log.e(TAG, "CPU error", e) }
        try { updateMemoryInfo() } catch (e: Exception) { Log.e(TAG, "Mem error", e) }
        try { updateBatteryInfo() } catch (e: Exception) { Log.e(TAG, "Battery error", e) }
        try { updateStorageInfo() } catch (e: Exception) { Log.e(TAG, "Storage error", e) }
        try { updateNetworkInfo() } catch (e: Exception) { Log.e(TAG, "Net error", e) }
        try { updateSystemInfo() } catch (e: Exception) { Log.e(TAG, "System error", e) }
        refreshCounter++
        // Refresh top apps every cycle (3 seconds)
        Thread { loadTopApps() }.start()
    }

        private fun initViews() {
        topBar = findViewById(R.id.topBar)
        rootLayout = topBar.parent as View
        tvCpuPercent = findViewById(R.id.tvCpuPercent)
        tvCpuBigPercent = findViewById(R.id.tvCpuBigPercent)
        tvMemUsed = findViewById(R.id.tvMemUsed)
        tvMemTotal = findViewById(R.id.tvMemTotal)
        tvMemSystem = findViewById(R.id.tvMemSystem)
        tvMemApps = findViewById(R.id.tvMemApps)
        tvBatteryPercent = findViewById(R.id.tvBatteryPercent)
        tvBatteryTemp = findViewById(R.id.tvBatteryTemp)
        tvBatteryStatus = findViewById(R.id.tvBatteryStatus)
        tvStorageAvail = findViewById(R.id.tvStorageAvail)
        tvStorageApps = findViewById(R.id.tvStorageApps)
        tvStorageMedia = findViewById(R.id.tvStorageMedia)
        tvStorageSystem = findViewById(R.id.tvStorageSystem)
        pbStorage = findViewById(R.id.pbStorage)
        tvNetSpeed = findViewById(R.id.tvNetSpeed)
        tvNetType = findViewById(R.id.tvNetType)
        tvUptime = findViewById(R.id.tvUptime)
        tvProcesses = findViewById(R.id.tvProcesses)
        tvShizukuStatus = findViewById(R.id.tvRootStatus)
        tvHealthStatus = findViewById(R.id.tvHealthStatus)
        tvHiddenStatus = findViewById(R.id.tvHiddenStatus)
        pbMemSystem = findViewById(R.id.pbMemSystem)
        pbMemApps = findViewById(R.id.pbMemApps)
        cpuProgress = findViewById(R.id.cpuProgress)
        topAppsContainer = findViewById(R.id.topAppsContainer)
        topAppsScroll = findViewById(R.id.topAppsScroll)
        tvCpuModel = findViewById(R.id.tvCpuModel)

        // Find core views
        val coreTextIds = arrayOf(R.id.tvCore0, R.id.tvCore1, R.id.tvCore2, R.id.tvCore3,
            R.id.tvCore4, R.id.tvCore5, R.id.tvCore6, R.id.tvCore7)
        val coreProgressIds = arrayOf(R.id.coreProgress0, R.id.coreProgress1, R.id.coreProgress2, R.id.coreProgress3,
            R.id.coreProgress4, R.id.coreProgress5, R.id.coreProgress6, R.id.coreProgress7)
        for (i in 0 until 8) {
            coreTextViews[i] = findViewById(coreTextIds[i])
            coreProgressBars[i] = findViewById(coreProgressIds[i])
        }

        updateHiddenCount()
    }

    private fun setupShizuku() {
        Shizuku.addRequestPermissionResultListener(shizukuPermissionResultListener)
        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
        checkShizukuPermission()
    }

    private fun checkShizukuPermission() {
        if (!Shizuku.pingBinder() || Shizuku.isPreV11()) {
            shizukuPermissionGranted = false
            serviceReady = false
            updateShizukuStatus()
            return
        }
        if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
            shizukuPermissionGranted = true
            bindShizukuService()
        } else {
            Shizuku.requestPermission(1001)
        }
        updateShizukuStatus()
    }

    private fun bindShizukuService() {
        try {
            Shizuku.bindUserService(
                Shizuku.UserServiceArgs(ComponentName(this, TaskHideService::class.java))
                    .daemon(false).processNameSuffix("taskhide").debuggable(true).version(1),
                serviceConnection
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to bind Shizuku service", e)
        }
    }

    private fun updateShizukuStatus() {
        runOnUiThread {
            val status = when {
                !Shizuku.pingBinder() -> "Shizuku 未运行"
                !shizukuPermissionGranted -> "未授权"
                !serviceReady -> "连接中..."
                else -> "已连接"
            }
            val color = if (serviceReady && shizukuPermissionGranted) getColor(R.color.primary) else getColor(R.color.error)
            tvShizukuStatus.text = status
            tvShizukuStatus.setTextColor(color)
        }
    }

    private fun autoGrantAllPermissions() {
        if (!serviceReady) return
        Thread {
            try {
                val componentName = ComponentName(this, NotificationListenerServiceImpl::class.java).flattenToString()
                executeCommandSync("cmd notification allow_listener $componentName")
                Log.d(TAG, "Auto-granted notification permission")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to auto-grant notification permission", e)
            }
            try {
                executeCommandSync("appops set ${packageName} QUERY_ALL_PACKAGES allow")
                Log.d(TAG, "Auto-granted QUERY_ALL_PACKAGES")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to auto-grant QUERY_ALL_PACKAGES", e)
            }
        }.start()
    }

    private fun setupButtons() {
        findViewById<ImageView>(R.id.btnToolbox).setOnClickListener {
            startActivity(Intent(this, ToolboxActivity::class.java))
            overridePendingTransition(R.anim.scale_in, 0)
        }
    }

    private fun detectCpuModel() {
        Thread {
            try {
                var hardware = ""
                var cpuModel = ""

                // Read /proc/cpuinfo for model info
                val cpuinfo = File("/proc/cpuinfo")
                if (cpuinfo.exists()) {
                    val lines = cpuinfo.readLines()
                    hardware = lines.firstOrNull { it.startsWith("Hardware") }?.substringAfter(":")?.trim() ?: ""
                    cpuModel = lines.firstOrNull { it.startsWith("model name") || it.startsWith("Processor") }?.substringAfter(":")?.trim() ?: ""
                }

                // Also try /proc/socinfo or properties
                if (hardware.isEmpty()) {
                    try {
                        val process = Runtime.getRuntime().exec(arrayOf("getprop", "ro.soc.model"))
                        val reader = BufferedReader(InputStreamReader(process.inputStream))
                        hardware = reader.readLine()?.trim() ?: ""
                        reader.close()
                    } catch (_: Exception) {}
                }
                if (hardware.isEmpty()) {
                    try {
                        val process = Runtime.getRuntime().exec(arrayOf("getprop", "ro.hardware.chipname"))
                        val reader = BufferedReader(InputStreamReader(process.inputStream))
                        hardware = reader.readLine()?.trim() ?: ""
                        reader.close()
                    } catch (_: Exception) {}
                }

                // Detect core configuration
                val cpuDir = File("/sys/devices/system/cpu/")
                val cpuDirs = cpuDir.listFiles { f ->
                    f.isDirectory && f.name.matches(Regex("cpu\\d+"))
                }?.sortedBy { it.name.removePrefix("cpu").toIntOrNull() ?: 0 }

                val coreCount = cpuDirs?.size ?: 0

                // Group cores by max frequency to find clusters
                val freqGroups = mutableMapOf<Long, Int>() // freq -> count
                cpuDirs?.forEach { cpuDir ->
                    try {
                        val maxFreqFile = File(cpuDir, "cpufreq/cpuinfo_max_freq")
                        if (maxFreqFile.exists()) {
                            val maxFreq = maxFreqFile.readText().trim().toLongOrNull() ?: 0
                            freqGroups[maxFreq] = (freqGroups[maxFreq] ?: 0) + 1
                        }
                    } catch (_: Exception) {}
                }

                val clusterInfo = if (freqGroups.size >= 3) {
                    val sorted = freqGroups.entries.sortedByDescending { it.key }
                    "${sorted[0].value}+${sorted[1].value}+${sorted[2].value}"
                } else if (freqGroups.size == 2) {
                    val sorted = freqGroups.entries.sortedByDescending { it.key }
                    "${sorted[0].value}+${sorted[1].value}"
                } else {
                    "${coreCount}"
                }

                val displayName = when {
                    hardware.isNotEmpty() && clusterInfo.isNotEmpty() -> "$hardware ($clusterInfo)"
                    hardware.isNotEmpty() -> hardware
                    cpuModel.isNotEmpty() -> cpuModel
                    else -> "CPU ($coreCount 核)"
                }

                Log.d(TAG, "CPU Model: $displayName, cores=$coreCount, clusters=$freqGroups")

                runOnUiThread {
                    tvCpuModel.text = displayName
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to detect CPU model", e)
            }
                }.start()
    }

    private fun updateCpuInfo() {
        // Read per-core frequency from /sys (lightweight)
        for (i in 0 until 8) {
            try {
                val freqFile = File("/sys/devices/system/cpu/cpu$i/cpufreq/scaling_cur_freq")
                val maxFreqFile = File("/sys/devices/system/cpu/cpu$i/cpufreq/cpuinfo_max_freq")
                if (freqFile.exists() && freqFile.canRead()) {
                    val freq = freqFile.readText().trim().toLongOrNull() ?: 0
                    val max = if (maxFreqFile.exists() && maxFreqFile.canRead()) maxFreqFile.readText().trim().toLongOrNull() ?: 1 else 1
                    val usage = if (max > 0) (freq * 100 / max).toInt().coerceIn(0, 100) else 0
                    runOnUiThread {
                        coreTextViews[i]?.text = "${freq / 1000} MHz"
                        animateProgress(coreProgressBars[i], usage)
                    }
                }
            } catch (_: Exception) {}
        }

        // Use dumpsys cpuinfo for total CPU (works via Shizuku)
        if (serviceReady) {
            Thread {
                try {
                    val output = executeCommandSync("dumpsys cpuinfo")
                    if (output.isNotEmpty()) {
                        for (line in output.lines()) {
                            val trimmed = line.trim()
                            if (trimmed.contains("TOTAL")) {
                                val match = Regex("([\\d.]+)%").find(trimmed)
                                if (match != null) {
                                    val usage = match.groupValues[1].toFloat().toInt()
                                    runOnUiThread {
                                        tvCpuPercent.text = "$usage%"
                                        tvCpuBigPercent.text = "$usage%"
                                        animateProgress(cpuProgress, usage)
                                    }
                                    return@Thread
                                }
                            }
                        }
                    }
                } catch (_: Exception) {}
            }.start()
        }
    }

    private val progressAnimators = mutableMapOf<ProgressBar, android.animation.ValueAnimator>()

    private fun animateProgress(progressBar: ProgressBar?, target: Int) {
        if (progressBar == null) return
        val current = progressBar.progress
        if (current == target) return
        progressAnimators[progressBar]?.cancel()
        val anim = android.animation.ValueAnimator.ofInt(current, target)
        anim.duration = 300
        anim.interpolator = android.view.animation.DecelerateInterpolator(1.5f)
        anim.addUpdateListener { progressBar.progress = it.animatedValue as Int }
        anim.addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) {
                progressAnimators.remove(progressBar)
            }
        })
        progressAnimators[progressBar] = anim
        anim.start()
    }

    private fun updateMemoryInfo() {
        try {
            val memFile = File("/proc/meminfo")
            if (!memFile.exists()) {
                Log.w(TAG, "/proc/meminfo not found")
                return
            }

            val lines = memFile.readLines()
            val memTotalLine = lines.firstOrNull { it.startsWith("MemTotal") }
            val memAvailLine = lines.firstOrNull { it.startsWith("MemAvailable") }

            if (memTotalLine == null || memAvailLine == null) {
                Log.w(TAG, "Could not find MemTotal or MemAvailable")
                return
            }

            val memTotal = memTotalLine.split("\\s+".toRegex())[1].toLong()
            val memAvail = memAvailLine.split("\\s+".toRegex())[1].toLong()
            val memUsed = memTotal - memAvail
            val totalGB = String.format("%.1f", memTotal / 1048576.0)
            val usedGB = String.format("%.1f", memUsed / 1048576.0)
            val usagePercent = (memUsed * 100 / memTotal).toInt()
            val systemPercent = (usagePercent * 0.15).toInt()
            val appsPercent = (usagePercent * 0.85).toInt()

            Log.d(TAG, "Memory: $usedGB/$totalGB GB ($usagePercent%)")

            runOnUiThread {
                tvMemUsed.text = "$usedGB GB"
                tvMemTotal.text = " / $totalGB GB"
                tvMemSystem.text = "${String.format("%.1f", memTotal * 0.15 / 1048576.0)} GB"
                tvMemApps.text = "${String.format("%.1f", memUsed * 0.85 / 1048576.0)} GB"
                pbMemSystem.progress = systemPercent
                pbMemApps.progress = appsPercent
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update memory info", e)
        }
    }

    private fun updateBatteryInfo() {
        try {
            val bm = getSystemService(Context.BATTERY_SERVICE) as android.os.BatteryManager
            val level = bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
            val batteryIntent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val temp = batteryIntent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
            val status = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, 0) ?: 0

            runOnUiThread {
                tvBatteryPercent.text = "$level"
                tvBatteryTemp.text = "${temp / 10}°C"
                tvBatteryStatus.text = when (status) {
                    BatteryManager.BATTERY_STATUS_CHARGING -> "充电中"
                    BatteryManager.BATTERY_STATUS_DISCHARGING -> "放电中"
                    BatteryManager.BATTERY_STATUS_FULL -> "已满"
                    else -> "未知"
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update battery info", e)
        }
    }

    private fun updateStorageInfo() {
        try {
            // Read real total storage from /sys/block/
            var totalBytes = 0L
            val blockDir = File("/sys/block")
            if (blockDir.exists()) {
                blockDir.listFiles()?.forEach { device ->
                    try {
                        val sizeFile = File(device, "size")
                        if (sizeFile.exists()) {
                            val sectors = sizeFile.readText().trim().toLongOrNull() ?: 0
                            val bytes = sectors * 512L // 512 bytes per sector
                            if (bytes > totalBytes) totalBytes = bytes
                        }
                    } catch (_: Exception) {}
                }
            }

            // Fallback to StatFs
            if (totalBytes == 0L) {
                val stat = android.os.StatFs("/data")
                totalBytes = stat.totalBytes
            }

            val stat = android.os.StatFs("/data")
            val availBytes = stat.availableBytes
            val usedBytes = totalBytes - availBytes
            val totalGB = totalBytes / 1073741824.0
            val usedGB = usedBytes / 1073741824.0
            val usedPercent = if (totalGB > 0) (usedGB * 100 / totalGB).toInt() else 0

            runOnUiThread {
                tvStorageAvail.text = "${String.format("%.0f", usedGB)} / ${String.format("%.0f", totalGB)} GB"
                pbStorage.progress = usedPercent
                tvStorageApps.text = "${String.format("%.0f", usedGB * 0.6)}G"
                tvStorageMedia.text = "${String.format("%.0f", usedGB * 0.25)}G"
                tvStorageSystem.text = "${String.format("%.0f", usedGB * 0.15)}G"
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update storage info", e)
        }
    }

    private fun updateNetworkInfo() {
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            val activeNetwork = cm.activeNetwork
            val caps = cm.getNetworkCapabilities(activeNetwork)
            val netType = when {
                caps == null -> "无网络"
                caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) -> "WiFi"
                caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) -> "移动数据"
                else -> "其他"
            }
            runOnUiThread { tvNetType.text = netType }
        } catch (_: Exception) {}

        try {
            val currentRx = TrafficStats.getTotalRxBytes()
            val currentTx = TrafficStats.getTotalTxBytes()
            if (currentRx < 0 || currentTx < 0) return
            val currentTime = System.currentTimeMillis()

            if (prevNetTime > 0) {
                val timeDiff = (currentTime - prevNetTime) / 1000.0
                if (timeDiff > 0) {
                    val rxDiff = currentRx - prevRxBytes
                    val txDiff = currentTx - prevTxBytes
                    val totalDiff = rxDiff + txDiff
                    val speedKBps = (totalDiff / 1024.0 / timeDiff).coerceAtLeast(0.0)
                    runOnUiThread {
                        tvNetSpeed.text = String.format("%.1f", speedKBps)
                    }
                }
            }

            prevRxBytes = currentRx
            prevTxBytes = currentTx
            prevNetTime = currentTime
        } catch (_: Exception) {}
    }

    private fun updateSystemInfo() {
        Thread {
            var hours = 0L
            var minutes = 0L
            var procCount = 0

            // Use Shizuku for system commands (has elevated permissions)
            if (serviceReady) {
                // Uptime via Shizuku
                try {
                    val uptimeOutput = executeCommandSync("cat /proc/uptime")
                    if (uptimeOutput.isNotEmpty()) {
                        val seconds = uptimeOutput.trim().split("\\s+".toRegex())[0].toDoubleOrNull()?.toLong() ?: 0
                        if (seconds > 0) {
                            hours = seconds / 3600
                            minutes = (seconds % 3600) / 60
                        }
                    }
                } catch (_: Exception) {}

                // Process count via Shizuku
                try {
                    val psOutput = executeCommandSync("ps -A -o NAME")
                    if (psOutput.isNotEmpty()) {
                        val lines = psOutput.lines().filter { it.isNotBlank() && !it.startsWith("NAME") }
                        procCount = lines.distinct().size // unique process names
                    }
                } catch (_: Exception) {}
            }

            // Fallback: direct commands (limited permissions)
            if (hours == 0L && minutes == 0L) {
                try {
                    val process = Runtime.getRuntime().exec(arrayOf("cat", "/proc/uptime"))
                    val output = BufferedReader(InputStreamReader(process.inputStream)).readText().trim()
                    process.waitFor()
                    val seconds = output.split("\\s+".toRegex())[0].toDoubleOrNull()?.toLong() ?: 0
                    if (seconds > 0) {
                        hours = seconds / 3600
                        minutes = (seconds % 3600) / 60
                    }
                } catch (_: Exception) {}
            }

            if (procCount == 0) {
                try {
                    procCount = File("/proc").listFiles()?.count {
                        it.isDirectory && it.name.matches(Regex("\\d+"))
                    } ?: 0
                } catch (_: Exception) {}
            }

            val health = when {
                procCount > 500 -> "警告"
                procCount > 300 -> "一般"
                else -> "良好"
            }

            runOnUiThread {
                tvUptime.text = "${hours}h ${minutes}m"
                tvProcesses.text = "$procCount"
                tvHealthStatus.text = health
            }

            updateShizukuStatus()
        }.start()
    }

    private fun loadTopApps() {
        try {
            val pm = packageManager
            val appCpuList = mutableListOf<Triple<String, Float, android.graphics.drawable.Drawable?>>()

            // Try top command
            try {
                val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", "top -bn1 -o %CPU | head -20"))
                val reader = BufferedReader(InputStreamReader(process.inputStream))
                val lines = reader.readLines()
                process.waitFor()

                for (line in lines) {
                    try {
                        val trimmed = line.trim()
                        if (trimmed.startsWith("PID") || trimmed.isEmpty()) continue

                        val parts = trimmed.split("\\s+".toRegex())
                        if (parts.size >= 9) {
                            val cpuPercent = parts[8].toFloatOrNull() ?: continue
                            if (cpuPercent <= 0f) continue
                            val pkg = parts.last().trim()

                            try {
                                val appInfo = pm.getApplicationInfo(pkg, 0)
                                val name = pm.getApplicationLabel(appInfo).toString()
                                val icon = pm.getApplicationIcon(appInfo)
                                appCpuList.add(Triple(name, cpuPercent, icon))
                            } catch (_: Exception) {}
                        }
                    } catch (_: Exception) {}
                }
            } catch (_: Exception) {}

            // Fallback: dumpsys cpuinfo
            if (appCpuList.isEmpty()) {
                try {
                    val cpuOutput = executeCommandSync("dumpsys cpuinfo")
                    if (cpuOutput.isNotEmpty()) {
                        val lines = cpuOutput.lines()
                        for (line in lines) {
                            try {
                                val trimmed = line.trim()
                                if (!trimmed.contains("%") || trimmed.startsWith("TOTAL")) continue
                                val match = Regex("^([\\d.]+)%\\s+\\d+/(.+?):").find(trimmed)
                                if (match != null) {
                                    val percent = match.groupValues[1].toFloatOrNull() ?: continue
                                    val pkg = match.groupValues[2].trim().substringBefore(":")
                                    try {
                                        val appInfo = pm.getApplicationInfo(pkg, 0)
                                        val name = pm.getApplicationLabel(appInfo).toString()
                                        val icon = pm.getApplicationIcon(appInfo)
                                        appCpuList.add(Triple(name, percent, icon))
                                    } catch (_: Exception) {}
                                }
                            } catch (_: Exception) {}
                        }
                    }
                } catch (_: Exception) {}
            }

            val topApps = appCpuList.sortedByDescending { it.second }.take(20)

            val packages = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            val launchable = packages.filter { pm.getLaunchIntentForPackage(it.packageName) != null }

            if (topApps.isEmpty()) {
                val fallback = launchable.take(20).map {
                    Triple(pm.getApplicationLabel(it).toString(), 0f, pm.getApplicationIcon(it))
                }
                runOnUiThread { renderTopApps(fallback) }
            } else {
                val existingPkgs = topApps.map { it.first }.toSet()
                val extras = launchable
                    .filter { pm.getApplicationLabel(it).toString() !in existingPkgs }
                    .take(20 - topApps.size)
                    .map { Triple(pm.getApplicationLabel(it).toString(), 0f, pm.getApplicationIcon(it)) }
                runOnUiThread { renderTopApps(topApps + extras) }
            }
        } catch (_: Exception) {}
    }

    private fun renderTopApps(apps: List<Triple<String, Float, android.graphics.drawable.Drawable?>>) {
        topAppsContainer.removeAllViews()
        for ((index, app) in apps.withIndex()) {
            val (name, percent, icon) = app
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(0, 12, 0, 12)
            }

            val rankView = TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(24, LinearLayout.LayoutParams.WRAP_CONTENT)
                text = "${index + 1}"
                textSize = 12f
                setTextColor(getColor(R.color.on_surface_variant))
            }

            val iconView = ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(36, 36).apply { marginStart = 8 }
                setImageDrawable(icon)
            }

            val nameView = TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = 12
                }
                text = name
                textSize = 14f
                setTextColor(getColor(R.color.on_surface))
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }

            val percentView = TextView(this).apply {
                // Estimate watts: CPU max ~5W, convert percentage to watts
                val watts = percent * 5.0f / 100.0f
                text = String.format("-%.1fW", watts)
                textSize = 12f
                setTextColor(getColor(R.color.primary))
            }

            row.addView(rankView)
            row.addView(iconView)
            row.addView(nameView)
            row.addView(percentView)
            topAppsContainer.addView(row)
        }
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
            val success = reply.readInt() != 0
            val output = reply.readString() ?: ""
            Log.d(TAG, "Command '$command' => success=$success output=${output.take(200)}")
            return output
        } catch (e: Exception) {
            Log.e(TAG, "Command failed: $command", e)
            return ""
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    private fun updateHiddenCount() {
        val hiddenApps = prefs.getStringSet("hidden_apps", emptySet()) ?: emptySet()
        tvHiddenStatus.text = "已隐藏 ${hiddenApps.size}"
    }

    override fun onResume() {
        super.onResume()
        updateHiddenCount()
        updateShizukuStatus()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        Shizuku.removeRequestPermissionResultListener(shizukuPermissionResultListener)
        Shizuku.removeBinderReceivedListener(binderReceivedListener)
        Shizuku.removeBinderDeadListener(binderDeadListener)
        try {
            Shizuku.unbindUserService(
                Shizuku.UserServiceArgs(ComponentName(this, TaskHideService::class.java)),
                serviceConnection, true
            )
        } catch (_: Exception) {}
    }
}
