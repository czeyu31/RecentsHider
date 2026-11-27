package com.example.hiderecents

import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import android.content.Intent
import com.example.hiderecents.hr.HeartRateService
import com.example.hiderecents.island.IslandPublish
import com.example.hiderecents.island.IslandStandard
import com.example.hiderecents.island.ThermalIsland
import com.example.hiderecents.island.IslandSettings
import com.example.hiderecents.ui.screens.IslandSettingsScreen
import com.example.hiderecents.ui.theme.AppTheme
import com.example.hiderecents.ui.theme.DshTheme
import com.example.hiderecents.ui.theme.applyDshSystemBars

/** 原生岛设置页。 */
class IslandActivity : DshActivity() {

    /** 上一次的岛优先级，用来判断这次改的是不是它 */
    private var lastTempPriority: Boolean? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("hide_recents_prefs", Context.MODE_PRIVATE)
        val theme = AppTheme.fromPref(prefs.getString("ui_theme", null))
        applyDshSystemBars(theme)
        IslandSettings.load(this)
        lastTempPriority = IslandSettings.tempPriority

        setContent {
            DshTheme(theme) {
                IslandSettingsScreen(
                    onBack = { dshGoBack() },
                    onChange = {
                        IslandSettings.save(this)
                        val priorityChanged = lastTempPriority != IslandSettings.tempPriority
                        lastTempPriority = IslandSettings.tempPriority
                        val refreshTemp = { ThermalIsland.refreshNow(this) }
                        val refreshHeart = {
                            runCatching {
                                startService(
                                    Intent(this, HeartRateService::class.java)
                                        .setAction(HeartRateService.ACTION_REFRESH_ISLAND),
                                )
                            }
                            Unit
                        }
                        if (priorityChanged) {
                            // 岛位在第一次上岛时就定死了，改 id 也不会挪；只能把两个岛都关掉再按新顺序重开
                            IslandPublish.nextSlots()
                            IslandPublish.cancelAll(this)
                            ThermalIsland.restart(this)
                            val handler = android.os.Handler(android.os.Looper.getMainLooper())
                            handler.postDelayed({
                                if (IslandSettings.tempPriority) {
                                    refreshTemp()
                                    handler.postDelayed(refreshHeart, 300L)
                                } else {
                                    refreshHeart()
                                    handler.postDelayed(refreshTemp, 300L)
                                }
                            }, 400L)
                        } else {
                            refreshTemp()
                            refreshHeart()
                        }
                    },
                    onTest = { postTest() },
                )
            }
        }
    }

    /** 「发一条测试岛」：不依赖采样与心率连接，直接贴一条固定的岛。按当前选的方式发。 */
    private fun postTest() {
        if (IslandSettings.mode == "std") {
            IslandStandard.post(
                context = this,
                id = IslandStandard.ID_TEMP,
                title = "CPU 46.2℃",
                content = "原生岛自检",
                leftText = "CPU",
                rightText = "46.2℃",
                chip = "46.2℃",
                progressPct = 62,
                heart = false,
                overThreshold = true,
                contentIntent = IslandPublish.intentFor(this, TempActivity::class.java, requestCode = 30),
            )
            val hint = if (IslandStandard.supported(this)) {
                "已发送，回到桌面看岛"
            } else {
                "本机不支持标准实时活动（需 Android 16）"
            }
            Toast.makeText(this, hint, Toast.LENGTH_SHORT).show()
            return
        }
        IslandPublish.post(
            context = this,
            id = IslandPublish.tempId(),
            title = "测试 46.2℃",
            content = "原生岛自检",
            leftText = "CPU",
            rightText = "46.2℃",
            progressPct = 62,
            heart = false,
            overThreshold = true,
            contentIntent = IslandPublish.intentFor(this, TempActivity::class.java, requestCode = 30),
        )
        Toast.makeText(this, "已发送，回到桌面看岛", Toast.LENGTH_SHORT).show()
    }
}