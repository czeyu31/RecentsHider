package com.example.hiderecents

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View

class GaugeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val paintBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 12f
        color = Color.parseColor("#353535")
        strokeCap = Paint.Cap.ROUND
    }

    private val paintFg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 12f
        color = Color.parseColor("#558DFF")
        strokeCap = Paint.Cap.ROUND
    }

    private val paintText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 48f
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }

    private val paintUnit = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 24f
        color = Color.parseColor("#C2C6D7")
        textAlign = Paint.Align.CENTER
    }

    private var progress = 0f
    private var maxValue = 100f
    private var currentValue = 0f
    private var accentColor = Color.parseColor("#558DFF")

    fun setAccentColor(color: Int) {
        accentColor = color
        paintFg.color = color
        invalidate()
    }

    fun setProgress(value: Float, max: Float = 100f) {
        currentValue = value
        maxValue = max
        progress = (value / max).coerceIn(0f, 1f)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()
        val strokeWidth = paintBg.strokeWidth
        val radius = (minOf(w, h) - strokeWidth) / 2f - 10f
        val cx = w / 2f
        val cy = h - 20f

        // Background arc
        val bgRect = RectF(cx - radius, cy - radius, cx + radius, cy + radius)
        canvas.drawArc(bgRect, -200f, 220f, false, paintBg)

        // Foreground arc
        val sweepAngle = 220f * progress
        canvas.drawArc(bgRect, -200f, sweepAngle, false, paintFg)

        // Value text
        val valueText = if (currentValue >= 100) {
            String.format("%.0f", currentValue)
        } else if (currentValue >= 10) {
            String.format("%.1f", currentValue)
        } else {
            String.format("%.2f", currentValue)
        }
        paintText.textSize = w * 0.18f
        canvas.drawText(valueText, cx, cy - radius * 0.3f, paintText)

        // Unit text
        paintUnit.textSize = w * 0.08f
        canvas.drawText("Mbps", cx, cy - radius * 0.05f, paintUnit)
    }
}
