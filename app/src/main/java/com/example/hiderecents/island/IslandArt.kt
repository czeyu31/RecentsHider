package com.example.hiderecents.island

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.ui.graphics.toArgb
import com.example.hiderecents.ui.theme.AppTheme
import com.example.hiderecents.ui.theme.GraphiteDesign
import com.example.hiderecents.ui.theme.PaperDesign

/**
 * 标准实时活动右侧那块像素图案。
 *
 * 图案是"字符网格"：一行一个字符串，每个字符查调色板得到颜色，放大后就是像素画。
 * 网格尺寸不固定（16×16 到 36×35 都行），绘制时按最长边等分并居中，所以宽高不等也不会变形。
 *
 * 两种上色方式：
 *  · [Art.palette] 非空 —— 图案自带配色（图腾、月薪喵），不跟主题走；
 *  · [Art.palette] 为空 —— 用 `#` 主色 + `+` 高光，主色取岛色（温度计）。
 */
object IslandArt {

    /** 一块图案：id 用于存设置，label 给人看 */
    data class Art(
        val id: String,
        val label: String,
        val pattern: Array<String>,
        /** null 表示跟着岛色走（`#` 主色、`+` 高光） */
        val palette: Map<Char, Int>?,
    )

    /** 岛色：null 表示跟随 app 主题 */
    data class Tint(val id: String, val label: String, val color: Int?)

    private val TINTS = listOf(
        Tint("theme", "跟随主题", null),
        Tint("blue", "蓝", 0xFF3B82F6.toInt()),
        Tint("sky", "天蓝", 0xFF38BDF8.toInt()),
        Tint("indigo", "靛", 0xFF6366F1.toInt()),
        Tint("purple", "紫", 0xFF8B5CF6.toInt()),
        Tint("pink", "粉", 0xFFF472B6.toInt()),
        Tint("rose", "玫", 0xFFFB7185.toInt()),
        Tint("red", "红", 0xFFEF4444.toInt()),
        Tint("orange", "橙", 0xFFFB923C.toInt()),
        Tint("amber", "琥珀", 0xFFFBBF24.toInt()),
        Tint("lime", "青柠", 0xFFA3E635.toInt()),
        Tint("green", "绿", 0xFF4ADE80.toInt()),
        Tint("teal", "青", 0xFF2DD4BF.toInt()),
        Tint("white", "白", 0xFFFFFFFF.toInt()),
        Tint("slate", "灰", 0xFF94A3B8.toInt()),
    )

    /** 高光：跟随岛色那一类图案用的浅色块 */
    private val LIGHT = 0xFFF2F0EA.toInt()

    // ---------------------------------------------------------------- 图案

    /**
     * 不死图腾（Minecraft）。每行以中线严格镜像，避免两边长度不一。
     */
    private val TOTEM = arrayOf(
        "................",
        "....111111......",
        "...13333331.....",
        "...23366332.....",
        "...24433442.....",
        "...26744762.....",
        "...27866872.....",
        "...25433452.....",
        ".111253352111...",
        ".134234432431...",
        "..2213663122....",
        "...13444431.....",
        "...21111112.....",
        "....245542......",
        "....245542......",
        ".....2222.......",
    )

    /** 图纸色号：G7 棕、F10 深棕、A21 浅黄、G4 米色、G10 橙棕、H18 近白、B5/R5 绿 */
    private val TOTEM_COLORS = mapOf(
        '1' to 0xFF8B5E3C.toInt(),
        '2' to 0xFF6B3B24.toInt(),
        '3' to 0xFFF5E6A8.toInt(),
        '4' to 0xFFE3CDA0.toInt(),
        '5' to 0xFFCE9A4C.toInt(),
        '6' to 0xFFF8F4EA.toInt(),
        '7' to 0xFF8CC152.toInt(),
        '8' to 0xFF7CB342.toInt(),
    )

    /**
     * 月薪喵（原图纸 36×35 拼豆，4 色 1260 颗）。
     *
     * H 白、M 米、F 深棕、C 蓝，照图纸逐格提取，颗数与图纸标注一致。
     * 已把外围那圈 H 白底清掉（否则整块画出来像贴了一张白纸），并裁到图案本体，剩下 23×23。
     */
    private val NEKO = arrayOf(
        ".....FF................",
        "....FMMF........FF.....",
        "....FMMFFFFFFFFFMMF....",
        "...FMMMMMMMMMMMMMMF....",
        "...FMMMMMMMMMMMMMMF....",
        "..FMMMMMMHHMMMMMMMF....",
        ".FMMMMMCHHHHCMMMMMFF...",
        "FMMMMMMCHHHHCMMMMMMMF..",
        "FMMMMMFFFFHHCMMMMMMMMF.",
        "FMMMMFFHHFHHCMMMMMMMMF.",
        ".FMMFHHHFHHHHHMMMMMMMF.",
        ".FMMFHHFHHHFFFHMMMMFF..",
        "..FFFHFHHHHFHHFHMMF....",
        "...FHHFHHHHFHHHHFFF....",
        "...FHHHHHHHHFHHHF......",
        "...FHHHHHHHHHFFHF......",
        "...FHHHHHHHHHHHHF......",
        "...FHHHHHHHHHHHHF......",
        "...FHHHHHHHHHHHHFF..FF.",
        "...FHHHHHHHHHHHHHFFFHHF",
        "...FHHHHHHHHHHHHHHFHHF.",
        "...FHHFFFFFFFFHHHFFFF..",
        "....FF........FFF......",
    )

    /** 月薪喵的图纸配色 */
    private val NEKO_COLORS = mapOf(
        'H' to 0xFFFDFDFC.toInt(),
        'M' to 0xFFDDD3BD.toInt(),
        'F' to 0xFF684233.toInt(),
        'C' to 0xFF374978.toInt(),
    )

    /**
     * 和平鸽（31×35 拼豆图纸，8 色 566 颗）。
     *
     * 深蓝 C12 勾轮廓，中浅蓝 C23/C3 做翅膀阴影，白 H18/H17 是身体，橙色 A 是喙和脚。
     * 逐格提取后各类颗数与图纸标注完全一致（135/98/48/224/54/7），裁到 29×33。
     */
    private val DOVE = arrayOf(
        "...111.................111...",
        "..1551................1251...",
        "..15551..............15551...",
        "..15551.............13551....",
        "..1255511..........152521....",
        "..13555551........1252521....",
        "..124555551......13254531....",
        "..1525555551.....15555531....",
        "..15445555551....1.255511....",
        "..135435555551..13525551.....",
        "..1533232552551112252551.....",
        "..1555422322555513222451.....",
        "..1345555542225513542421.....",
        "...1332555522245413424111....",
        "...15333333322555132215551...",
        "....15553335224555111555551..",
        "....122555552225551155515511.",
        ".....144555532255555555555661",
        "......13223442225555555531111",
        "......14554542224555555431...",
        ".......113244424555555531....",
        "........1113334555555541.....",
        "..........14455555555441.....",
        "....11111154555555555421.....",
        "...15555555555555554221......",
        ".1125555555552442222221......",
        "1455224444226662232211.......",
        "15235544443216111111.........",
        "124553344322161..............",
        ".123254223221................",
        "..1354222221.................",
        "...122212211.................",
        "...11111111..................",
    )

    private val DOVE_COLORS = mapOf(
        '1' to 0xFF22334B.toInt(),   // C12 深蓝
        '2' to 0xFFD5ECF3.toInt(),   // C23
        '3' to 0xFFC5EBF9.toInt(),   // C3
        '4' to 0xFFF0F2F0.toInt(),   // H17
        '5' to 0xFFFEFFFA.toInt(),   // H18
        '6' to 0xFFF3C657.toInt(),   // A 橙黄
    )

    /**
     * 摸头（43×44 拼豆图纸，7 色 1220 颗）。
     *
     * H7 黑勾轮廓和斑点、H2 纯白是脸、F14 粉是两颊、G1/G3/E11/G9 四档米色叠出帽子和阴影。
     * 提取后各类颗数与图纸一致，去掉图外水印后裁到 41×42。
     */
    private val PET = arrayOf(
        ".............................1111111.....",
        "...................111111111177777711....",
        "................111767777676777777761....",
        "..............11667677777677677677771....",
        "............11.71.67177777767677777441...",
        "..........1177717611777777777764444441...",
        ".........147771.71.7677744777744444441...",
        "........141741741777744411777444444411...",
        ".......141641.41667441111666444111111....",
        ".......11741741777411221777444122211.....",
        "......11741671774412221.7744112222211....",
        ".....122111741744122222444112222222211...",
        "....122222111.4412222214412222222222211..",
        "...122222222211122222221122222222222271..",
        "..1222222222222222222222222222222222221..",
        "..12222222222222222222222222222222222221.",
        ".122222222222222222222222222222222222221.",
        ".1222222222222222222222221112222222222211",
        "11222222222222222222222211111222222222261",
        "12222222222222222222222111233333222222751",
        "12222222211112222222221122333333322222751",
        "12222222111111222222221222333333322222751",
        "12222333332221122222222222333333322227751",
        "12223333333222222222222222233333222226551",
        "12223333333222222222222212222222222277551",
        "11222333333221222222222212222222222775511",
        ".122223333222122211222212222222272777551.",
        ".122222222222211122111122222222227775511.",
        "..1222222222222222222222222222226675551..",
        "..1722222222222222777222222222277755511..",
        "...16222222222222222222222227777755511...",
        "....122222222222222222222227777755511....",
        ".....1127222222222222272777777555511.....",
        "......11162222222222267667765555111......",
        "........1117777777767755555555111........",
        "..........111155555555555551111..........",
        ".............111111111115511.............",
        ".......................1551..............",
        "......................2115512............",
        "........................1151.............",
        ".........................1151............",
        "..........................1111...........",
    )

    private val PET_COLORS = mapOf(
        '1' to 0xFF010201.toInt(),   // H7 纯黑
        '2' to 0xFFFEFEFE.toInt(),   // H2 纯白
        '3' to 0xFFEEB2B2.toInt(),   // F14 粉
        '4' to 0xFFD9B88F.toInt(),   // G9 深米
        '5' to 0xFFE7C7AD.toInt(),   // G3 中米
        '6' to 0xFFF2E0D3.toInt(),   // E11 浅米
        '7' to 0xFFF6E5D2.toInt(),   // G1 米白
    )

    /** 可选的图案；加新图案只要在这里多一条 */
    val ARTS: List<Art> = listOf(
        Art("totem", "不死图腾", TOTEM, TOTEM_COLORS),
        Art("neko", "月薪喵", NEKO, NEKO_COLORS),
        Art("dove", "和平鸽", DOVE, DOVE_COLORS),
        Art("pet", "摸头", PET, PET_COLORS),
    )

    val TINT_OPTIONS: List<Tint> get() = TINTS

    fun artOf(id: String?): Art = ARTS.firstOrNull { it.id == id } ?: ARTS.first()

    private val cache = HashMap<String, Bitmap>()

    /** 画一个图案；px 是边长，同尺寸同图案同色只画一次 */
    fun bitmap(context: Context, artId: String?, px: Int, tintId: String? = "theme"): Bitmap {
        val art = artOf(artId)
        val tint = tintColor(context, tintId)
        val key = art.id + "|" + px + "|" + tint
        cache[key]?.let { return it }
        val bmp = render(art.pattern, px, art.palette ?: mapOf('#' to tint, '+' to LIGHT))
        cache[key] = bmp
        return bmp
    }

    /** 岛色：选了具体色就用它，选"跟随主题"则纸本用纸光、石墨用雾玉 */
    fun tintColor(context: Context, tintId: String? = "theme"): Int {
        TINTS.firstOrNull { it.id == tintId }?.color?.let { return it }
        val prefs = context.getSharedPreferences("hide_recents_prefs", Context.MODE_PRIVATE)
        val theme = AppTheme.fromPref(prefs.getString("ui_theme", null))
        return if (theme == AppTheme.GRAPHITE) {
            GraphiteDesign.colors.accent.toArgb()
        } else {
            PaperDesign.colors.canvasLift.toArgb()
        }
    }

    fun tintLabel(tintId: String?): String =
        TINTS.firstOrNull { it.id == tintId }?.label ?: TINTS.first().label

    private fun render(pattern: Array<String>, px: Int, palette: Map<Char, Int>): Bitmap {
        val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val paint = Paint()
        val rows = pattern.size
        val cols = pattern.maxOf { it.length }
        // 按最长边等分，保证格子是正方形；短的一边居中留白
        val cell = px.toFloat() / maxOf(rows, cols)
        val ox = (px - cols * cell) / 2f
        val oy = (px - rows * cell) / 2f
        pattern.forEachIndexed { row, line ->
            line.forEachIndexed { col, ch ->
                // 调色板里没有的字符就是透明的空格
                val color = palette[ch] ?: return@forEachIndexed
                paint.color = color
                // 右下各多描 0.5px：格子宽度往往不是整数，不重叠的话相邻格之间会漏出底色的细缝
                canvas.drawRect(
                    ox + col * cell,
                    oy + row * cell,
                    ox + (col + 1) * cell + 0.5f,
                    oy + (row + 1) * cell + 0.5f,
                    paint,
                )
            }
        }
        return bmp
    }
}
