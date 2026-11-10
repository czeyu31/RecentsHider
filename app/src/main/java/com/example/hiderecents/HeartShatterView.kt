package com.example.hiderecents

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

class HeartShatterView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private data class MiniHeart(
        var x: Float, var y: Float,
        var vx: Float, var vy: Float,
        var life: Float, val decay: Float,
        val size: Float, var rot: Float,
        val rotSpeed: Float
    )

    private val particles = mutableListOf<MiniHeart>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFE53935.toInt(); style = Paint.Style.FILL
    }
    private var running = false

    fun burst(worldX: Float, worldY: Float) {
        for (i in 0 until 6) {
            val angle = Random.nextFloat() * Math.PI.toFloat() * 2
            val speed = Random.nextFloat() * 3f + 1.5f // 慢速迸发
            particles.add(MiniHeart(
                x = worldX, y = worldY,
                vx = cos(angle) * speed,
                vy = sin(angle) * speed,
                life = 1f,
                decay = 1f / 180f, // ~3秒 @60fps
                size = Random.nextFloat() * 35f + 25f,
                rot = Random.nextFloat() * 360f,
                rotSpeed = (Random.nextFloat() - 0.5f) * 4f
            ))
        }
        if (!running) { running = true; postInvalidateOnAnimation() }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!running) return

        val iter = particles.iterator()
        while (iter.hasNext()) {
            val p = iter.next()
            p.x += p.vx; p.y += p.vy
            p.vx *= 0.985f; p.vy *= 0.985f
            p.vy += 0.02f
            p.life -= p.decay; p.rot += p.rotSpeed
            if (p.life <= 0f) { iter.remove(); continue }

            canvas.save()
            canvas.translate(p.x, p.y)
            canvas.rotate(p.rot)
            canvas.scale(p.life, p.life)
            paint.alpha = (p.life * 255).toInt()
            drawHeart(canvas, p.size)
            canvas.restore()
        }

        if (particles.isNotEmpty()) postInvalidateOnAnimation()
        else running = false
    }

    private fun drawHeart(canvas: Canvas, s: Float) {
        val path = Path()
        path.moveTo(0f, s * 0.6f)
        path.cubicTo(-s * 0.05f, s * 0.5f, -s * 0.95f, s * 0.25f, -s * 0.95f, -s * 0.05f)
        path.cubicTo(-s * 0.95f, -s * 0.5f, -s * 0.4f, -s * 0.7f, 0f, -s * 0.35f)
        path.cubicTo(s * 0.4f, -s * 0.7f, s * 0.95f, -s * 0.5f, s * 0.95f, -s * 0.05f)
        path.cubicTo(s * 0.95f, s * 0.25f, s * 0.05f, s * 0.5f, 0f, s * 0.6f)
        path.close()
        canvas.drawPath(path, paint)
    }

    override fun onDetachedFromWindow() { super.onDetachedFromWindow(); running = false }
}
