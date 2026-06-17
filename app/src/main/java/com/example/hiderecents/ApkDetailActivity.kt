package com.example.hiderecents

import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import java.io.File

class ApkDetailActivity : AppCompatActivity() {

    private lateinit var ivAppIcon: ImageView
    private lateinit var tvAppName: TextView
    private lateinit var tvVersion: TextView
    private lateinit var tvFileSize: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvPercent: TextView
    private lateinit var progressBar: View
    private lateinit var tvMinSdk: TextView
    private lateinit var tvTargetSdk: TextView
    private lateinit var tvPermCount: TextView
    private lateinit var tvPackageName: TextView
    private lateinit var btnInstall: TextView

    private var taskHideService: IBinder? = null
    private var shizukuBound = false
    private val handler = Handler(Looper.getMainLooper())
    private var apkFile: File? = null
    private var installed = false
    private var lastInstallError = ""
    private var autoInstall = false
    private var installing = false

    private val shizukuConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            taskHideService = service
            shizukuBound = true
        }
        override fun onServiceDisconnected(name: ComponentName) {
            taskHideService = null
            shizukuBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_apk_detail)

        ivAppIcon = findViewById(R.id.ivAppIcon)
        tvAppName = findViewById(R.id.tvAppName)
        tvVersion = findViewById(R.id.tvVersion)
        tvFileSize = findViewById(R.id.tvFileSize)
        tvStatus = findViewById(R.id.tvStatus)
        tvPercent = findViewById(R.id.tvPercent)
        progressBar = findViewById(R.id.progressBar)
        tvMinSdk = findViewById(R.id.tvMinSdk)
        tvTargetSdk = findViewById(R.id.tvTargetSdk)
        tvPermCount = findViewById(R.id.tvPermCount)
        tvPackageName = findViewById(R.id.tvPackageName)
        btnInstall = findViewById(R.id.btnInstall)

        findViewById<ImageView>(R.id.btnBack).setOnClickListener { finish() }

        // Get APK path from intent
        val apkPath = intent.getStringExtra("apk_path")
        if (apkPath != null) {
            autoInstall = true
            loadApkInfo(File(apkPath))
        } else {
            val uri = intent.data
            if (uri != null) {
                autoInstall = true
                copyAndLoadFromUri(uri)
            } else {
                Toast.makeText(this, "无文件信息", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    private fun getPathFromUri(uri: Uri): String? {
        try {
            if (uri.scheme == "file") {
                val path = uri.path
                if (path != null && File(path).canRead()) return path
                return null
            }
            if (android.provider.DocumentsContract.isDocumentUri(this, uri)) {
                val docId = android.provider.DocumentsContract.getDocumentId(uri)
                if (uri.authority == "com.android.externalstorage.documents") {
                    val parts = docId.split(":")
                    if (parts.size == 2) {
                        val path = "/storage/emulated/0/${parts[1]}"
                        if (File(path).canRead()) return path
                    }
                }
                if (uri.authority == "com.android.providers.downloads.documents") {
                    val path = if (docId.startsWith("raw:")) docId.removePrefix("raw:")
                               else "/storage/emulated/0/Download/$docId"
                    if (File(path).canRead()) return path
                }
            }
        } catch (_: Exception) {}
        return null
    }

    private fun copyAndLoadFromUri(uri: Uri) {
        Thread {
            try {
                val cursor = contentResolver.query(uri, null, null, null, null)
                var fileName = "app.apk"
                cursor?.use {
                    if (it.moveToFirst()) {
                        val idx = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                        if (idx >= 0) fileName = it.getString(idx)
                    }
                }
                val cacheFile = File(cacheDir, fileName)
                val input = contentResolver.openInputStream(uri)
                if (input == null) {
                    handler.post { Toast.makeText(this, "无法读取文件", Toast.LENGTH_SHORT).show() }
                    return@Thread
                }
                var totalBytes = 0L
                input.use { stream ->
                    val buffered = stream.buffered()
                    cacheFile.outputStream().use { output ->
                        val buf = ByteArray(65536)
                        var read: Int
                        while (buffered.read(buf).also { r -> read = r } != -1) {
                            output.write(buf, 0, read)
                            totalBytes += read
                        }
                    }
                }

                // Validate copied file
                val actualSize = cacheFile.length()
                val header = try { cacheFile.inputStream().use { it.readNBytes(4) } } catch (_: Exception) { ByteArray(0) }
                val isZip = header.size >= 4 &&
                    header[0] == 0x50.toByte() && header[1] == 0x4B.toByte() &&
                    header[2] == 0x03.toByte() && header[3] == 0x04.toByte()

                if (!isZip || actualSize < 100) {
                    handler.post {
                        Toast.makeText(this, "文件无效: read=$totalBytes actual=$actualSize zip=$isZip", Toast.LENGTH_LONG).show()
                    }
                    return@Thread
                }

                handler.post { loadApkInfo(cacheFile) }
            } catch (e: Exception) {
                handler.post { Toast.makeText(this, "读取失败: ${e.message}", Toast.LENGTH_SHORT).show() }
            }
        }.start()
    }

    private fun loadApkInfo(file: File) {
        if (!file.exists() || file.length() < 100) {
            Toast.makeText(this, "文件无效或不存在", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        apkFile = file
        val pm = packageManager
        val packageInfo = pm.getPackageArchiveInfo(file.absolutePath, PackageManager.GET_META_DATA or PackageManager.GET_PERMISSIONS)

        if (packageInfo != null) {
            packageInfo.applicationInfo.sourceDir = file.absolutePath
            packageInfo.applicationInfo.publicSourceDir = file.absolutePath

            // Load icon
            try {
                val icon: Drawable = packageInfo.applicationInfo.loadIcon(pm)
                ivAppIcon.setImageDrawable(icon)
            } catch (_: Exception) {}

            tvAppName.text = pm.getApplicationLabel(packageInfo.applicationInfo)
            tvVersion.text = "VERSION ${packageInfo.versionName ?: "unknown"}"
            tvFileSize.text = formatSize(file.length())
            tvMinSdk.text = "Android ${getAndroidVersion(packageInfo.applicationInfo.minSdkVersion)} (${packageInfo.applicationInfo.minSdkVersion})"
            tvTargetSdk.text = "Android ${getAndroidVersion(packageInfo.applicationInfo.targetSdkVersion)} (${packageInfo.applicationInfo.targetSdkVersion})"

            val permCount = packageInfo.requestedPermissions?.size ?: 0
            tvPermCount.text = "$permCount 项权限"
            tvPackageName.text = packageInfo.packageName
        } else {
            tvAppName.text = file.name
            tvVersion.text = "未知版本"
            tvFileSize.text = formatSize(file.length())
        }

        btnInstall.text = if (autoInstall) "安装中..." else "安装"
        btnInstall.isClickable = !autoInstall
        btnInstall.setOnClickListener {
            if (!installed) {
                btnInstall.text = "安装中..."
                btnInstall.isClickable = false
                startInstall()
            } else {
                val launchIntent = pm.getLaunchIntentForPackage(packageInfo?.packageName ?: "")
                if (launchIntent != null) startActivity(launchIntent)
                finish()
            }
        }

        // Bind Shizuku service
        try {
            rikka.shizuku.Shizuku.bindUserService(
                rikka.shizuku.Shizuku.UserServiceArgs(ComponentName(this, TaskHideService::class.java))
                    .daemon(false).processNameSuffix("apkdetail").debuggable(true).version(1),
                shizukuConnection
            )
        } catch (_: Exception) {}

        if (autoInstall) startInstall()
    }

    private fun startInstall() {
        if (installing || installed) return
        installing = true
        val file = apkFile ?: return

        // Wait for Shizuku service
        Thread {
            var waitCount = 0
            while (taskHideService == null && waitCount < 40) {
                Thread.sleep(250)
                waitCount++
            }

            if (taskHideService == null) {
                handler.post {
                    installing = false
                    tvStatus.text = "Shizuku 未连接"
                    tvStatus.setTextColor(getColor(R.color.error))
                    btnInstall.text = "重试"
                    btnInstall.isClickable = true
                }
                return@Thread
            }

            // Smooth progress animation
            var progress = 0
            val progressRunnable = object : Runnable {
                override fun run() {
                    if (progress < 85) {
                        progress += 1
                        tvPercent.text = "$progress%"
                        val lp = progressBar.layoutParams
                        lp.width = (progressBar.parent as View).width * progress / 100
                        progressBar.layoutParams = lp
                        handler.postDelayed(this, 50)
                    }
                }
            }
            handler.post(progressRunnable)

            val success = installApk(file)
            handler.removeCallbacks(progressRunnable)

            // Smooth finish to 100%
            val finishRunnable = object : Runnable {
                override fun run() {
                    if (progress < 100) {
                        progress += 2
                        if (progress > 100) progress = 100
                        tvPercent.text = "$progress%"
                        val lp = progressBar.layoutParams
                        lp.width = (progressBar.parent as View).width * progress / 100
                        progressBar.layoutParams = lp
                        if (progress < 100) handler.postDelayed(this, 30)
                        else showResult(success)
                    }
                }
            }
            handler.post(finishRunnable)
        }.start()
    }

    private fun showResult(success: Boolean) {
        installing = false
        if (success) {
            installed = true
            tvStatus.text = "安装成功"
            tvStatus.setTextColor(getColor(R.color.primary))
            btnInstall.text = "打开"
            btnInstall.isClickable = true
            btnInstall.setBackgroundResource(R.drawable.bg_rounded_primary)
        } else {
            tvStatus.text = "安装失败\n$lastInstallError"
            tvStatus.setTextColor(getColor(R.color.error))
            btnInstall.text = "重试"
            btnInstall.isClickable = true
            btnInstall.setBackgroundResource(R.drawable.bg_rounded_primary)
        }
    }

    private fun installApk(apkFile: File): Boolean {
        val binder = taskHideService ?: return false
        lastInstallError = ""

        fun execCmd(cmd: String): Pair<Boolean, String> {
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken(ITaskHideService.DESCRIPTOR)
                data.writeString(cmd)
                binder.transact(ITaskHideService.TRANSACTION_execCommand, data, reply, 0)
                reply.readException()
                return Pair(reply.readInt() != 0, reply.readString() ?: "")
            } finally { data.recycle(); reply.recycle() }
        }

        // Copy to external files dir (accessible by shell)
        val extDir = getExternalFilesDir(null)
        if (extDir != null && !extDir.exists()) extDir.mkdirs()
        val tmpFile = File(extDir, "install.apk")
        try {
            apkFile.copyTo(tmpFile, overwrite = true)
        } catch (e: Exception) {
            lastInstallError = "复制失败: ${e.message}"
            return false
        }

        val path = tmpFile.absolutePath

        try {
            val (s, o) = execCmd("pm install -r -g '$path'")
            if (s || o.contains("Success")) { tmpFile.delete(); return true }
            lastInstallError = "pm install: $o"
        } catch (e: Exception) {
            lastInstallError = "pm install exception: ${e.message}"
        }

        try {
            val (s, o) = execCmd("cat '$path' | pm install -S ${tmpFile.length()}")
            if (s || o.contains("Success")) { tmpFile.delete(); return true }
            lastInstallError += "\ncat pipe: $o"
        } catch (e: Exception) {
            lastInstallError += "\ncat pipe exception: ${e.message}"
        }

        tmpFile.delete()
        return false
    }

    private fun formatSize(bytes: Long): String {
        return when {
            bytes < 1024 * 1024 -> String.format("%.1f KB", bytes / 1024.0)
            bytes < 1024 * 1024 * 1024 -> String.format("%.1f MB", bytes / 1048576.0)
            else -> String.format("%.2f GB", bytes / 1073741824.0)
        }
    }

    private fun getAndroidVersion(sdk: Int): String {
        return when (sdk) {
            21 -> "5.0"; 22 -> "5.1"; 23 -> "6.0"; 24 -> "7.0"; 25 -> "7.1"
            26 -> "8.0"; 27 -> "8.1"; 28 -> "9"; 29 -> "10"; 30 -> "11"
            31 -> "12"; 32 -> "12L"; 33 -> "13"; 34 -> "14"; 35 -> "15"
            else -> "$sdk"
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        try {
            rikka.shizuku.Shizuku.unbindUserService(
                rikka.shizuku.Shizuku.UserServiceArgs(ComponentName(this, TaskHideService::class.java)),
                shizukuConnection, true
            )
        } catch (_: Exception) {}
    }
}
