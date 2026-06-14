package com.example.hiderecents

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.Parcel
import android.provider.Settings
import android.view.*
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import org.json.JSONArray
import rikka.shizuku.Shizuku

class NotificationHistoryActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyState: LinearLayout
    private lateinit var tvPermissionWarning: TextView
    private var notifications = mutableListOf<NotificationItem>()
    private var taskHideService: android.os.IBinder? = null
    private var serviceReady = false

    data class NotificationItem(
        val packageName: String,
        val appName: String,
        val appIcon: Drawable?,
        val title: String,
        val text: String,
        val time: Long
    )

    private val serviceConnection = object : android.content.ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: android.os.IBinder) {
            taskHideService = service
            serviceReady = true
        }
        override fun onServiceDisconnected(name: ComponentName) {
            taskHideService = null
            serviceReady = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_notification_history)

        recyclerView = findViewById(R.id.recyclerView)
        emptyState = findViewById(R.id.emptyState)
        tvPermissionWarning = findViewById(R.id.tvPermissionWarning)

        findViewById<ImageView>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<ImageView>(R.id.btnClearAll).setOnClickListener { clearAllNotifications() }

        recyclerView.layoutManager = LinearLayoutManager(this)

        // Try to auto-grant notification permission
        autoGrantNotificationPermission()

        checkNotificationPermission()
        loadNotifications()
    }

    override fun onResume() {
        super.onResume()
        checkNotificationPermission()
        loadNotifications()
    }

    private fun autoGrantNotificationPermission() {
        if (!Shizuku.pingBinder() || Shizuku.isPreV11()) return
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) return

        Thread {
            try {
                val componentName = ComponentName(this, NotificationListenerServiceImpl::class.java).flattenToString()
                val binder = Shizuku.getBinder()
                if (binder != null) {
                    val data = Parcel.obtain()
                    val reply = Parcel.obtain()
                    try {
                        data.writeInterfaceToken(ITaskHideService.DESCRIPTOR)
                        data.writeString("cmd notification allow_listener $componentName")
                        binder.transact(ITaskHideService.TRANSACTION_execCommand, data, reply, 0)
                        reply.readException()
                        reply.readInt()
                    } finally {
                        data.recycle()
                        reply.recycle()
                    }
                }
            } catch (_: Exception) {}
            try {
                val binder = Shizuku.getBinder()
                if (binder != null) {
                    val data = Parcel.obtain()
                    val reply = Parcel.obtain()
                    try {
                        data.writeInterfaceToken(ITaskHideService.DESCRIPTOR)
                        data.writeString("appops set $packageName QUERY_ALL_PACKAGES allow")
                        binder.transact(ITaskHideService.TRANSACTION_execCommand, data, reply, 0)
                        reply.readException()
                        reply.readInt()
                    } finally {
                        data.recycle()
                        reply.recycle()
                    }
                }
            } catch (_: Exception) {}
        }.start()
    }

    private fun checkNotificationPermission() {
        val enabledListeners = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        val componentName = ComponentName(this, NotificationListenerServiceImpl::class.java).flattenToString()
        val hasPermission = enabledListeners?.contains(componentName) == true
        tvPermissionWarning.visibility = if (hasPermission) View.GONE else View.VISIBLE

        if (!hasPermission) {
            tvPermissionWarning.setOnClickListener {
                // Open notification listener settings
                try {
                    startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
                } catch (_: Exception) {
                    try {
                        startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                    } catch (_: Exception) {
                        Toast.makeText(this, "请手动开启通知访问权限", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    private fun loadNotifications() {
        val prefs = getSharedPreferences("notification_history", Context.MODE_PRIVATE)
        val existing = prefs.getString("notifications", "[]") ?: "[]"
        val array = JSONArray(existing)

        notifications.clear()
        val pm = packageManager

        for (i in 0 until array.length()) {
            try {
                val obj = array.getJSONObject(i)
                val pkg = obj.getString("packageName")
                val title = obj.getString("title")
                val text = obj.getString("text")
                val time = obj.getLong("time")

                val appName = try {
                    pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
                } catch (_: Exception) { pkg }

                val icon = try {
                    pm.getApplicationIcon(pkg)
                } catch (_: Exception) { null }

                notifications.add(NotificationItem(pkg, appName, icon, title, text, time))
            } catch (_: Exception) {}
        }

        notifications.sortByDescending { it.time }

        if (notifications.isEmpty()) {
            recyclerView.visibility = View.GONE
            emptyState.visibility = View.VISIBLE
        } else {
            recyclerView.visibility = View.VISIBLE
            emptyState.visibility = View.GONE
            recyclerView.adapter = NotificationAdapter(notifications)
        }
    }

    private fun clearAllNotifications() {
        val prefs = getSharedPreferences("notification_history", Context.MODE_PRIVATE)
        prefs.edit().putString("notifications", "[]").apply()
        notifications.clear()
        recyclerView.adapter = null
        recyclerView.visibility = View.GONE
        emptyState.visibility = View.VISIBLE
    }

    inner class NotificationAdapter(
        private val items: List<NotificationItem>
    ) : RecyclerView.Adapter<NotificationAdapter.VH>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            return VH(LayoutInflater.from(parent.context).inflate(R.layout.item_notification, parent, false))
        }

        override fun onBindViewHolder(holder: VH, position: Int) { holder.bind(items[position]) }
        override fun getItemCount() = items.size

        inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val ivAppIcon: ImageView = itemView.findViewById(R.id.ivAppIcon)
            private val tvAppName: TextView = itemView.findViewById(R.id.tvAppName)
            private val tvTitle: TextView = itemView.findViewById(R.id.tvTitle)
            private val tvText: TextView = itemView.findViewById(R.id.tvText)
            private val tvTime: TextView = itemView.findViewById(R.id.tvTime)

            fun bind(item: NotificationItem) {
                if (item.appIcon != null) {
                    Glide.with(this@NotificationHistoryActivity).load(item.appIcon).into(ivAppIcon)
                }
                tvAppName.text = item.appName
                tvTitle.text = item.title
                tvText.text = item.text

                val now = System.currentTimeMillis()
                val diff = now - item.time
                tvTime.text = when {
                    diff < 60000 -> "刚刚"
                    diff < 3600000 -> "${diff / 60000}分钟前"
                    diff < 86400000 -> "${diff / 3600000}小时前"
                    else -> "${diff / 86400000}天前"
                }
            }
        }
    }
}
