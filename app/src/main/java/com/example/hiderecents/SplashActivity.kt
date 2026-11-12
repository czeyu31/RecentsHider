package com.example.hiderecents

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import com.example.hiderecents.ui.theme.AppTheme
import com.example.hiderecents.ui.theme.applyDshSystemBars
import com.example.hiderecents.ui.theme.Dsh
import com.example.hiderecents.ui.theme.DshTheme

/**
 * 开屏（Compose 重写版）。
 *
 * 行为与原 SplashActivity 一致：开屏期间用 AppInit 完成 Shizuku 权限检查与服务绑定，
 * 2 秒后跳首页并淡出。视觉与动效全部重做——不再是折叠文字，
 * 改成强调色标记 + 等宽字标 + 一条 900ms 画出来的进度线。
 */
class SplashActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 情况一：任务栈里已经压着页面（isTaskRoot = false）。
        // 点图标时系统会把 Splash 压到原页面之上，这里直接结束自己，
        // 下面那个页面就露出来了 —— 不开屏，也不跳首页。
        if (!isTaskRoot) {
            finish()
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
            return
        }

        // 情况二：进程还活着（例如保活服务把进程留着），但任务栈被系统清掉了。
        // 这时任务里没有可露出的页面，直接回到上次停留的页面，同样不播开屏。
        val warmTarget = AppSession.lastActivityName()
        if (warmTarget != null && warmTarget != SplashActivity::class.java.name) {
            runCatching { startActivity(Intent().setClassName(this, warmTarget)) }
            finish()
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
            return
        }

        val prefs = getSharedPreferences("hide_recents_prefs", Context.MODE_PRIVATE)
        val theme = AppTheme.fromPref(prefs.getString("ui_theme", null))
        applySystemBars(theme)

        setContent {
            DshTheme(theme = theme) {
                SplashScreen()
            }
        }

        // 后台预初始化 Shizuku、CPU 检测等
        AppInit.startPreInit(this)

        Handler(Looper.getMainLooper()).postDelayed({
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            @Suppress("DEPRECATION")
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        }, 2000)
    }

    private fun applySystemBars(theme: AppTheme) {
        applyDshSystemBars(theme)
    }
}

@Composable
private fun SplashScreen() {
    val colors = Dsh.colors
    val type = Dsh.type

    var started by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { started = true }

    val progress by animateFloatAsState(
        targetValue = if (started) 1f else 0f,
        animationSpec = tween(durationMillis = 900),
        label = "splashProgress",
    )

    Box(
        Modifier
            .fillMaxSize()
            .background(
                brush = Brush.verticalGradient(
                    listOf(colors.canvasLift, colors.canvas, colors.canvasShade),
                ),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier
                    .size(10.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(color = colors.accent),
            )
            Spacer(Modifier.height(18.dp))
            Text("SYSTEM TOOL", style = type.label, color = colors.ink)
            Spacer(Modifier.height(10.dp))
            Text("系统工具", style = type.caption, color = colors.inkFaint)
            Spacer(Modifier.height(26.dp))
            Box(
                Modifier
                    .width(72.dp)
                    .height(1.5.dp)
                    .background(color = colors.hairline),
            ) {
                Box(
                    Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(progress)
                        .background(color = colors.accent),
                )
            }
        }
    }
}
