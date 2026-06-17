package com.example.hiderecents

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

class MemoryRingView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private var progress = 0f
    private val strokeWidth = 6f * resources.displayMetrics.density

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = this@MemoryRingView.strokeWidth
        color = 0xFF353535.toInt()
    }

    private val fgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = this@MemoryRingView.strokeWidth
        color = 0xFFB0C6FF.toInt()
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFC2C6D7.toInt()
        textSize = 9f * resources.displayMetrics.scaledDensity
        textAlign = Paint.Align.CENTER
    }

    private val rect = RectF()

    fun setProgress(pct: Int) {
        progress = pct.toFloat()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val pad = strokeWidth / 2f
        rect.set(pad, pad, width - pad, height - pad)

        canvas.drawArc(rect, 0f, 360f, false, bgPaint)
        val sweep = (progress * 3.6f / 1.5f).coerceAtMost(360f)
        canvas.drawArc(rect, -90f, sweep, false, fgPaint)

        val cx = width / 2f
        val cy = height / 2f + textPaint.textSize / 3f
        canvas.drawText("Memory", cx, cy, textPaint)
    }
}
