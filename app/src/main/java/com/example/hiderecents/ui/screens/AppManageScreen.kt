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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
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
import com.example.hiderecents.ui.components.AppIconTile
import com.example.hiderecents.ui.components.DshDivider
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

/** 应用管理列表项。icon 为真实应用图标；拿不到时退化成配色方块。 */
data class ManagedApp(
    val packageName: String,
    val appName: String,
    val icon: ImageBitmap?,
    val isHidden: Boolean,
    val isSystem: Boolean,
    val isPinned: Boolean,
    val seed: Int,
)

/** 筛选档位：全部 / 用户应用 / 已隐藏 */
enum class AppFilter(val label: String) {
    ALL("全部"),
    USER("用户应用"),
    HIDDEN("已隐藏"),
}

/** 应用详情弹层数据 */
data class AppDetailInfo(
    val appName: String,
    val packageName: String,
    val icon: ImageBitmap?,
    val versionName: String,
    val versionCode: Long,
    val targetSdk: Int,
    val minSdk: Int,
    val permissions: List<String>,
    val seed: Int,
)

/**
 * 应用管理页。
 *
 * 功能与原 AppManageActivity 一致：搜索（名称 + 包名）、系统应用过滤、隐藏/恢复、
 * 长按操作（置顶 / 详情 / 提取 APK / 卸载）、隐藏计数、全部恢复。
 */
@Composable
fun AppManageScreen(
    apps: List<ManagedApp>,
    totalCount: Int,
    hiddenCount: Int,
    query: String,
    onQueryChange: (String) -> Unit,
    filter: AppFilter,
    onFilterChange: (AppFilter) -> Unit,
    loading: Boolean,
    onBack: () -> Unit,
    onToggleHide: (ManagedApp, Boolean) -> Unit,
    onOpenActions: (ManagedApp) -> Unit,
    onRestoreAll: () -> Unit,
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

            // ---------------- 顶栏 ----------------
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
                    Text("应用管理", style = type.h2, color = colors.ink)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = totalCount.toString() + " 个应用 · " + hiddenCount + " 个已隐藏",
                        style = type.label,
                        color = colors.inkFaint,
                    )
                }
            }

            Spacer(Modifier.height(dimens.gapM))

            // ---------------- 搜索 ----------------
            SearchField(query = query, onQueryChange = onQueryChange)

            Spacer(Modifier.height(12.dp))

            // ---------------- 筛选 ----------------
            FilterBar(
                filter = filter,
                onFilterChange = onFilterChange,
                counts = mapOf(
                    AppFilter.ALL to totalCount,
                    AppFilter.USER to apps.count { !it.isSystem },
                    AppFilter.HIDDEN to hiddenCount,
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
                    loading -> SkeletonList()
                    apps.isEmpty() -> EmptyState(hasQuery = query.isNotEmpty())
                    else -> LazyColumn(Modifier.fillMaxSize()) {
                        itemsIndexed(apps, key = { _, app -> app.packageName }) { index, app ->
                            if (index > 0) {
                                DshDivider(
                                    Modifier.padding(
                                        horizontal = if (surface.hasPanels) 14.dp else 0.dp,
                                    ),
                                )
                            }
                            if (index < 12) {
                                StaggeredEnter(index = index) {
                                    AppRow(
                                        app = app,
                                        onToggleHide = onToggleHide,
                                        onOpenActions = onOpenActions,
                                    )
                                }
                            } else {
                                AppRow(
                                    app = app,
                                    onToggleHide = onToggleHide,
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
                    text = hiddenCount.toString() + " 个应用已隐藏",
                    style = type.label,
                    color = colors.inkFaint,
                )
                Spacer(Modifier.weight(1f))
                if (hiddenCount > 0) {
                    DshGhostButton(text = "全部恢复", onClick = onRestoreAll)
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 搜索框
// ---------------------------------------------------------------------------

@Composable
private fun SearchField(query: String, onQueryChange: (String) -> Unit) {
    val colors = Dsh.colors
    val type = Dsh.type
    val surface = Dsh.surface
    val dimens = Dsh.dimens
    val shape = RoundedCornerShape(dimens.controlRadius)

    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .then(
                    if (surface.hasPanels) {
                        Modifier
                            .clip(shape)
                            .background(color = colors.panelTop.copy(alpha = 0.55f))
                            .border(1.dp, colors.hairline, shape)
                    } else {
                        Modifier
                    },
                )
                .height(44.dp)
                .padding(horizontal = if (surface.hasPanels) 14.dp else 0.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DshIcon(DshIconKind.SEARCH, iconSize = 15.dp, tint = colors.inkFaint)
            Spacer(Modifier.width(10.dp))
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = type.rowTitle.copy(color = colors.ink),
                cursorBrush = SolidColor(colors.accent),
                modifier = Modifier.weight(1f),
                decorationBox = { innerTextField ->
                    Box {
                        if (query.isEmpty()) {
                            Text(
                                text = "搜索应用或包名",
                                style = type.rowTitle,
                                color = colors.inkFaint,
                            )
                        }
                        innerTextField()
                    }
                },
            )
            if (query.isNotEmpty()) {
                Spacer(Modifier.width(8.dp))
                Box(
                    Modifier
                        .size(20.dp)
                        .clip(RoundedCornerShape(percent = 50))
                        .dshClickable(scaleOnPress = false) { onQueryChange("") },
                    contentAlignment = Alignment.Center,
                ) {
                    Text("×", style = type.caption, color = colors.inkMuted)
                }
            }
        }
        if (!surface.hasPanels) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(color = colors.hairline),
            )
        }
    }
}

// ---------------------------------------------------------------------------
// 筛选分段器
// ---------------------------------------------------------------------------

@Composable
private fun FilterBar(
    filter: AppFilter,
    onFilterChange: (AppFilter) -> Unit,
    counts: Map<AppFilter, Int>,
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
                        .background(color = colors.panelTop.copy(alpha = 0.5f))
                        .border(1.dp, colors.hairline, RoundedCornerShape(dimens.controlRadius))
                } else {
                    Modifier
                },
            )
            .padding(if (surface.hasPanels) 4.dp else 0.dp),
        horizontalArrangement = Arrangement.spacedBy(if (surface.hasPanels) 4.dp else 18.dp),
    ) {
        AppFilter.values().forEach { item ->
            val selected = item == filter
            val background by animateColorAsState(
                targetValue = if (selected && surface.hasPanels) colors.accentDim else Color.Transparent,
                label = "appFilterBg",
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
private fun AppRow(
    app: ManagedApp,
    onToggleHide: (ManagedApp, Boolean) -> Unit,
    onOpenActions: (ManagedApp) -> Unit,
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
        label = "appRowTint",
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
                    onToggleHide(app, !app.isHidden)
                },
                onLongClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onOpenActions(app)
                },
            )
            .padding(
                horizontal = if (surface.hasPanels) 14.dp else 0.dp,
                vertical = 11.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (app.icon != null) {
            Image(
                bitmap = app.icon,
                contentDescription = app.appName,
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(dimens.iconRadius)),
            )
        } else {
            AppIconTile(label = app.appName, seed = app.seed, tileSize = 36.dp)
        }

        Spacer(Modifier.width(13.dp))

        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (app.isPinned) {
                    Box(
                        Modifier
                            .size(5.dp)
                            .clip(RoundedCornerShape(percent = 50))
                            .background(color = colors.accent),
                    )
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    text = app.appName,
                    style = type.rowTitle,
                    color = colors.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = app.packageName,
                style = type.monoSmall,
                color = colors.inkFaint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Spacer(Modifier.width(12.dp))
        DshSwitch(checked = app.isHidden, onCheckedChange = { onToggleHide(app, it) })
    }
}

// ---------------------------------------------------------------------------
// 加载态 / 空态
// ---------------------------------------------------------------------------

@Composable
private fun SkeletonList() {
    val colors = Dsh.colors
    val transition = rememberInfiniteTransition(label = "appSkeleton")
    val pulse by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.8f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1100),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "appSkeletonPulse",
    )

    Column(Modifier.fillMaxWidth().padding(14.dp)) {
        repeat(7) {
            Row(
                Modifier.fillMaxWidth().padding(vertical = 11.dp),
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
                            .fillMaxWidth(0.42f)
                            .height(11.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(color = colors.inkFaint.copy(alpha = 0.18f * pulse)),
                    )
                    Spacer(Modifier.height(7.dp))
                    Box(
                        Modifier
                            .fillMaxWidth(0.62f)
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
private fun EmptyState(hasQuery: Boolean) {
    val colors = Dsh.colors
    val type = Dsh.type
    Column(
        Modifier.fillMaxSize().padding(horizontal = 28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        DshIcon(DshIconKind.SEARCH, iconSize = 22.dp, tint = colors.inkFaint)
        Spacer(Modifier.height(14.dp))
        Text(
            text = if (hasQuery) "没有匹配的应用" else "还没有加载到应用",
            style = type.title,
            color = colors.inkMuted,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = if (hasQuery) "换个关键词，或切到「全部」再试" else "确认 Shizuku 已连接后重新进入本页",
            style = type.caption,
            color = colors.inkFaint,
        )
    }
}

// ---------------------------------------------------------------------------
// 长按操作面板
// ---------------------------------------------------------------------------

@Composable
fun AppActionSheet(
    app: ManagedApp,
    onDismiss: () -> Unit,
    onTogglePin: () -> Unit,
    onShowDetail: () -> Unit,
    onExtractApk: () -> Unit,
    onUninstall: () -> Unit,
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
                    if (app.icon != null) {
                        Image(
                            bitmap = app.icon,
                            contentDescription = app.appName,
                            modifier = Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(dimens.iconRadius)),
                        )
                    } else {
                        AppIconTile(label = app.appName, seed = app.seed, tileSize = 36.dp)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = app.appName,
                            style = type.title,
                            color = colors.ink,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = app.packageName,
                            style = type.monoSmall,
                            color = colors.inkFaint,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                DshDivider()
                SheetAction(
                    title = if (app.isPinned) "取消置顶" else "置顶到首页",
                    desc = "置顶后排在列表最前",
                    onClick = onTogglePin,
                )
                SheetAction(
                    title = "应用详情",
                    desc = "版本、SDK、权限清单",
                    onClick = onShowDetail,
                )
                SheetAction(
                    title = "提取 APK",
                    desc = "保存到系统下载目录",
                    onClick = onExtractApk,
                )
                SheetAction(
                    title = "卸载应用",
                    desc = "可选择保留或删除数据",
                    danger = true,
                    onClick = onUninstall,
                )
            }
        }
    }
}

@Composable
private fun SheetAction(
    title: String,
    desc: String,
    danger: Boolean = false,
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
            Text(
                text = title,
                style = type.rowTitle,
                color = if (danger) colors.accent else colors.ink,
            )
            Spacer(Modifier.height(4.dp))
            Text(text = desc, style = type.caption, color = colors.inkFaint)
        }
        DshIcon(DshIconKind.CHEVRON, iconSize = 12.dp, tint = colors.inkFaint)
    }
}

// ---------------------------------------------------------------------------
// 应用详情弹层
// ---------------------------------------------------------------------------

@Composable
fun AppDetailDialog(
    detail: AppDetailInfo,
    onDismiss: () -> Unit,
    onExtractApk: () -> Unit,
    onOpenSystemSettings: () -> Unit,
) {
    val colors = Dsh.colors
    val type = Dsh.type
    val dimens = Dsh.dimens
    val surface = Dsh.surface

    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 600.dp)
                .clip(RoundedCornerShape(dimens.panelRadius))
                .background(color = colors.canvas)
                .border(1.dp, colors.hairlineStrong, RoundedCornerShape(dimens.panelRadius))
                .padding(20.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (detail.icon != null) {
                    Image(
                        bitmap = detail.icon,
                        contentDescription = detail.appName,
                        modifier = Modifier
                            .size(52.dp)
                            .clip(RoundedCornerShape(dimens.tileRadius)),
                    )
                } else {
                    AppIconTile(
                        label = detail.appName,
                        seed = detail.seed,
                        tileSize = 52.dp,
                        corner = dimens.tileRadius,
                    )
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = detail.appName,
                        style = type.h2,
                        color = colors.ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(5.dp))
                    Text(
                        text = detail.packageName,
                        style = type.monoSmall,
                        color = colors.inkFaint,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Box(
                    Modifier
                        .size(28.dp)
                        .clip(RoundedCornerShape(percent = 50))
                        .dshClickable(scaleOnPress = false, onClick = onDismiss),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("×", style = type.rowTitle, color = colors.inkMuted)
                }
            }

            Spacer(Modifier.height(18.dp))
            DshDivider()
            Spacer(Modifier.height(14.dp))

            Column(
                Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
            ) {
                Row(Modifier.fillMaxWidth()) {
                    DetailCell("版本", detail.versionName, Modifier.weight(1f))
                    DetailCell("版本号", detail.versionCode.toString(), Modifier.weight(1f))
                }
                Spacer(Modifier.height(14.dp))
                Row(Modifier.fillMaxWidth()) {
                    DetailCell("targetSdk", detail.targetSdk.toString(), Modifier.weight(1f))
                    DetailCell("minSdk", detail.minSdk.toString(), Modifier.weight(1f))
                }

                Spacer(Modifier.height(18.dp))
                Text(
                    text = "申请权限 " + detail.permissions.size + " 项",
                    style = type.label,
                    color = colors.inkFaint,
                )
                Spacer(Modifier.height(10.dp))
                if (detail.permissions.isEmpty()) {
                    Text("未申请权限", style = type.caption, color = colors.inkFaint)
                } else {
                    detail.permissions.take(12).forEach { permission ->
                        Text(
                            text = permission,
                            style = type.monoSmall,
                            color = colors.inkMuted,
                            modifier = Modifier.padding(bottom = 6.dp),
                        )
                    }
                    val rest = detail.permissions.size - 12
                    if (rest > 0) {
                        Text(
                            text = "还有 " + rest + " 项",
                            style = type.monoSmall,
                            color = colors.inkFaint,
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            if (!surface.hasPanels) DshDivider()
            Spacer(Modifier.height(14.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(Modifier.weight(1f)) {
                    DshGhostButton(
                        text = "提取 APK",
                        onClick = onExtractApk,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Box(Modifier.weight(1f)) {
                    DshGhostButton(
                        text = "系统设置",
                        onClick = onOpenSystemSettings,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

@Composable
private fun DetailCell(label: String, value: String, modifier: Modifier = Modifier) {
    val colors = Dsh.colors
    val type = Dsh.type
    Column(modifier) {
        Text(label, style = type.label, color = colors.inkFaint)
        Spacer(Modifier.height(8.dp))
        Text(value, style = type.mono, color = colors.ink)
    }
}
