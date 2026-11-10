package com.example.hiderecents.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.hiderecents.ui.components.DshDivider
import com.example.hiderecents.ui.components.DshIcon
import com.example.hiderecents.ui.components.DshIconKind
import com.example.hiderecents.ui.components.StaggeredEnter
import com.example.hiderecents.ui.components.dshCanvasBackground
import com.example.hiderecents.ui.components.dshClickable
import com.example.hiderecents.ui.components.dshFieldFill
import com.example.hiderecents.ui.components.paperHeaderRule
import com.example.hiderecents.ui.theme.Dsh

/** 工具箱中的一个工具项 */
data class ToolboxTool(
    val title: String,
    val desc: String,
    val meta: String,
    val icon: DshIconKind,
    val onClick: () -> Unit,
)

/** 半宽卡片的最小高度：保证同一行两张卡等高，且不依赖 intrinsic 测量 */
private val HalfTileMinHeight = 152.dp

/**
 * 工具箱。
 *
 * 等高规则（这是参考板的核心特征）：
 * 不再使用 IntrinsicSize.Max + fillMaxHeight —— 那套在 intrinsic 测量阶段会把子项固有高度算成 0，
 * 导致整行塌成零高、卡片全部消失（纸本工具箱就是这么空掉的）。
 * 改为内容驱动：标题 1 行 + 说明固定 2 行 + meta 1 行 + heightIn(min = 152dp)，
 * 同一行两张卡片结构一致，高度天然相等。
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
                .safeDrawingPadding()
                .padding(horizontal = dimens.screenPadding),
        ) {
            Spacer(Modifier.height(4.dp))

            // 顶栏：返回 + 标题 + 副标题（纸本底边一条 1.5dp 墨黑实线）
            Row(
                Modifier.fillMaxWidth().height(56.dp).paperHeaderRule(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(32.dp)
                        .clip(RoundedCornerShape(dimens.iconRadius))
                        .dshClickable(onClick = onBack),
                    contentAlignment = Alignment.Center,
                ) {
                    DshIcon(DshIconKind.BACK, iconSize = 15.dp, tint = colors.inkMuted)
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

            Spacer(Modifier.height(if (surface.hasPanels) dimens.gapL else 26.dp))

            // 首项独占整行
            tools.firstOrNull()?.let { first ->
                StaggeredEnter(index = 0) {
                    ToolTile(tool = first, wide = true)
                }
                if (surface.hasPanels) Spacer(Modifier.height(10.dp))
            }

            // 其余两列，逐行排列；最后一行若只剩一个工具，让它整行铺满
            // 注意：行间距的 Spacer 必须放在 StaggeredEnter 外面。
            // StaggeredEnter 内部是 Box，Box 的子项是层叠而非依次排列，
            // Spacer 放进去不会撑开高度，会被卡片盖住 —— 这就是「卡片上下没有间隔」的原因。
            tools.drop(1).chunked(2).forEachIndexed { rowIndex, pair ->
                StaggeredEnter(index = rowIndex + 1) {
                    if (pair.size == 1) {
                        ToolTile(tool = pair[0], wide = true)
                    } else {
                        Row(Modifier.fillMaxWidth()) {
                            pair.forEachIndexed { index, tool ->
                                if (index > 0 && surface.hasPanels) {
                                    Spacer(Modifier.width(10.dp))
                                }
                                Box(Modifier.weight(1f)) {
                                    ToolTile(
                                        tool = tool,
                                        wide = false,
                                        leftRule = index > 0,
                                    )
                                }
                            }
                        }
                    }
                }
                if (surface.hasPanels) Spacer(Modifier.height(10.dp))
            }

            if (!surface.hasPanels) DshDivider()

            // 底部不再放提示文案（原「长按工具可置顶到首页」已移除）
            Spacer(Modifier.weight(1f))
            Spacer(Modifier.height(26.dp))
        }
    }
}

@Composable
private fun EmptyCell() {
    val hairline = Dsh.colors.hairline
    val hasPanels = Dsh.surface.hasPanels
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(min = if (hasPanels) HalfTileMinHeight else 0.dp)
            .then(
                if (hasPanels) {
                    Modifier
                } else {
                    Modifier.drawBehind {
                        val w = 1.dp.toPx()
                        drawLine(hairline, Offset(0f, 0f), Offset(size.width, 0f), w)
                        drawLine(hairline, Offset(0f, 0f), Offset(0f, size.height), w)
                    }
                },
            ),
    )
}

@Composable
private fun ToolTile(
    tool: ToolboxTool,
    wide: Boolean,
    leftRule: Boolean = false,
) {
    val colors = Dsh.colors
    val dimens = Dsh.dimens
    val type = Dsh.type
    val surface = Dsh.surface
    val hairline = colors.hairline

    Column(
        Modifier
            .fillMaxWidth()
            .then(
                if (surface.hasPanels) {
                    Modifier
                } else {
                    Modifier.drawBehind {
                        val w = 1.dp.toPx()
                        drawLine(hairline, Offset(0f, 0f), Offset(size.width, 0f), w)
                        if (leftRule) {
                            drawLine(hairline, Offset(0f, 0f), Offset(0f, size.height), w)
                        }
                    }
                },
            )
            .clip(RoundedCornerShape(if (surface.hasPanels) dimens.tileRadius else 0.dp))
            .then(
                if (surface.hasPanels) {
                    Modifier
                        .background(color = dshFieldFill(0.55f))
                        .border(1.dp, hairline, RoundedCornerShape(dimens.tileRadius))
                } else {
                    Modifier
                },
            )
            .dshClickable(scaleOnPress = true, onClick = tool.onClick)
            // 只有石墨需要锁高对齐；纸本原本就是按内容撑高，加最小高度反而多余
            .heightIn(min = if (wide || !surface.hasPanels) 0.dp else HalfTileMinHeight)
            .padding(
                start = if (surface.hasPanels) 16.dp else if (leftRule) 18.dp else 0.dp,
                top = 16.dp,
                end = 16.dp,
                bottom = 16.dp,
            ),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            if (surface.hasPanels) {
                Box(
                    Modifier
                        .size(32.dp)
                        .clip(RoundedCornerShape(dimens.iconRadius))
                        .background(color = Color.White.copy(alpha = 0.05f))
                        .border(1.dp, hairline, RoundedCornerShape(dimens.iconRadius)),
                    contentAlignment = Alignment.Center,
                ) {
                    DshIcon(tool.icon, iconSize = 15.dp, tint = colors.accent)
                }
            } else {
                DshIcon(tool.icon, iconSize = 17.dp, tint = colors.accent)
            }
            Spacer(Modifier.weight(1f))
            DshIcon(DshIconKind.CHEVRON, iconSize = 12.dp, tint = colors.inkFaint)
        }

        Spacer(Modifier.height(if (surface.hasPanels) 14.dp else 16.dp))
        Text(
            text = tool.title,
            style = if (wide) type.h2 else type.title,
            color = colors.ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = tool.desc,
            style = type.caption,
            color = colors.inkFaint,
            minLines = if (wide || !surface.hasPanels) 1 else 2,
            maxLines = if (wide) 1 else 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(12.dp))
        Text(tool.meta, style = type.label, color = colors.inkMuted)
    }
}
