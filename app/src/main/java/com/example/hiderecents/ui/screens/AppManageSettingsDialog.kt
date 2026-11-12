package com.example.hiderecents.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.example.hiderecents.ui.components.DshDivider
import com.example.hiderecents.ui.components.DshGhostButton
import com.example.hiderecents.ui.components.DshSwitch
import com.example.hiderecents.ui.components.dshClickable
import com.example.hiderecents.ui.theme.Dsh

/**
 * 应用管理的本地设置。
 *
 * 只放「跟隐藏/恢复行为有关」的开关，所以从首页设置里挪到这里：
 * 用户在应用管理里操作隐藏/恢复时，最需要的正是这个开关。
 */
@Composable
fun AppManageSettingsDialog(
    relaunchOnUnhide: Boolean,
    onRelaunchOnUnhideChange: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = Dsh.colors
    val type = Dsh.type
    val dimens = Dsh.dimens
    val shape = RoundedCornerShape(dimens.panelRadius)

    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(color = colors.canvas)
                .border(1.dp, colors.hairlineStrong, shape)
                .padding(20.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("应用管理设置", style = type.h2, color = colors.ink)
                Spacer(Modifier.weight(1f))
                Box(
                    Modifier
                        .size(28.dp)
                        .clip(RoundedCornerShape(dimens.iconRadius))
                        .dshClickable(scaleOnPress = false, onClick = onDismiss),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("×", style = type.rowTitle, color = colors.inkMuted)
                }
            }

            Spacer(Modifier.height(16.dp))
            DshDivider()
            Spacer(Modifier.height(6.dp))

            Row(
                Modifier.fillMaxWidth().padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("恢复时回到前台", style = type.rowTitle, color = colors.ink)
                    Spacer(Modifier.height(5.dp))
                    Text(
                        "关闭后取消隐藏只清掉隐藏标记，不会把应用拉到前台",
                        style = type.caption,
                        color = colors.inkFaint,
                    )
                }
                Spacer(Modifier.width(14.dp))
                DshSwitch(
                    checked = relaunchOnUnhide,
                    onCheckedChange = onRelaunchOnUnhideChange,
                )
            }

            Spacer(Modifier.height(12.dp))
            DshGhostButton("完成", onClick = onDismiss, modifier = Modifier.fillMaxWidth())
        }
    }
}
