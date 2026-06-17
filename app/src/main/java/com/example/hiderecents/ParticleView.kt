package com.example.hiderecents

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.sqrt
import kotlin.random.Random

class ParticleView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private data class Star(
        var x: Float, var y: Float,
        val baseSize: Float,
        val baseAlpha: Float,
        val depth: Float,
        var pushX: Float = 0f,
        var pushY: Float = 0f
    )

    private val stars = mutableListOf<Star>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; isAntiAlias = false }
    private val rect = RectF()
    private var running = false
    private var w = 0f; private var h = 0f
    private var lastDraw = 0L
    private var globalAngle = 0f
    private var lastDirChange = 0L
    private val dirChangeInterval = 5000L // 5秒换一次
    private var touchX = -1f; private var touchY = -1f
    private var touching = false

    init {
        for (i in 0 until 1500) addStar(0.06f, 0.06f, 0.3f, 0.8f)
        for (i in 0 until 800) addStar(0.18f, 0.1f, 0.6f, 1.2f)
        for (i in 0 until 500) addStar(0.35f, 0.18f, 1.5f, 2.5f)
        for (i in 0 until 300) addStar(0.55f, 0.3f, 3f, 4f)
        for (i in 0 until 200) addStar(0.72f, 0.45f, 6f, 6f)
        for (i in 0 until 150) addStar(0.9f, 0.7f, 10f, 12f)
        for (i in 0 until 60) addStar(0.98f, 0.85f, 18f, 18f)
        globalAngle = Random.nextFloat() * 6.283f
    }

    private fun addStar(depth: Float, alpha: Float, minSize: Float, sizeRange: Float) {
        stars.add(Star(
            x = Random.nextFloat(), y = Random.nextFloat(),
            baseSize = Random.nextFloat() * sizeRange + minSize,
            baseAlpha = Random.nextFloat() * alpha + alpha * 0.3f,
            depth = depth
        ))
    }

    fun start() { if (!running) { running = true; lastDraw = System.currentTimeMillis(); lastDirChange = lastDraw; postInvalidateOnAnimation() } }
    fun stop() { running = false }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> { touchX = event.x / w; touchY = event.y / h; touching = true }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { touching = false }
        }
        return true
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh); this.w = w.toFloat(); this.h = h.toFloat()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!running) return

        val now = System.currentTimeMillis()
        val dt = ((now - lastDraw).coerceAtMost(50)) / 16.667f
        lastDraw = now

        // 持续缓慢旋转
        globalAngle += (Random.nextFloat() - 0.5f) * 0.02f * dt

        val dirX = Math.cos(globalAngle.toDouble()).toFloat()
        val dirY = Math.sin(globalAngle.toDouble()).toFloat()

        for (s in stars) {
            // 小范围涟漪排斥
            if (touching) {
                val dx = s.x - touchX
                val dy = s.y - touchY
                val dist = sqrt(dx * dx + dy * dy)
                val radius = 0.04f // 极小范围
                if (dist < radius && dist > 0.001f) {
                    val force = (1f - dist / radius) * 0.0015f * s.depth
                    s.pushX += (dx / dist) * force
                    s.pushY += (dy / dist) * force
                }
            }

            // 2秒回归衰减
            s.pushX *= 0.96f
            s.pushY *= 0.96f

            val speed = 0.0004f + s.depth * 0.0025f
            s.x += dirX * speed * dt + s.pushX * dt
            s.y += dirY * speed * dt + s.pushY * dt

            if (s.x < -0.02f) s.x += 1.04f
            if (s.x > 1.02f) s.x -= 1.04f
            if (s.y < -0.02f) s.y += 1.04f
            if (s.y > 1.02f) s.y -= 1.04f

            paint.alpha = (s.baseAlpha * 255).toInt()
            val px = s.x * w; val py = s.y * h; val hs = s.baseSize / 2f
            rect.set(px - hs, py - hs, px + hs, py + hs)
            canvas.drawRect(rect, paint)
        }

        postInvalidateOnAnimation()
    }

    override fun onDetachedFromWindow() { super.onDetachedFromWindow(); running = false }
}
