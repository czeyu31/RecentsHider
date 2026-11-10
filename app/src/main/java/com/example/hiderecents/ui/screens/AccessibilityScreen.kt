package com.example.hiderecents.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.hiderecents.ui.components.paperHeaderRule
import com.example.hiderecents.ui.components.AppIconTile
import com.example.hiderecents.ui.components.DshDivider
import com.example.hiderecents.ui.components.dshFieldFill
import com.example.hiderecents.ui.components.DshGhostButton
import com.example.hiderecents.ui.components.DshIcon
import com.example.hiderecents.ui.components.DshIconKind
import com.example.hiderecents.ui.components.DshSwitch
import com.example.hiderecents.ui.components.StaggeredEnter
import com.example.hiderecents.ui.components.dshCanvasBackground
import com.example.hiderecents.ui.components.dshClickable
import com.example.hiderecents.ui.theme.Dsh
import com.example.hiderecents.ui.theme.MotionSpecs
import com.example.hiderecents.ui.theme.rememberMotionEnabled
import com.example.hiderecents.ui.components.DshTextField

/** 无障碍服务列表项。icon 为所属应用的真实图标。 */
data class A11yServiceItem(
    val componentName: String,
    val packageName: String,
    val appName: String,
    val serviceName: String,
    val description: String,
    val icon: ImageBitmap?,
    val isEnabled: Boolean,
    val isProtected: Boolean,
    val isPinned: Boolean,
    val isSystem: Boolean,
    val seed: Int,
)

/** 筛选档位：全部 / 用户应用 / 已保护 */
enum class A11yFilter(val label: String) {
    ALL("全部"),
    USER("用户应用"),
    PROTECTED("已保护"),
}

/**
 * 无障碍管理页。
 *
 * 功能与原 AccessibilityManageActivity 一致：服务枚举、启用/禁用、防回收保护状态机、
 * 守护服务确保开启、四字段搜索、系统应用过滤（三档）、长按置顶与保护。
 */
@Composable
fun AccessibilityScreen(
    services: List<A11yServiceItem>,
    totalCount: Int,
    enabledCount: Int,
    protectedCount: Int,
    autoProtect: Boolean,
    keepAliveEnabled: Boolean,
    query: String,
    onQueryChange: (String) -> Unit,
    filter: A11yFilter,
    onFilterChange: (A11yFilter) -> Unit,
    loading: Boolean,
    onBack: () -> Unit,
    onToggleAutoProtect: (Boolean) -> Unit,
    onToggleService: (A11yServiceItem, Boolean) -> Unit,
    onOpenActions: (A11yServiceItem) -> Unit,
    onOpenSystemSettings: () -> Unit,
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

            // ---------------- 顶栏 ----------------
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
                    Text("无障碍管理", style = type.h2, color = colors.ink)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = enabledCount.toString() + " / " + totalCount + " 个服务已启用",
                        style = type.label,
                        color = colors.inkFaint,
                    )
                }
            }

            Spacer(Modifier.height(if (Dsh.surface.hasPanels) dimens.gapM else 22.dp))

            // ---------------- 防回收保护 ----------------
            StaggeredEnter(index = 0) {
                AutoProtectBlock(
                    autoProtect = autoProtect,
                    protectedCount = protectedCount,
                    keepAliveEnabled = keepAliveEnabled,
                    onToggle = onToggleAutoProtect,
                    onOpenSettings = onOpenSystemSettings,
                )
            }

            Spacer(Modifier.height(dimens.gapM))

            // ---------------- 搜索 ----------------
            A11ySearchField(query = query, onQueryChange = onQueryChange)

            Spacer(Modifier.height(12.dp))

            // ---------------- 筛选 ----------------
            A11yFilterBar(
                filter = filter,
                onFilterChange = onFilterChange,
                counts = mapOf(
                    A11yFilter.ALL to totalCount,
                    A11yFilter.USER to services.count { !it.isSystem },
                    A11yFilter.PROTECTED to protectedCount,
                ),
            )

            Spacer(Modifier.height(12.dp))

            // ---------------- 列表 ----------------
            Box(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .then(
                        if (surface.hasPanels) {
                            Modifier
                                .clip(RoundedCornerShape(dimens.panelRadius))
                                .background(
                                    brush = Brush.verticalGradient(
                                        listOf(colors.panelTop, colors.panelBottom),
                                    ),
                                )
                                .border(1.dp, colors.hairline, RoundedCornerShape(dimens.panelRadius))
                        } else {
                            Modifier
                        },
                    ),
            ) {
                when {
                    loading -> A11ySkeleton()
                    services.isEmpty() -> A11yEmpty(hasQuery = query.isNotEmpty())
                    else -> LazyColumn(Modifier.fillMaxSize()) {
                        itemsIndexed(services, key = { _, item -> item.componentName }) { index, item ->
                            if (index > 0) {
                                DshDivider(
                                    Modifier.padding(
                                        horizontal = if (surface.hasPanels) 14.dp else 0.dp,
                                    ),
                                )
                            }
                            if (index < 12) {
                                StaggeredEnter(index = index) {
                                    A11yRow(
                                        item = item,
                                        onToggle = onToggleService,
                                        onOpenActions = onOpenActions,
                                    )
                                }
                            } else {
                                A11yRow(
                                    item = item,
                                    onToggle = onToggleService,
                                    onOpenActions = onOpenActions,
                                )
                            }
                        }
                    }
                }
            }

            // ---------------- 底栏 ----------------
            Spacer(Modifier.height(10.dp))
            DshDivider(strong = !surface.hasPanels)
            Row(
                Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 26.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "保护中 " + protectedCount + " 个服务",
                    style = type.label,
                    color = colors.inkFaint,
                )
                Spacer(Modifier.weight(1f))
                DshGhostButton(text = "系统设置", onClick = onOpenSystemSettings)
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 防回收保护区块
// ---------------------------------------------------------------------------

@Composable
private fun AutoProtectBlock(
    autoProtect: Boolean,
    protectedCount: Int,
    keepAliveEnabled: Boolean,
    onToggle: (Boolean) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val colors = Dsh.colors
    val type = Dsh.type
    val surface = Dsh.surface
    val dimens = Dsh.dimens

    val shape = RoundedCornerShape(dimens.panelRadius)
    Column(
        Modifier
            .fillMaxWidth()
            .then(
                if (surface.hasPanels) {
                    Modifier
                        .clip(shape)
                        .background(
                            brush = Brush.verticalGradient(listOf(colors.panelTop, colors.panelBottom)),
                        )
                        .border(1.dp, colors.hairline, shape)
                        .padding(18.dp)
                } else {
                    Modifier.padding(vertical = 16.dp)
                },
            ),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("防回收保护", style = type.title, color = colors.ink)
                Spacer(Modifier.height(6.dp))
                Text(
                    text = when {
                        !autoProtect -> "关闭状态下被系统关掉的服务不会自动恢复"
                        protectedCount == 0 -> "已开启，但还没有选择需要保护的服务"
                        else -> "已开启，实时监听变化，被关掉 15 秒内自动恢复"
                    },
                    style = type.caption,
                    color = colors.inkFaint,
                )
            }
            Spacer(Modifier.width(12.dp))
            DshSwitch(checked = autoProtect, onCheckedChange = onToggle)
        }

        if (autoProtect) {
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(5.dp)
                        .clip(RoundedCornerShape(percent = 50))
                        .background(
                            color = if (keepAliveEnabled) colors.accent else colors.inkFaint.copy(alpha = 0.5f),
                        ),
                )
                Spacer(Modifier.width(7.dp))
                Text(
                    // 诚实态：只有系统无障碍列表里真的存在我们的守护服务，才说「运行中」。
                    // 没有 Shizuku 时本应用无法写 secure settings，不能假装开启成功。
                    text = if (keepAliveEnabled) {
                        "守护服务运行中"
                    } else {
                        "守护服务未在系统无障碍中启用"
                    },
                    style = type.monoSmall,
                    color = if (keepAliveEnabled) colors.inkFaint else colors.accent,
                    modifier = Modifier.weight(1f),
                )
                if (!keepAliveEnabled) {
                    Spacer(Modifier.width(10.dp))
                    DshGhostButton(text = "去开启", onClick = onOpenSettings)
                }
            }
            if (!keepAliveEnabled) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "在系统无障碍设置里找到 System Tool 并打开，之后防回收才能生效",
                    style = type.caption,
                    color = colors.inkFaint,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 搜索 / 筛选
// ---------------------------------------------------------------------------

@Composable
private fun A11ySearchField(query: String, onQueryChange: (String) -> Unit) {
    val surface = Dsh.surface

    Column(Modifier.fillMaxWidth()) {
        DshTextField(
            value = query,
            onValueChange = onQueryChange,
            label = "搜索应用、服务名或描述",
        )
        if (!surface.hasPanels) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(color = Dsh.colors.hairline),
            )
        }
    }
}
@Composable
private fun A11yFilterBar(
    filter: A11yFilter,
    onFilterChange: (A11yFilter) -> Unit,
    counts: Map<A11yFilter, Int>,
) {
    val colors = Dsh.colors
    val type = Dsh.type
    val surface = Dsh.surface
    val dimens = Dsh.dimens

    Row(
        Modifier
            .fillMaxWidth()
            .then(
                if (surface.hasPanels) {
                    Modifier
                        .clip(RoundedCornerShape(dimens.controlRadius))
                        .background(color = dshFieldFill(0.5f))
                        .border(1.dp, colors.hairline, RoundedCornerShape(dimens.controlRadius))
                } else {
                    Modifier
                },
            )
            .padding(if (surface.hasPanels) 4.dp else 0.dp),
        horizontalArrangement = Arrangement.spacedBy(if (surface.hasPanels) 4.dp else 18.dp),
    ) {
        A11yFilter.values().forEach { item ->
            val selected = item == filter
            val background by animateColorAsState(
                targetValue = if (selected && surface.hasPanels) colors.accentDim else Color.Transparent,
                label = "a11yFilterBg",
            )
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(if (surface.hasPanels) dimens.controlRadius - 4.dp else 0.dp))
                    .background(color = background)
                    .dshClickable(scaleOnPress = false) { onFilterChange(item) }
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = item.label + " " + (counts[item] ?: 0),
                        style = type.rowTitle,
                        color = if (selected) colors.ink else colors.inkFaint,
                        maxLines = 1,
                    )
                    if (!surface.hasPanels) {
                        Spacer(Modifier.height(7.dp))
                        Box(
                            Modifier
                                .width(if (selected) 26.dp else 0.dp)
                                .height(1.5.dp)
                                .background(color = if (selected) colors.accent else Color.Transparent),
                        )
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 列表行
// ---------------------------------------------------------------------------

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun A11yRow(
    item: A11yServiceItem,
    onToggle: (A11yServiceItem, Boolean) -> Unit,
    onOpenActions: (A11yServiceItem) -> Unit,
) {
    val colors = Dsh.colors
    val type = Dsh.type
    val surface = Dsh.surface
    val dimens = Dsh.dimens
    val motion = Dsh.motion
    val motionEnabled = rememberMotionEnabled()
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val haptics = LocalHapticFeedback.current

    val tint by animateColorAsState(
        targetValue = if (pressed) {
            if (surface.hasPanels) Color.White.copy(alpha = 0.045f) else Color.Black.copy(alpha = 0.035f)
        } else {
            Color.Transparent
        },
        animationSpec = MotionSpecs.color(motion, motionEnabled),
        label = "a11yRowTint",
    )

    Row(
        Modifier
            .fillMaxWidth()
            .background(color = tint)
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onToggle(item, !item.isEnabled)
                },
                onLongClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onOpenActions(item)
                },
            )
            .padding(
                horizontal = if (surface.hasPanels) 14.dp else 0.dp,
                vertical = 12.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (item.icon != null) {
            Image(
                bitmap = item.icon,
                contentDescription = item.appName,
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(dimens.iconRadius)),
            )
        } else {
            AppIconTile(label = item.appName, seed = item.seed, tileSize = 36.dp)
        }

        Spacer(Modifier.width(13.dp))

        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (item.isPinned) {
                    Box(
                        Modifier
                            .size(5.dp)
                            .clip(RoundedCornerShape(percent = 50))
                            .background(color = colors.accent),
                    )
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    text = item.appName,
                    style = type.rowTitle,
                    color = colors.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = item.serviceName,
                style = type.monoSmall,
                color = colors.inkFaint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (item.description.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = item.description,
                    style = type.caption,
                    color = colors.inkFaint.copy(alpha = 0.8f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (item.isProtected) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "防回收保护中",
                    style = type.monoSmall,
                    color = colors.accent,
                )
            }
        }

        Spacer(Modifier.width(12.dp))
        DshSwitch(checked = item.isEnabled, onCheckedChange = { onToggle(item, it) })
    }
}

// ---------------------------------------------------------------------------
// 加载态 / 空态
// ---------------------------------------------------------------------------

@Composable
private fun A11ySkeleton() {
    val colors = Dsh.colors
    val transition = rememberInfiniteTransition(label = "a11ySkeleton")
    val pulse by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.8f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1100),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "a11ySkeletonPulse",
    )

    Column(Modifier.fillMaxWidth().padding(14.dp)) {
        repeat(6) {
            Row(
                Modifier.fillMaxWidth().padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(Dsh.dimens.iconRadius))
                        .background(color = colors.inkFaint.copy(alpha = 0.16f * pulse)),
                )
                Spacer(Modifier.width(13.dp))
                Column(Modifier.weight(1f)) {
                    Box(
                        Modifier
                            .fillMaxWidth(0.44f)
                            .height(11.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(color = colors.inkFaint.copy(alpha = 0.18f * pulse)),
                    )
                    Spacer(Modifier.height(7.dp))
                    Box(
                        Modifier
                            .fillMaxWidth(0.66f)
                            .height(9.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(color = colors.inkFaint.copy(alpha = 0.11f * pulse)),
                    )
                }
            }
        }
    }
}

@Composable
private fun A11yEmpty(hasQuery: Boolean) {
    val colors = Dsh.colors
    val type = Dsh.type
    Column(
        Modifier.fillMaxSize().padding(horizontal = 28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        DshIcon(DshIconKind.SHIELD, iconSize = 22.dp, tint = colors.inkFaint)
        Spacer(Modifier.height(14.dp))
        Text(
            text = if (hasQuery) "没有匹配的服务" else "没有检测到无障碍服务",
            style = type.title,
            color = colors.inkMuted,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = if (hasQuery) "换个关键词，或切到「全部」再试" else "先在系统里安装并注册一个无障碍服务",
            style = type.caption,
            color = colors.inkFaint,
        )
    }
}

// ---------------------------------------------------------------------------
// 长按操作面板
// ---------------------------------------------------------------------------

@Composable
fun A11yActionSheet(
    item: A11yServiceItem,
    onDismiss: () -> Unit,
    onTogglePin: () -> Unit,
    onToggleProtect: () -> Unit,
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
                    .padding(vertical = 8.dp),
            ) {
                Row(
                    Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (item.icon != null) {
                        Image(
                            bitmap = item.icon,
                            contentDescription = item.appName,
                            modifier = Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(dimens.iconRadius)),
                        )
                    } else {
                        AppIconTile(label = item.appName, seed = item.seed, tileSize = 36.dp)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = item.appName,
                            style = type.title,
                            color = colors.ink,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = item.serviceName,
                            style = type.monoSmall,
                            color = colors.inkFaint,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                DshDivider()
                A11ySheetAction(
                    title = if (item.isPinned) "取消置顶" else "置顶到列表最前",
                    desc = "置顶后排在列表首位",
                    onClick = onTogglePin,
                )
                A11ySheetAction(
                    title = if (item.isProtected) "关闭防回收保护" else "开启防回收保护",
                    desc = "被系统关掉后自动恢复",
                    onClick = onToggleProtect,
                )
            }
        }
    }
}

@Composable
private fun A11ySheetAction(
    title: String,
    desc: String,
    onClick: () -> Unit,
) {
    val colors = Dsh.colors
    val type = Dsh.type

    Row(
        Modifier
            .fillMaxWidth()
            .dshClickable(scaleOnPress = false, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(text = title, style = type.rowTitle, color = colors.ink)
            Spacer(Modifier.height(4.dp))
            Text(text = desc, style = type.caption, color = colors.inkFaint)
        }
        DshIcon(DshIconKind.CHEVRON, iconSize = 12.dp, tint = colors.inkFaint)
    }
}
