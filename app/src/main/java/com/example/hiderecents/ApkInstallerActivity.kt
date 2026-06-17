package com.example.hiderecents

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.view.LayoutInflater
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import java.io.File

class ApkInstallerActivity : AppCompatActivity() {

    private lateinit var overallProgressCard: LinearLayout
    private lateinit var tvOverallStatus: TextView
    private lateinit var tvOverallPercent: TextView
    private lateinit var overallProgressBar: View
    private lateinit var tvOverallInfo: TextView
    private lateinit var installQueue: LinearLayout
    private lateinit var infoCard: LinearLayout

    private var taskHideService: IBinder? = null
    private var shizukuBound = false
    private val apkList = mutableListOf<ApkItem>()
    private var currentIndex = -1
    private var installedCount = 0
    private val handler = Handler(Looper.getMainLooper())

    data class ApkItem(
        val name: String,
        val packageName: String,
        val version: String,
        val size: Long,
        val filePath: String,
        val icon: android.graphics.drawable.Drawable?,
        var status: String = "waiting"
    )

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
        setContentView(R.layout.activity_apk_installer)

        overallProgressCard = findViewById(R.id.overallProgressCard)
        tvOverallStatus = findViewById(R.id.tvOverallStatus)
        tvOverallPercent = findViewById(R.id.tvOverallPercent)
        overallProgressBar = findViewById(R.id.overallProgressBar)
        tvOverallInfo = findViewById(R.id.tvOverallInfo)
        installQueue = findViewById(R.id.installQueue)
        infoCard = findViewById(R.id.infoCard)

        findViewById<ImageView>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<LinearLayout>(R.id.btnSelectApk).setOnClickListener { selectApk() }

        // Bind Shizuku service
        try {
            rikka.shizuku.Shizuku.bindUserService(
                rikka.shizuku.Shizuku.UserServiceArgs(ComponentName(this, TaskHideService::class.java))
                    .daemon(false).processNameSuffix("apkinstaller").debuggable(true).version(1),
                shizukuConnection
            )
        } catch (_: Exception) {}
    }

    private fun selectApk() {
        val intent = Intent(Intent.ACTION_GET_CONTENT)
        intent.type = "*/*"
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        intent.addCategory(Intent.CATEGORY_OPENABLE)
        startActivityForResult(intent, 2001)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 2001 && resultCode == RESULT_OK) {
            apkList.clear()
            installQueue.removeAllViews()

            val uris = mutableListOf<Uri>()
            if (data?.clipData != null) {
                for (i in 0 until data.clipData!!.itemCount) {
                    uris.add(data.clipData!!.getItemAt(i).uri)
                }
            } else if (data?.data != null) {
                uris.add(data.data!!)
            }

            for (uri in uris) {
                try {
                    val cursor = contentResolver.query(uri, null, null, null, null)
                    var fileName = "app.apk"
                    var realPath: String? = null
                    cursor?.use {
                        if (it.moveToFirst()) {
                            val idx = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                            if (idx >= 0) fileName = it.getString(idx)
                        }
                    }

                    // Try to get real file path
                    realPath = getRealPathFromUri(uri)

                    val apkFile: File
                    if (realPath != null && File(realPath).exists()) {
                        apkFile = File(realPath)
                        AppLogger.i("ApkInstaller: using real path: $realPath")
                    } else {
                        // Fallback: copy to cache
                        val cacheFile = File(cacheDir, fileName)
                        contentResolver.openInputStream(uri)?.use { input ->
                            val buffered = input.buffered()
                            cacheFile.outputStream().use { output ->
                                val buf = ByteArray(65536)
                                var read: Int
                                while (buffered.read(buf).also { r -> read = r } != -1) {
                                    output.write(buf, 0, read)
                                }
                            }
                        }
                        apkFile = cacheFile
                        AppLogger.i("ApkInstaller: copied to cache: ${cacheFile.absolutePath}, size=${cacheFile.length()}")
                    }

                    val pm = packageManager
                    val packageInfo = pm.getPackageArchiveInfo(apkFile.absolutePath, PackageManager.GET_META_DATA)
                    var appName = fileName
                    var pkgName = ""
                    var version = ""
                    var icon: android.graphics.drawable.Drawable? = null

                    if (packageInfo != null) {
                        packageInfo.applicationInfo.sourceDir = apkFile.absolutePath
                        packageInfo.applicationInfo.publicSourceDir = apkFile.absolutePath
                        appName = pm.getApplicationLabel(packageInfo.applicationInfo).toString()
                        pkgName = packageInfo.packageName
                        version = packageInfo.versionName ?: ""
                        try { icon = packageInfo.applicationInfo.loadIcon(pm) } catch (_: Exception) {}
                    }

                    apkList.add(ApkItem(appName, pkgName, version, apkFile.length(), apkFile.absolutePath, icon))
                } catch (_: Exception) {}
            }

            if (apkList.isNotEmpty()) {
                if (apkList.size == 1) {
                    // Single APK - show detail page
                    val intent = Intent(this, ApkDetailActivity::class.java)
                    intent.putExtra("apk_path", apkList[0].filePath)
                    startActivity(intent)
                    finish()
                } else {
                    renderQueue()
                    startInstallation()
                }
            }
        }
    }

    private fun renderQueue() {
        installQueue.removeAllViews()
        for ((index, apk) in apkList.withIndex()) {
            val row = LayoutInflater.from(this).inflate(R.layout.item_apk_install, installQueue, false)
            val ivIcon = row.findViewById<ImageView>(R.id.ivApkIcon)
            val tvName = row.findViewById<TextView>(R.id.tvApkName)
            val tvPkg = row.findViewById<TextView>(R.id.tvApkPackage)
            val tvStatus = row.findViewById<TextView>(R.id.tvStatus)

            if (apk.icon != null) {
                ivIcon.setImageDrawable(apk.icon)
            } else {
                ivIcon.setImageResource(R.drawable.ic_apk_install)
            }
            tvName.text = apk.name
            tvPkg.text = "${apk.packageName} • v${apk.version}"
            tvStatus.text = "等待中"
            tvStatus.setTextColor(getColor(R.color.on_surface_variant))

            installQueue.addView(row)
        }
    }

    private fun startInstallation() {
        overallProgressCard.visibility = View.VISIBLE
        infoCard.visibility = View.VISIBLE
        currentIndex = 0
        installedCount = 0
        installNext()
    }

    private fun installNext() {
        if (currentIndex >= apkList.size) {
            // All done
            tvOverallStatus.text = "安装完成"
            tvOverallPercent.text = "100%"
            overallProgressBar.layoutParams = overallProgressBar.layoutParams.apply { width = -1 }
            tvOverallInfo.text = "已安装 $installedCount/${apkList.size} 个应用"
            return
        }

        val apk = apkList[currentIndex]
        apk.status = "installing"
        updateQueueItem(currentIndex, "正在安装", getColor(R.color.primary))

        tvOverallStatus.text = "正在安装 ${currentIndex + 1}/${apkList.size}"
        val percent = (currentIndex * 100 / apkList.size)
        tvOverallPercent.text = "$percent%"
        overallProgressBar.layoutParams = overallProgressBar.layoutParams.apply {
            width = (overallProgressBar.parent as View).width * percent / 100
        }
        tvOverallInfo.text = apk.name

        Thread {
            val success = installApk(File(apk.filePath))
            apk.status = if (success) "installed" else "failed"
            if (success) installedCount++

            handler.post {
                if (success) {
                    updateQueueItem(currentIndex, "已安装", getColor(R.color.primary))
                } else {
                    updateQueueItem(currentIndex, "安装失败", getColor(R.color.error))
                }
                currentIndex++
                installNext()
            }
        }.start()
    }

    private fun updateQueueItem(index: Int, status: String, color: Int) {
        val row = installQueue.getChildAt(index) ?: return
        val tvStatus = row.findViewById<TextView>(R.id.tvStatus)
        tvStatus.text = status
        tvStatus.setTextColor(color)
    }

    private fun installApk(apkFile: File): Boolean {
        AppLogger.i("ApkInstaller: file=${apkFile.absolutePath}, exists=${apkFile.exists()}, size=${apkFile.length()}")

        var waitCount = 0
        while (taskHideService == null && waitCount < 20) {
            Thread.sleep(250)
            waitCount++
        }

        val binder = taskHideService
        if (binder == null) {
            AppLogger.e("ApkInstaller: taskHideService is null after waiting")
            return false
        }

        fun execCmd(cmd: String): Pair<Boolean, String> {
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken(ITaskHideService.DESCRIPTOR)
                data.writeString(cmd)
                binder.transact(ITaskHideService.TRANSACTION_execCommand, data, reply, 0)
                reply.readException()
                val success = reply.readInt() != 0
                val output = reply.readString() ?: ""
                AppLogger.i("ApkInstaller cmd: $cmd => success=$success, output=${output.take(200)}")
                return Pair(success, output)
            } finally {
                data.recycle()
                reply.recycle()
            }
        }

        // Method 1: Direct pm install
        try {
            val (success, output) = execCmd("pm install -r -g '${apkFile.absolutePath}'")
            if (success || output.contains("Success")) return true
            AppLogger.i("ApkInstaller: direct pm install failed, trying cat pipe")
        } catch (e: Exception) {
            AppLogger.e("ApkInstaller: direct install exception", e)
        }

        // Method 2: cat pipe
        try {
            val (success, output) = execCmd("cat '${apkFile.absolutePath}' | pm install -S ${apkFile.length()}")
            if (success || output.contains("Success")) return true
            AppLogger.i("ApkInstaller: cat pipe failed, trying session install")
        } catch (e: Exception) {
            AppLogger.e("ApkInstaller: cat pipe exception", e)
        }

        // Method 3: Session install (for split APKs)
        try {
            val size = apkFile.length()
            val (createSuccess, createOutput) = execCmd("pm install-create -S $size")
            AppLogger.i("ApkInstaller session: create success=$createSuccess, output=$createOutput")
            if (createSuccess || createOutput.contains("Success")) {
                val sessionMatch = Regex("\\[(\\d+)\\]").find(createOutput)
                if (sessionMatch != null) {
                    val sessionId = sessionMatch.groupValues[1]
                    AppLogger.i("ApkInstaller session: sessionId=$sessionId")
                    val (writeSuccess, writeOutput) = execCmd("pm install-write $sessionId base '${apkFile.absolutePath}'")
                    AppLogger.i("ApkInstaller session: write success=$writeSuccess, output=$writeOutput")
                    val (commitSuccess, commitOutput) = execCmd("pm install-commit $sessionId")
                    AppLogger.i("ApkInstaller session: commit success=$commitSuccess, output=$commitOutput")
                    if (commitSuccess || commitOutput.contains("Success")) return true
                }
            }
        } catch (e: Exception) {
            AppLogger.e("ApkInstaller: session install exception", e)
        }

        return false
    }

    private fun getRealPathFromUri(uri: android.net.Uri): String? {
        try {
            // Check if it's a file URI
            if (uri.scheme == "file") {
                return uri.path
            }

            // Try to get path from document URI
            if (android.provider.DocumentsContract.isDocumentUri(this, uri)) {
                val docId = android.provider.DocumentsContract.getDocumentId(uri)
                if (uri.authority == "com.android.externalstorage.documents") {
                    val parts = docId.split(":")
                    if (parts.size == 2) {
                        return "/storage/emulated/0/${parts[1]}"
                    }
                }
                if (uri.authority == "com.android.providers.downloads.documents") {
                    if (docId.startsWith("raw:")) {
                        return docId.removePrefix("raw:")
                    }
                }
            }

            // Try content provider query
            val projection = arrayOf(android.provider.MediaStore.MediaColumns.DATA)
            contentResolver.query(uri, projection, null, null, null)?.use {
                if (it.moveToFirst()) {
                    val idx = it.getColumnIndex(android.provider.MediaStore.MediaColumns.DATA)
                    if (idx >= 0) return it.getString(idx)
                }
            }
        } catch (_: Exception) {}
        return null
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            rikka.shizuku.Shizuku.unbindUserService(
                rikka.shizuku.Shizuku.UserServiceArgs(ComponentName(this, TaskHideService::class.java)),
                shizukuConnection, true
            )
        } catch (_: Exception) {}
    }
}
