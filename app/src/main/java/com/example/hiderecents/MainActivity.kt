package com.example.hiderecents

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import rikka.shizuku.Shizuku

class MainActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: AppListAdapter
    private lateinit var prefs: SharedPreferences
    private lateinit var etSearch: TextInputEditText
    private lateinit var tvHiddenCount: TextView
    private lateinit var toolbar: MaterialToolbar
    private lateinit var searchLayout: TextInputLayout
    private lateinit var btnRestoreAll: MaterialButton
    private lateinit var rootLayout: View
    private lateinit var bottomNav: BottomNavigationView
    private lateinit var appsContainer: View
    private lateinit var adbContainer: View
    private lateinit var etAdbCommand: TextInputEditText
    private lateinit var btnExecute: MaterialButton
    private lateinit var tvAdbOutput: TextView
    private lateinit var btnClearOutput: MaterialButton
    private lateinit var svOutput: ScrollView
    private lateinit var tvDeviceInfo: TextView
    private lateinit var tvAndroidInfo: TextView
    private lateinit var rvCommands: RecyclerView
    private lateinit var llCommandsHeader: View
    private lateinit var ivExpandArrow: ImageView

    private val hiddenApps = mutableSetOf<String>()
    private var shizukuPermissionGranted = false
    private var taskHideService: IBinder? = null
    private var serviceReady = false
    private var serviceToastShown = false
    private var allApps = listOf<AppItem>()
    private var currentQuery = ""
    private var isCommandsExpanded = false
    private val adbHistory = StringBuilder()

    private val themeColors = intArrayOf(
        0xFF90CAF9.toInt(), 0xFFA5D6A7.toInt(), 0xFFCE93D8.toInt(), 0xFFFFCC80.toInt(),
        0xFFEF9A9A.toInt(), 0xFF80CBC4.toInt(), 0xFFF48FB1.toInt(), 0xFF80DEEA.toInt(),
        0xFFFFFFFF.toInt(), 0xFFBDBDBD.toInt(), 0xFF000000.toInt(), 0xFFA1887F.toInt()
    )
    private var currentThemeColor = 0

    data class AdbCommand(val command: String, val description: String)

    private val commonCommands = listOf(
        AdbCommand("getprop ro.product.model", "获取设备型号"),
        AdbCommand("getprop ro.build.version.release", "获取Android版本"),
        AdbCommand("getprop ro.product.brand", "获取设备品牌"),
        AdbCommand("getprop ro.build.display.id", "获取系统版本号"),
        AdbCommand("settings get system screen_brightness", "获取屏幕亮度"),
        AdbCommand("dumpsys battery", "查看电池状态"),
        AdbCommand("pm list packages", "列出所有已安装应用"),
        AdbCommand("dumpsys activity recents", "查看最近任务列表"),
        AdbCommand("getprop persist.sys.timezone", "获取系统时区"),
        AdbCommand("wm size", "获取屏幕分辨率"),
        AdbCommand("wm density", "获取屏幕密度"),
        AdbCommand("df -h", "查看存储空间"),
        AdbCommand("cat /proc/cpuinfo", "查看CPU信息"),
        AdbCommand("cat /proc/meminfo", "查看内存信息")
    )

    private val shizukuPermissionResultListener =
        Shizuku.OnRequestPermissionResultListener { _, grantResult ->
            shizukuPermissionGranted = grantResult == PackageManager.PERMISSION_GRANTED
            runOnUiThread {
                Toast.makeText(this, if (shizukuPermissionGranted) "Shizuku 已授权" else "Shizuku 权限被拒绝", Toast.LENGTH_SHORT).show()
            }
        }

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener { checkShizukuPermission() }
    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        shizukuPermissionGranted = false; taskHideService = null; serviceReady = false; serviceToastShown = false
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            taskHideService = service; serviceReady = true
            if (!serviceToastShown) {
                serviceToastShown = true
                runOnUiThread { Toast.makeText(this@MainActivity, "服务已连接", Toast.LENGTH_SHORT).show() }
            }
            loadDeviceInfo()
        }
        override fun onServiceDisconnected(name: ComponentName) {
            taskHideService = null; serviceReady = false; serviceToastShown = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences("hide_recents_prefs", Context.MODE_PRIVATE)
        hiddenApps.addAll(prefs.getStringSet("hidden_apps", emptySet()) ?: emptySet())
        currentThemeColor = prefs.getInt("theme_color", 0)

        toolbar = findViewById(R.id.toolbar)
        searchLayout = findViewById(R.id.searchLayout)
        btnRestoreAll = findViewById(R.id.btnRestoreAll)
        etSearch = findViewById(R.id.etSearch)
        tvHiddenCount = findViewById(R.id.tvHiddenCount)
        rootLayout = findViewById(R.id.rootLayout)
        bottomNav = findViewById(R.id.bottomNav)
        appsContainer = findViewById(R.id.appsContainer)
        adbContainer = findViewById(R.id.adbContainer)
        etAdbCommand = findViewById(R.id.etAdbCommand)
        btnExecute = findViewById(R.id.btnExecute)
        tvAdbOutput = findViewById(R.id.tvAdbOutput)
        btnClearOutput = findViewById(R.id.btnClearOutput)
        svOutput = findViewById(R.id.svOutput)
        tvDeviceInfo = findViewById(R.id.tvDeviceInfo)
        tvAndroidInfo = findViewById(R.id.tvAndroidInfo)
        rvCommands = findViewById(R.id.rvCommands)
        llCommandsHeader = findViewById(R.id.llCommandsHeader)
        ivExpandArrow = findViewById(R.id.ivExpandArrow)

        setSupportActionBar(toolbar)
        applyThemeColor()

        recyclerView = findViewById(R.id.recyclerView)
        recyclerView.layoutManager = LinearLayoutManager(this)
        adapter = AppListAdapter(
            onToggle = { pkg, hide -> toggleAppVisibility(pkg, hide) },
            onUninstall = { pkg, name -> confirmUninstall(pkg, name) },
            onLongClick = { pkg -> showAppDetail(pkg) }
        )
        recyclerView.adapter = adapter

        rvCommands.layoutManager = LinearLayoutManager(this)
        rvCommands.adapter = CommandAdapter(commonCommands) { cmd ->
            etAdbCommand.setText(cmd)
            executeAdbCommand()
        }

        llCommandsHeader.setOnClickListener {
            isCommandsExpanded = !isCommandsExpanded
            rvCommands.visibility = if (isCommandsExpanded) View.VISIBLE else View.GONE
            ivExpandArrow.rotation = if (isCommandsExpanded) 180f else 0f
        }

        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                currentQuery = s?.toString()?.lowercase() ?: ""
                filterApps()
            }
        })

        btnRestoreAll.setOnClickListener { restoreAllApps() }

        bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_apps -> { appsContainer.visibility = View.VISIBLE; adbContainer.visibility = View.GONE; true }
                R.id.nav_adb -> { appsContainer.visibility = View.GONE; adbContainer.visibility = View.VISIBLE; true }
                else -> false
            }
        }

        btnExecute.setOnClickListener { executeAdbCommand() }
        btnClearOutput.setOnClickListener { adbHistory.clear(); tvAdbOutput.text = "等待执行命令..." }

        Shizuku.addRequestPermissionResultListener(shizukuPermissionResultListener)
        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)

        checkShizukuPermission()
        loadInstalledApps()
    }

    override fun onDestroy() {
        super.onDestroy()
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

    private fun loadDeviceInfo() {
        Thread {
            val model = executeCommandSync("getprop ro.product.model")
            val brand = executeCommandSync("getprop ro.product.brand")
            runOnUiThread {
                tvDeviceInfo.text = "$brand $model"
                tvAndroidInfo.text = "Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})"
            }
        }.start()
    }

    private fun executeCommandSync(command: String): String {
        val binder = taskHideService ?: return ""
        val data = Parcel.obtain(); val reply = Parcel.obtain()
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

    private fun applyThemeColor() {
        val color = themeColors[currentThemeColor]
        val isLight = isColorLight(color)
        val textColor = if (isLight) Color.BLACK else Color.WHITE
        val subTextColor = if (isLight) Color.argb(200, 0, 0, 0) else Color.argb(200, 255, 255, 255)
        val hintColor = if (isLight) Color.argb(120, 0, 0, 0) else Color.argb(120, 255, 255, 255)

        window.statusBarColor = color
        window.navigationBarColor = color

        toolbar.setBackgroundColor(color)
        toolbar.setTitleTextColor(textColor)
        toolbar.overflowIcon?.setTint(textColor)

        searchLayout.boxStrokeColor = textColor
        searchLayout.setStartIconTintList(android.content.res.ColorStateList.valueOf(textColor))
        etSearch.setTextColor(textColor)
        etSearch.setHintTextColor(hintColor)

        btnRestoreAll.setTextColor(textColor)
        tvHiddenCount.setTextColor(subTextColor)

        etAdbCommand.setTextColor(textColor)
        etAdbCommand.setHintTextColor(hintColor)
        tvAdbOutput.setTextColor(textColor)
        tvDeviceInfo.setTextColor(textColor)
        tvAndroidInfo.setTextColor(subTextColor)

        val colorStateList = android.content.res.ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_selected), intArrayOf()),
            intArrayOf(textColor, subTextColor)
        )
        bottomNav.setBackgroundColor(color)
        bottomNav.itemIconTintList = colorStateList
        bottomNav.itemTextColor = colorStateList

        rootLayout.setBackgroundColor(color)
        invalidateOptionsMenu()
    }

    private fun isColorLight(color: Int): Boolean {
        return (0.299 * Color.red(color) + 0.587 * Color.green(color) + 0.114 * Color.blue(color)) / 255 > 0.5
    }

    private fun lightenColor(color: Int, factor: Float): Int {
        val r = Color.red(color); val g = Color.green(color); val b = Color.blue(color)
        return Color.argb(255, (r + (255 - r) * factor).toInt().coerceIn(0, 255),
            (g + (255 - g) * factor).toInt().coerceIn(0, 255), (b + (255 - b) * factor).toInt().coerceIn(0, 255))
    }

    private fun darkenColor(color: Int, factor: Float): Int {
        val r = Color.red(color); val g = Color.green(color); val b = Color.blue(color)
        return Color.argb(255, (r * (1 - factor)).toInt().coerceIn(0, 255),
            (g * (1 - factor)).toInt().coerceIn(0, 255), (b * (1 - factor)).toInt().coerceIn(0, 255))
    }

    private fun showThemeColorPicker() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_color_picker, null)
        val dialog = MaterialAlertDialogBuilder(this).setTitle("选择主题颜色").setView(dialogView).setCancelable(true).create()
        val ids = listOf(R.id.color_0, R.id.color_1, R.id.color_2, R.id.color_3, R.id.color_4, R.id.color_5,
            R.id.color_6, R.id.color_7, R.id.color_8, R.id.color_9, R.id.color_10, R.id.color_11)
        ids.forEachIndexed { index, id ->
            dialogView.findViewById<View>(id).setOnClickListener {
                currentThemeColor = index
                prefs.edit().putInt("theme_color", index).apply()
                applyThemeColor()
                adapter.notifyDataSetChanged()
                (rvCommands.adapter as? CommandAdapter)?.notifyDataSetChanged()
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun showSettings() {
        MaterialAlertDialogBuilder(this).setTitle("设置")
            .setMessage("使用说明：\n• 点击调色盘可切换主题颜色\n• 点击开关可隐藏/恢复应用\n• 长按应用图标查看详情\n• 底部导航栏切换应用/ADB模式")
            .setPositiveButton("确定", null).show()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean { menuInflater.inflate(R.menu.main_menu, menu); return true }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_theme -> { showThemeColorPicker(); true }
            R.id.action_settings -> { showSettings(); true }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        val color = themeColors[currentThemeColor]
        val iconColor = if (isColorLight(color)) Color.BLACK else Color.WHITE
        menu.findItem(R.id.action_theme)?.icon?.setTint(iconColor)
        menu.findItem(R.id.action_settings)?.icon?.setTint(iconColor)
        return super.onPrepareOptionsMenu(menu)
    }

    private fun checkShizukuPermission() {
        if (!Shizuku.pingBinder() || Shizuku.isPreV11()) return
        if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
            shizukuPermissionGranted = true; bindService()
        } else {
            Shizuku.requestPermission(1001)
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

    private fun loadInstalledApps() {
        val pm = packageManager
        val packages = pm.getInstalledApplications(PackageManager.GET_META_DATA)
        allApps = packages
            .filter { pm.getLaunchIntentForPackage(it.packageName) != null }
            .map { AppItem(it.packageName, pm.getApplicationLabel(it).toString(), pm.getApplicationIcon(it), hiddenApps.contains(it.packageName)) }
            .sortedWith(compareByDescending<AppItem> { it.isHidden }.thenBy { it.appName.lowercase() })
        filterApps()
    }

    private fun filterApps() {
        val filtered = if (currentQuery.isEmpty()) allApps else allApps.filter {
            it.appName.lowercase().contains(currentQuery) || it.packageName.lowercase().contains(currentQuery)
        }
        tvHiddenCount.text = getString(R.string.hidden_count, allApps.count { it.isHidden })
        runOnUiThread { adapter.submitList(filtered) }
    }

    private fun callSetInvisible(packageName: String, invisible: Boolean): Boolean {
        val binder = taskHideService ?: return false
        val data = Parcel.obtain(); val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(ITaskHideService.DESCRIPTOR)
            data.writeString(packageName)
            data.writeByte(if (invisible) 1 else 0)
            binder.transact(ITaskHideService.TRANSACTION_setTaskInvisible, data, reply, 0)
            reply.readException()
            return reply.readInt() != 0
        } catch (e: Exception) {
            runOnUiThread { Toast.makeText(this, "错误: ${e.message}", Toast.LENGTH_SHORT).show() }
            return false
        } finally { data.recycle(); reply.recycle() }
    }

    private fun toggleAppVisibility(packageName: String, hide: Boolean) {
        if (!shizukuPermissionGranted || !serviceReady) {
            runOnUiThread { Toast.makeText(this, "Shizuku 未就绪", Toast.LENGTH_SHORT).show() }; return
        }
        Thread {
            val success = callSetInvisible(packageName, hide)
            runOnUiThread {
                if (hide) {
                    if (success) {
                        hiddenApps.add(packageName)
                        prefs.edit().putStringSet("hidden_apps", hiddenApps).apply()
                        Snackbar.make(recyclerView, "已从最近任务隐藏", Snackbar.LENGTH_SHORT).show()
                    } else Toast.makeText(this, "隐藏失败", Toast.LENGTH_SHORT).show()
                } else {
                    hiddenApps.remove(packageName)
                    prefs.edit().putStringSet("hidden_apps", hiddenApps).apply()
                    Snackbar.make(recyclerView, "已恢复", Snackbar.LENGTH_SHORT).show()
                }
                loadInstalledApps()
            }
        }.start()
    }

    private fun restoreAllApps() {
        if (!shizukuPermissionGranted || !serviceReady) { Toast.makeText(this, "Shizuku 未就绪", Toast.LENGTH_SHORT).show(); return }
        Thread {
            var count = 0
            for (pkg in hiddenApps.toList()) { if (callSetInvisible(pkg, false)) count++ }
            hiddenApps.clear(); prefs.edit().putStringSet("hidden_apps", hiddenApps).apply()
            runOnUiThread { loadInstalledApps(); Snackbar.make(recyclerView, "已恢复 $count 个应用", Snackbar.LENGTH_SHORT).show() }
        }.start()
    }

    private fun confirmUninstall(packageName: String, appName: String) {
        MaterialAlertDialogBuilder(this).setTitle("卸载应用").setMessage("选择卸载方式：")
            .setPositiveButton("卸载保留数据") { _, _ -> uninstallApp(packageName, false) }
            .setNeutralButton("卸载删除数据") { _, _ -> uninstallApp(packageName, true) }
            .setNegativeButton("取消", null).show()
    }

    private fun uninstallApp(packageName: String, deleteData: Boolean) {
        if (!shizukuPermissionGranted || !serviceReady) { Toast.makeText(this, "Shizuku 未就绪", Toast.LENGTH_SHORT).show(); return }
        Thread {
            val binder = taskHideService ?: return@Thread
            val data = Parcel.obtain(); val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken(ITaskHideService.DESCRIPTOR)
                data.writeString(packageName)
                data.writeByte(if (deleteData) 1 else 0)
                binder.transact(ITaskHideService.TRANSACTION_uninstallApp, data, reply, 0)
                reply.readException()
                val success = reply.readInt() != 0
                runOnUiThread {
                    if (success) {
                        hiddenApps.remove(packageName)
                        prefs.edit().putStringSet("hidden_apps", hiddenApps).apply()
                        loadInstalledApps()
                        Snackbar.make(recyclerView, if (deleteData) "已卸载(删除数据)" else "已卸载(保留数据)", Snackbar.LENGTH_SHORT).show()
                    } else Toast.makeText(this, "卸载失败", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) { runOnUiThread { Toast.makeText(this, "卸载错误: ${e.message}", Toast.LENGTH_SHORT).show() } }
            finally { data.recycle(); reply.recycle() }
        }.start()
    }

    private fun showAppDetail(packageName: String) {
        val pm = packageManager
        val appInfo = try { pm.getApplicationInfo(packageName, PackageManager.GET_META_DATA) } catch (e: Exception) { return }
        val packageInfo = try { pm.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS) } catch (e: Exception) { return }

        val dialogView = layoutInflater.inflate(R.layout.dialog_app_detail, null)
        Glide.with(this).load(pm.getApplicationIcon(appInfo)).into(dialogView.findViewById<ImageView>(R.id.ivDetailIcon))
        dialogView.findViewById<TextView>(R.id.tvDetailAppName).text = pm.getApplicationLabel(appInfo)
        dialogView.findViewById<TextView>(R.id.tvDetailPackageName).text = packageName
        dialogView.findViewById<TextView>(R.id.tvDetailVersion).text = "版本名: ${packageInfo.versionName ?: "未知"}"
        dialogView.findViewById<TextView>(R.id.tvDetailVersionCode).text = "版本号: ${packageInfo.longVersionCode}"
        dialogView.findViewById<TextView>(R.id.tvDetailTargetSdk).text = "目标SDK: ${appInfo.targetSdkVersion}"
        dialogView.findViewById<TextView>(R.id.tvDetailMinSdk).text = "最低SDK: ${appInfo.minSdkVersion}"
        val permissions = packageInfo.requestedPermissions?.toList() ?: emptyList()
        dialogView.findViewById<TextView>(R.id.tvDetailPermissions).text = if (permissions.isEmpty()) "无权限请求" else permissions.joinToString("\n")

        val dialog = MaterialAlertDialogBuilder(this).setView(dialogView).setCancelable(true).create()
        dialogView.findViewById<MaterialButton>(R.id.btnExtractApk).setOnClickListener {
            extractApk(packageName); dialog.dismiss()
        }
        dialogView.findViewById<MaterialButton>(R.id.btnOpenSettings).setOnClickListener {
            try { startActivity(android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply { data = android.net.Uri.parse("package:$packageName") }) }
            catch (_: Exception) { Toast.makeText(this, "无法打开设置", Toast.LENGTH_SHORT).show() }
            dialog.dismiss()
        }
        dialog.show()
    }

    private fun extractApk(packageName: String) {
        val pm = packageManager
        val appInfo = try { pm.getApplicationInfo(packageName, 0) } catch (e: Exception) { Toast.makeText(this, "无法获取应用信息", Toast.LENGTH_SHORT).show(); return }
        val outputPath = "/storage/emulated/0/Download/${pm.getApplicationLabel(appInfo)}_${packageName}.apk"
        Thread {
            val (success, _) = runShellCommand("cp '${appInfo.sourceDir}' '$outputPath'")
            runOnUiThread { Toast.makeText(this, if (success) "APK已提取到: $outputPath" else "提取失败", if (success) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show() }
        }.start()
    }

    private fun executeAdbCommand() {
        val command = etAdbCommand.text?.toString()?.trim()
        if (command.isNullOrEmpty()) { Toast.makeText(this, "请输入命令", Toast.LENGTH_SHORT).show(); return }
        if (!shizukuPermissionGranted || !serviceReady) { Toast.makeText(this, "Shizuku 未就绪", Toast.LENGTH_SHORT).show(); return }

        Thread {
            val binder = taskHideService ?: return@Thread
            val data = Parcel.obtain(); val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken(ITaskHideService.DESCRIPTOR)
                data.writeString(command)
                binder.transact(ITaskHideService.TRANSACTION_execCommand, data, reply, 0)
                reply.readException()
                val success = reply.readInt() != 0
                val output = reply.readString() ?: ""
                runOnUiThread {
                    val ts = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
                    adbHistory.append("\n[$ts] $command\n${if (success) output.ifEmpty { "(无输出)" } else "错误: $output"}\n${"─".repeat(40)}")
                    tvAdbOutput.text = adbHistory.toString().trim()
                    svOutput.post { svOutput.fullScroll(View.FOCUS_DOWN) }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    val ts = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
                    adbHistory.append("\n[$ts] $command\n错误: ${e.message}\n${"─".repeat(40)}")
                    tvAdbOutput.text = adbHistory.toString().trim()
                }
            } finally { data.recycle(); reply.recycle() }
        }.start()
    }

    private fun runShellCommand(command: String): Pair<Boolean, String> {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
            val output = process.inputStream.bufferedReader().readText()
            Pair(process.waitFor() == 0, output)
        } catch (e: Exception) { Pair(false, e.message ?: "未知错误") }
    }

    data class AppItem(val packageName: String, val appName: String, val icon: Drawable?, val isHidden: Boolean)

    inner class CommandAdapter(
        private val commands: List<AdbCommand>,
        private val onClick: (String) -> Unit
    ) : RecyclerView.Adapter<CommandAdapter.VH>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            return VH(LayoutInflater.from(parent.context).inflate(R.layout.item_command, parent, false))
        }
        override fun onBindViewHolder(holder: VH, position: Int) { holder.bind(commands[position]) }
        override fun getItemCount() = commands.size

        inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val tvCmd: TextView = itemView.findViewById(R.id.tvCommand)
            private val tvDesc: TextView = itemView.findViewById(R.id.tvDescription)
            private val card: MaterialCardView = itemView as MaterialCardView

            fun bind(cmd: AdbCommand) {
                tvCmd.text = cmd.command
                tvDesc.text = cmd.description
                val color = themeColors[currentThemeColor]
                val isLight = isColorLight(color)
                card.setCardBackgroundColor(if (isLight) darkenColor(color, 0.1f) else lightenColor(color, 0.15f))
                tvCmd.setTextColor(if (isLight) Color.BLACK else Color.WHITE)
                tvDesc.setTextColor(if (isLight) Color.argb(180, 0, 0, 0) else Color.argb(180, 255, 255, 255))
                itemView.setOnClickListener { onClick(cmd.command) }
            }
        }
    }

    inner class AppListAdapter(
        private val onToggle: (String, Boolean) -> Unit,
        private val onUninstall: (String, String) -> Unit,
        private val onLongClick: (String) -> Unit
    ) : RecyclerView.Adapter<AppListAdapter.VH>() {
        private var appList = listOf<AppItem>()
        fun submitList(list: List<AppItem>) { appList = list; notifyDataSetChanged() }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            return VH(LayoutInflater.from(parent.context).inflate(R.layout.item_app, parent, false))
        }
        override fun onBindViewHolder(holder: VH, position: Int) { holder.bind(appList[position]) }
        override fun getItemCount() = appList.size

        inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val ivIcon: ImageView = itemView.findViewById(R.id.ivIcon)
            private val tvName: TextView = itemView.findViewById(R.id.tvAppName)
            private val tvPkg: TextView = itemView.findViewById(R.id.tvPackageName)
            private val sw: MaterialSwitch = itemView.findViewById(R.id.switchHide)
            private val ivDel: ImageView = itemView.findViewById(R.id.ivUninstall)
            private val card: MaterialCardView = itemView as MaterialCardView

            fun bind(app: AppItem) {
                tvName.text = app.appName
                tvPkg.text = app.packageName
                Glide.with(this@MainActivity).load(app.icon).into(ivIcon)

                val color = themeColors[currentThemeColor]
                val isLight = isColorLight(color)
                val text = if (isLight) Color.BLACK else Color.WHITE
                val sub = if (isLight) Color.argb(200, 0, 0, 0) else Color.argb(200, 255, 255, 255)

                sw.thumbTintList = android.content.res.ColorStateList.valueOf(text)
                sw.trackTintList = android.content.res.ColorStateList.valueOf(
                    if (isLight) Color.argb(80, 0, 0, 0) else Color.argb(80, 255, 255, 255))

                card.setCardBackgroundColor(if (isLight) darkenColor(color, 0.1f) else lightenColor(color, 0.15f))
                tvName.setTextColor(text)
                tvPkg.setTextColor(sub)

                sw.setOnCheckedChangeListener(null)
                sw.isChecked = hiddenApps.contains(app.packageName)
                sw.setOnCheckedChangeListener { _, isChecked -> onToggle(app.packageName, isChecked) }

                ivDel.setOnClickListener { onUninstall(app.packageName, app.appName) }
                itemView.setOnClickListener { sw.toggle() }
                itemView.setOnLongClickListener { onLongClick(app.packageName); true }
            }
        }
    }
}
