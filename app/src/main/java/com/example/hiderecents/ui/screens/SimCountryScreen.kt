package com.example.hiderecents.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.hiderecents.sim.CallRecord
import com.example.hiderecents.sim.CountryPreset
import com.example.hiderecents.sim.Presets
import com.example.hiderecents.sim.RecentCountry
import com.example.hiderecents.sim.SimSlot
import com.example.hiderecents.ui.components.DshDivider
import com.example.hiderecents.ui.components.DshGhostButton
import com.example.hiderecents.ui.components.DshIcon
import com.example.hiderecents.ui.components.DshIconKind
import com.example.hiderecents.ui.components.DshPanel
import com.example.hiderecents.ui.components.DshPrimaryButton
import com.example.hiderecents.ui.components.SectionHeader
import com.example.hiderecents.ui.components.dshCanvasBackground
import com.example.hiderecents.ui.components.dshClickable
import com.example.hiderecents.ui.components.dshFieldFill
import com.example.hiderecents.ui.theme.Dsh
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 页面内的分段：国家码主体 / 调用与记录 */
enum class SimSegment(val title: String) {
    COUNTRY("国家码"),
    LOG("调用与记录"),
}

/**
 * SIM 国家码整页。
 *
 * 标题栏与分段固定在顶部，下面是一个横向分页容器：左右滑动在「国家码」和「调用与记录」
 * 之间切换，点分段同样是滑过去，下划线跟着联动 —— 所以两页都不再各自画标题，避免滑动时
 * 标题来回跳。
 */
@Composable
fun SimCountryScreen(
    slots: List<SimSlot>,
    selectedIndex: Int,
    target: CountryPreset?,
    carrierName: String,
    recent: List<RecentCountry>,
    calls: List<CallRecord>,
    todayCount: Int,
    shizukuVersion: String,
    channelLabel: String,
    uid: Int,
    segment: SimSegment,
    busy: Boolean,
    message: String?,
    onSegment: (SimSegment) -> Unit,
    onSelectSlot: (Int) -> Unit,
    onPickTarget: () -> Unit,
    onPickCarrier: () -> Unit,
    onRecentPick: (RecentCountry) -> Unit,
    onApply: () -> Unit,
    onReset: () -> Unit,
    onExport: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dimens = Dsh.dimens
    val pagerState = rememberPagerState { SimSegment.values().size }

    // 点分段 -> 动画滑到对应页
    LaunchedEffect(segment) {
        val index = segment.ordinal
        if (pagerState.currentPage != index) pagerState.animateScrollToPage(index)
    }
    // 手势滑页 -> 同步分段状态
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage }.collect { page ->
            val target = SimSegment.values()[page]
            if (target != segment) onSegment(target)
        }
    }

    Box(modifier.fillMaxSize().dshCanvasBackground()) {
        Column(
            Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(horizontal = dimens.screenPadding),
        ) {
            SimPageHeader(
                subtitle = "国家码 · COUNTRY CODE",
                title = "国家码覆盖",
                onBack = onBack,
            )
            Spacer(Modifier.height(12.dp))
            DshDivider(strong = true)
            SimSegments(segment = segment, onSegment = onSegment, pagerState = pagerState)

            HorizontalPager(
                state = pagerState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                beyondViewportPageCount = 1,
            ) { page ->
                when (SimSegment.values()[page]) {
                    SimSegment.COUNTRY -> CountryPage(
                        slots = slots,
                        selectedIndex = selectedIndex,
                        target = target,
                        carrierName = carrierName,
                        recent = recent,
                        busy = busy,
                        message = message,
                        onSelectSlot = onSelectSlot,
                        onPickTarget = onPickTarget,
                        onPickCarrier = onPickCarrier,
                        onRecentPick = onRecentPick,
                        onApply = onApply,
                        onReset = onReset,
                    )

                    SimSegment.LOG -> LogPage(
                        slots = slots,
                        selectedIndex = selectedIndex,
                        target = target,
                        calls = calls,
                        todayCount = todayCount,
                        shizukuVersion = shizukuVersion,
                        channelLabel = channelLabel,
                        uid = uid,
                        onReset = onReset,
                        onExport = onExport,
                    )
                }
            }
        }
    }
}

// ------------------------------------------------------------------ 分段

@Composable
private fun SimSegments(
    segment: SimSegment,
    onSegment: (SimSegment) -> Unit,
    pagerState: PagerState,
) {
    val colors = Dsh.colors
    val type = Dsh.type
    val positions = remember { mutableStateMapOf<Int, Pair<Float, Float>>() }

    Box(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(top = 12.dp)) {
            SimSegment.values().forEachIndexed { index, item ->
                if (index > 0) Spacer(Modifier.width(24.dp))
                val selected = item == segment
                // 间距用 Spacer 而不是 Box 的 padding：否则测到的是「文字 + 内边距」，
                // 下划线会比文字长出一截。
                Box(
                    Modifier
                        .onGloballyPositioned { coords ->
                            positions[index] = coords.positionInParent().x to coords.size.width.toFloat()
                        }
                        .dshClickable(scaleOnPress = false) { onSegment(item) }
                        .padding(bottom = 10.dp),
                ) {
                    Text(
                        text = item.title,
                        style = if (selected) type.rowTitle else type.body,
                        color = if (selected) colors.ink else colors.inkFaint,
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            if (!Dsh.surface.hasPanels) {
                Box(Modifier.padding(top = 2.dp)) {
                    Text("SHIZUKU", style = type.label, color = colors.inkFaint)
                }
            }
        }

        // 下划线跟 pager 的实时偏移走：拖到一半时它也在两段之间，翻页结束才落到目标位置。
        // 必须用 matchParentSize 跟住 Box 高度；fillMaxSize 会把 Box 撑满整页。
        val pageFloat = pagerState.currentPage + pagerState.currentPageOffsetFraction
        val lastIndex = SimSegment.values().size - 1
        val fromIndex = pageFloat.toInt().coerceIn(0, lastIndex)
        val toIndex = (fromIndex + 1).coerceAtMost(lastIndex)
        val t = (pageFloat - fromIndex).coerceIn(0f, 1f)
        val from = positions[fromIndex]
        val to = positions[toIndex] ?: from

        if (from != null && to != null) {
            val barX = from.first + (to.first - from.first) * t
            val barWidth = from.second + (to.second - from.second) * t
            Canvas(Modifier.matchParentSize()) {
                drawRect(
                    color = colors.accent,
                    topLeft = Offset(barX, size.height - 2.dp.toPx()),
                    size = Size(barWidth, 2.dp.toPx()),
                )
            }
        }
    }
}

// ------------------------------------------------------------------ 国家码页

@Composable
private fun CountryPage(
    slots: List<SimSlot>,
    selectedIndex: Int,
    target: CountryPreset?,
    carrierName: String,
    recent: List<RecentCountry>,
    busy: Boolean,
    message: String?,
    onSelectSlot: (Int) -> Unit,
    onPickTarget: () -> Unit,
    onPickCarrier: () -> Unit,
    onRecentPick: (RecentCountry) -> Unit,
    onApply: () -> Unit,
    onReset: () -> Unit,
) {
    val colors = Dsh.colors
    val type = Dsh.type
    val slot = slots.getOrNull(selectedIndex)

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        Spacer(Modifier.height(16.dp))

        if (slots.isEmpty()) {
            EmptySimNotice()
        } else {
            SimCardSelector(slots = slots, selectedIndex = selectedIndex, onSelect = onSelectSlot)
            Spacer(Modifier.height(18.dp))
            SimStatTriple(slot = slot, target = target)
        }

        Spacer(Modifier.height(22.dp))
        SectionHeader(title = "目标")
        Spacer(Modifier.height(10.dp))
        PickedRow(
            label = "目标国家",
            value = target?.let { it.label + "  " + it.mcc + " · " + it.code } ?: "未选择",
            onClick = onPickTarget,
        )
        Spacer(Modifier.height(8.dp))
        PickedRow(
            label = "运营商名",
            value = carrierName.ifEmpty { "不覆盖" },
            onClick = onPickCarrier,
        )

        if (recent.isNotEmpty()) {
            Spacer(Modifier.height(26.dp))
            SectionHeader(title = "最近使用", trailing = "点按直接应用")
            Spacer(Modifier.height(4.dp))
            recent.forEachIndexed { index, item ->
                if (index > 0) DshDivider()
                RecentRow(item = item, onClick = { onRecentPick(item) })
            }
        }

        if (message != null) {
            Spacer(Modifier.height(16.dp))
            Text(message, style = type.caption, color = colors.accent)
        }

        Spacer(Modifier.height(24.dp))
        DshPrimaryButton(
            text = if (busy) "正在写入…" else "更换国家码",
            onClick = { if (!busy) onApply() },
        )
        Spacer(Modifier.height(10.dp))
        DshGhostButton(text = "还原为运营商默认", onClick = { if (!busy) onReset() })
        Spacer(Modifier.height(16.dp))
        Text(
            text = "修改只影响系统读取到的国家码，不会改变实际网络注册。" +
                "可能影响运营商业、漫游计费与部分应用的地区判定。",
            style = type.caption,
            color = colors.inkFaint,
        )
        Spacer(Modifier.height(28.dp))
    }
}

// ------------------------------------------------------------------ 调用与记录页

@Composable
private fun LogPage(
    slots: List<SimSlot>,
    selectedIndex: Int,
    target: CountryPreset?,
    calls: List<CallRecord>,
    todayCount: Int,
    shizukuVersion: String,
    channelLabel: String,
    uid: Int,
    onReset: () -> Unit,
    onExport: () -> Unit,
) {
    val colors = Dsh.colors
    val type = Dsh.type
    val slot = slots.getOrNull(selectedIndex)
    val lastMillis = calls.firstOrNull()?.millis
    val failures = calls.count { !it.ok }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        Spacer(Modifier.height(18.dp))
        SimDiagnosticsStats(count = todayCount, lastMillis = lastMillis, failures = failures)

        Spacer(Modifier.height(22.dp))
        SectionHeader(title = "运行环境")
        Spacer(Modifier.height(4.dp))
        InfoRow("Shizuku", shizukuVersion)
        DshDivider()
        InfoRow("配置通道", channelLabel)
        DshDivider()
        InfoRow("身份", "uid " + uid + " · 经 Shizuku 借 shell 权限")
        DshDivider()
        InfoRow("目标卡", slot?.let { "卡 ${it.slotIndex + 1} · subId ${it.subId}" } ?: "无可用卡")

        if (slot != null) {
            val lastApply = calls.firstOrNull {
                it.action == "overrideConfig" && it.subId == slot.subId
            }
            val persistLabel = when {
                lastApply == null -> if (slot.hasOverride) "已生效" else "原样"
                lastApply.note.contains("non-persistent") -> "重启后失效"
                else -> "重启后保持"
            }

            Spacer(Modifier.height(22.dp))
            SectionHeader(title = "写入前后")
            Spacer(Modifier.height(10.dp))
            BeforeAfterBlock(slot = slot, target = target)

            Spacer(Modifier.height(22.dp))
            SectionHeader(title = "覆盖字段")
            Spacer(Modifier.height(4.dp))
            InfoRow(
                "国家码",
                slot.countryOverride
                    ?.let { it.uppercase() + "（" + (Presets.byCode(it)?.label ?: "未知") + "）" }
                    ?: "未覆盖",
            )
            DshDivider()
            InfoRow(
                "MCC / MNC",
                listOf(slot.mcc, slot.mnc).filter { it.isNotEmpty() }.joinToString(" / ")
                    .ifEmpty { "未读到" },
            )
            DshDivider()
            InfoRow("生效对象", "subId ${slot.subId} · 卡 ${slot.slotIndex + 1} · " + persistLabel)
        }

        Spacer(Modifier.height(22.dp))
        SectionHeader(
            title = "调用记录",
            trailing = if (calls.isEmpty()) null else calls.size.toString() + " 条",
        )
        Spacer(Modifier.height(6.dp))
        if (calls.isEmpty()) {
            Text("还没有调用过", style = type.caption, color = colors.inkFaint)
        } else {
            calls.take(12).forEach { record ->
                Text(
                    text = clock(record.timestamp) + "  " + record.action +
                        " subId=" + record.subId +
                        (if (record.ok) " OK " else " FAIL ") + record.millis + "ms" +
                        (if (record.note.isNotEmpty()) "  " + record.note else ""),
                    style = type.monoSmall,
                    color = if (record.ok) colors.inkMuted else colors.accent,
                )
                Spacer(Modifier.height(6.dp))
            }
        }

        Spacer(Modifier.height(24.dp))
        DshPrimaryButton(text = "还原为运营商默认", onClick = onReset)
        Spacer(Modifier.height(10.dp))
        DshGhostButton(text = "导出脱敏日志", onClick = onExport)
        Spacer(Modifier.height(16.dp))
        Text(
            text = "记录只保存在本机，导出内容不含运营商名称与号码。",
            style = type.caption,
            color = colors.inkFaint,
        )
        Spacer(Modifier.height(28.dp))
    }
}

// ------------------------------------------------------------------ 页头

@Composable
internal fun SimPageHeader(
    subtitle: String,
    title: String,
    onBack: () -> Unit,
    trailing: String? = null,
) {
    val colors = Dsh.colors
    val dimens = Dsh.dimens
    val type = Dsh.type

    Spacer(Modifier.height(6.dp))
    Row(
        Modifier.fillMaxWidth().height(40.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(32.dp)
                .clip(RoundedCornerShape(dimens.iconRadius))
                .border(1.dp, colors.hairline, RoundedCornerShape(dimens.iconRadius))
                .dshClickable(onClick = onBack),
            contentAlignment = Alignment.Center,
        ) {
            DshIcon(DshIconKind.BACK, iconSize = 14.dp, tint = colors.inkMuted)
        }
        Spacer(Modifier.width(10.dp))
        Text(subtitle, style = type.label, color = colors.inkFaint)
        Spacer(Modifier.weight(1f))
        if (trailing != null) {
            Text(trailing, style = type.monoSmall, color = colors.inkFaint)
        }
    }
    Spacer(Modifier.height(12.dp))
    Text(title, style = type.h1, color = colors.ink)
}

// ------------------------------------------------------------------ 卡选择

@Composable
private fun SimCardSelector(slots: List<SimSlot>, selectedIndex: Int, onSelect: (Int) -> Unit) {
    val colors = Dsh.colors
    val type = Dsh.type
    val hairline = colors.hairline
    val hasPanels = Dsh.surface.hasPanels
    val radius = RoundedCornerShape(if (hasPanels) Dsh.dimens.tileRadius else 0.dp)

    Row(
        Modifier
            .fillMaxWidth()
            .clip(radius)
            .border(1.dp, hairline, radius),
    ) {
        slots.forEachIndexed { index, slot ->
            if (index > 0) {
                Box(Modifier.width(1.dp).height(96.dp).background(hairline))
            }
            val selected = index == selectedIndex
            Box(
                Modifier
                    .weight(1f)
                    .dshClickable { onSelect(index) }
                    .background(if (selected) dshFieldFill(0.9f) else Color.Transparent)
                    // 强调色竖条铺满整卡高度：上下各多画 1.5dp，盖住外层的 1dp 描边
                    .drawBehind {
                        if (selected) {
                            val over = 1.5.dp.toPx()
                            drawRect(
                                color = colors.accent,
                                topLeft = Offset(0f, -over),
                                size = Size(2.dp.toPx(), size.height + over * 2),
                            )
                        }
                    }
                    .padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 12.dp),
            ) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("卡 " + (slot.slotIndex + 1), style = type.label, color = colors.inkFaint)
                        if (slot.hasOverride) {
                            Spacer(Modifier.width(8.dp))
                            OverrideTag()
                        }
                    }
                    Spacer(Modifier.height(7.dp))
                    Text(
                        text = slot.carrierName.ifEmpty { "未知运营商" },
                        style = type.title,
                        color = colors.ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(7.dp))
                    Text(
                        text = listOf(slot.operatorCode, slot.effectiveIso, slot.networkLabel)
                            .filter { it.isNotEmpty() }
                            .joinToString(" · "),
                        style = type.monoSmall,
                        color = colors.inkFaint,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun OverrideTag() {
    val colors = Dsh.colors
    val type = Dsh.type
    val hasPanels = Dsh.surface.hasPanels
    Box(
        Modifier
            .clip(RoundedCornerShape(2.dp))
            .background(if (hasPanels) colors.accentDim else colors.accent)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(
            text = "覆盖中",
            style = type.label,
            color = if (hasPanels) colors.accent else colors.accentInk,
        )
    }
}

// ------------------------------------------------------------------ 三列数据

@Composable
private fun SimStatTriple(slot: SimSlot?, target: CountryPreset?) {
    val colors = Dsh.colors
    val hairline = colors.hairline
    val applied = target != null && slot?.countryOverride?.equals(target.code, ignoreCase = true) == true

    Row(Modifier.fillMaxWidth()) {
        StatCell(
            label = "当前 MCC",
            value = slot?.mcc?.ifEmpty { "—" } ?: "—",
            footnote = slot?.let { "运营商 " + it.carrierName.ifEmpty { "未知" } } ?: "无卡",
            modifier = Modifier.weight(1f),
        )
        Box(Modifier.width(1.dp).height(96.dp).background(hairline))
        StatCell(
            label = "覆盖为",
            value = target?.mcc ?: "—",
            footnote = target?.let {
                it.label + " " + it.code + " · " + if (applied) "已生效" else "待应用"
            } ?: "未选择",
            accent = true,
            modifier = Modifier.weight(1f),
            insetStart = 16.dp,
        )
        Box(Modifier.width(1.dp).height(96.dp).background(hairline))
        StatCell(
            label = "影响卡",
            value = slot?.let { "卡 " + (it.slotIndex + 1) } ?: "—",
            footnote = "其余卡保持原样",
            modifier = Modifier.weight(1f),
            insetStart = 16.dp,
        )
    }
    DshDivider(strong = true)
}

@Composable
private fun StatCell(
    label: String,
    value: String,
    footnote: String,
    modifier: Modifier = Modifier,
    accent: Boolean = false,
    insetStart: Dp = 0.dp,
) {
    val colors = Dsh.colors
    val type = Dsh.type
    Column(modifier.padding(start = insetStart, end = 14.dp)) {
        Text(label, style = type.label, color = colors.inkFaint)
        Spacer(Modifier.height(8.dp))
        Text(
            text = value,
            style = type.number,
            color = if (accent) colors.accent else colors.ink,
            maxLines = 1,
        )
        Spacer(Modifier.height(8.dp))
        Text(footnote, style = type.caption, color = colors.inkMuted, maxLines = 2)
    }
}

// ------------------------------------------------------------------ 目标与运营商

@Composable
private fun PickedRow(label: String, value: String, onClick: () -> Unit) {
    val colors = Dsh.colors
    val type = Dsh.type
    val hasPanels = Dsh.surface.hasPanels
    val shape = RoundedCornerShape(if (hasPanels) Dsh.dimens.controlRadius else 0.dp)

    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .border(1.dp, colors.hairline, shape)
            .dshClickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = type.caption, color = colors.inkMuted)
        Spacer(Modifier.weight(1f))
        Text(
            text = value,
            style = type.monoSmall,
            color = colors.ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.width(8.dp))
        DshIcon(DshIconKind.CHEVRON, iconSize = 12.dp, tint = colors.inkFaint)
    }
}

@Composable
private fun RecentRow(item: RecentCountry, onClick: () -> Unit) {
    val colors = Dsh.colors
    val type = Dsh.type
    Box(Modifier.fillMaxWidth().dshClickable(onClick = onClick)) {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CodeBox(item.code)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(item.label, style = type.rowTitle, color = colors.ink)
                Spacer(Modifier.height(4.dp))
                Text(
                    text = listOf(item.operator, item.mcc).filter { it.isNotEmpty() }.joinToString(" · "),
                    style = type.monoSmall,
                    color = colors.inkFaint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(10.dp))
            Text(relativeTime(item.timestamp), style = type.monoSmall, color = colors.inkFaint)
        }
    }
}

@Composable
internal fun CodeBox(code: String) {
    val colors = Dsh.colors
    val type = Dsh.type
    val shape = RoundedCornerShape(if (Dsh.surface.hasPanels) 6.dp else 0.dp)
    Box(
        Modifier
            .size(width = 34.dp, height = 30.dp)
            .clip(shape)
            .border(1.dp, colors.hairline, shape),
        contentAlignment = Alignment.Center,
    ) {
        Text(code.uppercase(), style = type.monoSmall, color = colors.inkMuted)
    }
}

// ------------------------------------------------------------------ 诊断页零件

@Composable
private fun SimDiagnosticsStats(count: Int, lastMillis: Long?, failures: Int) {
    val colors = Dsh.colors
    val hairline = colors.hairline
    Row(Modifier.fillMaxWidth()) {
        StatCell(
            label = "调用",
            value = count.toString(),
            footnote = "次 · 今日",
            modifier = Modifier.weight(1f),
        )
        Box(Modifier.width(1.dp).height(96.dp).background(hairline))
        StatCell(
            label = "耗时",
            value = lastMillis?.toString() ?: "—",
            footnote = "ms · 最近一次",
            modifier = Modifier.weight(1f),
            insetStart = 16.dp,
        )
        Box(Modifier.width(1.dp).height(96.dp).background(hairline))
        StatCell(
            label = "状态",
            value = if (failures == 0) "OK" else "FAIL",
            footnote = if (failures == 0) "无失败" else "失败 " + failures + " 次",
            accent = true,
            modifier = Modifier.weight(1f),
            insetStart = 16.dp,
        )
    }
    DshDivider(strong = true)
}

@Composable
private fun InfoRow(label: String, value: String) {
    val colors = Dsh.colors
    val type = Dsh.type
    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = type.rowTitle, color = colors.inkMuted)
        Spacer(Modifier.weight(1f))
        Text(
            text = value,
            style = type.monoSmall,
            color = colors.ink,
            textAlign = TextAlign.End,
            maxLines = 2,
        )
    }
}

@Composable
private fun BeforeAfterBlock(slot: SimSlot, target: CountryPreset?) {
    val colors = Dsh.colors
    val type = Dsh.type

    DshPanel(contentPadding = PaddingValues(14.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f)) {
                Text("覆盖前", style = type.label, color = colors.inkFaint)
                Spacer(Modifier.height(6.dp))
                Text(
                    text = listOf(slot.iso.ifEmpty { "—" }, slot.mcc, slot.mnc)
                        .filter { it.isNotEmpty() }.joinToString(" · "),
                    style = type.mono,
                    color = colors.ink,
                )
            }
            Text("→", style = type.body, color = colors.accent)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = "覆盖后",
                    style = type.label,
                    color = colors.inkFaint,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.End,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = target?.let { it.code + " · " + it.mcc } ?: "未选择",
                    style = type.mono,
                    color = colors.accent,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.End,
                )
            }
        }
    }
}

@Composable
private fun EmptySimNotice() {
    val colors = Dsh.colors
    val type = Dsh.type
    DshPanel(contentPadding = PaddingValues(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            DshIcon(DshIconKind.SIM, iconSize = 18.dp, tint = colors.accent)
            Spacer(Modifier.width(12.dp))
            Column {
                Text("未检测到 SIM 卡", style = type.rowTitle, color = colors.ink)
                Spacer(Modifier.height(5.dp))
                Text(
                    "插入实体卡后回到本页刷新。没有卡时无法写入运营商配置。",
                    style = type.caption,
                    color = colors.inkFaint,
                )
            }
        }
    }
}

// ------------------------------------------------------------------ 确认抽屉

@Composable
fun SimConfirmSheet(
    slot: SimSlot,
    target: CountryPreset,
    carrierName: String,
    acknowledged: Boolean,
    busy: Boolean,
    onAcknowledgedChange: (Boolean) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = Dsh.colors
    val type = Dsh.type

    Column(Modifier.fillMaxWidth().padding(horizontal = Dsh.dimens.screenPadding, vertical = 18.dp)) {
        Box(
            Modifier
                .align(Alignment.CenterHorizontally)
                .size(width = 40.dp, height = 3.dp)
                .background(colors.hairlineStrong),
        )
        Spacer(Modifier.height(20.dp))
        Text("应用到卡 " + (slot.slotIndex + 1), style = type.h2, color = colors.ink)
        Spacer(Modifier.height(6.dp))
        Text(
            text = listOf(slot.carrierName.ifEmpty { "未知运营商" }, slot.operatorCode, slot.networkLabel)
                .filter { it.isNotEmpty() }.joinToString(" · "),
            style = type.caption,
            color = colors.inkMuted,
        )

        Spacer(Modifier.height(18.dp))
        BeforeAfterBlock(slot = slot, target = target)

        Spacer(Modifier.height(18.dp))
        InfoRow("写入接口", "ICarrierConfigLoader")
        DshDivider()
        InfoRow("影响范围", "运营商国家 / 配置匹配")
        DshDivider()
        InfoRow("是否可还原", "随时可还原")
        DshDivider()
        InfoRow("运营商名", carrierName.ifEmpty { "不覆盖（只改国家码）" })

        Spacer(Modifier.height(18.dp))
        Row(
            Modifier.fillMaxWidth().dshClickable { onAcknowledgedChange(!acknowledged) },
            verticalAlignment = Alignment.Top,
        ) {
            Box(
                Modifier
                    .padding(top = 1.dp)
                    .size(18.dp)
                    .border(1.dp, colors.hairlineStrong),
                contentAlignment = Alignment.Center,
            ) {
                if (acknowledged) {
                    Box(Modifier.size(10.dp).background(colors.accent))
                }
            }
            Spacer(Modifier.width(10.dp))
            Text(
                text = "我了解：修改可能影响漫游计费、运营商业务与部分应用的地区判定，" +
                    "且不改变 SIM 卡本身。",
                style = type.caption,
                color = colors.inkMuted,
            )
        }

        Spacer(Modifier.height(20.dp))
        DshPrimaryButton(
            text = if (busy) "正在写入…" else "确认覆盖",
            onClick = { if (acknowledged && !busy) onConfirm() },
        )
        Spacer(Modifier.height(10.dp))
        DshGhostButton(text = "取消", onClick = onDismiss)
        Spacer(Modifier.height(6.dp))
    }
}

// ------------------------------------------------------------------ 小工具

private fun clock(timestamp: Long): String =
    SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(timestamp))

/** 最近使用的相对时间：今天显示时刻，昨天显示「昨天」，更早显示月-日 */
internal fun relativeTime(timestamp: Long): String {
    val today = java.util.Calendar.getInstance().apply {
        set(java.util.Calendar.HOUR_OF_DAY, 0)
        set(java.util.Calendar.MINUTE, 0)
        set(java.util.Calendar.SECOND, 0)
        set(java.util.Calendar.MILLISECOND, 0)
    }.timeInMillis

    return when {
        timestamp >= today -> SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestamp))
        timestamp >= today - 86_400_000L -> "昨天"
        else -> SimpleDateFormat("MM-dd", Locale.getDefault()).format(Date(timestamp))
    }
}