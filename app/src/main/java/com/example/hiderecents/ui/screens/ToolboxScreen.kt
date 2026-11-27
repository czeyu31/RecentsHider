package com.example.hiderecents.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridItemInfo
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.example.hiderecents.ui.components.DshDivider
import com.example.hiderecents.ui.components.DshIconButton
import com.example.hiderecents.ui.components.DshIcon
import com.example.hiderecents.ui.components.DshIconKind
import com.example.hiderecents.ui.components.StaggeredEnter
import com.example.hiderecents.ui.components.dshCanvasBackground
import com.example.hiderecents.ui.components.dshClickable
import com.example.hiderecents.ui.components.dshFieldFill
import com.example.hiderecents.ui.components.paperHeaderRule
import com.example.hiderecents.ui.theme.Dsh
import kotlin.math.roundToInt

/** 工具箱中的一个工具项 */
data class ToolboxTool(
    val title: String,
    val desc: String,
    val meta: String,
    val icon: DshIconKind,
    val onClick: () -> Unit,
    /** 半宽卡片里强制正方形（心率广播 / 原生岛这一对） */
    val square: Boolean = false,
)

/** 半宽卡片的最小高度：保证同一行两张卡等高，且不依赖 intrinsic 测量 */
private val HalfTileMinHeight = 152.dp

/**
 * 工具箱。
 *
 * 布局：LazyVerticalGrid 两列，首项独占整行（span = 2），其余按两列排；
 * 若剩余数量为奇数，最后一个独占整行（与旧版 chunked(2) 的收尾规则一致）。
 * 卡片外观完全交给 [ToolTile]，方形/纸本描边逻辑保持不变。
 *
 * 拖拽排序（原地网格）：
 * - 长按某张卡进入拖拽（点按仍然是打开工具），触发一次触感反馈；
 * - 被托起的卡用一层悬浮「残影」跟手，原格变透明但保留占位，作为落位空档；
 * - 手指每帧落在哪一格，就用 onReorder 实时交换顺序；
 * - 其余卡片靠 Modifier.animateItem() 的弹簧动画让位；
 * - 松手后由调用方持久化顺序（onReorderSettled）。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ToolboxScreen(
    tools: List<ToolboxTool>,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenSettings: () -> Unit = {},
    onReorder: (from: Int, to: Int) -> Unit = { _, _ -> },
    onReorderSettled: () -> Unit = {},
) {
    val colors = Dsh.colors
    val dimens = Dsh.dimens
    val type = Dsh.type
    val surface = Dsh.surface

    val gridState = rememberLazyGridState()
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current

    // 拖拽运行时状态（用 snapshot state，确保手势协程读到的是最新值，而不是捕获时的旧值）
    var draggingKey by remember { mutableStateOf<String?>(null) }
    var draggingIndex by remember { mutableIntStateOf(-1) }
    var pointer by remember { mutableStateOf(Offset.Zero) }
    var grab by remember { mutableStateOf(Offset.Zero) }
    var ghostWidthPx by remember { mutableIntStateOf(0) }
    var ghostWide by remember { mutableStateOf(false) }
    var showGhost by remember { mutableStateOf(false) }
    var autoScroll by remember { mutableStateOf(0f) }

    // 回调可能在重组中换实例，用 rememberUpdatedState 保证手势里用最新的
    val currentOnReorder by rememberUpdatedState(onReorder)
    val currentOnSettled by rememberUpdatedState(onReorderSettled)

    // 拖到上/下边缘时的自动滚动：手势作用域不能直接调 suspend 的 scrollBy，
    // 这里用手势写入的 autoScroll 信号，在每帧协程里真正滚动。
    LaunchedEffect(Unit) {
        while (true) {
            val s = autoScroll
            if (s != 0f) gridState.scrollBy(s * 22f)
            withFrameNanos { }
        }
    }

    val isPanel = surface.hasPanels
    val gap = if (isPanel) 10.dp else 0.dp
    val lastLone = (tools.size - 1) % 2 == 1

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
                Spacer(Modifier.width(10.dp))
                // 设置入口：与首页完全一致 —— 石墨（hasPanels）用滑杆图标，纸本用文字「设置」
                if (surface.hasPanels) {
                    DshIconButton(DshIconKind.SLIDERS, onClick = onOpenSettings)
                } else {
                    Box(
                        Modifier
                            .dshClickable(scaleOnPress = false, onClick = onOpenSettings)
                            .padding(horizontal = 6.dp, vertical = 4.dp),
                    ) {
                        Text("设置", style = type.caption, color = colors.inkMuted)
                    }
                }
            }

            Spacer(Modifier.height(if (surface.hasPanels) dimens.gapL else 26.dp))

            Box(Modifier.fillMaxWidth().weight(1f)) {
                val draggingTool = draggingKey?.let { k -> tools.firstOrNull { it.title == k } }

                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    state = gridState,
                    contentPadding = PaddingValues(bottom = 26.dp),
                    horizontalArrangement = Arrangement.spacedBy(gap),
                    verticalArrangement = Arrangement.spacedBy(gap),
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                // 长按才进入拖拽；长按前若有明显位移则让给滚动
                                val longPress = awaitLongPressOrCancellation(down.id)
                                    ?: return@awaitEachGesture

                                fun itemAt(p: Offset): LazyGridItemInfo? =
                                    gridState.layoutInfo.visibleItemsInfo.firstOrNull { info ->
                                        if (info.index >= tools.size) return@firstOrNull false
                                        val o = info.offset
                                        p.x >= o.x && p.x < o.x + info.size.width &&
                                            p.y >= o.y && p.y < o.y + info.size.height
                                    }

                                val start = itemAt(longPress.position)
                                    ?: return@awaitEachGesture

                                draggingKey = start.key as? String
                                draggingIndex = start.index
                                grab = longPress.position -
                                    Offset(start.offset.x.toFloat(), start.offset.y.toFloat())
                                ghostWidthPx = start.size.width
                                ghostWide = start.index == 0
                                pointer = longPress.position
                                showGhost = true
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)

                                val h = size.height.toFloat()
                                val edge = 56.dp.toPx()

                                while (true) {
                                    val event = awaitPointerEvent(PointerEventPass.Initial)
                                    val ch = event.changes.firstOrNull { it.id == down.id } ?: break
                                    pointer = ch.position

                                    val target = itemAt(ch.position)
                                    if (target != null && target.index != draggingIndex) {
                                        currentOnReorder(draggingIndex, target.index)
                                        draggingIndex = target.index
                                    }

                                    // 通知每帧协程：靠近上/下边缘时自动滚动，方便跨屏排序
                                    autoScroll = when {
                                        ch.position.y < edge -> -1f
                                        ch.position.y > h - edge -> 1f
                                        else -> 0f
                                    }

                                    if (ch.changedToUpIgnoreConsumed()) {
                                        ch.consume()
                                        break
                                    }
                                    ch.consume()
                                }

                                showGhost = false
                                draggingKey = null
                                draggingIndex = -1
                                autoScroll = 0f
                                currentOnSettled()
                            }
                        },
                ) {
                    tools.forEachIndexed { index, tool ->
                        val isFull = index == 0 || (lastLone && index == tools.size - 1)
                        item(
                            key = tool.title,
                            span = { GridItemSpan(if (isFull) maxLineSpan else 1) },
                        ) {
                            val isDragging = tool.title == draggingKey
                            val col = if (index <= 0) 0 else (index - 1) % 2
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .animateItem()
                                    .graphicsLayer { alpha = if (isDragging) 0f else 1f },
                            ) {
                                StaggeredEnter(index = index) {
                                    ToolTile(
                                        tool = tool,
                                        wide = isFull,
                                        leftRule = !isPanel && col == 1,
                                    )
                                }
                            }
                        }
                    }

                    if (!isPanel) {
                        item(key = "__divider__", span = { GridItemSpan(maxLineSpan) }) {
                            DshDivider()
                        }
                    }
                }

                // 悬浮残影：跟随手指，视觉上就是「被拿起来的那张卡」
                if (showGhost && draggingTool != null) {
                    val ghostW = with(density) { ghostWidthPx.toDp() }
                    Box(
                        Modifier
                            .offset {
                                IntOffset(
                                    (pointer.x - grab.x).roundToInt(),
                                    (pointer.y - grab.y).roundToInt(),
                                )
                            }
                            .width(ghostW)
                            .graphicsLayer {
                                scaleX = 1.045f
                                scaleY = 1.045f
                                // 石墨：投影 + 圆角，表示“拿起来”；纸本是平面印刷感，不加投影也不加描边
                                if (isPanel) {
                                    shadowElevation = 20.dp.toPx()
                                    shape = RoundedCornerShape(dimens.tileRadius)
                                }
                                clip = false
                            },
                    ) {
                        ToolTile(tool = draggingTool, wide = ghostWide, interactive = false)
                    }
                }
            }
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
    interactive: Boolean = true,
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
            .then(
                if (interactive) {
                    Modifier.dshClickable(scaleOnPress = true, onClick = tool.onClick)
                } else {
                    Modifier
                },
            )
            .then(
                if (!wide && tool.square) {
                    Modifier.aspectRatio(1f)
                } else {
                    // 只有石墨需要锁高对齐；纸本原本就是按内容撑高，加最小高度反而多余
                    Modifier.heightIn(min = if (wide || !surface.hasPanels) 0.dp else HalfTileMinHeight)
                },
            )
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
