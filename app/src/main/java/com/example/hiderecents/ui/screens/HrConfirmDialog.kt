package com.example.hiderecents.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.example.hiderecents.ui.components.DshGhostButton
import com.example.hiderecents.ui.components.dshClickable
import com.example.hiderecents.ui.theme.Dsh

/**
 * 心率模块的确认弹窗（删除单条 / 清空全部共用）。
 *
 * 居中卡片、一像素描边，与设置页弹窗同一套外观；确认按钮用强调色描边区分破坏性操作。
 */
@Composable
fun HrConfirmDialog(
    title: String,
    detail: String,
    note: String,
    confirmText: String,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
) {
    val colors = Dsh.colors
    val type = Dsh.type
    val shape = RoundedCornerShape(Dsh.dimens.panelRadius)

    Dialog(onDismissRequest = onCancel) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(color = colors.canvas)
                .border(1.dp, colors.hairlineStrong, shape)
                .padding(20.dp),
        ) {
            Text(title, style = type.h2, color = colors.ink)
            Spacer(Modifier.height(12.dp))
            Text(detail, style = type.label, color = colors.inkMuted)
            Spacer(Modifier.height(6.dp))
            Text(note, style = type.caption, color = colors.inkFaint)
            Spacer(Modifier.height(20.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.weight(1f)) {
                    DshGhostButton(
                        text = "取消",
                        onClick = onCancel,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Box(Modifier.weight(1f)) {
                    HrDangerButton(
                        text = confirmText,
                        onClick = onConfirm,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

/** 主色描边的破坏性按钮：形状与 DshGhostButton 对齐，只用强调色区分。 */
@Composable
fun HrDangerButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = Dsh.colors
    val shape = RoundedCornerShape(Dsh.dimens.controlRadius)
    Box(
        modifier
            .height(40.dp)
            .clip(shape)
            .background(color = colors.accent.copy(alpha = if (Dsh.surface.hasPanels) 0.10f else 0.05f))
            .border(1.dp, colors.accentBorder, shape)
            .dshClickable(onClick = onClick)
            .padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = Dsh.type.rowTitle, color = colors.accent)
    }
}
