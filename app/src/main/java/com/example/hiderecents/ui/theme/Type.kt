package com.example.hiderecents.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.example.hiderecents.R

private val OutfitFamily = FontFamily(
    Font(R.font.outfit, FontWeight.Light),
    Font(R.font.outfit, FontWeight.Normal),
    Font(R.font.outfit, FontWeight.Medium),
    Font(R.font.outfit, FontWeight.SemiBold),
)

private val MonoFamily = FontFamily(
    Font(R.font.jetbrains_mono, FontWeight.Light),
    Font(R.font.jetbrains_mono, FontWeight.Normal),
    Font(R.font.jetbrains_mono, FontWeight.Medium),
)

@Immutable
data class DshType(
    /** 首页状态主标题 */
    val display: TextStyle,
    /** 次级大标题 */
    val h1: TextStyle,
    /** 页面标题 */
    val h2: TextStyle,
    /** 区块标题 */
    val title: TextStyle,
    /** 列表主行 */
    val rowTitle: TextStyle,
    /** 正文 */
    val body: TextStyle,
    /** 元信息 */
    val caption: TextStyle,
    /** 全大写等宽标签，字距 0.2em */
    val label: TextStyle,
    /** 等宽正文（包名、命令、地址） */
    val mono: TextStyle,
    val monoSmall: TextStyle,
    /** 大数字 */
    val number: TextStyle,
    val numberSmall: TextStyle,
)

val PaperType = DshType(
    display = TextStyle(
        fontFamily = OutfitFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 44.sp,
        lineHeight = 46.sp,
        letterSpacing = (-1.6f).sp,
    ),
    h1 = TextStyle(
        fontFamily = OutfitFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 32.sp,
        lineHeight = 35.sp,
        letterSpacing = (-1.1f).sp,
    ),
    h2 = TextStyle(
        fontFamily = OutfitFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 23.sp,
        lineHeight = 27.sp,
        letterSpacing = (-0.7f).sp,
    ),
    title = TextStyle(
        fontFamily = OutfitFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 16.5.sp,
        lineHeight = 21.sp,
        letterSpacing = (-0.2f).sp,
    ),
    rowTitle = TextStyle(
        fontFamily = OutfitFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 14.5.sp,
        lineHeight = 19.sp,
        letterSpacing = (-0.1f).sp,
    ),
    body = TextStyle(
        fontFamily = OutfitFamily,
        fontWeight = FontWeight.Light,
        fontSize = 13.sp,
        lineHeight = 20.sp,
    ),
    caption = TextStyle(
        fontFamily = OutfitFamily,
        fontWeight = FontWeight.Light,
        fontSize = 10.5.sp,
        lineHeight = 15.sp,
    ),
    label = TextStyle(
        fontFamily = MonoFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 9.5.sp,
        lineHeight = 13.sp,
        letterSpacing = 1.9.sp,
    ),
    mono = TextStyle(
        fontFamily = MonoFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
    ),
    monoSmall = TextStyle(
        fontFamily = MonoFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 10.sp,
        lineHeight = 14.sp,
        letterSpacing = 0.2.sp,
    ),
    number = TextStyle(
        fontFamily = MonoFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 34.sp,
        lineHeight = 34.sp,
        letterSpacing = (-1f).sp,
    ),
    numberSmall = TextStyle(
        fontFamily = MonoFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 27.sp,
        lineHeight = 27.sp,
        letterSpacing = (-0.8f).sp,
    ),
)

val GraphiteType = DshType(
    display = TextStyle(
        fontFamily = OutfitFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 32.sp,
        lineHeight = 33.sp,
        letterSpacing = (-1.1f).sp,
    ),
    h1 = TextStyle(
        fontFamily = OutfitFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 31.sp,
        lineHeight = 33.sp,
        letterSpacing = (-1f).sp,
    ),
    h2 = TextStyle(
        fontFamily = OutfitFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 23.sp,
        lineHeight = 27.sp,
        letterSpacing = (-0.7f).sp,
    ),
    title = TextStyle(
        fontFamily = OutfitFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 14.5.sp,
        lineHeight = 19.sp,
        letterSpacing = (-0.1f).sp,
    ),
    rowTitle = TextStyle(
        fontFamily = OutfitFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 13.5.sp,
        lineHeight = 18.sp,
        letterSpacing = (-0.05f).sp,
    ),
    body = TextStyle(
        fontFamily = OutfitFamily,
        fontWeight = FontWeight.Light,
        fontSize = 12.5.sp,
        lineHeight = 19.sp,
    ),
    caption = TextStyle(
        fontFamily = OutfitFamily,
        fontWeight = FontWeight.Light,
        fontSize = 10.5.sp,
        lineHeight = 15.sp,
    ),
    label = TextStyle(
        fontFamily = MonoFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 9.5.sp,
        lineHeight = 13.sp,
        letterSpacing = 1.9.sp,
    ),
    mono = TextStyle(
        fontFamily = MonoFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
    ),
    monoSmall = TextStyle(
        fontFamily = MonoFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 10.sp,
        lineHeight = 14.sp,
        letterSpacing = 0.2.sp,
    ),
    number = TextStyle(
        fontFamily = MonoFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 31.sp,
        lineHeight = 31.sp,
        letterSpacing = (-1f).sp,
    ),
    numberSmall = TextStyle(
        fontFamily = MonoFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 24.sp,
        lineHeight = 24.sp,
        letterSpacing = (-0.8f).sp,
    ),
)

val LocalDshType = staticCompositionLocalOf { PaperType }
