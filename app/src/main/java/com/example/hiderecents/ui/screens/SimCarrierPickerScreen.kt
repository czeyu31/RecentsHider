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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.hiderecents.sim.CountryPreset
import com.example.hiderecents.sim.Presets
import com.example.hiderecents.ui.components.DshDivider
import com.example.hiderecents.ui.components.DshTextField
import com.example.hiderecents.ui.components.dshCanvasBackground
import com.example.hiderecents.ui.components.dshClickable
import com.example.hiderecents.ui.theme.Dsh

/**
 * 运营商名选择器。
 *
 * 提供的预设按所选国家过滤；末尾固定一条「自定义」，
 * 允许直接输入列表里没有的运营商名。
 */
@Composable
fun SimCarrierPickerScreen(
    country: CountryPreset?,
    current: String,
    onPick: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Dsh.colors
    val dimens = Dsh.dimens
    val type = Dsh.type

    val presets = remember(country?.code) {
        country?.let { Presets.carriersOf(it.code) }.orEmpty()
    }
    var custom by remember { mutableStateOf(if (current in presets.map { it.label }) "" else current) }

    Box(modifier.fillMaxSize().dshCanvasBackground()) {
        Column(
            Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(horizontal = dimens.screenPadding),
        ) {
            SimPageHeader(
                subtitle = "运营商名 · CARRIER NAME",
                title = "选择运营商",
                onBack = onBack,
                trailing = country?.label ?: "未选国家",
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = if (country == null) {
                    "先选择目标国家，运营商列表会按地区过滤。"
                } else {
                    "下列是该地区的运营商预设，也可以直接输入。"
                },
                style = type.body,
                color = colors.inkMuted,
            )
            Spacer(Modifier.height(14.dp))
            DshTextField(
                value = custom,
                onValueChange = { custom = it },
                label = "自定义运营商名",
            )
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier
                    .clip(RoundedCornerShape(if (Dsh.surface.hasPanels) dimens.controlRadius else 0.dp))
                    .border(
                        1.dp,
                        colors.hairline,
                        RoundedCornerShape(if (Dsh.surface.hasPanels) dimens.controlRadius else 0.dp),
                    )
                    .dshClickable { onPick(custom.trim()) }
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
            ) {
                Text(
                    text = if (custom.isBlank()) "使用自定义（留空则不覆盖）" else "使用「" + custom.trim() + "」",
                    style = type.caption,
                    color = colors.inkMuted,
                )
            }

            Spacer(Modifier.height(18.dp))
            SectionLabel("预设运营商", presets.size.toString() + " 个")
            Spacer(Modifier.height(2.dp))

            if (presets.isEmpty()) {
                Spacer(Modifier.height(20.dp))
                Text("该地区还没有预设，请用上面的自定义输入", style = type.caption, color = colors.inkFaint)
            } else {
                LazyColumn(Modifier.weight(1f)) {
                    items(presets, key = { it.label }) { item ->
                        Box(Modifier.fillMaxWidth().dshClickable { onPick(item.label) }) {
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(item.label, style = type.rowTitle, color = colors.ink)
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        text = country?.label.orEmpty() + " · " + item.country,
                                        style = type.monoSmall,
                                        color = colors.inkFaint,
                                    )
                                }
                                if (item.label == current) {
                                    Text("当前", style = type.label, color = colors.accent)
                                }
                            }
                        }
                        DshDivider()
                    }
                }
            }

            Spacer(Modifier.height(10.dp))
            Text(
                text = "部分应用按运营商名判定地区；填上目标地区的运营商更容易生效。",
                style = type.caption,
                color = colors.inkFaint,
            )
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun SectionLabel(title: String, trailing: String?) {
    val colors = Dsh.colors
    val type = Dsh.type
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = type.label, color = colors.inkFaint)
        Spacer(Modifier.weight(1f))
        if (trailing != null) {
            Text(trailing, style = type.label, color = colors.inkFaint.copy(alpha = 0.6f))
        }
    }
}