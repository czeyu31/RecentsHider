package com.example.hiderecents.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.hiderecents.ui.components.DshDivider
import com.example.hiderecents.ui.components.dshFieldFill
import com.example.hiderecents.ui.components.DshIcon
import com.example.hiderecents.ui.components.DshIconKind
import com.example.hiderecents.ui.components.StaggeredEnter
import com.example.hiderecents.ui.components.dshCanvasBackground
import com.example.hiderecents.ui.components.dshClickable
import com.example.hiderecents.ui.theme.Dsh
import com.example.hiderecents.ui.theme.MotionSpecs
import com.example.hiderecents.ui.theme.rememberMotionEnabled

/** 本地 HTTP 服务状态 */
data class FileServerState(
    val ip: String,
    val port: Int,
    val running: Boolean,
)

/** 共享文件（id 即 HTTP 路径里的 fileId） */
data class SharedFileItem(
    val id: String,
    val name: String,
    val sizeLabel: String,
    val isApk: Boolean,
)

/** 局域网发现的设备 */
data class NearbyDeviceItem(
    val key: String,
    val alias: String,
    val endpoint: String,
    val isBluetooth: Boolean,
)

/** 传输任务状态，与原实现的五种字符串状态一一对应 */
enum class TaskStatus(val label: String) {
    WAITING("等待中"),
    TRANSFERRING("传输中"),
    INSTALLING("安装中"),
    COMPLETED("已完成"),
    INSTALLED("已安装"),
}

/** 传输任务（UI 模型，速度与进度在宿主侧算好） */
data class TransferTaskItem(
    val id: String,
    val fileName: String,
    val target: String,
    val isSend: Boolean,
    val sizeLabel: String,
    val progressLabel: String,
    val speedLabel: String,
    val fraction: Float,
    val status: TaskStatus,
    val filePath: String,
)

/** 六个分享入口：文件 / 媒体 / 剪贴板 / 文本 / 文件夹 / 应用 */
data class ShareAction(
    val label: String,
    val icon: DshIconKind,
    val onClick: () -> Unit,
)

/**
 * 文件传输页。
 *
 * 功能与原 FileTransferActivity 一致：服务地址与共享链接、六条分享入口、URL 接收、
 * 附近设备（UDP + 蓝牙 + 网段探测）、传输任务与进度、共享文件管理与移除。
 * HTTP 服务、UDP 监听、下载与上传逻辑全部留在宿主侧。
 */
@Composable
fun FileTransferScreen(
    server: FileServerState,
    shareUrl: String?,
    actions: List<ShareAction>,
    receiveUrl: String,
    onReceiveUrlChange: (String) -> Unit,
    onReceive: () -> Unit,
    devices: List<NearbyDeviceItem>,
    scanning: Boolean,
    tasks: List<TransferTaskItem>,
    totalTransferredLabel: String,
    sharedFiles: List<SharedFileItem>,
    availableSpaceLabel: String,
    onBack: () -> Unit,
    onScan: () -> Unit,
    onOpenSettings: () -> Unit,
    onCopyUrl: () -> Unit,
    onRemoveSharedFile: (String) -> Unit,
    onSendToDevice: (NearbyDeviceItem) -> Unit,
    onOpenTaskFile: (TransferTaskItem) -> Unit,
    deviceSendStates: Map<String, String> = emptyMap(),
    modifier: Modifier = Modifier,
) {
    val colors = Dsh.colors
    val dimens = Dsh.dimens
    val type = Dsh.type

    Box(modifier.fillMaxSize().dshCanvasBackground()) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
            Spacer(Modifier.height(4.dp))

            // ---------------- 顶栏 ----------------
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .padding(horizontal = dimens.screenPadding),
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
                    Text("文件传输", style = type.h2, color = colors.ink)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = if (server.running) "同一 Wi-Fi 下无需数据线" else "服务未启动，请重进本页",
                        style = type.label,
                        color = if (server.running) colors.inkFaint else colors.accent,
                    )
                }
                Box(
                    Modifier
                        .clip(RoundedCornerShape(dimens.iconRadius))
                        .dshClickable(scaleOnPress = false, onClick = onScan)
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = if (scanning) "扫描中" else "扫描",
                        style = type.caption,
                        color = if (scanning) colors.accent else colors.inkMuted,
                    )
                }
                Spacer(Modifier.width(4.dp))
                Box(
                    Modifier
                        .size(30.dp)
                        .clip(RoundedCornerShape(dimens.iconRadius))
                        .dshClickable(onClick = onOpenSettings),
                    contentAlignment = Alignment.Center,
                ) {
                    // 纸本（无面板）用文字「设置」，与首页/工具箱一致；石墨（有面板）用滑杆图标
                    if (Dsh.surface.hasPanels) {
                        DshIcon(DshIconKind.SLIDERS, iconSize = 15.dp, tint = colors.inkMuted)
                    } else {
                        Text("设置", style = type.caption, color = colors.inkMuted)
                    }
                }
            }

            // ---------------- 正文 ----------------
            Column(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = dimens.screenPadding),
            ) {
                StaggeredEnter(index = 0) {
                    ServerBlock(
                        server = server,
                        shareUrl = shareUrl,
                        onCopyUrl = onCopyUrl,
                    )
                }

                Spacer(Modifier.height(if (Dsh.surface.hasPanels) dimens.gapM else 22.dp))

                StaggeredEnter(index = 1) {
                    ShareGrid(actions = actions)
                }

                Spacer(Modifier.height(dimens.gapM))

                StaggeredEnter(index = 2) {
                    ReceiveRow(
                        receiveUrl = receiveUrl,
                        onReceiveUrlChange = onReceiveUrlChange,
                        onReceive = onReceive,
                    )
                }

                Spacer(Modifier.height(dimens.gapXl))

                StaggeredEnter(index = 3) {
                    DevicesSection(
                        devices = devices,
                        scanning = scanning,
                        onScan = onScan,
                        onSendToDevice = onSendToDevice,
                        deviceSendStates = deviceSendStates,
                    )
                }

                Spacer(Modifier.height(dimens.gapXl))

                StaggeredEnter(index = 4) {
                    TasksSection(
                        tasks = tasks,
                        totalTransferredLabel = totalTransferredLabel,
                        onOpenTaskFile = onOpenTaskFile,
                    )
                }

                Spacer(Modifier.height(dimens.gapXl))

                StaggeredEnter(index = 5) {
                    FilesSection(
                        files = sharedFiles,
                        availableSpaceLabel = availableSpaceLabel,
                        onRemove = onRemoveSharedFile,
                    )
                }

                Spacer(Modifier.height(26.dp))
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 服务地址
// ---------------------------------------------------------------------------

@Composable
private fun ServerBlock(
    server: FileServerState,
    shareUrl: String?,
    onCopyUrl: () -> Unit,
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
            Text("服务地址", style = type.label, color = colors.inkFaint)
            Spacer(Modifier.weight(1f))
            Box(
                Modifier
                    .size(5.dp)
                    .clip(RoundedCornerShape(percent = 50))
                    .background(color = if (server.running) colors.accent else colors.inkFaint),
            )
            Spacer(Modifier.width(7.dp))
            Text(
                text = if (server.running) "运行中" else "未启动",
                style = type.monoSmall,
                color = if (server.running) colors.accent else colors.inkFaint,
            )
        }

        Spacer(Modifier.height(12.dp))

        Text(
            text = if (server.ip.isEmpty()) "未连接 Wi-Fi" else server.ip + ":" + server.port,
            style = type.numberSmall,
            color = colors.ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        Spacer(Modifier.height(10.dp))

        Text(
            text = "在其他设备的浏览器打开此地址即可收发文件",
            style = type.caption,
            color = colors.inkFaint,
        )

        if (shareUrl != null) {
            Spacer(Modifier.height(14.dp))
            DshDivider()
            Spacer(Modifier.height(12.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .dshClickable(scaleOnPress = false, onClick = onCopyUrl),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("当前共享链接", style = type.label, color = colors.inkFaint)
                    Spacer(Modifier.height(7.dp))
                    Text(
                        text = shareUrl,
                        style = type.monoSmall,
                        color = colors.accent,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.width(10.dp))
                Text("复制", style = type.caption, color = colors.inkMuted)
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 六条分享入口
// ---------------------------------------------------------------------------

@Composable
private fun ShareGrid(actions: List<ShareAction>) {
    val colors = Dsh.colors
    val type = Dsh.type
    val surface = Dsh.surface
    val dimens = Dsh.dimens
    val shape = RoundedCornerShape(dimens.tileRadius)

    val rows = actions.chunked(3)

    Column(
        Modifier
            .fillMaxWidth()
            .then(
                if (surface.hasPanels) {
                    Modifier
                        .clip(shape)
                        .background(color = dshFieldFill(0.5f))
                        .border(1.dp, colors.hairline, shape)
                } else {
                    Modifier
                },
            ),
    ) {
        rows.forEachIndexed { rowIndex, row ->
            if (rowIndex > 0) {
                DshDivider(
                    Modifier.padding(horizontal = if (surface.hasPanels) 12.dp else 0.dp),
                )
            }
            Row(Modifier.fillMaxWidth()) {
                row.forEachIndexed { colIndex, action ->
                    if (colIndex > 0) {
                        Box(
                            Modifier
                                .width(1.dp)
                                .height(58.dp)
                                .background(color = colors.hairline),
                        )
                    }
                    Column(
                        Modifier
                            .weight(1f)
                            .dshClickable(scaleOnPress = false, onClick = action.onClick)
                            .padding(vertical = 14.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        DshIcon(action.icon, iconSize = 17.dp, tint = colors.accent)
                        Spacer(Modifier.height(8.dp))
                        Text(action.label, style = type.caption, color = colors.inkMuted)
                    }
                }
                // 补齐空位，保证每行三格等宽
                repeat(3 - row.size) {
                    Box(Modifier.weight(1f))
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// URL 接收
// ---------------------------------------------------------------------------

@Composable
private fun ReceiveRow(
    receiveUrl: String,
    onReceiveUrlChange: (String) -> Unit,
    onReceive: () -> Unit,
) {
    val colors = Dsh.colors
    val type = Dsh.type
    val surface = Dsh.surface
    val dimens = Dsh.dimens
    val shape = RoundedCornerShape(dimens.controlRadius)

    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .weight(1f)
                .height(46.dp)
                .clip(shape)
                .background(color = colors.panelTop.copy(alpha = if (surface.hasPanels) 0.55f else 0f))
                .border(1.dp, colors.hairline, shape)
                .padding(horizontal = 14.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            BasicTextField(
                value = receiveUrl,
                onValueChange = onReceiveUrlChange,
                singleLine = true,
                textStyle = type.monoSmall.copy(color = colors.ink),
                cursorBrush = SolidColor(colors.accent),
                modifier = Modifier.fillMaxWidth(),
                decorationBox = { innerTextField ->
                    Box {
                        if (receiveUrl.isEmpty()) {
                            Text("输入 URL 接收", style = type.monoSmall, color = colors.inkFaint)
                        }
                        innerTextField()
                    }
                },
            )
        }
        Spacer(Modifier.width(10.dp))
        Box(
            Modifier
                .height(46.dp)
                .clip(shape)
                .background(color = if (surface.hasPanels) colors.accent else Color.Transparent)
                .border(
                    width = if (surface.hasPanels) 0.dp else 1.dp,
                    color = if (surface.hasPanels) Color.Transparent else colors.accentBorder,
                    shape = shape,
                )
                .dshClickable(onClick = onReceive)
                .padding(horizontal = 18.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "接收",
                style = type.rowTitle,
                color = if (surface.hasPanels) colors.accentInk else colors.accent,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// 附近设备
// ---------------------------------------------------------------------------

@Composable
private fun DevicesSection(
    devices: List<NearbyDeviceItem>,
    scanning: Boolean,
    onScan: () -> Unit,
    onSendToDevice: (NearbyDeviceItem) -> Unit,
    deviceSendStates: Map<String, String> = emptyMap(),
) {
    val colors = Dsh.colors
    val type = Dsh.type
    val surface = Dsh.surface
    val dimens = Dsh.dimens
    val shape = RoundedCornerShape(dimens.panelRadius)

    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("附近设备", style = type.label, color = colors.inkFaint)
            Spacer(Modifier.width(8.dp))
            Text(
                text = when {
                    scanning -> "扫描中"
                    devices.isEmpty() -> "0"
                    else -> devices.size.toString() + " 台在线"
                },
                style = type.monoSmall,
                color = if (devices.isEmpty()) colors.inkFaint else colors.accent,
            )
            Spacer(Modifier.weight(1f))
            Box(
                Modifier.dshClickable(scaleOnPress = false, onClick = onScan).padding(4.dp),
            ) {
                Text("重新扫描", style = type.caption, color = colors.inkMuted)
            }
        }

        Spacer(Modifier.height(10.dp))

        if (devices.isEmpty()) {
            HintRow(
                text = if (scanning) "正在通过 UDP 广播与网段探测查找…" else "没有发现设备，确认两台设备在同一个 Wi-Fi",
            )
        } else {
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
                        } else {
                            Modifier
                        },
                    ),
            ) {
                devices.forEachIndexed { index, device ->
                    if (index > 0) {
                        DshDivider(Modifier.padding(horizontal = if (surface.hasPanels) 14.dp else 0.dp))
                    }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .dshClickable(scaleOnPress = false) { onSendToDevice(device) }
                            .padding(
                                horizontal = if (surface.hasPanels) 14.dp else 0.dp,
                                vertical = 13.dp,
                            ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier
                                .size(30.dp)
                                .clip(RoundedCornerShape(dimens.iconRadius))
                                .background(color = colors.accentDim),
                            contentAlignment = Alignment.Center,
                        ) {
                            DshIcon(
                                kind = if (device.isBluetooth) DshIconKind.BELL else DshIconKind.TRANSFER,
                                iconSize = 14.dp,
                                tint = colors.accent,
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = device.alias,
                                style = type.rowTitle,
                                color = colors.ink,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = device.endpoint,
                                style = type.monoSmall,
                                color = colors.inkFaint,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Spacer(Modifier.width(10.dp))
                        when (deviceSendStates[device.key]) {
                            "running" -> CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = colors.accent,
                            )
                            "done" -> Text("\u2713", style = type.rowTitle, color = colors.accent)
                            "fail" -> Text("\u2715", style = type.rowTitle, color = colors.accent)
                            else -> DshIcon(DshIconKind.ARROW_RIGHT, iconSize = 13.dp, tint = colors.accent)
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 传输任务
// ---------------------------------------------------------------------------

@Composable
private fun TasksSection(
    tasks: List<TransferTaskItem>,
    totalTransferredLabel: String,
    onOpenTaskFile: (TransferTaskItem) -> Unit,
) {
    val colors = Dsh.colors
    val type = Dsh.type
    val surface = Dsh.surface
    val dimens = Dsh.dimens
    val shape = RoundedCornerShape(dimens.panelRadius)

    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("传输任务", style = type.label, color = colors.inkFaint)
            Spacer(Modifier.weight(1f))
            Text(totalTransferredLabel, style = type.monoSmall, color = colors.inkFaint)
        }

        Spacer(Modifier.height(10.dp))

        if (tasks.isEmpty()) {
            HintRow(text = "还没有传输任务，选择文件或接收一个链接开始")
        } else {
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
                        } else {
                            Modifier
                        },
                    ),
            ) {
                tasks.forEachIndexed { index, task ->
                    if (index > 0) {
                        DshDivider(Modifier.padding(horizontal = if (surface.hasPanels) 14.dp else 0.dp))
                    }
                    TaskRow(
                        task = task,
                        canOpen = task.filePath.isNotEmpty() &&
                            (task.status == TaskStatus.COMPLETED || task.status == TaskStatus.INSTALLED),
                        onOpen = { onOpenTaskFile(task) },
                    )
                }
            }
        }
    }
}

@Composable
private fun TaskRow(task: TransferTaskItem, canOpen: Boolean, onOpen: () -> Unit) {
    val colors = Dsh.colors
    val type = Dsh.type
    val surface = Dsh.surface
    val motionEnabled = rememberMotionEnabled()

    val statusColor by animateColorAsState(
        targetValue = when (task.status) {
            TaskStatus.TRANSFERRING, TaskStatus.INSTALLING, TaskStatus.INSTALLED -> colors.accent
            TaskStatus.COMPLETED -> colors.inkMuted
            TaskStatus.WAITING -> colors.inkFaint
        },
        animationSpec = MotionSpecs.color(Dsh.motion, motionEnabled),
        label = "taskStatusColor",
    )

    val fraction by animateFloatAsState(
        targetValue = task.fraction.coerceIn(0f, 1f),
        animationSpec = MotionSpecs.enter(Dsh.motion, motionEnabled),
        label = "taskProgress",
    )

    Column(
        Modifier
            .fillMaxWidth()
            .then(if (canOpen) Modifier.dshClickable(scaleOnPress = false, onClick = onOpen) else Modifier)
            .padding(
                horizontal = if (surface.hasPanels) 14.dp else 0.dp,
                vertical = 13.dp,
            ),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = task.fileName,
                style = type.rowTitle,
                color = colors.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(10.dp))
            Text(task.status.label, style = type.monoSmall, color = statusColor)
        }

        Spacer(Modifier.height(5.dp))

        Text(
            text = (if (task.isSend) "发送到 " else "来自 ") + task.target + " · " + task.sizeLabel,
            style = type.monoSmall,
            color = colors.inkFaint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        if (task.status == TaskStatus.TRANSFERRING || task.status == TaskStatus.WAITING) {
            Spacer(Modifier.height(10.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(color = colors.track),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(fraction)
                        .height(3.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(color = colors.accent),
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(task.progressLabel, style = type.monoSmall, color = colors.inkFaint)
                Spacer(Modifier.weight(1f))
                Text(task.speedLabel, style = type.monoSmall, color = colors.accent)
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 共享文件
// ---------------------------------------------------------------------------

@Composable
private fun FilesSection(
    files: List<SharedFileItem>,
    availableSpaceLabel: String,
    onRemove: (String) -> Unit,
) {
    val colors = Dsh.colors
    val type = Dsh.type
    val surface = Dsh.surface
    val dimens = Dsh.dimens
    val shape = RoundedCornerShape(dimens.panelRadius)

    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("共享文件", style = type.label, color = colors.inkFaint)
            Spacer(Modifier.weight(1f))
            Text("可用 " + availableSpaceLabel, style = type.monoSmall, color = colors.inkFaint)
        }

        Spacer(Modifier.height(10.dp))

        if (files.isEmpty()) {
            HintRow(text = "还没有共享文件，从上面六个入口选一个")
        } else {
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
                        } else {
                            Modifier
                        },
                    ),
            ) {
                files.forEachIndexed { index, file ->
                    if (index > 0) {
                        DshDivider(Modifier.padding(horizontal = if (surface.hasPanels) 14.dp else 0.dp))
                    }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(
                                horizontal = if (surface.hasPanels) 14.dp else 0.dp,
                                vertical = 12.dp,
                            ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier
                                .size(30.dp)
                                .clip(RoundedCornerShape(dimens.iconRadius))
                                .background(color = colors.accentDim),
                            contentAlignment = Alignment.Center,
                        ) {
                            DshIcon(
                                kind = if (file.isApk) DshIconKind.PACKAGE else DshIconKind.FILE,
                                iconSize = 14.dp,
                                tint = colors.accent,
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = file.name,
                                style = type.rowTitle,
                                color = colors.ink,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(file.sizeLabel, style = type.monoSmall, color = colors.inkFaint)
                        }
                        Spacer(Modifier.width(10.dp))
                        Box(
                            Modifier
                                .dshClickable(scaleOnPress = false) { onRemove(file.id) }
                                .padding(horizontal = 6.dp, vertical = 4.dp),
                        ) {
                            Text("移除", style = type.caption, color = colors.inkMuted)
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 通用提示行
// ---------------------------------------------------------------------------

@Composable
private fun HintRow(text: String) {
    val colors = Dsh.colors
    val type = Dsh.type
    val dimens = Dsh.dimens
    val surface = Dsh.surface
    val shape = RoundedCornerShape(dimens.controlRadius)

    Box(
        Modifier
            .fillMaxWidth()
            .then(
                if (surface.hasPanels) {
                    Modifier
                        .clip(shape)
                        .background(color = dshFieldFill(0.35f))
                        .border(1.dp, colors.hairline, shape)
                        .padding(14.dp)
                } else {
                    Modifier.padding(vertical = 12.dp)
                },
            ),
    ) {
        Text(text, style = type.caption, color = colors.inkFaint)
    }
}
