package com.example.hiderecents

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity

/**
 * 统一返回语义：本页已是任务根时先回首页再结束自己，否则直接结束。
 *
 * 离场统一向右抽离（0ms 系统转场 + slide_right），和页面进入时的左滑方向相反。
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
 * 「返回 = 退出本页」的基类。
 *
 * 用 [OnBackPressedCallback] 而不是重写 onBackPressed：
 * 清单里开了 enableOnBackInvokedCallback，API 33+ 的返回会先走 OnBackPressedDispatcher，
 * 注册成回调才在两种返回方式下都稳定生效。
 */
open class DshActivity : AppCompatActivity() {

    private val backCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            onDshBackPressed()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        onBackPressedDispatcher.addCallback(this, backCallback)
    }

    protected open fun onDshBackPressed() {
        dshGoBack()
    }
}
