package com.example.hiderecents.island

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.hiderecents.hr.HeartRateService
import com.example.hiderecents.temp.TempSampler
import com.example.hiderecents.temp.TempStore

/** 岛上那两个「录制」按钮按下来之后走这里。 */
class IslandReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_TEMP_REC -> {
                if (TempSampler.recording) {
                    val session = TempSampler.stopRecording()
                    if (session != null) {
                        val list = (listOf(session) + TempStore.load(context)).take(50)
                        TempStore.save(context, list)
                    }
                } else {
                    TempSampler.startRecording()
                }
                ThermalIsland.refreshNow(context)
            }

            ACTION_HEART_REC -> {
                val i = Intent(context, HeartRateService::class.java)
                    .setAction(HeartRateService.ACTION_TOGGLE_RECORD)
                runCatching { context.startService(i) }
            }

            ACTION_TEMP_CLOSE -> {
                ThermalIsland.closeFromIsland(context)
            }
        }
    }

    companion object {
        const val ACTION_TEMP_REC = "com.example.hiderecents.island.TEMP_REC"
        const val ACTION_TEMP_CLOSE = "com.example.hiderecents.island.TEMP_CLOSE"
        const val ACTION_HEART_REC = "com.example.hiderecents.island.HEART_REC"
    }
}