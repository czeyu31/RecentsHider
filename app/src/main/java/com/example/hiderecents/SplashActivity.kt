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

    @Suppress("DEPRECATION")
    private fun applySystemBars(theme: AppTheme) {
        val systemDark = (resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        val paper = when (theme) {
            AppTheme.SYSTEM -> !systemDark
            AppTheme.PAPER -> true
            AppTheme.GRAPHITE -> false
        }
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.isAppearanceLightStatusBars = paper
        controller.isAppearanceLightNavigationBars = paper
        val color = if (paper) 0xFFF1EDE3.toInt() else 0xFF0F1214.toInt()
        window.statusBarColor = color
        window.navigationBarColor = color
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
