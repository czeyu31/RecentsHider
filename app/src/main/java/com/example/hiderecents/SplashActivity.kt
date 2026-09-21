package com.example.hiderecents

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity

class SplashActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 纯黑背景
        val root = FrameLayout(this).apply {
            setBackgroundColor(0xFF000000.toInt())
        }

        // FoldText 居中，白色大字
        val foldText = FoldTextView(this).apply {
            text = "System Tool"
            textSizeSp = 60f
            textColor = 0xFFFFFFFF.toInt()
        }
        val lp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply { gravity = Gravity.CENTER }
        root.addView(foldText, lp)

        setContentView(root)

        // 后台预初始化 Shizuku、CPU 检测等
        AppInit.startPreInit(this)

        // 播放动画
        foldText.startAnimation()

        // 2秒后跳转主页
        Handler(Looper.getMainLooper()).postDelayed({
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        }, 2000)
    }
}
