package com.example.hiderecents.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.hiderecents.ui.components.AppIconTile
import com.example.hiderecents.ui.components.DshDivider
import com.example.hiderecents.ui.components.DshIcon
import com.example.hiderecents.ui.components.DshIconKind
import com.example.hiderecents.ui.components.StaggeredEnter
import com.example.hiderecents.ui.components.dshCanvasBackground
import com.example.hiderecents.ui.components.dshClickable
import com.example.hiderecents.ui.theme.Dsh
import com.example.hiderecents.ui.theme.MotionSpecs
import com.example.hiderecents.ui.theme.rememberMotionEnabled

/** 队列中单个 APK 的安装状态 */
enum class ApkStatus(val label: String) {
    WAITING("等待中"),
    INSTALLING("正在安装"),
    INSTALLED("已安装"),
    FAILED("安装失败"),
}

/** 安装队列项（UI 模型） */
data class ApkQueueItem(
    val name: String,
    val packageName: String,
    val version: String,
    val sizeLabel: String,
    val icon: ImageBitmap?,
    val status: ApkStatus,
)

/**
 * APK 安装器页。
 *
 * 功能与原 ApkInstallerActivity 一致：多选 APK、解析元信息、单文件分流到详情页、
 * 批量串行安装、逐项状态与总体进度、三路安装策略在宿主侧实现。
 */
@Composable
fun ApkInstallerScreen(
    items: List<ApkQueueItem>,
    installing: Boolean,
    overallLabel: String,
    overallPercent: Int,
    overallInfo: String,
    shizukuReady: Boolean,
    onBack: () -> Unit,
    onSelectApk: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Dsh.colors
    val dimens = Dsh.dimens
    val type = Dsh.type
    val surface = Dsh.surface
    val motionEnabled = rememberMotionEnabled()

    val progressFraction by animateFloatAsState(
        targetValue = (overallPercent.coerceIn(0, 100)) / 100f,
        animationSpec = MotionSpecs.enter(Dsh.motion, motionEnabled),
        label = "apkOverallProgress",
    )

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
                    Text("APK 安装器", style = type.h2, color = colors.ink)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = if (shizukuReady) "本地文件 · 无需联网" else "Shizuku 未就绪，安装会失败",
                        style = type.label,
                        color = if (shizukuReady) colors.inkFaint else colors.accent,
                    )
                }
                SelectApkButton(onClick = onSelectApk)
            }

            Spacer(Modifier.height(dimens.gapM))

            // ---------------- 总体进度 ----------------
            if (installing || items.isNotEmpty()) {
                StaggeredEnter(index = 0) {
                    OverallBlock(
                        label = overallLabel,
                        percent = overallPercent,
                        info = overallInfo,
                        fraction = progressFraction,
                        installing = installing,
                    )
                }
                Spacer(Modifier.height(dimens.gapM))
            }

            // ---------------- 队列 / 空状态 ----------------
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
                    EmptyDropZone(onSelectApk = onSelectApk)
                } else {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                        items.forEachIndexed { index, item ->
                            if (index > 0) {
                                DshDivider(
                                    Modifier.padding(
                                        horizontal = if (surface.hasPanels) 14.dp else 0.dp,
                                    ),
                                )
                            }
                            if (index < 12) {
                                StaggeredEnter(index = index + 1) {
                                    QueueRow(item = item)
                                }
                            } else {
                                QueueRow(item = item)
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
                    text = if (items.isEmpty()) {
                        "安装前会校验包名与签名"
                    } else {
                        items.count { it.status == ApkStatus.INSTALLED }.toString() +
                            " / " + items.size + " 个已安装"
                    },
                    style = type.label,
                    color = colors.inkFaint,
                )
                Spacer(Modifier.weight(1f))
                if (!installing && items.any { it.status == ApkStatus.FAILED }) {
                    RetryButton(onClick = onRetry)
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 顶栏按钮
// ---------------------------------------------------------------------------

@Composable
private fun SelectApkButton(onClick: () -> Unit) {
    val colors = Dsh.colors
    val type = Dsh.type
    val surface = Dsh.surface
    val dimens = Dsh.dimens

    if (surface.hasPanels) {
        val shape = RoundedCornerShape(dimens.controlRadius)
        Box(
            Modifier
                .height(34.dp)
                .clip(shape)
                .background(color = colors.accent)
                .dshClickable(onClick = onClick)
                .padding(horizontal = 14.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text("选择 APK", style = type.caption, color = colors.accentInk)
        }
    } else {
        Column(
            Modifier.dshClickable(onClick = onClick).padding(horizontal = 6.dp, vertical = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("选择 APK", style = type.caption, color = colors.accent)
            Spacer(Modifier.height(3.dp))
            Box(Modifier.width(46.dp).height(1.5.dp).background(color = colors.accent))
        }
    }
}

@Composable
private fun RetryButton(onClick: () -> Unit) {
    val colors = Dsh.colors
    val type = Dsh.type
    val dimens = Dsh.dimens
    val shape = RoundedCornerShape(dimens.controlRadius)

    Box(
        Modifier
            .height(36.dp)
            .clip(shape)
            .border(1.dp, colors.hairlineStrong, shape)
            .dshClickable(onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text("重试失败项", style = type.caption, color = colors.inkMuted)
    }
}

// ---------------------------------------------------------------------------
// 总体进度块
// ---------------------------------------------------------------------------

@Composable
private fun OverallBlock(
    label: String,
    percent: Int,
    info: String,
    fraction: Float,
    installing: Boolean,
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
                        .padding(16.dp)
                } else {
                    Modifier.padding(vertical = 14.dp)
                },
            ),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = type.title, color = colors.ink, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(10.dp))
            Text(
                text = percent.toString() + "%",
                style = type.mono,
                color = if (installing) colors.accent else colors.inkMuted,
            )
        }
        Spacer(Modifier.height(12.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(3.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(color = colors.track),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(fraction.coerceIn(0f, 1f))
                    .fillMaxSize()
                    .clip(RoundedCornerShape(2.dp))
                    .background(color = colors.accent),
            )
        }
        Spacer(Modifier.height(10.dp))
        Text(
            text = info,
            style = type.caption,
            color = colors.inkFaint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// ---------------------------------------------------------------------------
// 空状态 / 拖入区
// ---------------------------------------------------------------------------

@Composable
private fun EmptyDropZone(onSelectApk: () -> Unit) {
    val colors = Dsh.colors
    val type = Dsh.type
    val dimens = Dsh.dimens

    Column(
        Modifier.fillMaxSize().padding(horizontal = 24.dp),
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
        Text("还没有选择 APK", style = type.title, color = colors.inkMuted)
        Spacer(Modifier.height(8.dp))
        Text(
            text = "支持一次选多个文件批量安装，单文件会直接进入详情页",
            style = type.caption,
            color = colors.inkFaint,
        )
        Spacer(Modifier.height(18.dp))
        Box(
            Modifier
                .height(38.dp)
                .clip(RoundedCornerShape(dimens.controlRadius))
                .border(1.dp, colors.accentBorder, RoundedCornerShape(dimens.controlRadius))
                .dshClickable(onClick = onSelectApk)
                .padding(horizontal = 20.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text("选择 APK", style = type.rowTitle, color = colors.accent)
        }
    }
}

// ---------------------------------------------------------------------------
// 队列行
// ---------------------------------------------------------------------------

@Composable
private fun QueueRow(item: ApkQueueItem) {
    val colors = Dsh.colors
    val type = Dsh.type
    val dimens = Dsh.dimens
    val surface = Dsh.surface
    val motionEnabled = rememberMotionEnabled()

    val statusColor by animateColorAsState(
        targetValue = when (item.status) {
            ApkStatus.WAITING -> colors.inkFaint
            ApkStatus.INSTALLING -> colors.accent
            ApkStatus.INSTALLED -> colors.accent
            ApkStatus.FAILED -> colors.accent
        },
        animationSpec = MotionSpecs.color(Dsh.motion, motionEnabled),
        label = "apkStatusColor",
    )

    Row(
        Modifier
            .fillMaxWidth()
            .padding(
                horizontal = if (surface.hasPanels) 14.dp else 0.dp,
                vertical = 12.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (item.icon != null) {
            Image(
                bitmap = item.icon,
                contentDescription = item.name,
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(dimens.iconRadius)),
            )
        } else {
            AppIconTile(label = item.name, seed = item.name.hashCode(), tileSize = 36.dp)
        }

        Spacer(Modifier.width(13.dp))

        Column(Modifier.weight(1f)) {
            Text(
                text = item.name,
                style = type.rowTitle,
                color = colors.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = buildString {
                    if (item.packageName.isNotEmpty()) append(item.packageName)
                    if (item.version.isNotEmpty()) {
                        if (item.packageName.isNotEmpty()) append(" · ")
                        append("v")
                        append(item.version)
                    }
                    if (isNotEmpty()) append(" · ")
                    append(item.sizeLabel)
                },
                style = type.monoSmall,
                color = colors.inkFaint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Spacer(Modifier.width(10.dp))
        Text(item.status.label, style = type.monoSmall, color = statusColor)
    }
}
