package com.example.hiderecents

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import kotlin.math.cbrt
import kotlin.math.cos
import kotlin.math.sin

/**
 * Android port of reactbits.dev Particles background.
 * 3D floating particles rendered via Canvas on a regular View.
 */
class ParticlesBackgroundView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var particleCount = 1000
    var speed = 0.1f
    var baseSize = 3f

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var elapsed = 0f
    private var lastFrame = 0L
    private var particlesInit = false

    // Particle data
    private lateinit var px: FloatArray
    private lateinit var py: FloatArray
    private lateinit var pz: FloatArray
    private lateinit var rand: FloatArray
    private lateinit var pColors: IntArray

    private fun ensureParticles() {
        if (particlesInit && ::px.isInitialized) return
        particlesInit = true
        val count = particleCount
        val w = width.toFloat().coerceAtLeast(1f)
        val h = height.toFloat().coerceAtLeast(1f)
        val spreadX = w * 0.8f
        val spreadY = h * 0.8f

        px = FloatArray(count)
        py = FloatArray(count)
        pz = FloatArray(count)
        rand = FloatArray(count * 4)
        pColors = IntArray(count)

        val palette = intArrayOf(
            0x66FFFFFF.toInt(),
            0x55B0C6FF.toInt(),
            0x44BDF4FF.toInt()
        )

        for (i in 0 until count) {
            px[i] = (Math.random().toFloat() - 0.5f) * spreadX * 2f
            py[i] = (Math.random().toFloat() - 0.5f) * spreadY * 2f
            pz[i] = (Math.random().toFloat() - 0.5f) * spreadX * 0.4f

            for (j in 0..3) rand[i * 4 + j] = Math.random().toFloat()
            pColors[i] = palette[(Math.random() * palette.size).toInt()]
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        ensureParticles()

        val now = System.nanoTime()
        if (lastFrame == 0L) lastFrame = now
        val dt = (now - lastFrame) / 1_000_000_000f  // seconds
        lastFrame = now
        elapsed += dt * speed

        drawParticles(canvas)
        postInvalidateOnAnimation()
    }

    private fun drawParticles(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2f
        val cy = h / 2f
        val t = elapsed
        val count = particleCount

        val rotX = sin(t * 0.2f) * 0.1f
        val rotY = cos(t * 0.5f) * 0.15f
        val rotZ = t * 0.1f

        val cosRx = cos(rotX); val sinRx = sin(rotX)
        val cosRy = cos(rotY); val sinRy = sin(rotY)
        val cosRz = cos(rotZ); val sinRz = sin(rotZ)

        for (i in 0 until count) {
            val i4 = i * 4
            val rx = rand[i4]; val ry = rand[i4 + 1]; val rz = rand[i4 + 2]; val rw = rand[i4 + 3]

            var x = px[i] + sin(t * rz * 3f + 6.28f * rw) * (5f + 30f * rx)
            var y = py[i] + sin(t * ry * 3f + 6.28f * rx) * (5f + 30f * rw)
            var z = pz[i] + sin(t * rw * 3f + 6.28f * ry) * (5f + 30f * rz)

            var y1 = y * cosRx - z * sinRx
            var z1 = y * sinRx + z * cosRx
            var x1 = x * cosRy + z1 * sinRy
            var z2 = -x * sinRy + z1 * cosRy
            val x2 = x1 * cosRz - y1 * sinRz
            val y2 = x1 * sinRz + y1 * cosRz

            val depth = 600f / (600f + z2)
            val sx = cx + x2 * depth
            val sy = cy + y2 * depth

            if (sx < -20 || sx > w + 20 || sy < -20 || sy > h + 20) continue

            val size = baseSize * depth
            if (size < 0.3f) continue

            paint.color = pColors[i]
            paint.alpha = (200 * depth).toInt().coerceIn(30, 200)
            canvas.drawCircle(sx, sy, size, paint)
        }
    }
}
