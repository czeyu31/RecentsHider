package com.example.hiderecents

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

class HeartView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFE53935.toInt()
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
        typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD)
        setShadowLayer(20f, 0f, 0f, 0x80E53935.toInt())
    }

    private var bpm = 0

    fun setBpm(value: Int) { bpm = value; postInvalidateOnAnimation() }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f; val cy = height / 2f
        if (bpm > 0) {
            textPaint.textSize = minOf(width, height) * 0.5f
            canvas.drawText(bpm.toString(), cx, cy + textPaint.textSize * 0.35f, textPaint)
        } else {
            textPaint.textSize = minOf(width, height) * 0.2f
            canvas.drawText("--", cx, cy + textPaint.textSize * 0.35f, textPaint)
        }
    }
}
