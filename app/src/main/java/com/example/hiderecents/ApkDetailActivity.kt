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
import com.example.hiderecents.ui.screens.ApkDetailInfo
import com.example.hiderecents.ui.screens.ApkDetailScreen
import com.example.hiderecents.ui.screens.InstallStage
import com.example.hiderecents.ui.screens.LoadingShell
import com.example.hiderecents.ui.theme.AppTheme
import com.example.hiderecents.ui.theme.applyDshSystemBars
import com.example.hiderecents.ui.theme.DshTheme
import rikka.shizuku.Shizuku
import java.io.File

/**
 * APK 详情（Compose 重写版）。
 *
 * 功能与原 ApkDetailActivity 逐条对齐：
 * - apk_path extra 与 intent.data 两条入口，后者复制到缓存并校验 ZIP 头与大小
 * - getPackageArchiveInfo 取应用名 / 包名 / 版本 / 图标 / minSdk / targetSdk / 权限数
 * - 安装前复制到 getExternalFilesDir(null)/install.apk（shell 读不到应用 cache）
 * - 两路安装：pm install -r -g -> cat | pm install -S
 * - 成功后主按钮变「打开应用」，失败变「重试安装」并显示原因
 *
 * 与原实现的一处差异：不再用匀速假进度，改为真实阶段（准备 / 复制 / 安装 / 结果）。
 */
class ApkDetailActivity : DshActivity() {

    private lateinit var prefs: SharedPreferences
    private val handler = Handler(Looper.getMainLooper())

    private var taskHideService: IBinder? = null
    private var shizukuBound = false

    private var apkFile: File? = null
    private var packageNameHint = ""
    private var installed = false
    private var installing = false
    private var lastInstallError = ""

    private var info by mutableStateOf<ApkDetailInfo?>(null)
    private var stage by mutableStateOf(InstallStage.IDLE)
    private var errorMessage by mutableStateOf("")

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
                val current = info
                if (current == null) {
                    LoadingShell(onBack = { dshGoBack() })
                } else {
                    ApkDetailScreen(
                        info = current,
                        stage = stage,
                        errorMessage = errorMessage,
                        installed = installed,
                        onBack = { dshGoBack() },
                        onInstall = { startInstall() },
                        onOpen = { openInstalledApp() },
                    )
                }
            }
        }

        bindShizuku()
        resolveInput()
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

    private fun applySystemBars(theme: AppTheme) {
        applyDshSystemBars(theme)
    }

    // ------------------------------------------------------------------ Shizuku

    private fun bindShizuku() {
        try {
            Shizuku.bindUserService(
                Shizuku.UserServiceArgs(ComponentName(this, TaskHideService::class.java))
                    .daemon(false).processNameSuffix("apkdetail").debuggable(true).version(2),
                shizukuConnection,
            )
        } catch (_: Exception) {
        }
    }

    // ------------------------------------------------------------------ 输入解析

    private fun resolveInput() {
        val apkPath = intent.getStringExtra("apk_path")
        if (apkPath != null) {
            loadApkInfo(File(apkPath), autoInstall = true)
            return
        }
        val uri = intent.data
        if (uri != null) {
            copyAndLoadFromUri(uri)
            return
        }
        Toast.makeText(this, "无文件信息", Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun copyAndLoadFromUri(uri: Uri) {
        stage = InstallStage.PREPARING
        Thread {
            try {
                var fileName = "app.apk"
                contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                        if (idx >= 0) fileName = cursor.getString(idx) ?: fileName
                    }
                }

                val normalized = fileName.replace(Regex("\\.apk\\.\\d+$", RegexOption.IGNORE_CASE), ".apk")
                val cacheFile = File(cacheDir, normalized)

                val input = contentResolver.openInputStream(uri)
                if (input == null) {
                    handler.post {
                        stage = InstallStage.FAILED
                        errorMessage = "无法读取文件"
                        Toast.makeText(this, "无法读取文件", Toast.LENGTH_SHORT).show()
                    }
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

                val actualSize = cacheFile.length()
                val header = try {
                    cacheFile.inputStream().use { it.readNBytes(4) }
                } catch (_: Exception) {
                    ByteArray(0)
                }
                val isZip = header.size >= 4 &&
                    header[0] == 0x50.toByte() && header[1] == 0x4B.toByte() &&
                    header[2] == 0x03.toByte() && header[3] == 0x04.toByte()

                if (!isZip || actualSize < 100) {
                    handler.post {
                        stage = InstallStage.FAILED
                        errorMessage = "文件无效：read=" + totalBytes + " actual=" + actualSize + " zip=" + isZip
                        Toast.makeText(
                            this,
                            "文件无效: read=$totalBytes actual=$actualSize zip=$isZip",
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                    return@Thread
                }

                handler.post {
                    stage = InstallStage.IDLE
                    loadApkInfo(cacheFile, autoInstall = true)
                }
            } catch (e: Exception) {
                handler.post {
                    stage = InstallStage.FAILED
                    errorMessage = "读取失败：" + (e.message ?: "")
                    Toast.makeText(this, "读取失败: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    // ------------------------------------------------------------------ 解析 APK

    private fun loadApkInfo(file: File, autoInstall: Boolean) {
        if (!file.exists() || file.length() < 100) {
            Toast.makeText(this, "文件无效或不存在", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        apkFile = file
        val pm = packageManager
        val packageInfo = pm.getPackageArchiveInfo(
            file.absolutePath,
            PackageManager.GET_META_DATA or PackageManager.GET_PERMISSIONS,
        )

        var appName = file.name
        var pkgName = ""
        var version = ""
        var minSdkText = "未知"
        var targetSdkText = "未知"
        var permissionCount = 0
        var icon: ImageBitmap? = null

        if (packageInfo != null) {
            pkgName = packageInfo.packageName
            version = packageInfo.versionName ?: ""
            permissionCount = packageInfo.requestedPermissions?.size ?: 0
            packageInfo.applicationInfo?.let { appInfo ->
                appInfo.sourceDir = file.absolutePath
                appInfo.publicSourceDir = file.absolutePath
                appName = pm.getApplicationLabel(appInfo).toString()
                minSdkText = "Android " + getAndroidVersion(appInfo.minSdkVersion) +
                    " (" + appInfo.minSdkVersion + ")"
                targetSdkText = "Android " + getAndroidVersion(appInfo.targetSdkVersion) +
                    " (" + appInfo.targetSdkVersion + ")"
                icon = try {
                    AppIconLoader.toSafeBitmap(appInfo.loadIcon(pm), 128).asImageBitmap()
                } catch (_: Exception) {
                    null
                }
            }
        } else {
            version = "未知版本"
        }

        packageNameHint = pkgName
        info = ApkDetailInfo(
            appName = appName,
            packageName = pkgName,
            version = version,
            fileSize = formatSize(file.length()),
            minSdk = minSdkText,
            targetSdk = targetSdkText,
            permissionCount = permissionCount,
            icon = icon,
            seed = file.name.hashCode(),
        )

        if (autoInstall) startInstall()
    }

    // ------------------------------------------------------------------ 安装

    private fun startInstall() {
        if (installing || installed) return
        val file = apkFile ?: return
        installing = true
        errorMessage = ""
        stage = InstallStage.PREPARING

        Thread {
            var waitCount = 0
            while (TaskHideBridge.resolve(taskHideService) == null && waitCount < 40) {
                Thread.sleep(250)
                waitCount++
            }

            val binder = TaskHideBridge.resolve(taskHideService)
            if (binder == null) {
                handler.post {
                    installing = false
                    stage = InstallStage.FAILED
                    errorMessage = "Shizuku 未连接，无法以 shell 身份安装"
                    Toast.makeText(this, "Shizuku 未连接", Toast.LENGTH_SHORT).show()
                }
                return@Thread
            }

            handler.post { stage = InstallStage.COPYING }
            val success = installApk(file, binder)

            handler.post {
                installing = false
                if (success) {
                    installed = true
                    stage = InstallStage.SUCCESS
                    errorMessage = ""
                } else {
                    stage = InstallStage.FAILED
                    errorMessage = lastInstallError.ifEmpty { "安装失败，未拿到系统返回" }
                }
            }
        }.start()
    }

    private fun installApk(apkFile: File, binder: IBinder): Boolean {
        lastInstallError = ""

        fun execCmd(cmd: String): Pair<Boolean, String> {
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken(ITaskHideService.DESCRIPTOR)
                data.writeString(cmd)
                binder.transact(ITaskHideService.TRANSACTION_execCommand, data, reply, 0)
                reply.readException()
                val ok = reply.readInt() != 0
                val out = reply.readString() ?: ""
                AppLogger.i("ApkDetail cmd: $cmd => success=$ok output=${out.take(200)}")
                return Pair(ok, out)
            } finally {
                data.recycle()
                reply.recycle()
            }
        }

        // 复制到 shell 可读的外部私有目录
        val extDir = getExternalFilesDir(null)
        if (extDir != null && !extDir.exists()) extDir.mkdirs()
        val tmpFile = File(extDir, "install.apk")
        try {
            apkFile.copyTo(tmpFile, overwrite = true)
        } catch (e: Exception) {
            lastInstallError = "复制失败：" + (e.message ?: "")
            return false
        }

        handler.post { stage = InstallStage.INSTALLING }
        val path = tmpFile.absolutePath

        try {
            val (ok, out) = execCmd("pm install -r -g '$path'")
            if (ok || out.contains("Success")) {
                tmpFile.delete()
                return true
            }
            lastInstallError = "pm install：" + out
        } catch (e: Exception) {
            lastInstallError = "pm install 异常：" + (e.message ?: "")
        }

        try {
            val (ok, out) = execCmd("cat '$path' | pm install -S ${tmpFile.length()}")
            if (ok || out.contains("Success")) {
                tmpFile.delete()
                return true
            }
            lastInstallError = lastInstallError + "\ncat 管道：" + out
        } catch (e: Exception) {
            lastInstallError = lastInstallError + "\ncat 管道异常：" + (e.message ?: "")
        }

        tmpFile.delete()

        // shell 安装在这台 MIUI 上会被 verifyInstallFromShell 秒取消，兜底交给系统安装会话，
        // 安装页由用户手动确认；偏好 install_page_fallback=false 时纯静默。
        if (!SilentApkInstaller.pageFallbackEnabled()) {
            AppLogger.i("ApkDetail: page fallback off, shell only")
            return false
        }
        val sys = SilentApkInstaller.installViaSystem(this, apkFile)
        AppLogger.i("ApkDetail system fallback: ok=" + sys.first + " msg=" + sys.second)
        if (sys.first) return true

        lastInstallError = lastInstallError + "\n系统安装器：" + sys.second
        return false
    }

    private fun openInstalledApp() {
        val pkg = packageNameHint
        if (pkg.isEmpty()) {
            finish()
            return
        }
        val launchIntent = packageManager.getLaunchIntentForPackage(pkg)
        if (launchIntent != null) {
            startActivity(launchIntent)
        } else {
            Toast.makeText(this, "没有可启动的界面", Toast.LENGTH_SHORT).show()
        }
        finish()
    }

    // ------------------------------------------------------------------ 工具

    private fun formatSize(bytes: Long): String = when {
        bytes < 1024 * 1024 -> String.format("%.1f KB", bytes / 1024.0)
        bytes < 1024L * 1024 * 1024 -> String.format("%.1f MB", bytes / 1048576.0)
        else -> String.format("%.2f GB", bytes / 1073741824.0)
    }

    private fun getAndroidVersion(sdk: Int): String = when (sdk) {
        21 -> "5.0"
        22 -> "5.1"
        23 -> "6.0"
        24 -> "7.0"
        25 -> "7.1"
        26 -> "8.0"
        27 -> "8.1"
        28 -> "9"
        29 -> "10"
        30 -> "11"
        31 -> "12"
        32 -> "12L"
        33 -> "13"
        34 -> "14"
        35 -> "15"
        else -> sdk.toString()
    }
}
