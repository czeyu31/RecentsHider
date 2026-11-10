package com.example.hiderecents.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.hiderecents.ui.components.AppIconTile
import com.example.hiderecents.ui.components.DshDivider
import com.example.hiderecents.ui.components.DshGhostButton
import com.example.hiderecents.ui.components.DshIcon
import com.example.hiderecents.ui.components.DshIconKind
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
                Spacer(Modifier.height(dimens.gapM))
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
                                    NotificationRow(item = item)
                                }
                            } else {
                                NotificationRow(item = item)
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
                    else -> colors.panelTop.copy(alpha = 0.5f)
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

@Composable
private fun NotificationRow(item: NotificationEntry) {
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
                        apps.forEach { app ->
                            val checked = app.packageName !in current
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .dshClickable(scaleOnPress = false) {
                                        current = if (checked) {
                                            current + app.packageName
                                        } else {
                                            current - app.packageName
                                        }
                                    }
                                    .padding(vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(
                                    Modifier
                                        .size(16.dp)
                                        .clip(RoundedCornerShape(3.dp))
                                        .background(
                                            color = if (checked) colors.accentDim else Color.Transparent,
                                        )
                                        .border(
                                            width = 1.dp,
                                            color = if (checked) colors.accent else colors.hairlineStrong,
                                            shape = RoundedCornerShape(3.dp),
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
                                    color = colors.ink,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
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
