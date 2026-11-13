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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.hiderecents.hr.HrDevice
import com.example.hiderecents.ui.components.BreathingDot
import com.example.hiderecents.ui.components.DshDivider
import com.example.hiderecents.ui.components.DshGhostButton
import com.example.hiderecents.ui.components.DshIcon
import com.example.hiderecents.ui.components.DshIconKind
import com.example.hiderecents.ui.components.dshClickable
import com.example.hiderecents.ui.theme.Dsh

/**
 * 选择心率设备的底部抽屉。
 *
 * 只列出广播里带心率服务（0x180D）的设备 —— 这就是「打开了心率广播」的设备；
 * 列表为空时不是坏了，是附近没有设备在广播。
 */
@Composable
fun HrDeviceSheet(
    devices: List<HrDevice>,
    scanning: Boolean,
    statusText: String,
    connectedAddress: String?,
    onDismiss: () -> Unit,
    onRescan: () -> Unit,
    onPick: (HrDevice) -> Unit,
    onDisconnect: () -> Unit,
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("选择心率设备", style = type.h2, color = colors.ink)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "带「心率」标记的设备支持标准心率服务（0x180D）",
                            style = type.caption,
                            color = colors.inkFaint,
                        )
                    }
                    if (scanning) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            BreathingDot(color = colors.accent, dotSize = 5.dp)
                            Spacer(Modifier.width(6.dp))
                            Text("搜索中", style = type.label, color = colors.accent)
                        }
                    }
                }

                Spacer(Modifier.height(14.dp))
                DshDivider()

                if (devices.isEmpty()) {
                    Spacer(Modifier.height(26.dp))
                    Text(
                        text = if (scanning) "正在搜索附近的蓝牙设备…" else "附近没有找到蓝牙设备",
                        style = type.rowTitle,
                        color = colors.inkMuted,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "手机上打开手环/手表的「心率广播」，保持屏幕亮着，再点下面的「重新扫描」。",
                        style = type.caption,
                        color = colors.inkFaint,
                    )
                    Spacer(Modifier.height(26.dp))
                } else {
                    Column(
                        Modifier
                            .heightIn(max = 320.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        devices.forEach { device ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .dshClickable(scaleOnPress = false) { onPick(device) }
                                    .padding(vertical = 13.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = device.name,
                                            style = type.rowTitle,
                                            color = colors.ink,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        if (device.address == connectedAddress) {
                                            Spacer(Modifier.width(8.dp))
                                            Text("已连接", style = type.label, color = colors.accent)
                                        } else if (device.hrAdvertising) {
                                            Spacer(Modifier.width(8.dp))
                                            Text("心率", style = type.label, color = colors.accent)
                                        }
                                    }
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        text = device.address + " · " + device.rssi + " dBm",
                                        style = type.monoSmall,
                                        color = colors.inkFaint,
                                    )
                                }
                                DshIcon(
                                    DshIconKind.CHEVRON,
                                    iconSize = 12.dp,
                                    tint = colors.inkFaint,
                                )
                            }
                            DshDivider()
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.weight(1f)) {
                        DshGhostButton(
                            text = if (scanning) "停止搜索" else "重新扫描",
                            onClick = onRescan,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Box(Modifier.weight(1f)) {
                        DshGhostButton(
                            text = if (connectedAddress != null) "断开" else "关闭",
                            onClick = { if (connectedAddress != null) onDisconnect() else onDismiss() },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }

                if (statusText.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Text(statusText, style = type.caption, color = colors.inkFaint)
                }
            }
        }
    }
}
