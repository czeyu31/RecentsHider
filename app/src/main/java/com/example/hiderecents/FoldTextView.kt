package com.example.hiderecents

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import kotlin.math.cos
import kotlin.math.max

/**
 * Android port of reactbits.dev FoldText.
 * 3D folding text animation — each character unfolds from top hinge with stagger.
 */
class FoldTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var text = "System Tool"
        set(value) { field = value; requestLayout(); invalidate() }
    var foldDuration = 650L
    var staggerMs = 45L
    var perspective = 700f
    var creaseShading = 0.55f
    var textColor = 0xFFB0C6FF.toInt()
    var textSizeSp = 26f

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val creasePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val matrix3d = Matrix()
    private var charWidths = FloatArray(0)
    private var totalWidth = 0f
    private var charProgress = FloatArray(0) // 0..1 per character
    private var animator: ValueAnimator? = null
    private var hasAnimated = false

    private val density = context.resources.displayMetrics.density

    init {
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        paint.textSize = textSizeSp * density
        paint.typeface = android.graphics.Typeface.DEFAULT_BOLD
        paint.isFakeBoldText = true

        charWidths = FloatArray(text.length)
        totalWidth = 0f
        for (i in text.indices) {
            charWidths[i] = paint.measureText(text[i].toString())
            totalWidth += charWidths[i]
        }

        val fontMetrics = paint.fontMetrics
        val textHeight = fontMetrics.descent - fontMetrics.ascent
        val desiredW = totalWidth.toInt() + paddingLeft + paddingRight
        val desiredH = (textHeight * 1.8f).toInt() + paddingTop + paddingBottom
        setMeasuredDimension(
            resolveSize(desiredW, widthMeasureSpec),
            resolveSize(desiredH, heightMeasureSpec)
        )
    }

    fun startAnimation() {
        if (hasAnimated) return
        hasAnimated = true
        charProgress = FloatArray(text.length) { 0f }

        animator?.cancel()
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = foldDuration + staggerMs * text.length + 100
            interpolator = DecelerateInterpolator(1.2f)
            addUpdateListener { anim ->
                val totalT = anim.animatedValue as Float
                val totalDuration = duration.toFloat()
                for (i in text.indices) {
                    val charStart = (staggerMs * i) / totalDuration
                    val charEnd = charStart + (foldDuration.toFloat() / totalDuration)
                    charProgress[i] = when {
                        totalT <= charStart -> 0f
                        totalT >= charEnd -> 1f
                        else -> {
                            val localT = (totalT - charStart) / (charEnd - charStart)
                            // Ease out cubic
                            1f - (1f - localT) * (1f - localT) * (1f - localT)
                        }
                    }
                }
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    charProgress = FloatArray(text.length) { 1f }
                    invalidate()
                }
            })
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val fm = paint.fontMetrics
        val textHeight = fm.descent - fm.ascent
        val baseY = paddingTop + textHeight - fm.descent + textHeight * 0.15f

        var xOffset = paddingLeft.toFloat()

        for (i in text.indices) {
            val ch = text[i].toString()
            val progress = if (charProgress.isEmpty()) 0f else charProgress[i]
            val angle = (1f - progress) * 92f // degrees from folded to flat

            canvas.save()

            val charCx = xOffset + charWidths[i] / 2f
            val charCy = baseY - textHeight * 0.35f

            // Translate to character center
            canvas.translate(charCx, charCy)

            // Apply 3D rotation around X axis (top hinge)
            val rad = Math.toRadians(angle.toDouble())
            val cosA = cos(rad).toFloat()

            // Perspective foreshortening
            val perspScale = perspective / (perspective + sinApprox(rad.toFloat()) * textHeight * 0.5f)

            matrix3d.reset()
            // Scale Y for perspective + rotation
            matrix3d.setSinCos(
                sinApprox(rad.toFloat()),
                cosA,
                0f, 0f
            )
            matrix3d.preScale(1f, cosA * perspScale)
            canvas.concat(matrix3d)

            // Draw character
            paint.color = textColor
            paint.alpha = if (progress > 0f) 255 else 0
            canvas.drawText(ch, -charWidths[i] / 2f, 0f, paint)

            // Draw crease shading overlay
            if (progress in 0.01f..0.99f) {
                val creaseAlpha = (creaseShading * 255 * (1f - cosA)).toInt().coerceIn(0, 255)
                if (creaseAlpha > 5) {
                    creasePaint.shader = LinearGradient(
                        0f, -textHeight * 0.5f,
                        0f, textHeight * 0.5f,
                        intArrayOf(
                            Color.argb(creaseAlpha, 0, 0, 0),
                            Color.argb((creaseAlpha * 0.4f).toInt(), 0, 0, 0),
                            Color.argb((creaseAlpha * 0.5f).toInt(), 255, 255, 255)
                        ),
                        floatArrayOf(0f, 0.42f, 1f),
                        Shader.TileMode.CLAMP
                    )
                    canvas.drawRect(
                        -charWidths[i] / 2f - 2 * density,
                        -textHeight * 0.55f,
                        charWidths[i] / 2f + 2 * density,
                        textHeight * 0.55f,
                        creasePaint
                    )
                }
            }

            canvas.restore()
            xOffset += charWidths[i]
        }
    }

    private fun sinApprox(rad: Float): Float {
        // Simple sin approximation for small angles
        return kotlin.math.sin(rad)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        animator?.cancel()
    }
}
