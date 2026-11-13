package com.example.hiderecents.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.hiderecents.ui.components.DshDivider
import com.example.hiderecents.ui.components.DshGhostButton
import com.example.hiderecents.ui.components.DshSwitch
import com.example.hiderecents.ui.components.dshClickable
import com.example.hiderecents.ui.theme.Dsh

/**
 * 心率广播的设置抽屉。
 *
 * 两项都是「行为开关」，没有数值：打开自动连接省一次选设备，保持连接让心率在离开页面后继续。
 */
@Composable
fun HrSettingsSheet(
    autoConnect: Boolean,
    keepLink: Boolean,
    deviceName: String,
    onAutoConnect: (Boolean) -> Unit,
    onKeepLink: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = Dsh.colors
    val type = Dsh.type
    val dimens = Dsh.dimens

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            Box(
                Modifier
                    .fillMaxSize()
                    .dshClickable(scaleOnPress = false, haptic = false) { onDismiss() },
            )
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 28.dp)
                    .clip(RoundedCornerShape(dimens.panelRadius))
                    .background(color = colors.canvas)
                    .border(1.dp, colors.hairlineStrong, RoundedCornerShape(dimens.panelRadius))
                    .padding(18.dp),
            ) {
                Text("设置", style = type.h2, color = colors.ink)
                Spacer(Modifier.height(4.dp))
                Text(
                    text = if (deviceName.isEmpty()) "还没有连接过设备" else "上次连接：" + deviceName,
                    style = type.caption,
                    color = colors.inkFaint,
                )
                Spacer(Modifier.height(14.dp))
                DshDivider()
                Spacer(Modifier.height(6.dp))

                SettingRow(
                    title = "自动连接上次设备",
                    detail = "打开心率广播时直接连上次那台，省掉选设备这一步",
                    checked = autoConnect,
                    onChecked = onAutoConnect,
                )
                DshDivider()
                SettingRow(
                    title = "退出页面后保持连接",
                    detail = "开着时离开这一页仍会继续接收心率，通知栏一直显示当前心率",
                    checked = keepLink,
                    onChecked = onKeepLink,
                )
                DshDivider()

                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    DshGhostButton(
                        text = "关闭",
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingRow(
    title: String,
    detail: String,
    checked: Boolean,
    onChecked: (Boolean) -> Unit,
) {
    val colors = Dsh.colors
    val type = Dsh.type
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = type.rowTitle, color = colors.ink)
            Spacer(Modifier.height(3.dp))
            Text(detail, style = type.caption, color = colors.inkFaint)
        }
        Spacer(Modifier.width(12.dp))
        DshSwitch(checked = checked, onCheckedChange = onChecked)
    }
}
