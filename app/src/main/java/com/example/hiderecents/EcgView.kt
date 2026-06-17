package com.example.hiderecents

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.View
import kotlin.random.Random

class EcgView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x0FFFFFFF.toInt(); style = Paint.Style.STROKE; strokeWidth = 0.5f
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2.5f; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 7f; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }

    private val dataPoints = FloatArray(200) { 0.5f }
    private var pulseIntensity = 0f
    private var bpm = 72f
    private var running = false
    private var connected = false
    private var lastBeatTime = 0L
    private var lastPointTime = 0L
    // 状态机：0=平线等待 1=突起 2=回落
    private var state = 0
    private var stateTimer = 0L
    private var bumpValue = 0f
    private val handler = Handler(Looper.getMainLooper())
    private val pointInterval = 200L

    private val updateRunnable = object : Runnable {
        override fun run() {
            if (!running) return
            val now = System.currentTimeMillis()
            if (now - lastPointTime >= pointInterval) {
                updatePoint(now)
                lastPointTime = now
            }
            invalidate()
            handler.postDelayed(this, 8)
        }
    }

    fun setBpm(value: Int) {
        bpm = value.coerceAtLeast(30).toFloat()
        connected = value > 0
    }

    fun onHeartBeat() { pulseIntensity = 1f }

    fun start() {
        if (running) return
        running = true
        lastPointTime = System.currentTimeMillis()
        state = 0
        stateTimer = lastPointTime + (1500 + Random.nextLong() * 1500) // 1.5~3秒后第一次变化
        handler.post(updateRunnable)
    }

    fun stop() { running = false; handler.removeCallbacks(updateRunnable) }

    private fun updatePoint(now: Long) {
        for (i in 0 until dataPoints.size - 1) dataPoints[i] = dataPoints[i + 1]

        if (!connected) {
            when (state) {
                0 -> { // 平线
                    dataPoints[dataPoints.size - 1] = 0.5f
                    if (now > stateTimer) {
                        state = 1
                        bumpValue = when (Random.nextInt(4)) {
                            0 -> 0.15f; 1 -> -0.15f; 2 -> 0.22f; else -> -0.22f
                        }
                    }
                }
                1 -> { // 突起（1个点）
                    dataPoints[dataPoints.size - 1] = 0.5f + bumpValue
                    state = 2
                }
                2 -> { // 回落（1个点）
                    dataPoints[dataPoints.size - 1] = 0.5f + bumpValue * 0.2f
                    state = 0
                    stateTimer = now + (1500 + Random.nextLong() * 1500) // 再等1.5~3秒
                }
            }
            return
        }

        // 连接后：真实心率
        val beatInterval = (60000f / bpm).toLong()
        if (now - lastBeatTime > beatInterval) { lastBeatTime = now; pulseIntensity = 1f }

        pulseIntensity *= 0.90f
        if (pulseIntensity < 0.01f) pulseIntensity = 0f

        val noise = (Random.nextFloat() - 0.5f) * 0.008f
        val wave = if (pulseIntensity > 0.08f) -Math.sin(now / 18.0).toFloat() * pulseIntensity * 0.7f else 0f
        dataPoints[dataPoints.size - 1] = (0.5f + noise + wave).coerceIn(0.05f, 0.95f)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat(); val h = height.toFloat()

        var x = 0f; while (x < w) { canvas.drawLine(x, 0f, x, h, gridPaint); x += 50f }
        var y = 0f; while (y < h) { canvas.drawLine(0f, y, w, y, gridPaint); y += 50f }

        val gradient = LinearGradient(0f, 0f, w, 0f, 0xFFFF3366.toInt(), 0xFFFF88AA.toInt(), Shader.TileMode.CLAMP)
        linePaint.shader = gradient
        glowPaint.shader = gradient; glowPaint.alpha = 50

        val stepX = w / (dataPoints.size - 1)
        val path = Path()
        for (i in dataPoints.indices) {
            val px = i * stepX; val py = dataPoints[i] * h
            if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        canvas.drawPath(path, glowPaint)
        canvas.drawPath(path, linePaint)
    }

    override fun onDetachedFromWindow() { super.onDetachedFromWindow(); stop() }
}
