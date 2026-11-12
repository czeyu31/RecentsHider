package com.example.hiderecents.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.hiderecents.ui.theme.Dsh

/**
 * 带浮动标签的输入框。
 *
 * 标签行为（不是占位符）：
 * - 未输入且未聚焦：标签停留在框内，垂直居中
 * - 聚焦或有内容：标签缩小（13.5sp -> 11sp）并上移到框上方，之后一直显示
 *
 * 实现要点：容器高度固定为「16dp 标签带 + 46dp 输入区」，
 * 标签只在纵向做位移与字号动画，不改变布局高度，所以不会引起页面跳动。
 */
@Composable
fun DshTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    enabled: Boolean = true,
) {
    val colors = Dsh.colors
    val type = Dsh.type
    val dimens = Dsh.dimens
    val hasPanels = Dsh.surface.hasPanels

    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val floated = focused || value.isNotEmpty()
    val p by animateFloatAsState(
        targetValue = if (floated) 1f else 0f,
        animationSpec = tween(durationMillis = 150),
        label = "floatLabelProgress",
    )

    val fieldHeight = 46.dp
    val bandHeight = 16.dp
    val shape = RoundedCornerShape(dimens.controlRadius)

    val labelColor by animateColorAsState(
        targetValue = when {
            focused -> colors.accent
            floated -> colors.inkMuted
            else -> colors.inkFaint
        },
        animationSpec = tween(durationMillis = 150),
        label = "floatLabelColor",
    )
    val borderColor by animateColorAsState(
        targetValue = if (focused) colors.accentBorder else colors.hairline,
        animationSpec = tween(durationMillis = 150),
        label = "floatLabelBorder",
    )

    Box(
        modifier
            .fillMaxWidth()
            .height(fieldHeight + bandHeight),
    ) {
        // 输入区底：石墨用面板色半透明，纸本透明（靠 1px 描边分层）
        Box(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .height(fieldHeight)
                .clip(shape)
                .then(
                    if (hasPanels) {
                        Modifier.background(color = dshFieldFill(0.55f))
                    } else {
                        Modifier
                    },
                )
                .border(width = 1.dp, color = borderColor, shape = shape),
        )

        Box(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .height(fieldHeight)
                .padding(horizontal = 14.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = singleLine,
                enabled = enabled,
                interactionSource = interaction,
                textStyle = type.rowTitle.copy(color = colors.ink),
                cursorBrush = SolidColor(colors.accent),
                modifier = Modifier.fillMaxWidth(),
            )
        }

        // 浮动标签：p=0 时落在输入区垂直中心（16dp + 13dp），p=1 时贴到容器顶部
        Text(
            text = label,
            color = labelColor,
            fontSize = (13.5f - 2.5f * p).sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .offset(x = 14.dp, y = (29f * (1f - p)).dp)
                .fillMaxWidth(0.86f),
        )
    }
}
