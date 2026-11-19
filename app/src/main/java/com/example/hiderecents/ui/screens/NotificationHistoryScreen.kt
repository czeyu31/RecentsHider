package com.example.hiderecents.ui.screens

import android.content.ClipData
import android.util.Log
import android.content.ClipboardManager
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.hiderecents.ui.components.paperHeaderRule
import com.example.hiderecents.ui.components.AppIconTile
import com.example.hiderecents.ui.components.DshAnimatedDialog
import com.example.hiderecents.ui.components.DshDivider
import com.example.hiderecents.ui.components.dshFieldFill
import com.example.hiderecents.ui.components.DshGhostButton
import com.example.hiderecents.ui.components.DshIcon
import com.example.hiderecents.ui.components.DshIconKind
import com.example.hiderecents.ui.components.LongTextDialog
import com.example.hiderecents.ui.components.SectionHeader
import com.example.hiderecents.ui.components.StaggeredEnter
import com.example.hiderecents.ui.components.dshCanvasBackground
import com.example.hiderecents.ui.components.dshClickable
import com.example.hiderecents.ui.theme.Dsh

/** 一条历史通知。timeLabel 在宿主侧算好，避免 Composable 里读系统时间。 */
data class NotificationEntry(
    val packageName: String,
    val appName: String,
    val icon: ImageBitmap?,
    val title: String,
    val text: String,
    val time: Long,
    val timeLabel: String,
)

/** 顶部展示侧筛选用的应用条目，label 里带条数。 */
data class AppChip(val packageName: String, val label: String)

/**
 * 历史通知页。
 *
 * 功能与原 NotificationHistoryActivity 一致：权限检测与横幅、按应用筛选（展示侧）、
 * 记录侧排除筛选、一键清空、相对时间、空状态。
 */
@Composable
fun NotificationHistoryScreen(
    items: List<NotificationEntry>,
    totalCount: Int,
    chips: List<AppChip>,
    selectedPackage: String?,
    onSelectPackage: (String?) -> Unit,
    permissionGranted: Boolean,
    onRequestPermission: () -> Unit,
    onBack: () -> Unit,
    onOpenFilter: () -> Unit,
    onClearAll: () -> Unit,
    onDelete: (NotificationEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Dsh.colors
    val dimens = Dsh.dimens
    val type = Dsh.type
    val surface = Dsh.surface
    var detail by remember { mutableStateOf<NotificationEntry?>(null) }
    val context = LocalContext.current

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
                    Text("历史通知", style = type.h2, color = colors.ink)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = totalCount.toString() + " 条记录 · 本机保存",
                        style = type.label,
                        color = colors.inkFaint,
                    )
                }
                Box(
                    Modifier
                        .clip(RoundedCornerShape(dimens.iconRadius))
                        .dshClickable(onClick = onOpenFilter)
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("筛选", style = type.caption, color = colors.inkMuted)
                }
                Spacer(Modifier.width(4.dp))
                Box(
                    Modifier
                        .clip(RoundedCornerShape(dimens.iconRadius))
                        .dshClickable(onClick = onClearAll)
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("清空", style = type.caption, color = colors.inkMuted)
                }
            }

            Spacer(Modifier.height(dimens.gapS))

            // ---------------- 权限横幅 ----------------
            if (!permissionGranted) {
                StaggeredEnter(index = 0) {
                    PermissionBanner(onClick = onRequestPermission)
                }
                Spacer(Modifier.height(if (Dsh.surface.hasPanels) dimens.gapM else 22.dp))
            }

            // ---------------- 应用 chips ----------------
            if (chips.isNotEmpty()) {
                StaggeredEnter(index = 1) {
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        ChipItem(
                            label = "全部 " + totalCount,
                            selected = selectedPackage == null,
                            onClick = { onSelectPackage(null) },
                        )
                        chips.forEach { chip ->
                            ChipItem(
                                label = chip.label,
                                selected = selectedPackage == chip.packageName,
                                onClick = { onSelectPackage(chip.packageName) },
                            )
                        }
                    }
                }
                Spacer(Modifier.height(dimens.gapM))
            }

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
                if (items.isEmpty()) {
                    NotificationEmpty(hasFilter = selectedPackage != null)
                } else {
                    LazyColumn(Modifier.fillMaxSize()) {
                        itemsIndexed(
                            items = items,
                            key = { _, item -> item.packageName + "_" + item.time + "_" + item.title },
                        ) { index, item ->
                            if (index > 0) {
                                DshDivider(
                                    Modifier.padding(
                                        horizontal = if (surface.hasPanels) 14.dp else 0.dp,
                                    ),
                                )
                            }
                            if (index < 12) {
                                StaggeredEnter(index = index) {
                                    SwipeNotificationRow(
                                        item = item,
                                        onOpenDetail = { detail = item },
                                        onDelete = { onDelete(item) },
                                    )
                                }
                            } else {
                                SwipeNotificationRow(
                                    item = item,
                                    onOpenDetail = { detail = item },
                                    onDelete = { onDelete(item) },
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
                    text = if (permissionGranted) "通知访问已授权" else "未授权，记录不会更新",
                    style = type.label,
                    color = if (permissionGranted) colors.inkFaint else colors.accent,
                )
                Spacer(Modifier.weight(1f))
                Text("共 " + totalCount + " 条", style = type.label, color = colors.inkFaint)
            }
        }

        val shownDetail = detail
        if (shownDetail != null) {
            DshAnimatedDialog(onDismissRequest = { detail = null }) { close ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .dshClickable(scaleOnPress = false, haptic = false) { close() },
                    )
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp)
                            .clip(RoundedCornerShape(dimens.panelRadius))
                            .background(colors.canvas)
                            .border(1.dp, colors.hairlineStrong, RoundedCornerShape(dimens.panelRadius))
                            .padding(16.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val icon = shownDetail.icon
                            if (icon != null) {
                                Image(
                                    bitmap = icon,
                                    contentDescription = shownDetail.appName,
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(RoundedCornerShape(dimens.iconRadius)),
                                )
                            } else {
                                AppIconTile(
                                    label = shownDetail.appName,
                                    seed = shownDetail.packageName.hashCode(),
                                    tileSize = 36.dp,
                                )
                            }
                            Spacer(Modifier.width(11.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = shownDetail.appName,
                                    style = type.rowTitle,
                                    color = colors.ink,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Spacer(Modifier.height(3.dp))
                                Text(
                                    text = shownDetail.timeLabel,
                                    style = type.label,
                                    color = colors.inkFaint,
                                )
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        DshDivider()
                        Spacer(Modifier.height(12.dp))
                        if (shownDetail.title.isNotEmpty()) {
                            Text(shownDetail.title, style = type.title, color = colors.ink)
                            Spacer(Modifier.height(8.dp))
                        }
                        Column(
                            Modifier
                                .heightIn(max = 340.dp)
                                .verticalScroll(rememberScrollState()),
                        ) {
                            Text(
                                text = shownDetail.text.ifEmpty { "（这条通知没有正文）" },
                                style = type.caption,
                                color = colors.inkMuted,
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                        DshDivider()
                        Spacer(Modifier.height(10.dp))
                        Row(Modifier.fillMaxWidth()) {
                            Box(Modifier.weight(1f)) {
                                DshGhostButton(
                                    text = "复制",
                                    onClick = {
                                        val clip = if (shownDetail.title.isNotEmpty() && shownDetail.text.isNotEmpty()) {
                                            shownDetail.title + "\n" + shownDetail.text
                                        } else {
                                            shownDetail.title.ifEmpty { shownDetail.text }
                                        }
                                        context.getSystemService(ClipboardManager::class.java)
                                            ?.setPrimaryClip(ClipData.newPlainText("notification", clip))
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                            Spacer(Modifier.width(10.dp))
                            Box(Modifier.weight(1f)) {
                                DshGhostButton(
                                    text = "关闭",
                                    onClick = { close() },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 权限横幅
// ---------------------------------------------------------------------------

@Composable
private fun PermissionBanner(onClick: () -> Unit) {
    val colors = Dsh.colors
    val type = Dsh.type
    val dimens = Dsh.dimens
    val surface = Dsh.surface
    val shape = RoundedCornerShape(dimens.controlRadius)

    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(color = colors.accentDim)
            .border(1.dp, colors.accentBorder, shape)
            .dshClickable(scaleOnPress = false, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DshIcon(DshIconKind.BELL, iconSize = 15.dp, tint = colors.accent)
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f)) {
            Text("需要通知访问权限", style = type.rowTitle, color = colors.ink)
            Spacer(Modifier.height(4.dp))
            Text(
                text = if (surface.hasPanels) "点此前往系统设置开启监听" else "点此前往系统设置开启监听",
                style = type.caption,
                color = colors.inkFaint,
            )
        }
        DshIcon(DshIconKind.CHEVRON, iconSize = 12.dp, tint = colors.accent)
    }
}

// ---------------------------------------------------------------------------
// chips
// ---------------------------------------------------------------------------

@Composable
private fun ChipItem(label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = Dsh.colors
    val type = Dsh.type
    val surface = Dsh.surface
    val dimens = Dsh.dimens
    val shape = RoundedCornerShape(dimens.controlRadius)

    Box(
        Modifier
            .clip(shape)
            .background(
                color = when {
                    selected && surface.hasPanels -> colors.accentDim
                    selected -> Color.Transparent
                    else -> dshFieldFill(0.5f)
                },
            )
            .border(
                width = 1.dp,
                color = if (selected) colors.accentBorder else colors.hairline,
                shape = shape,
            )
            .dshClickable(scaleOnPress = false, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(
            text = label,
            style = type.monoSmall,
            color = if (selected) colors.accent else colors.inkMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// ---------------------------------------------------------------------------
// 列表行
// ---------------------------------------------------------------------------

/** 左滑露出两项动作：看完整内容 / 删除。不复用长按，也不再使用彩色菜单。 */
@Composable
private fun SwipeNotificationRow(
    item: NotificationEntry,
    onOpenDetail: () -> Unit,
    onDelete: () -> Unit,
) {
    val colors = Dsh.colors
    val type = Dsh.type
    val density = LocalDensity.current
    val menuWidth = 132.dp
    val menuPx = with(density) { menuWidth.toPx() }
    val offsetX = remember { Animatable(0f) }
    var dragStart by remember { mutableStateOf(0f) }
    val scope = rememberCoroutineScope()
    val dragState = rememberDraggableState { delta ->
        scope.launch { offsetX.snapTo((offsetX.value + delta).coerceIn(-menuPx, 0f)) }
    }
    // 打开带一点回弹，收回慢一些，避免"啪"地弹回去
    val openSpec = spring<Float>(dampingRatio = 0.72f, stiffness = Spring.StiffnessMediumLow)
    val closeSpec = tween<Float>(durationMillis = 420, easing = FastOutSlowInEasing)
    val progress = (-offsetX.value / menuPx).coerceIn(0f, 1f)

    Box(Modifier.fillMaxWidth().clipToBounds()) {
        // 只在拖动时渲染：内容左移后就地露出，不需要给整行铺背景遮罩（那会造成色差）
        if (progress > 0f) {
            Row(
                Modifier
                    .align(Alignment.CenterEnd)
                    .width(menuWidth)
                    .heightIn(min = 72.dp)
                    .graphicsLayer { alpha = progress },
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
Box(
                    Modifier
                        .width(1.dp)
                        .height(18.dp)
                        .background(colors.hairlineStrong),
                )
                Text(
                    text = "详情",
                    style = type.caption,
                    color = colors.inkMuted,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .weight(1f)
                        .dshClickable(scaleOnPress = false) {
                            scope.launch { offsetX.animateTo(0f, closeSpec) }
                            onOpenDetail()
                        }
                        .padding(vertical = 12.dp),
                )
                Box(
                    Modifier
                        .width(1.dp)
                        .height(18.dp)
                        .background(colors.hairlineStrong),
                )
                Text(
                    text = "删除",
                    style = type.caption,
                    color = colors.inkMuted,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .weight(1f)
                        .dshClickable(scaleOnPress = false) {
                            scope.launch { offsetX.animateTo(0f, closeSpec) }
                            onDelete()
                        }
                        .padding(vertical = 12.dp),
                )
            }
        }

        Box(
            Modifier
                .offset { IntOffset(offsetX.value.roundToInt(), 0) }
                .fillMaxWidth()
                .draggable(
                    state = dragState,
                    orientation = Orientation.Horizontal,
onDragStarted = { dragStart = offsetX.value },
                    onDragStopped = {
                        // 按拖动方向判定：打开态往右滑一点就收，关闭态往左滑一点就开
                        val wasOpen = dragStart <= -menuPx * 0.5f
                        val open = if (wasOpen) {
                            offsetX.value < -menuPx * 0.82f
                        } else {
                            offsetX.value < -menuPx * 0.18f
                        }
                        if (open) {
                            offsetX.animateTo(-menuPx, openSpec)
                        } else {
                            offsetX.animateTo(0f, closeSpec)
                        }
                    },
                ),
        ) {
            NotificationRowContent(item)
        }
    }
}

@Composable
private fun NotificationRowContent(item: NotificationEntry) {
    val colors = Dsh.colors
    val type = Dsh.type
    val dimens = Dsh.dimens
    val surface = Dsh.surface

    Row(
        Modifier
            .fillMaxWidth()
            .padding(
                horizontal = if (surface.hasPanels) 14.dp else 0.dp,
                vertical = 12.dp,
            ),
        verticalAlignment = Alignment.Top,
    ) {
        if (item.icon != null) {
            Image(
                bitmap = item.icon,
                contentDescription = item.appName,
                modifier = Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(dimens.iconRadius)),
            )
        } else {
            AppIconTile(label = item.appName, seed = item.packageName.hashCode(), tileSize = 34.dp)
        }

        Spacer(Modifier.width(12.dp))

        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = item.appName,
                    style = type.monoSmall,
                    color = colors.inkFaint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.weight(1f))
                Text(item.timeLabel, style = type.monoSmall, color = colors.inkFaint)
            }
            Spacer(Modifier.height(5.dp))
            if (item.title.isNotEmpty()) {
                Text(
                    text = item.title,
                    style = type.rowTitle,
                    color = colors.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
            }
            if (item.text.isNotEmpty()) {
                Text(
                    text = item.text,
                    style = type.caption,
                    color = colors.inkMuted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 空状态
// ---------------------------------------------------------------------------

@Composable
private fun NotificationEmpty(hasFilter: Boolean) {
    val colors = Dsh.colors
    val type = Dsh.type

    Column(
        Modifier.fillMaxSize().padding(horizontal = 28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        DshIcon(DshIconKind.BELL, iconSize = 22.dp, tint = colors.inkFaint)
        Spacer(Modifier.height(14.dp))
        Text(
            text = if (hasFilter) "这个应用还没有记录" else "还没有历史通知",
            style = type.title,
            color = colors.inkMuted,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = if (hasFilter) "点上面的「全部」看其他应用" else "授权通知访问后，新通知会在这里按时间排列",
            style = type.caption,
            color = colors.inkFaint,
        )
    }
}

// ---------------------------------------------------------------------------
// 记录侧排除筛选
// ---------------------------------------------------------------------------

@Composable
fun NotificationFilterSheet(
    apps: List<AppChip>,
    excluded: Set<String>,
    onDismiss: () -> Unit,
    onConfirm: (Set<String>) -> Unit,
) {
    val colors = Dsh.colors
    val type = Dsh.type
    val dimens = Dsh.dimens
    var current by remember { mutableStateOf(excluded) }

    // 分组在浮层打开这一刻定格：之后勾选变化只改行自身的勾选框，不跨栏移动。
    // 每次重新打开浮层，remember 随新的 excluded 重新求值，才按最新状态重新分组。
    val checkedApps = remember(excluded, apps) { apps.filter { it.packageName !in excluded } }
    val uncheckedApps = remember(excluded, apps) { apps.filter { it.packageName in excluded } }

    DshAnimatedDialog(onDismissRequest = onDismiss) { close ->
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            Box(
                Modifier
                    .fillMaxSize()
                    .dshClickable(scaleOnPress = false, haptic = false) { close() },
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
                Text("通知记录筛选", style = type.h2, color = colors.ink)
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "取消勾选的应用，之后的新通知不会再被记录",
                    style = type.caption,
                    color = colors.inkFaint,
                )
                Spacer(Modifier.height(14.dp))

                if (apps.isEmpty()) {
                    Text("还没有任何应用的记录", style = type.caption, color = colors.inkFaint)
                } else {
                    Column(
                        Modifier
                            .heightIn(max = 300.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        if (checkedApps.isNotEmpty()) {
                            SectionHeader(title = "已勾选 · " + checkedApps.size)
                            Spacer(Modifier.height(4.dp))
                            checkedApps.forEach { app ->
                                FilterAppRow(
                                    app = app,
                                    checked = app.packageName !in current,
                                    onToggle = {
                                        current = if (app.packageName in current) {
                                            current - app.packageName
                                        } else {
                                            current + app.packageName
                                        }
                                    },
                                )
                            }
                        }

                        if (checkedApps.isNotEmpty() && uncheckedApps.isNotEmpty()) {
                            Spacer(Modifier.height(10.dp))
                            DshDivider()
                            Spacer(Modifier.height(10.dp))
                        }

                        if (uncheckedApps.isNotEmpty()) {
                            SectionHeader(title = "未勾选 · " + uncheckedApps.size)
                            Spacer(Modifier.height(4.dp))
                            uncheckedApps.forEach { app ->
                                FilterAppRow(
                                    app = app,
                                    checked = app.packageName !in current,
                                    onToggle = {
                                        current = if (app.packageName in current) {
                                            current - app.packageName
                                        } else {
                                            current + app.packageName
                                        }
                                    },
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))
                DshDivider()
                Spacer(Modifier.height(14.dp))
                Row(Modifier.fillMaxWidth()) {
                    Box(Modifier.weight(1f)) {
                        DshGhostButton(
                            text = "全部接收",
                            onClick = { current = emptySet() },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Box(Modifier.weight(1f)) {
                        DshGhostButton(
                            text = "确定",
                            onClick = { onConfirm(current) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }
}

/** 筛选浮层的一行：勾选框样式沿用 16dp 方块 + accent 描边 + 「·」。 */
@Composable
private fun FilterAppRow(app: AppChip, checked: Boolean, onToggle: () -> Unit) {
    val colors = Dsh.colors
    val type = Dsh.type
    val shape = RoundedCornerShape(3.dp)

    Row(
        Modifier
            .fillMaxWidth()
            .dshClickable(scaleOnPress = false, onClick = onToggle)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(16.dp)
                .clip(shape)
                .background(color = if (checked) colors.accentDim else Color.Transparent)
                .border(
                    width = 1.dp,
                    color = if (checked) colors.accent else colors.hairlineStrong,
                    shape = shape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (checked) {
                Text("·", style = type.caption, color = colors.accent)
            }
        }
        Spacer(Modifier.width(12.dp))
        Text(
            text = app.label,
            style = type.rowTitle,
            color = if (checked) colors.ink else colors.inkMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
