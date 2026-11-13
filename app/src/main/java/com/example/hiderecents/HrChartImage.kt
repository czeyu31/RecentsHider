package com.example.hiderecents

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import com.example.hiderecents.hr.HrSession
import com.example.hiderecents.hr.clockOf
import com.example.hiderecents.hr.dayOf
import com.example.hiderecents.hr.hm
import com.example.hiderecents.ui.theme.AppTheme
import java.io.File
import java.io.FileOutputStream
import kotlin.math.roundToInt

/**
 * 把一次会话画成一张 PNG，用于「分享曲线图」。
 *
 * 用 android.graphics 直接绘制（不依赖 Compose 离屏渲染），配色跟当前主题一致：
 * 纸本是米色底 + 墨黑 + 砖红，石墨是深灰底 + 冷白 + 薄荷绿。
 */
object HrChartImage {

    private class Palette(
        val bg: Int,
        val ink: Int,
        val muted: Int,
        val accent: Int,
        val hairline: Int,
    )

    fun render(context: Context, s: HrSession, theme: AppTheme): File {
        val dark = theme == AppTheme.GRAPHITE
        val p = if (dark) {
            Palette(
                bg = 0xFF0F1214.toInt(),
                ink = 0xFFE7EBEA.toInt(),
                muted = 0xFF8A9492.toInt(),
                accent = 0xFF8FB3A4.toInt(),
                hairline = 0x33FFFFFF,
            )
        } else {
            Palette(
                bg = 0xFFF1EDE3.toInt(),
                ink = 0xFF151410.toInt(),
                muted = 0xFF7A736A.toInt(),
                accent = 0xFFAE4327.toInt(),
                hairline = 0x22000000,
            )
        }

        val w = 1080
        val h = 760
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(p.bg)

        val sans = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        val bold = Typeface.create(Typeface.SERIF, Typeface.BOLD)

        val title = Paint().apply {
            color = p.ink
            textSize = 46f
            typeface = bold
            isAntiAlias = true
        }
        c.drawText("心率会话", 72f, 104f, title)

        val sub = Paint().apply {
            color = p.muted
            textSize = 28f
            typeface = sans
            isAntiAlias = true
        }
        val end = s.startedAt + s.durationSec * 1000L
        c.drawText(
            dayOf(s.startedAt) + "  " + hm(s.startedAt) + " – " + hm(end) +
                "  ·  " + clockOf(s.durationSec),
            72f,
            152f,
            sub,
        )

        val avg = s.values.average().roundToInt()
        val min = s.values.minOrNull() ?: 0
        val max = s.values.maxOrNull() ?: 0

        val big = Paint().apply {
            color = p.ink
            textSize = 96f
            typeface = bold
            isAntiAlias = true
        }
        c.drawText(avg.toString(), 72f, 288f, big)
        val unit = Paint().apply {
            color = p.muted
            textSize = 30f
            typeface = sans
            isAntiAlias = true
        }
        c.drawText("平均 bpm   $min – $max", 260f, 280f, unit)

        // 曲线区
        val left = 72f
        val right = (w - 72).toFloat()
        val top = 360f
        val bottom = 620f
        val lo = (min - 6).coerceAtLeast(40)
        val hi = (max + 6).coerceAtLeast(110)
        val span = (hi - lo).coerceAtLeast(1).toFloat()

        fun yOf(v: Int): Float = bottom - (v - lo) / span * (bottom - top)

        val grid = Paint().apply {
            color = p.hairline
            strokeWidth = 2f
            pathEffect = DashPathEffect(floatArrayOf(10f, 10f), 0f)
            isAntiAlias = true
        }
        c.drawLine(left, yOf(60), right, yOf(60), grid)
        c.drawLine(left, yOf(100), right, yOf(100), grid)

        val values = s.values
        if (values.size >= 2) {
            val step = (right - left) / (values.size - 1)
            val path = Path()
            values.forEachIndexed { i, v ->
                val x = left + i * step
                val y = yOf(v)
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            val line = Paint().apply {
                color = p.accent
                strokeWidth = 5f
                style = Paint.Style.STROKE
                strokeJoin = Paint.Join.ROUND
                strokeCap = Paint.Cap.ROUND
                isAntiAlias = true
            }
            c.drawPath(path, line)
        }

        val axis = Paint().apply {
            color = p.muted
            textSize = 26f
            typeface = sans
            isAntiAlias = true
        }
        c.drawText(hm(s.startedAt), left, bottom + 44f, axis)
        c.drawText(hm(s.startedAt + s.durationSec * 500L), w / 2f - 40f, bottom + 44f, axis)
        val endText = hm(end)
        c.drawText(endText, right - axis.measureText(endText), bottom + 44f, axis)

        val foot = Paint().apply {
            color = p.muted
            textSize = 24f
            typeface = sans
            isAntiAlias = true
        }
        c.drawText("非医疗设备，数据仅供运动参考 · 系统工具", 72f, h - 44f, foot)

        val file = File(context.cacheDir, "hr_chart_" + s.id + ".png")
        FileOutputStream(file).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bmp.recycle()
        return file
    }
}
