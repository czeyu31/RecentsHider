package com.example.hiderecents

import android.content.Context

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity

/**
 * 统一的返回语义。
 *
 * 为什么需要：SplashActivity 的热恢复分支会 startActivity(上次停留页面) 再 finish 自己，
 * 于是那一页直接成了任务根（numActivities=1）。这种情况下再 finish() 只能退回桌面 ——
 * 就是「在某个页面按返回直接回到桌面」的复现路径。
 * 之前只有 Toolbox / AppManage 加了这个兜底，其余页面都没有。
 */
fun Activity.dshGoBack() {
    if (isTaskRoot) {
        val intent = Intent(this, MainActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        runCatching { startActivity(intent) }
    }
    finish()
    @Suppress("DEPRECATION")
    overridePendingTransition(0, R.anim.slide_right)
}

/**
 * 「返回 = 退出本页」的 Activity 基类。
 *
 * 同时兼顾预测返回：开启 enableOnBackInvokedCallback 后系统不再调用 onBackPressed()，
 * 必须注册 OnBackPressedCallback 才能收到返回事件。
 */
open class DshActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyPredictiveBackPref()
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    onDshBackPressed()
                }
            },
        )
    }

    protected open fun onDshBackPressed() {
        dshGoBack()
    }
}

/**
 * 应用「预测返回手势」开关。
 *
 * 开启 enableOnBackInvokedCallback 后返回手势由系统接管（滑动时预览上一页）；
 * 关掉开关则退回旧的返回行为（直接 finish，无预览）。
 * 用反射调用 OnBackPressedDispatcher.setOnBackInvokedCallbackEnabled，
 * 避免不同 androidx 版本上该方法是否可见的编译差异。
 */
fun Activity.applyPredictiveBackPref() {
    if (android.os.Build.VERSION.SDK_INT < 33) return
    val enabled = getSharedPreferences("hide_recents_prefs", Context.MODE_PRIVATE)
        .getBoolean("predictive_back_enabled", true)
    runCatching {
        val dispatcher = (this as? androidx.activity.ComponentActivity)?.onBackPressedDispatcher ?: return
        val method = dispatcher.javaClass.methods.firstOrNull {
            it.name == "setOnBackInvokedCallbackEnabled" && it.parameterTypes.size == 1
        }
        method?.invoke(dispatcher, enabled)
    }
}
