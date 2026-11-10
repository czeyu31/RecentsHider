package com.example.hiderecents.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.example.hiderecents.ui.components.DshIcon
import com.example.hiderecents.ui.components.DshIconKind
import com.example.hiderecents.ui.components.dshCanvasBackground
import com.example.hiderecents.ui.components.dshClickable
import com.example.hiderecents.ui.theme.Dsh

/**
 * 通用加载壳：详情类页面在解析文件期间显示的过渡界面。
 * 不用圆形转圈，用一个静态构图 + 一句可执行的说明。
 */
@Composable
fun LoadingShell(
    message: String = "正在解析文件…",
    hint: String = "大文件需要几秒钟",
    onBack: (() -> Unit)? = null,
) {
    val colors = Dsh.colors
    val type = Dsh.type
    val dimens = Dsh.dimens

    Box(Modifier.fillMaxSize().dshCanvasBackground()) {
        Column(
            Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(horizontal = dimens.screenPadding),
        ) {
            Spacer(Modifier.height(4.dp))
            Box(
                Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(dimens.iconRadius))
                    .then(
                        if (onBack != null) Modifier.dshClickable(onClick = onBack) else Modifier,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (onBack != null) {
                    DshIcon(DshIconKind.BACK, iconSize = 15.dp, tint = colors.inkMuted)
                }
            }

            Column(
                Modifier.fillMaxSize().padding(bottom = 80.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(dimens.tileRadius))
                        .background(color = colors.accentDim),
                    contentAlignment = Alignment.Center,
                ) {
                    DshIcon(DshIconKind.PACKAGE, iconSize = 20.dp, tint = colors.accent)
                }
                Spacer(Modifier.height(16.dp))
                Text(message, style = type.title, color = colors.inkMuted)
                Spacer(Modifier.height(8.dp))
                Text(hint, style = type.caption, color = colors.inkFaint)
            }
        }
    }
}
