package com.example.hiderecents

import android.content.*
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.*
import android.text.Editable
import android.util.Log
import android.text.TextWatcher
import android.view.*
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.google.android.material.textfield.TextInputEditText
import rikka.shizuku.Shizuku

class AppManageActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: AppListAdapter
    private lateinit var prefs: SharedPreferences
    private lateinit var etSearch: TextInputEditText
    private lateinit var tvHiddenCount: TextView

    private val hiddenApps = mutableSetOf<String>()
    private var shizukuPermissionGranted = false
    private var taskHideService: IBinder? = null
    private var serviceReady = false
    private var allApps = listOf<AppItem>()
    private var currentQuery = ""
    private var hideSystemApps = true

    data class AppItem(val packageName: String, val appName: String, val icon: Drawable?, val isHidden: Boolean, val isSystem: Boolean = false)

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            taskHideService = service; serviceReady = true
            loadInstalledApps()
        }
        override fun onServiceDisconnected(name: ComponentName) {
            taskHideService = null; serviceReady = false
        }
    }

    private val shizukuPermissionResultListener =
        Shizuku.OnRequestPermissionResultListener { _, grantResult ->
            shizukuPermissionGranted = grantResult == PackageManager.PERMISSION_GRANTED
            if (shizukuPermissionGranted) bindService()
        }

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener { checkShizukuPermission() }
    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        shizukuPermissionGranted = false; taskHideService = null; serviceReady = false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_app_manage)

        prefs = getSharedPreferences("hide_recents_prefs", Context.MODE_PRIVATE)
        hiddenApps.addAll(prefs.getStringSet("hidden_apps", emptySet()) ?: emptySet())

        etSearch = findViewById(R.id.etSearch)
        tvHiddenCount = findViewById(R.id.tvHiddenCount)

        val switchHideSystem = findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(R.id.switchHideSystem)
        switchHideSystem.setOnCheckedChangeListener { _, isChecked ->
            hideSystemApps = isChecked
            filterApps()
        }

        findViewById<ImageView>(R.id.btnBack).setOnClickListener { finish() }

        recyclerView = findViewById(R.id.recyclerView)
        recyclerView.layoutManager = LinearLayoutManager(this)
        adapter = AppListAdapter(
            onToggle = { pkg, hide -> toggleAppVisibility(pkg, hide) },
            onUninstall = { pkg, name -> confirmUninstall(pkg, name) }
        )
        recyclerView.adapter = adapter

        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                currentQuery = s?.toString()?.lowercase() ?: ""
                filterApps()
            }
        })

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
        Thread {
            try {
                val binder = taskHideService ?: run {
                    runOnUiThread { loadInstalledAppsLocal() }
                    return@Thread
                }
                val data = Parcel.obtain()
                val reply = Parcel.obtain()
                try {
                    data.writeInterfaceToken(ITaskHideService.DESCRIPTOR)
                    data.writeString("pm list packages")
                    binder.transact(ITaskHideService.TRANSACTION_execCommand, data, reply, 0)
                    reply.readException()
                    val success = reply.readInt() != 0
                    val output = reply.readString() ?: ""
                    Log.d("AppManage", "pm list success=$success output=${output.take(200)}")
                    if (success && output.isNotBlank()) {
                        val pm = packageManager
                        val packageNames = output.lines()
                            .map { it.removePrefix("package:").trim() }
                            .filter { it.isNotEmpty() }
                        allApps = packageNames.mapNotNull { pkg ->
                            try {
                                val appInfo = pm.getApplicationInfo(pkg, 0)
                                val isSystem = (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
                                AppItem(pkg, pm.getApplicationLabel(appInfo).toString(), pm.getApplicationIcon(appInfo), hiddenApps.contains(pkg), isSystem)
                            } catch (_: Exception) { null }
                        }.sortedWith(compareByDescending<AppItem> { it.isHidden }.thenBy { it.appName.lowercase() })
                        runOnUiThread { filterApps() }
                    } else {
                        runOnUiThread { loadInstalledAppsLocal() }
                    }
                } finally {
                    data.recycle()
                    reply.recycle()
                }
            } catch (e: Exception) {
                Log.e("AppManage", "Failed to load via Shizuku", e)
                runOnUiThread { loadInstalledAppsLocal() }
            }
        }.start()
    }

    private fun loadInstalledAppsLocal() {
        val pm = packageManager
        val packages = pm.getInstalledApplications(PackageManager.GET_META_DATA)
        allApps = packages
            .filter { pm.getLaunchIntentForPackage(it.packageName) != null }
            .map {
                val isSystem = (it.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
                AppItem(it.packageName, pm.getApplicationLabel(it).toString(), pm.getApplicationIcon(it), hiddenApps.contains(it.packageName), isSystem)
            }
            .sortedWith(compareByDescending<AppItem> { it.isHidden }.thenBy { it.appName.lowercase() })
        filterApps()
    }

    private fun filterApps() {
        var filtered = allApps
        if (hideSystemApps) {
            filtered = filtered.filter { !it.isSystem }
        }
        if (currentQuery.isNotEmpty()) {
            filtered = filtered.filter {
                it.appName.lowercase().contains(currentQuery) || it.packageName.lowercase().contains(currentQuery)
            }
        }
        tvHiddenCount.text = "已隐藏 ${allApps.count { it.isHidden }}"
        runOnUiThread { adapter.submitList(filtered) }
    }

    private fun toggleAppVisibility(packageName: String, hide: Boolean) {
        if (!shizukuPermissionGranted || !serviceReady) {
            Toast.makeText(this, "Shizuku 未就绪", Toast.LENGTH_SHORT).show()
            return
        }
        Thread {
            val success = callSetInvisible(packageName, hide)
            runOnUiThread {
                if (hide) {
                    if (success) {
                        hiddenApps.add(packageName)
                        prefs.edit().putStringSet("hidden_apps", hiddenApps).apply()
                        Toast.makeText(this, "已从最近任务隐藏", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this, "隐藏失败", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    hiddenApps.remove(packageName)
                    prefs.edit().putStringSet("hidden_apps", hiddenApps).apply()
                    if (hiddenApps.isEmpty()) {
                        prefs.edit().putBoolean("all_hidden", false).apply()
                    }
                    Toast.makeText(this, "已恢复", Toast.LENGTH_SHORT).show()
                }
                loadInstalledApps()
            }
        }.start()
    }

    private fun confirmUninstall(packageName: String, appName: String) {
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("卸载应用")
            .setMessage("确定要卸载 $appName 吗？")
            .setPositiveButton("卸载保留数据") { _, _ -> uninstallApp(packageName, false) }
            .setNeutralButton("卸载删除数据") { _, _ -> uninstallApp(packageName, true) }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun uninstallApp(packageName: String, deleteData: Boolean) {
        if (!shizukuPermissionGranted || !serviceReady) {
            Toast.makeText(this, "Shizuku 未就绪", Toast.LENGTH_SHORT).show()
            return
        }
        Thread {
            val binder = taskHideService ?: return@Thread
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
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
                        Toast.makeText(this, if (deleteData) "已卸载(删除数据)" else "已卸载(保留数据)", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this, "卸载失败", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread { Toast.makeText(this, "卸载错误: ${e.message}", Toast.LENGTH_SHORT).show() }
            } finally {
                data.recycle()
                reply.recycle()
            }
        }.start()
    }

    private fun callSetInvisible(packageName: String, invisible: Boolean): Boolean {
        val binder = taskHideService ?: return false
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
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

    inner class AppListAdapter(
        private val onToggle: (String, Boolean) -> Unit,
        private val onUninstall: (String, String) -> Unit
    ) : RecyclerView.Adapter<AppListAdapter.VH>() {
        private var appList = listOf<AppItem>()
        fun submitList(list: List<AppItem>) { appList = list; notifyDataSetChanged() }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            return VH(LayoutInflater.from(parent.context).inflate(R.layout.item_app_manage, parent, false))
        }
        override fun onBindViewHolder(holder: VH, position: Int) { holder.bind(appList[position]) }
        override fun getItemCount() = appList.size

        inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val ivIcon: ImageView = itemView.findViewById(R.id.ivIcon)
            private val tvName: TextView = itemView.findViewById(R.id.tvAppName)
            private val tvPkg: TextView = itemView.findViewById(R.id.tvPackageName)
            private val ivUninstall: ImageView = itemView.findViewById(R.id.ivUninstall)
            private val sw: com.google.android.material.materialswitch.MaterialSwitch = itemView.findViewById(R.id.switchHide)

            fun bind(app: AppItem) {
                tvName.text = app.appName
                tvPkg.text = app.packageName
                Glide.with(this@AppManageActivity).load(app.icon).into(ivIcon)
                sw.setOnCheckedChangeListener(null)
                sw.isChecked = hiddenApps.contains(app.packageName)
                sw.setOnCheckedChangeListener { _, isChecked -> onToggle(app.packageName, isChecked) }
                ivUninstall.setOnClickListener { onUninstall(app.packageName, app.appName) }
                itemView.setOnClickListener { sw.toggle() }
            }
        }
    }
}
