@file:OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)

package com.example.hiderecents.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.example.hiderecents.R

/**
 * 等宽字族：Roboto Mono。
 *
 * 选它而不是 JetBrains Mono 的原因很具体：JBM 的零是「点状零」（oval + 中心点），
 * 在 31sp 的大数字上看起来像字符坏掉了。Roboto Mono 的零是干净的椭圆。
 *
 * 每个字重都显式给 variationSettings —— 这是可变字体的关键：
 * 只传 weight 而不传字重轴，Compose 拿不到轴值，所有字重会塌到同一个默认实例。
 */
private fun mono(weight: Int): Font = Font(
    resId = R.font.roboto_mono,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

private val MonoFamily = FontFamily(
    mono(300),
    mono(400),
    mono(500),
    mono(600),
)

/**
 * 正文与标题字族：系统字族。
 *
 * 这里的取舍要说清楚：原先用 Outfit，但 Outfit 不含 CJK 字形，
 * 中文走系统回退时请求的字重不生效，渲染出来是发丝级字重、整体发虚。
 * 这套界面 95% 的字是中文，所以中文清晰度优先 —— 拉丁字母也一并交给系统字族，
 * 由 MiSans / Roboto 按显式字重渲染。Outfit 仍保留在 res/font 里备用。
 */
private val SansFamily = FontFamily.Default

/**
 * 中式衬线字族（纸本专用）。
 *
 * 用系统 serif 而不是打包字体：MIUI / AOSP 的 serif 在中文上解析为 Noto Serif CJK，
 * 正是宋体骨架（横细竖粗、起笔有顿），符合纸本的气质；
 * 打包一套思源宋体会让 APK 增加 8~10MB，对一个工具类应用不划算。
 *
 * 只给纸本的 display / number / numberSmall 用，石墨仍走无衬线。
 */
private val SerifFamily = FontFamily.Serif

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
        fontFamily = SerifFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 44.sp,
        lineHeight = 48.sp,
        letterSpacing = (-1.4f).sp,
    ),
    h1 = TextStyle(
        fontFamily = SansFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 32.sp,
        lineHeight = 37.sp,
        letterSpacing = (-1f).sp,
    ),
    h2 = TextStyle(
        fontFamily = SansFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 23.sp,
        lineHeight = 29.sp,
        letterSpacing = (-0.6f).sp,
    ),
    title = TextStyle(
        fontFamily = SansFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 16.5.sp,
        lineHeight = 22.sp,
        letterSpacing = (-0.1f).sp,
    ),
    rowTitle = TextStyle(
        fontFamily = SansFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 14.5.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.sp,
    ),
    body = TextStyle(
        fontFamily = SansFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 21.sp,
        letterSpacing = 0.sp,
    ),
    caption = TextStyle(
        fontFamily = SansFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.sp,
    ),
    label = TextStyle(
        fontFamily = MonoFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 9.5.sp,
        lineHeight = 13.sp,
        letterSpacing = 1.0.sp,
    ),
    mono = TextStyle(
        fontFamily = MonoFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 17.sp,
    ),
    monoSmall = TextStyle(
        fontFamily = MonoFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 10.sp,
        lineHeight = 15.sp,
        letterSpacing = 0.2.sp,
    ),
    number = TextStyle(
        fontFamily = SerifFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 34.sp,
        fontFeatureSettings = "tnum",
        lineHeight = 36.sp,
        letterSpacing = (-0.8f).sp,
    ),
    numberSmall = TextStyle(
        fontFamily = SerifFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 27.sp,
        fontFeatureSettings = "tnum",
        lineHeight = 29.sp,
        letterSpacing = (-0.6f).sp,
    ),
)

val GraphiteType = DshType(
    display = TextStyle(
        fontFamily = SansFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 32.sp,
        lineHeight = 37.sp,
        letterSpacing = (-1f).sp,
    ),
    h1 = TextStyle(
        fontFamily = SansFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 31.sp,
        lineHeight = 36.sp,
        letterSpacing = (-0.9f).sp,
    ),
    h2 = TextStyle(
        fontFamily = SansFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 23.sp,
        lineHeight = 29.sp,
        letterSpacing = (-0.6f).sp,
    ),
    title = TextStyle(
        fontFamily = SansFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 14.5.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.sp,
    ),
    rowTitle = TextStyle(
        fontFamily = SansFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 13.5.sp,
        lineHeight = 19.sp,
        letterSpacing = 0.sp,
    ),
    body = TextStyle(
        fontFamily = SansFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 12.5.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.sp,
    ),
    caption = TextStyle(
        fontFamily = SansFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.sp,
    ),
    label = TextStyle(
        fontFamily = MonoFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 9.5.sp,
        lineHeight = 13.sp,
        letterSpacing = 1.0.sp,
    ),
    mono = TextStyle(
        fontFamily = MonoFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 17.sp,
    ),
    monoSmall = TextStyle(
        fontFamily = MonoFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 10.sp,
        lineHeight = 15.sp,
        letterSpacing = 0.2.sp,
    ),
    number = TextStyle(
        fontFamily = SansFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 31.sp,
        fontFeatureSettings = "tnum",
        lineHeight = 33.sp,
        letterSpacing = (-0.8f).sp,
    ),
    numberSmall = TextStyle(
        fontFamily = SansFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 24.sp,
        fontFeatureSettings = "tnum",
        lineHeight = 26.sp,
        letterSpacing = (-0.6f).sp,
    ),
)

val LocalDshType = staticCompositionLocalOf { PaperType }
