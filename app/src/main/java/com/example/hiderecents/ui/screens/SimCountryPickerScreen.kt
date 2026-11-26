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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.hiderecents.sim.CountryPreset
import com.example.hiderecents.sim.Presets
import com.example.hiderecents.sim.RecentCountry
import com.example.hiderecents.ui.components.DshDivider
import com.example.hiderecents.ui.components.DshTextField
import com.example.hiderecents.ui.components.dshCanvasBackground
import com.example.hiderecents.ui.components.dshClickable
import com.example.hiderecents.ui.components.dshFieldFill
import com.example.hiderecents.ui.theme.Dsh

private enum class PickerTab(val title: String) {
    COMMON("常用"),
    ALL("全部"),
    RECENT("最近"),
}

@Composable
fun SimCountryPickerScreen(
    currentIso: String?,
    recent: List<RecentCountry>,
    onPick: (CountryPreset) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Dsh.colors
    val dimens = Dsh.dimens
    val type = Dsh.type

    var query by remember { mutableStateOf("") }
    var tab by remember { mutableStateOf(PickerTab.COMMON) }

    val keyword = query.trim()
    val source = when (tab) {
        PickerTab.COMMON -> Presets.common
        PickerTab.ALL -> Presets.countries
        PickerTab.RECENT -> recent.mapNotNull { item ->
            Presets.byCode(item.code) ?: CountryPreset(item.code, item.label, item.mcc)
        }
    }
    val list = if (keyword.isEmpty()) {
        source
    } else {
        source.filter {
            it.label.contains(keyword, ignoreCase = true) ||
                it.code.contains(keyword, ignoreCase = true) ||
                it.mcc.startsWith(keyword)
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
                title = "选择国家和地区",
                onBack = onBack,
                trailing = Presets.countries.size.toString() + " 个",
            )
            Spacer(Modifier.height(16.dp))
            DshTextField(
                value = query,
                onValueChange = { query = it },
                label = "搜索国家、地区或 MCC",
            )
            Spacer(Modifier.height(14.dp))
            PickerTabs(tab = tab, onTab = { tab = it })

            Spacer(Modifier.height(6.dp))
            if (list.isEmpty()) {
                Spacer(Modifier.height(28.dp))
                Text(
                    text = if (keyword.isEmpty()) "最近还没有用过" else "没有匹配的国家或 MCC",
                    style = type.caption,
                    color = colors.inkFaint,
                )
            } else {
                LazyColumn(Modifier.weight(1f)) {
                    items(list, key = { it.code }) { item ->
                        PickerRow(
                            item = item,
                            isCurrent = item.code.equals(currentIso, ignoreCase = true),
                            onClick = { onPick(item) },
                        )
                        DshDivider()
                    }
                }
            }

            Spacer(Modifier.height(10.dp))
            Text(
                text = "国家码只改变系统对运营商国家的判断，不会改变网络注册与信号；双卡可分别设置。",
                style = type.caption,
                color = colors.inkFaint,
            )
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun PickerTabs(tab: PickerTab, onTab: (PickerTab) -> Unit) {
    val colors = Dsh.colors
    val type = Dsh.type
    val hasPanels = Dsh.surface.hasPanels
    val shape = RoundedCornerShape(if (hasPanels) Dsh.dimens.controlRadius else 0.dp)

    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .border(1.dp, colors.hairline, shape),
    ) {
        PickerTab.values().forEachIndexed { index, item ->
            if (index > 0) {
                Box(Modifier.width(1.dp).height(40.dp).background(colors.hairline))
            }
            val selected = item == tab
            Box(
                Modifier
                    .weight(1f)
                    .dshClickable(scaleOnPress = false) { onTab(item) }
                    .background(if (selected) dshFieldFill(1f) else Color.Transparent)
                    .padding(vertical = 11.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = item.title,
                    style = if (selected) type.rowTitle else type.caption,
                    color = if (selected) colors.ink else colors.inkFaint,
                )
            }
        }
    }
}

@Composable
private fun PickerRow(item: CountryPreset, isCurrent: Boolean, onClick: () -> Unit) {
    val colors = Dsh.colors
    val type = Dsh.type
    val hasPanels = Dsh.surface.hasPanels

    Box(Modifier.fillMaxWidth().dshClickable(onClick = onClick)) {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CodeBox(item.code)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(item.label, style = type.rowTitle, color = colors.ink)
                    if (isCurrent) {
                        Spacer(Modifier.width(8.dp))
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(2.dp))
                                .background(if (hasPanels) colors.accentDim else colors.accent)
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                        ) {
                            Text(
                                text = "当前",
                                style = type.label,
                                color = if (hasPanels) colors.accent else colors.accentInk,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = listOfNotNull(Presets.representativeCarrier(item.code), item.mcc)
                        .joinToString(" · "),
                    style = type.monoSmall,
                    color = colors.inkFaint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(10.dp))
            Text(item.mcc, style = type.mono, color = colors.inkMuted)
        }
    }
}