package com.example.hiderecents.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.hiderecents.ui.components.AppIconTile
import com.example.hiderecents.ui.components.DshDivider
import com.example.hiderecents.ui.components.DshIcon
import com.example.hiderecents.ui.components.DshIconKind
import com.example.hiderecents.ui.components.DshPrimaryButton
import com.example.hiderecents.ui.components.StaggeredEnter
import com.example.hiderecents.ui.components.dshCanvasBackground
import com.example.hiderecents.ui.components.dshClickable
import com.example.hiderecents.ui.theme.Dsh
import com.example.hiderecents.ui.theme.MotionSpecs
import com.example.hiderecents.ui.theme.rememberMotionEnabled

/** APK 详情数据（宿主侧解析后喂进来） */
data class ApkDetailInfo(
    val appName: String,
    val packageName: String,
    val version: String,
    val fileSize: String,
    val minSdk: String,
    val targetSdk: String,
    val permissionCount: Int,
    val icon: ImageBitmap?,
    val seed: Int,
)

/** 安装阶段：不使用假百分比，用真实阶段推进 */
enum class InstallStage(val label: String) {
    IDLE("待安装"),
    PREPARING("准备文件"),
    COPYING("复制到可读目录"),
    INSTALLING("正在安装"),
    SUCCESS("安装成功"),
    FAILED("安装失败"),
}

/**
 * APK 详情页。
 *
 * 功能与原 ApkDetailActivity 一致：图标 / 名称 / 包名、版本与 SDK 信息、文件大小、
 * 权限数、安装阶段状态、主操作按钮（安装 / 安装中 / 重试 / 打开）。
 */
@Composable
fun ApkDetailScreen(
    info: ApkDetailInfo,
    stage: InstallStage,
    errorMessage: String,
    installed: Boolean,
    onBack: () -> Unit,
    onInstall: () -> Unit,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Dsh.colors
    val dimens = Dsh.dimens
    val type = Dsh.type
    val surface = Dsh.surface
    val motionEnabled = rememberMotionEnabled()

    val busy = stage == InstallStage.PREPARING ||
        stage == InstallStage.COPYING ||
        stage == InstallStage.INSTALLING

    val stageColor by animateColorAsState(
        targetValue = when (stage) {
            InstallStage.SUCCESS -> colors.accent
            InstallStage.FAILED -> colors.accent
            InstallStage.IDLE -> colors.inkFaint
            else -> colors.accent
        },
        animationSpec = MotionSpecs.color(Dsh.motion, motionEnabled),
        label = "apkDetailStageColor",
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
                    Text("APK 详情", style = type.h2, color = colors.ink)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "安装前校验包名与签名",
                        style = type.label,
                        color = colors.inkFaint,
                    )
                }
                Text(stage.label, style = type.monoSmall, color = stageColor)
            }

            Spacer(Modifier.height(dimens.gapM))

            Column(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
            ) {
                // ---------------- 头部 ----------------
                StaggeredEnter(index = 0) {
                    HeaderBlock(info = info)
                }

                Spacer(Modifier.height(dimens.gapM))

                // ---------------- 信息块 ----------------
                StaggeredEnter(index = 1) {
                    InfoBlock(info = info)
                }

                // ---------------- 安装状态 ----------------
                if (busy) {
                    Spacer(Modifier.height(dimens.gapM))
                    StaggeredEnter(index = 2) {
                        ProgressBlock(stage = stage)
                    }
                }

                if (stage == InstallStage.FAILED && errorMessage.isNotEmpty()) {
                    Spacer(Modifier.height(dimens.gapM))
                    ErrorBlock(message = errorMessage)
                }

                if (!surface.hasPanels) {
                    Spacer(Modifier.height(dimens.gapM))
                    DshDivider(strong = true)
                }

                Spacer(Modifier.height(24.dp))
            }

            // ---------------- 主操作 ----------------
            Column(Modifier.padding(bottom = 26.dp)) {
                val label = when {
                    installed -> "打开应用"
                    stage == InstallStage.FAILED -> "重试安装"
                    busy -> "安装中…"
                    else -> "安装"
                }
                if (busy) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(46.dp)
                            .clip(RoundedCornerShape(dimens.controlRadius))
                            .background(color = colors.inkFaint.copy(alpha = 0.16f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(label, style = type.rowTitle, color = colors.inkFaint)
                    }
                } else {
                    DshPrimaryButton(
                        text = label,
                        onClick = { if (installed) onOpen() else onInstall() },
                        modifier = Modifier.fillMaxWidth().height(46.dp),
                    )
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    text = if (installed) {
                        "安装已完成，可直接拉起应用"
                    } else {
                        "通过 Shizuku 以 shell 身份安装，无需点击系统安装器"
                    },
                    style = type.caption,
                    color = colors.inkFaint,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 头部
// ---------------------------------------------------------------------------

@Composable
private fun HeaderBlock(info: ApkDetailInfo) {
    val colors = Dsh.colors
    val type = Dsh.type
    val dimens = Dsh.dimens

    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (info.icon != null) {
            Image(
                bitmap = info.icon,
                contentDescription = info.appName,
                modifier = Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(dimens.tileRadius)),
            )
        } else {
            AppIconTile(
                label = info.appName,
                seed = info.seed,
                tileSize = 64.dp,
                corner = dimens.tileRadius,
            )
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = info.appName,
                style = type.h1,
                color = colors.ink,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(7.dp))
            Text(
                text = info.packageName.ifEmpty { "未知包名" },
                style = type.monoSmall,
                color = colors.inkFaint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// 信息块
// ---------------------------------------------------------------------------

@Composable
private fun InfoBlock(info: ApkDetailInfo) {
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
                            brush = Brush.verticalGradient(
                                listOf(colors.panelTop, colors.panelBottom),
                            ),
                        )
                        .border(1.dp, colors.hairline, shape)
                        .padding(16.dp)
                } else {
                    Modifier.padding(vertical = 6.dp)
                },
            ),
    ) {
        InfoRow("版本", info.version.ifEmpty { "未知" })
        InfoRow("文件大小", info.fileSize)
        InfoRow("Target SDK", info.targetSdk)
        InfoRow("Min SDK", info.minSdk)
        InfoRow("权限", info.permissionCount.toString() + " 项", last = true)
    }
}

@Composable
private fun InfoRow(label: String, value: String, last: Boolean = false) {
    val colors = Dsh.colors
    val type = Dsh.type

    Column {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, style = type.caption, color = colors.inkFaint, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(12.dp))
            Text(
                text = value,
                style = type.mono,
                color = colors.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (!last) DshDivider()
    }
}

// ---------------------------------------------------------------------------
// 进度块（不定长呼吸，不假装知道百分比）
// ---------------------------------------------------------------------------

@Composable
private fun ProgressBlock(stage: InstallStage) {
    val colors = Dsh.colors
    val type = Dsh.type
    val surface = Dsh.surface
    val dimens = Dsh.dimens
    val motionEnabled = rememberMotionEnabled()
    val shape = RoundedCornerShape(dimens.panelRadius)

    val transition = rememberInfiniteTransition(label = "apkInstallBreath")
    val fraction by transition.animateFloat(
        initialValue = 0.22f,
        targetValue = 0.88f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1100),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "apkInstallFraction",
    )
    val shown = if (motionEnabled) fraction else 0.55f

    Column(
        Modifier
            .fillMaxWidth()
            .then(
                if (surface.hasPanels) {
                    Modifier
                        .clip(shape)
                        .background(
                            brush = Brush.verticalGradient(
                                listOf(colors.panelTop, colors.panelBottom),
                            ),
                        )
                        .border(1.dp, colors.hairline, shape)
                        .padding(16.dp)
                } else {
                    Modifier.padding(vertical = 14.dp)
                },
            ),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stage.label, style = type.title, color = colors.ink, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(10.dp))
            Text("进行中", style = type.monoSmall, color = colors.accent)
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
                    .fillMaxWidth(shown.coerceIn(0f, 1f))
                    .height(3.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(color = colors.accent),
            )
        }
        Spacer(Modifier.height(10.dp))
        Text(
            text = when (stage) {
                InstallStage.PREPARING -> "检查文件与包信息"
                InstallStage.COPYING -> "复制到应用可读目录，供 shell 访问"
                else -> "执行 pm install，请勿退出"
            },
            style = type.caption,
            color = colors.inkFaint,
        )
    }
}

// ---------------------------------------------------------------------------
// 失败块
// ---------------------------------------------------------------------------

@Composable
private fun ErrorBlock(message: String) {
    val colors = Dsh.colors
    val type = Dsh.type
    val dimens = Dsh.dimens
    val shape = RoundedCornerShape(dimens.controlRadius)

    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(color = colors.accentDim)
            .border(1.dp, colors.accentBorder, shape)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("安装失败", style = type.rowTitle, color = colors.accent)
        Text(
            text = message,
            style = type.monoSmall,
            color = colors.inkMuted,
            maxLines = 6,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
