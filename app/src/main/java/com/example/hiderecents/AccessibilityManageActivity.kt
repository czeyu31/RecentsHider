package com.example.hiderecents

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityManager
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.google.android.material.textfield.TextInputEditText
import rikka.shizuku.Shizuku

class AccessibilityManageActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "AccessibilityManage"
    }

    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: AccessibilityListAdapter
    private lateinit var prefs: SharedPreferences
    private lateinit var tvAccessCount: TextView
    private lateinit var tvProtectStatus: TextView
    private lateinit var tvListHeader: TextView
    private lateinit var etSearch: TextInputEditText
    private lateinit var switchAutoProtect: SquishSwitch

    private var shizukuPermissionGranted = false
    private var taskHideService: IBinder? = null
    private var serviceReady = false
    private val protectedServices = mutableSetOf<String>()
    private val pinnedServices = mutableSetOf<String>()
    private var allItems = listOf<AccessibilityItem>()
    private var currentQuery = ""
    private var hideSystemApps = false

    data class AccessibilityItem(
        val componentName: String,
        val packageName: String,
        val appName: String,
        val serviceName: String,
        val description: String,
        val icon: android.graphics.drawable.Drawable?,
        val isEnabled: Boolean,
        val isProtected: Boolean,
        val isPinned: Boolean,
        val isSystem: Boolean
    )

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            taskHideService = service; serviceReady = true
            loadAccessibilityServices()
            updateProtectStatus()
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
        setContentView(R.layout.activity_accessibility_manage)

        prefs = getSharedPreferences("hide_recents_prefs", Context.MODE_PRIVATE)
        protectedServices.addAll(prefs.getStringSet("protected_a11y_services", emptySet()) ?: emptySet())
        pinnedServices.addAll(prefs.getStringSet("pinned_a11y_services", emptySet()) ?: emptySet())

        hideSystemApps = prefs.getBoolean("a11y_hide_system", false)

        tvAccessCount = findViewById(R.id.tvAccessCount)
        tvProtectStatus = findViewById(R.id.tvProtectStatus)
        tvListHeader = findViewById(R.id.tvListHeader)
        etSearch = findViewById(R.id.etSearch)
        switchAutoProtect = findViewById(R.id.switchAutoProtect)

        val switchHideSystem = findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(R.id.switchHideSystem)
        switchHideSystem.isChecked = hideSystemApps
        switchHideSystem.setOnCheckedChangeListener { _, isChecked ->
            hideSystemApps = isChecked
            prefs.edit().putBoolean("a11y_hide_system", isChecked).apply()
            filterAndDisplay()
        }

        switchAutoProtect.isChecked = prefs.getBoolean("a11y_auto_protect", false)

        switchAutoProtect.listener = { isChecked ->
            prefs.edit().putBoolean("a11y_auto_protect", isChecked).apply()
            if (isChecked) {
                ensureKeepAliveEnabled()
                autoAddEnabledToProtected()
            }
            loadAccessibilityServices()
            updateProtectStatus()
        }

        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                currentQuery = s?.toString()?.lowercase() ?: ""
                filterAndDisplay()
            }
        })

        findViewById<ImageView>(R.id.btnBack).setOnClickListener { finish() }

        recyclerView = findViewById(R.id.recyclerView)
        recyclerView.layoutManager = LinearLayoutManager(this)
        adapter = AccessibilityListAdapter(
            onToggle = { item, enable -> toggleService(item, enable) },
            onToggleProtect = { item, protect -> toggleProtection(item, protect) }
        )
        recyclerView.adapter = adapter

        // 右滑返回手势
        val gestureDetector = android.view.GestureDetector(this, object : android.view.GestureDetector.SimpleOnGestureListener() {
            override fun onFling(e1: android.view.MotionEvent?, e2: android.view.MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                if (e1 == null) return false
                val diffX = e2.x - e1.x
                if (kotlin.math.abs(diffX) > 100 && kotlin.math.abs(velocityX) > 100) {
                    if (diffX > 0) { finish(); return true }
                }
                return false
            }
        })
        findViewById<View>(android.R.id.content).setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            false
        }

        Shizuku.addRequestPermissionResultListener(shizukuPermissionResultListener)
        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
        checkShizukuPermission()

        loadAccessibilityServices()

        // 首次打开自动开启防回收保护和守护服务
        if (!prefs.getBoolean("a11y_first_setup_done", false)) {
            prefs.edit().putBoolean("a11y_auto_protect", true).apply()
            switchAutoProtect.isChecked = true
            autoAddEnabledToProtected()
            ensureKeepAliveEnabled()
            prefs.edit().putBoolean("a11y_first_setup_done", true).apply()
        }

        // 每次打开都检查守护服务是否在运行，如果不是则提醒
        if (prefs.getBoolean("a11y_auto_protect", false) && !isKeepAliveServiceEnabled()) {
            ensureKeepAliveEnabled()
        }
    }

    override fun onResume() {
        super.onResume()
        loadAccessibilityServices()
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

    private fun ensureKeepAliveEnabled() {
        if (isKeepAliveServiceEnabled()) return
        // 通过 Shizuku 直接开启守护无障碍服务
        Thread {
            val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
            val myCn = "${packageName}/${AccessibilityKeepAliveService::class.java.name}"
            if (!enabled.contains(myCn)) {
                val newValue = if (enabled.isEmpty()) myCn else "$enabled:$myCn"
                val success = executeCommandSync("settings put secure enabled_accessibility_services $newValue")
                if (success) {
                    executeCommandSync("settings put secure accessibility_enabled 1")
                }
                runOnUiThread {
                    if (success) {
                        Toast.makeText(this, "已自动开启无障碍保活守护服务", Toast.LENGTH_SHORT).show()
                    } else {
                        // Shizuku 失败，引导手动开启
                        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                            .setTitle("需要开启守护服务")
                            .setMessage("防回收功能需要开启「System Tool 无障碍保活」服务。\n\n请在以下页面找到并开启它。")
                            .setPositiveButton("去开启") { _, _ ->
                                try { startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)) } catch (_: Exception) {}
                            }
                            .setNegativeButton("取消", null)
                            .show()
                    }
                    loadAccessibilityServices()
                }
            }
        }.start()
    }

    private fun isKeepAliveServiceEnabled(): Boolean {
        val enabledServices = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
        val myComponent = ComponentName(this, AccessibilityKeepAliveService::class.java).flattenToString()
        // 也可能用短格式
        val shortCn = "${packageName}/${AccessibilityKeepAliveService::class.java.name}"
        return enabledServices.contains(myComponent) || enabledServices.contains(shortCn) ||
            enabledServices.contains(AccessibilityKeepAliveService::class.java.name)
    }

    private fun autoAddEnabledToProtected() {
        // 不再自动把所有已启用服务加入保护，只保护用户明确标记的
    }

    private fun loadAccessibilityServices() {
        Thread {
            try {
                val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
                val installedServices = am.installedAccessibilityServiceList
                val currentEnabled = getEnabledServices()
                val pm = packageManager

                allItems = installedServices.mapNotNull { info ->
                    try {
                        val si = info.resolveInfo.serviceInfo
                        val cn = ComponentName(si.packageName, si.name).flattenToString()
                        val appInfo = pm.getApplicationInfo(si.packageName, 0)
                        val appName = pm.getApplicationLabel(appInfo).toString()
                        val icon = loadIconCached(pm, si.packageName)
                        val isEnabled = cn in currentEnabled
                        val desc = info.description?.toString() ?: ""
                        val isSystem = (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
                        AccessibilityItem(
                            componentName = cn,
                            packageName = si.packageName,
                            appName = appName,
                            serviceName = si.name.substringAfterLast('.'),
                            description = desc,
                            icon = icon,
                            isEnabled = isEnabled,
                            isProtected = cn in protectedServices,
                            isPinned = cn in pinnedServices,
                            isSystem = isSystem
                        )
                    } catch (_: Exception) { null }
                }.sortedWith(
                    compareByDescending<AccessibilityItem> { it.isPinned }
                        .thenByDescending { it.isProtected }
                        .thenByDescending { it.isEnabled }
                        .thenBy { it.appName.lowercase() }
                )

                runOnUiThread { filterAndDisplay() }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load accessibility services", e)
            }
        }.start()
    }

    private fun loadIconCached(pm: PackageManager, packageName: String): android.graphics.drawable.Drawable? {
        AppIconLoader.getCached(packageName)?.let { return it }
        return try {
            val icon = pm.getApplicationIcon(pm.getApplicationInfo(packageName, 0))
            AppIconLoader.put(packageName, icon)
            icon
        } catch (_: Exception) { null }
    }

    private fun filterAndDisplay() {
        var filtered = allItems
        if (hideSystemApps) {
            filtered = filtered.filter { !it.isSystem }
        }
        if (currentQuery.isNotEmpty()) {
            filtered = filtered.filter {
                it.appName.lowercase().contains(currentQuery) ||
                    it.packageName.lowercase().contains(currentQuery) ||
                    it.serviceName.lowercase().contains(currentQuery) ||
                    it.description.lowercase().contains(currentQuery)
            }
        }
        adapter.submitList(filtered)
        tvAccessCount.text = "${allItems.count { it.isEnabled }} / ${allItems.size} 个服务"
        tvListHeader.text = if (currentQuery.isEmpty()) "已注册的无障碍服务 (${allItems.size})" else "搜索结果 (${filtered.size})"
    }

    private fun getEnabledServices(): Set<String> {
        val enabledServices = mutableSetOf<String>()
        try {
            val flat = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            if (!flat.isNullOrEmpty()) {
                flat.split(':').forEach { component ->
                    if (component.isNotBlank()) enabledServices.add(component.trim())
                }
            }
        } catch (_: Exception) {}
        return enabledServices
    }

    private fun toggleService(item: AccessibilityItem, enable: Boolean) {
        if (!shizukuPermissionGranted || !serviceReady) {
            Toast.makeText(this, "需要 Shizuku 权限来管理无障碍服务", Toast.LENGTH_SHORT).show()
            return
        }
        Thread {
            val result = if (enable) {
                enableAccessibilityService(item.componentName)
            } else {
                disableAccessibilityService(item.componentName)
            }
            runOnUiThread {
                if (result) {
                    Toast.makeText(this, if (enable) "已启用" else "已禁用", Toast.LENGTH_SHORT).show()
                    if (enable && prefs.getBoolean("a11y_auto_protect", false)) {
                        // 启用时自动加入保护列表，并从用户主动禁用列表移除
                        protectedServices.add(item.componentName)
                        prefs.edit().putStringSet("protected_a11y_services", protectedServices).apply()
                        val userDisabled = prefs.getStringSet("user_disabled_a11y", mutableSetOf()) ?: mutableSetOf()
                        userDisabled.remove(item.componentName)
                        prefs.edit().putStringSet("user_disabled_a11y", userDisabled).apply()
                    } else if (!enable) {
                        // 用户主动关闭：从保护列表移除，防止防回收自动恢复
                        protectedServices.remove(item.componentName)
                        prefs.edit().putStringSet("protected_a11y_services", protectedServices).apply()
                        // 同时记录到用户主动禁用列表
                        val userDisabled = prefs.getStringSet("user_disabled_a11y", mutableSetOf()) ?: mutableSetOf()
                        userDisabled.add(item.componentName)
                        prefs.edit().putStringSet("user_disabled_a11y", userDisabled).apply()
                    }
                } else {
                    Toast.makeText(this, "操作失败", Toast.LENGTH_SHORT).show()
                }
                loadAccessibilityServices()
                updateProtectStatus()
            }
        }.start()
    }

    private fun enableAccessibilityService(componentName: String): Boolean {
        if (!serviceReady) return false
        try {
            val currentEnabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
            val services = currentEnabled.split(':').filter { it.isNotBlank() }.toMutableList()
            if (componentName !in services) services.add(componentName)
            val newValue = services.joinToString(":")
            val success = executeCommandSync("settings put secure enabled_accessibility_services $newValue")
            executeCommandSync("settings put secure accessibility_enabled 1")
            return success
        } catch (e: Exception) {
            Log.e(TAG, "enableAccessibilityService failed", e)
            return false
        }
    }

    private fun disableAccessibilityService(componentName: String): Boolean {
        if (!serviceReady) return false
        try {
            val currentEnabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
            val services = currentEnabled.split(':').filter { it.isNotBlank() && it != componentName }.toMutableList()
            val newValue = services.joinToString(":")
            val success = executeCommandSync("settings put secure enabled_accessibility_services $newValue")
            if (services.isEmpty()) executeCommandSync("settings put secure accessibility_enabled 0")
            return success
        } catch (e: Exception) {
            Log.e(TAG, "disableAccessibilityService failed", e)
            return false
        }
    }

    private fun toggleProtection(item: AccessibilityItem, protect: Boolean) {
        if (protect) {
            protectedServices.add(item.componentName)
        } else {
            protectedServices.remove(item.componentName)
        }
        prefs.edit().putStringSet("protected_a11y_services", protectedServices).apply()
        loadAccessibilityServices()
        updateProtectStatus()
    }

    private fun updateProtectStatus() {
        val autoProtect = prefs.getBoolean("a11y_auto_protect", false)
        val userDisabled = prefs.getStringSet("user_disabled_a11y", emptySet()) ?: emptySet()
        val activeProtectedCount = protectedServices.count { it !in userDisabled }
        tvProtectStatus.text = when {
            !serviceReady && !autoProtect -> "Shizuku 未连接，无法启用保护"
            !autoProtect -> "防回收已关闭"
            activeProtectedCount == 0 -> "已开启，但未选择需要保护的服务"
            else -> "保护中: $activeProtectedCount 个服务，实时监听变化"
        }
    }

    private fun executeCommandSync(command: String): Boolean {
        val binder = taskHideService ?: return false
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(ITaskHideService.DESCRIPTOR)
            data.writeString(command)
            binder.transact(ITaskHideService.TRANSACTION_execCommand, data, reply, 0)
            reply.readException()
            val success = reply.readInt() != 0
            return success
        } catch (e: Exception) {
            Log.e(TAG, "Command failed: $command", e)
            return false
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    inner class AccessibilityListAdapter(
        private val onToggle: (AccessibilityItem, Boolean) -> Unit,
        private val onToggleProtect: (AccessibilityItem, Boolean) -> Unit
    ) : RecyclerView.Adapter<AccessibilityListAdapter.VH>() {
        private var items = listOf<AccessibilityItem>()
        fun submitList(list: List<AccessibilityItem>) { items = list; notifyDataSetChanged() }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            return VH(LayoutInflater.from(parent.context).inflate(R.layout.item_accessibility_service, parent, false))
        }
        override fun onBindViewHolder(holder: VH, position: Int) { holder.bind(items[position]) }
        override fun getItemCount() = items.size

        inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val ivIcon: ImageView = itemView.findViewById(R.id.ivIcon)
            private val tvAppName: TextView = itemView.findViewById(R.id.tvAppName)
            private val tvServiceName: TextView = itemView.findViewById(R.id.tvServiceName)
            private val tvDescription: TextView = itemView.findViewById(R.id.tvDescription)
            private val tvStatus: TextView = itemView.findViewById(R.id.tvStatus)
            private val sw: SquishSwitch = itemView.findViewById(R.id.switchEnabled)

            fun bind(item: AccessibilityItem) {
                tvAppName.text = if (item.isPinned) "📌 ${item.appName}" else item.appName
                tvServiceName.text = item.serviceName
                Glide.with(this@AccessibilityManageActivity).load(item.icon).into(ivIcon)

                if (item.description.isNotEmpty()) {
                    tvDescription.visibility = View.VISIBLE
                    tvDescription.text = item.description
                } else {
                    tvDescription.visibility = View.GONE
                }

                val statusText = buildString {
                    append(if (item.isEnabled) "已启用" else "已禁用")
                    if (item.isProtected) append(" · 防回收保护中")
                }
                tvStatus.text = statusText
                tvStatus.setTextColor(getColor(if (item.isProtected) R.color.primary else R.color.on_surface_variant))

                sw.listener = null
                sw.isChecked = item.isEnabled
                sw.listener = { isChecked -> onToggle(item, isChecked) }
                ivIcon.setOnClickListener { sw.toggle() }
                itemView.setOnClickListener { sw.toggle() }
                itemView.setOnLongClickListener { onShowItemMenu(item); true }
            }
        }
    }

    private fun onShowItemMenu(item: AccessibilityItem) {
        val isPinned = item.componentName in pinnedServices
        val isProtected = item.componentName in protectedServices
        val items = arrayOf(
            if (isPinned) "取消置顶" else "置顶",
            if (isProtected) "取消防回收保护" else "开启防回收保护"
        )
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(item.appName)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> togglePin(item)
                    1 -> toggleProtection(item, !isProtected)
                }
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun togglePin(item: AccessibilityItem) {
        if (item.componentName in pinnedServices) {
            pinnedServices.remove(item.componentName)
        } else {
            pinnedServices.add(item.componentName)
        }
        prefs.edit().putStringSet("pinned_a11y_services", pinnedServices).apply()
        loadAccessibilityServices()
    }
}
