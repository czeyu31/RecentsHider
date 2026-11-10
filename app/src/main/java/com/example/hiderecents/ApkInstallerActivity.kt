package com.example.hiderecents

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import androidx.core.view.WindowCompat
import com.example.hiderecents.ui.screens.ApkInstallerScreen
import com.example.hiderecents.ui.screens.ApkQueueItem
import com.example.hiderecents.ui.screens.ApkStatus
import com.example.hiderecents.ui.theme.AppTheme
import com.example.hiderecents.ui.theme.DshTheme
import rikka.shizuku.Shizuku
import java.io.File

/**
 * APK 安装器（Compose 重写版）。
 *
 * 功能与原 ApkInstallerActivity 逐条对齐：
 * - 多选 APK，解析文件名（.apk.1 规范化）、真实路径或复制到缓存
 * - getPackageArchiveInfo 取应用名 / 包名 / 版本 / 图标
 * - 单文件跳 ApkDetailActivity，多文件进队列串行安装
 * - 三路安装：pm install -r -g -> cat | pm install -S -> pm install-create/write/commit
 * - 逐项状态与总体进度、失败重试
 */
class ApkInstallerActivity : AppCompatActivity() {

    private companion object {
        const val REQ_PICK_APK = 2001
    }

    private lateinit var prefs: SharedPreferences
    private val handler = Handler(Looper.getMainLooper())

    private var taskHideService: IBinder? = null
    private var shizukuBound = false

    private var queue by mutableStateOf(listOf<ApkQueueItem>())
    private var installing by mutableStateOf(false)
    private var overallLabel by mutableStateOf("")
    private var overallPercent by mutableStateOf(0)
    private var overallInfo by mutableStateOf("")

    /** 宿主侧队列，携带真实文件路径 */
    private data class QueuedApk(
        val name: String,
        val packageName: String,
        val version: String,
        val size: Long,
        val filePath: String,
        val icon: ImageBitmap?,
        var status: ApkStatus = ApkStatus.WAITING,
    )

    private val queued = mutableListOf<QueuedApk>()
    private var currentIndex = -1
    private var installedCount = 0

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

        prefs = getSharedPreferences("hide_recents_prefs", Context.MODE_PRIVATE)
        val theme = AppTheme.fromPref(prefs.getString("ui_theme", null))
        applySystemBars(theme)

        setContent {
            DshTheme(theme = theme) {
                ApkInstallerScreen(
                    items = queue,
                    installing = installing,
                    overallLabel = overallLabel,
                    overallPercent = overallPercent,
                    overallInfo = overallInfo,
                    shizukuReady = shizukuBound,
                    onBack = { finish() },
                    onSelectApk = { selectApk() },
                    onRetry = { retryFailed() },
                )
            }
        }

        bindShizuku()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
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

    @Suppress("DEPRECATION")
    private fun applySystemBars(theme: AppTheme) {
        val systemDark = (resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        val paper = when (theme) {
            AppTheme.SYSTEM -> !systemDark
            AppTheme.PAPER -> true
            AppTheme.GRAPHITE -> false
        }
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.isAppearanceLightStatusBars = paper
        controller.isAppearanceLightNavigationBars = paper
        val color = if (paper) 0xFFF1EDE3.toInt() else 0xFF0F1214.toInt()
        window.statusBarColor = color
        window.navigationBarColor = color
    }

    // ------------------------------------------------------------------ Shizuku

    private fun bindShizuku() {
        try {
            Shizuku.bindUserService(
                Shizuku.UserServiceArgs(ComponentName(this, TaskHideService::class.java))
                    .daemon(false).processNameSuffix("apkinstaller").debuggable(true).version(1),
                shizukuConnection,
            )
        } catch (_: Exception) {
        }
    }

    // ------------------------------------------------------------------ 选择文件

    @Suppress("DEPRECATION")
    private fun selectApk() {
        val intent = Intent(Intent.ACTION_GET_CONTENT)
        intent.type = "*/*"
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        intent.addCategory(Intent.CATEGORY_OPENABLE)
        startActivityForResult(intent, REQ_PICK_APK)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_PICK_APK || resultCode != RESULT_OK) return

        queued.clear()
        queue = emptyList()
        installedCount = 0
        overallPercent = 0

        val uris = mutableListOf<Uri>()
        val clip = data?.clipData
        if (clip != null) {
            for (i in 0 until clip.itemCount) uris.add(clip.getItemAt(i).uri)
        } else if (data?.data != null) {
            uris.add(data.data!!)
        }

        for (uri in uris) {
            try {
                var fileName = "app.apk"
                contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                        if (idx >= 0) fileName = cursor.getString(idx) ?: fileName
                    }
                }

                val normalized = fileName.replace(Regex("\\.apk\\.\\d+$", RegexOption.IGNORE_CASE), ".apk")
                val realPath = getRealPathFromUri(uri)

                val apkFile: File
                if (realPath != null && File(realPath).exists()) {
                    apkFile = File(realPath)
                    AppLogger.i("ApkInstaller: using real path: $realPath")
                } else {
                    val cacheFile = File(cacheDir, normalized)
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
                val packageInfo = pm.getPackageArchiveInfo(
                    apkFile.absolutePath,
                    PackageManager.GET_META_DATA,
                )

                var appName = fileName
                var pkgName = ""
                var version = ""
                var icon: ImageBitmap? = null

                if (packageInfo != null) {
                    packageInfo.applicationInfo?.let { info ->
                        info.sourceDir = apkFile.absolutePath
                        info.publicSourceDir = apkFile.absolutePath
                        appName = pm.getApplicationLabel(info).toString()
                        pkgName = packageInfo.packageName
                        version = packageInfo.versionName ?: ""
                        icon = try {
                            info.loadIcon(pm).toBitmap(96, 96).asImageBitmap()
                        } catch (_: Exception) {
                            null
                        }
                    }
                }

                queued.add(
                    QueuedApk(
                        name = appName,
                        packageName = pkgName,
                        version = version,
                        size = apkFile.length(),
                        filePath = apkFile.absolutePath,
                        icon = icon,
                    ),
                )
            } catch (e: Exception) {
                AppLogger.e("ApkInstaller: parse uri failed", e)
            }
        }

        if (queued.isEmpty()) {
            Toast.makeText(this, "没有读到有效的 APK", Toast.LENGTH_SHORT).show()
            return
        }

        if (queued.size == 1) {
            val intent = Intent(this, ApkDetailActivity::class.java)
            intent.putExtra("apk_path", queued[0].filePath)
            startActivity(intent)
            finish()
            return
        }

        publish()
        startInstallation()
    }

    private fun getRealPathFromUri(uri: Uri): String? {
        try {
            if (uri.scheme == "file") return uri.path

            if (android.provider.DocumentsContract.isDocumentUri(this, uri)) {
                val docId = android.provider.DocumentsContract.getDocumentId(uri)
                if (uri.authority == "com.android.externalstorage.documents") {
                    val parts = docId.split(":")
                    if (parts.size == 2) return "/storage/emulated/0/${parts[1]}"
                }
                if (uri.authority == "com.android.providers.downloads.documents") {
                    if (docId.startsWith("raw:")) return docId.removePrefix("raw:")
                }
            }

            val projection = arrayOf(android.provider.MediaStore.MediaColumns.DATA)
            contentResolver.query(uri, projection, null, null, null)?.use {
                if (it.moveToFirst()) {
                    val idx = it.getColumnIndex(android.provider.MediaStore.MediaColumns.DATA)
                    if (idx >= 0) return it.getString(idx)
                }
            }
        } catch (_: Exception) {
        }
        return null
    }

    // ------------------------------------------------------------------ 安装

    private fun publish() {
        queue = queued.map { item ->
            ApkQueueItem(
                name = item.name,
                packageName = item.packageName,
                version = item.version,
                sizeLabel = formatSize(item.size),
                icon = item.icon,
                status = item.status,
            )
        }
    }

    private fun startInstallation() {
        installing = true
        currentIndex = 0
        installedCount = 0
        installNext()
    }

    private fun installNext() {
        if (currentIndex >= queued.size) {
            installing = false
            overallLabel = "安装完成"
            overallPercent = 100
            overallInfo = "已安装 " + installedCount + "/" + queued.size + " 个应用"
            publish()
            return
        }

        val apk = queued[currentIndex]
        apk.status = ApkStatus.INSTALLING
        publish()

        overallLabel = "正在安装 " + (currentIndex + 1) + "/" + queued.size
        overallPercent = currentIndex * 100 / queued.size
        overallInfo = apk.name

        Thread {
            val success = installApk(File(apk.filePath))
            handler.post {
                apk.status = if (success) ApkStatus.INSTALLED else ApkStatus.FAILED
                if (success) installedCount++
                currentIndex++
                publish()
                installNext()
            }
        }.start()
    }

    private fun retryFailed() {
        val failed = queued.filter { it.status == ApkStatus.FAILED }
        if (failed.isEmpty()) return
        failed.forEach { it.status = ApkStatus.WAITING }
        queued.removeAll { it.status == ApkStatus.INSTALLED }
        if (queued.isEmpty()) return
        publish()
        startInstallation()
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

        // 1) 直接 pm install
        try {
            val (success, output) = execCmd("pm install -r -g '${apkFile.absolutePath}'")
            if (success || output.contains("Success")) return true
            AppLogger.i("ApkInstaller: direct pm install failed, trying cat pipe")
        } catch (e: Exception) {
            AppLogger.e("ApkInstaller: direct install exception", e)
        }

        // 2) cat 管道
        try {
            val (success, output) = execCmd("cat '${apkFile.absolutePath}' | pm install -S ${apkFile.length()}")
            if (success || output.contains("Success")) return true
            AppLogger.i("ApkInstaller: cat pipe failed, trying session install")
        } catch (e: Exception) {
            AppLogger.e("ApkInstaller: cat pipe exception", e)
        }

        // 3) session 安装
        try {
            val size = apkFile.length()
            val (createSuccess, createOutput) = execCmd("pm install-create -S $size")
            AppLogger.i("ApkInstaller session: create success=$createSuccess, output=$createOutput")
            if (createSuccess || createOutput.contains("Success")) {
                val match = Regex("\\[(\\d+)\\]").find(createOutput)
                if (match != null) {
                    val sessionId = match.groupValues[1]
                    val (writeSuccess, writeOutput) = execCmd(
                        "pm install-write $sessionId base '${apkFile.absolutePath}'",
                    )
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

    private fun formatSize(bytes: Long): String = when {
        bytes < 1024 * 1024 -> String.format("%.1f KB", bytes / 1024.0)
        bytes < 1024L * 1024 * 1024 -> String.format("%.1f MB", bytes / 1048576.0)
        else -> String.format("%.2f GB", bytes / 1073741824.0)
    }
}
