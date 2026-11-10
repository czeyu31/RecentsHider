package com.example.hiderecents.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.hiderecents.ui.components.DshIcon
import com.example.hiderecents.ui.components.DshIconKind
import com.example.hiderecents.ui.components.DshPanel
import com.example.hiderecents.ui.components.StaggeredEnter
import com.example.hiderecents.ui.components.dshCanvasBackground
import com.example.hiderecents.ui.components.dshClickable
import com.example.hiderecents.ui.theme.Dsh

/** 工具箱中的一个工具项 */
data class ToolboxTool(
    val title: String,
    val desc: String,
    val meta: String,
    val icon: DshIconKind,
    val onClick: () -> Unit,
)

/**
 * 工具箱：两列网格，首项独占整行（打破 3 等分卡片那种模板感）。
 * 每个卡片入场做 30ms 阶梯，按压走统一的缩放 + 触感反馈。
 */
@Composable
fun ToolboxScreen(
    tools: List<ToolboxTool>,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Dsh.colors
    val dimens = Dsh.dimens
    val type = Dsh.type
    val surface = Dsh.surface

    Box(modifier.fillMaxSize().dshCanvasBackground()) {
        Column(
            Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .padding(horizontal = dimens.screenPadding),
        ) {
            Spacer(Modifier.height(4.dp))

            // 顶栏：返回 + 标题 + 副标题
            Row(
                Modifier.fillMaxWidth().height(56.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(32.dp)
                        .clip(RoundedCornerShape(dimens.iconRadius))
                        .dshClickable(onClick = onBack),
                    contentAlignment = Alignment.Center,
                ) {
                    DshIcon(DshIconKind.ARROW_LEFT, iconSize = 15.dp, tint = colors.inkMuted)
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("工具箱", style = type.h2, color = colors.ink)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = tools.size.toString() + " 个工具 · 全部本机执行",
                        style = type.label,
                        color = colors.inkFaint,
                    )
                }
            }

            Spacer(Modifier.height(dimens.gapL))

            // 网格：首项整行，其余两列
            val rest = tools.drop(1)
            tools.firstOrNull()?.let { first ->
                StaggeredEnter(index = 0) {
                    ToolTile(tool = first, wide = true)
                }
                Spacer(Modifier.height(10.dp))
            }

            rest.chunked(2).forEachIndexed { rowIndex, pair ->
                StaggeredEnter(index = rowIndex + 1) {
                    Column {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            for (tool in pair) {
                                Box(Modifier.weight(1f)) { ToolTile(tool = tool, wide = false) }
                            }
                            if (pair.size == 1) Box(Modifier.weight(1f))
                        }
                        Spacer(Modifier.height(10.dp))
                    }
                }
            }

            Spacer(Modifier.weight(1f))

            Column(Modifier.padding(bottom = 26.dp)) {
                if (!surface.hasPanels) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(color = colors.hairline),
                    )
                    Spacer(Modifier.height(14.dp))
                }
                Text("长按工具可置顶到首页", style = type.caption, color = colors.inkFaint)
            }
        }
    }
}

@Composable
private fun ToolTile(tool: ToolboxTool, wide: Boolean) {
    val colors = Dsh.colors
    val dimens = Dsh.dimens
    val type = Dsh.type
    val surface = Dsh.surface

    val padding = PaddingValues(
        start = if (surface.hasPanels) 16.dp else 0.dp,
        top = if (surface.hasPanels) 16.dp else 16.dp,
        end = if (surface.hasPanels) 16.dp else 0.dp,
        bottom = if (surface.hasPanels) 16.dp else 16.dp,
    )

    Box(
        Modifier
            .fillMaxWidth()
            .then(if (surface.hasPanels) Modifier else Modifier.padding(horizontal = 0.dp))
            .clip(RoundedCornerShape(if (surface.hasPanels) dimens.tileRadius else 0.dp))
            .then(
                if (surface.hasPanels) {
                    Modifier.background(color = colors.panelTop.copy(alpha = 0.55f))
                        .border(1.dp, colors.hairline, RoundedCornerShape(dimens.tileRadius))
                } else {
                    Modifier
                        .border(0.dp, Color.Transparent)
                },
            )
            .dshClickable(scaleOnPress = true, onClick = tool.onClick)
            .padding(padding),
    ) {
        Column {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                Box(
                    Modifier
                        .size(32.dp)
                        .clip(RoundedCornerShape(dimens.iconRadius))
                        .background(
                            color = if (surface.hasPanels) Color.White.copy(alpha = 0.05f) else Color.Transparent,
                        )
                        .then(
                            if (surface.hasPanels) {
                                Modifier.border(1.dp, colors.hairline, RoundedCornerShape(dimens.iconRadius))
                            } else {
                                Modifier
                            },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    DshIcon(tool.icon, iconSize = 15.dp, tint = colors.accent)
                }
                Spacer(Modifier.weight(1f))
                DshIcon(DshIconKind.CHEVRON, iconSize = 12.dp, tint = colors.inkFaint)
            }

            Spacer(Modifier.height(14.dp))
            Text(
                text = tool.title,
                style = if (wide) type.h2 else type.rowTitle,
                color = colors.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = tool.desc,
                style = type.caption,
                color = colors.inkFaint,
                maxLines = if (wide) 1 else 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(12.dp))
            Text(tool.meta, style = type.label, color = colors.inkMuted)
        }
    }
}
