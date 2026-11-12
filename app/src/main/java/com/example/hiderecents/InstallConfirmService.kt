package com.example.hiderecents

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 安装确认页自动点击。
 *
 * MIUI 对 shell 发起的安装（Shizuku / pm install）会插入自己的确认页
 * com.miui.securitycenter/com.miui.permcenter.install.AdbInstallActivity
 * （由 PackageManagerServiceImpl.verifyInstallFromShell 拉起），
 * 没人点「继续安装」它会自己取消，安装返回
 * INSTALL_FAILED_USER_RESTRICTED: Install canceled by user。
 *
 * 这个服务只监听安装器包名窗口内容变化，看到确认按钮就点一下，
 * 不做任何数据采集、不读取其他应用的界面内容。
 */
class InstallConfirmService : AccessibilityService() {

    companion object {
        private const val TAG = "InstallConfirm"

        /** 会出现安装确认页的包名 */
        private val INSTALLER_PACKAGES = setOf(
            "com.miui.securitycenter",
            "com.miui.packageinstaller",
            "com.android.packageinstaller",
            "com.google.android.packageinstaller",
        )

        /** 需要点击的按钮文案，靠前的优先匹配 */
        private val BUTTON_TEXTS = listOf(
            "继续安装",
            "仍然安装",
            "立即安装",
            "确认安装",
            "安装",
            "确定",
            "允许",
            "继续",
            "完成",
        )

        @Volatile
        var connected = false
            private set

        @Volatile
        var lastAction: String = ""
            private set
    }

    private val handler = Handler(Looper.getMainLooper())
    private var lastClickAt = 0L
    private var checkPending = false
    private var retryLeft = 0
    private var retryPkg = ""

    override fun onServiceConnected() {
        super.onServiceConnected()
        connected = true
        Log.d(TAG, "InstallConfirmService connected")
        AppLogger.log(TAG, "安装确认服务已连接")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val e = event ?: return
        val type = e.eventType
        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            type != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        ) {
            return
        }
        val pkg = e.packageName?.toString().orEmpty()
        // 窗口切换：任何包名都可能是安装器换了窗口，交给 tryConfirm 再判包名
        // 内容变化：只处理安装器，避免全系统事件刷屏
        if (type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED && pkg !in INSTALLER_PACKAGES) {
            return
        }
        if (checkPending) return
        checkPending = true
        retryPkg = pkg
        retryLeft = 20
        handler.postDelayed({
            checkPending = false
            tryConfirm(pkg)
        }, 400)
    }

    private fun tryConfirm(fromPkg: String) {
        if (System.currentTimeMillis() - lastClickAt < 1500) {
            scheduleRetry(fromPkg)
            return
        }
        val prefs = getSharedPreferences("hide_recents_prefs", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("auto_confirm_install", true)) return
        val root = try {
            rootInActiveWindow
        } catch (_: Exception) {
            null
        } ?: run {
            scheduleRetry(fromPkg)
            return
        }
        val rootPkg = root.packageName?.toString().orEmpty()
        val rootClass = root.className?.toString().orEmpty()
        if (rootPkg !in INSTALLER_PACKAGES) {
            scheduleRetry(fromPkg)
            return
        }
        // 只在「安装页」上动手：安全中心里还有一堆别的页面，不能盲点
        val looksInstaller = rootClass.lowercase().contains("install") ||
            rootPkg.contains("packageinstaller")
        if (!looksInstaller) {
            Log.d(TAG, "skip non-install page: " + rootPkg + "/" + rootClass)
            return
        }
        val hit = findButton(root)
        if (hit == null) {
            Log.d(TAG, "no confirm button in " + rootPkg + "/" + rootClass)
            scheduleRetry(rootPkg)
            return
        }
        val label = hit.second
        val ok = try {
            hit.first.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        } catch (_: Exception) {
            false
        }
        if (ok) {
            lastClickAt = System.currentTimeMillis()
            lastAction = label
            Log.d(TAG, "clicked [" + label + "] in " + rootPkg + " from=" + fromPkg)
            AppLogger.log(TAG, "已点击安装确认按钮：" + label + "（" + rootPkg + "/" + rootClass + "）")
            scheduleRetry(rootPkg)
        } else {
            Log.w(TAG, "click failed on [" + label + "]")
            scheduleRetry(rootPkg)
        }
    }

    /** 安装页会连弹「准备 → 安全提醒 → 安装进度」好几屏，点完一屏要接着盯下一屏 */
    private fun scheduleRetry(pkg: String) {
        if (retryLeft <= 0) return
        retryLeft--
        handler.postDelayed({
            if (!connected) return@postDelayed
            tryConfirm(pkg)
        }, 700)
    }

    private fun findButton(root: AccessibilityNodeInfo): Pair<AccessibilityNodeInfo, String>? {
        for (text in BUTTON_TEXTS) {
            val hit = findByText(root, text, 0)
            if (hit != null) return hit
        }
        return null
    }

    /** 返回「可点击节点 + 命中的文案」，文案用于日志，避免点到空标签的父节点后无法判断 */
    private fun findByText(node: AccessibilityNodeInfo, want: String, depth: Int): Pair<AccessibilityNodeInfo, String>? {
        if (depth > 30) return null
        val t = nodeText(node)
        // 「安装」这类短词必须完全相等，否则「安装中」「正在安装」也会被点
        val matched = t == want || (want.length >= 3 && t.contains(want))
        if (t.isNotEmpty() && matched && !t.contains("取消")) {
            val clickable = clickableSelfOrParent(node)
            if (clickable != null) return Pair(clickable, t)
        }
        var i = 0
        while (i < node.childCount) {
            val child = try {
                node.getChild(i)
            } catch (_: Exception) {
                null
            }
            if (child != null) {
                val hit = findByText(child, want, depth + 1)
                if (hit != null) return hit
            }
            i++
        }
        return null
    }

    private fun clickableSelfOrParent(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var cur: AccessibilityNodeInfo? = node
        var d = 0
        while (cur != null && d < 6) {
            if (cur.isClickable && cur.isEnabled) return cur
            cur = try {
                cur.parent
            } catch (_: Exception) {
                null
            }
            d++
        }
        return null
    }

    private fun nodeText(node: AccessibilityNodeInfo): String {
        val text = node.text?.toString().orEmpty()
        if (text.isNotEmpty()) return text
        return node.contentDescription?.toString().orEmpty()
    }

    override fun onInterrupt() {
        connected = false
    }

    override fun onDestroy() {
        connected = false
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
