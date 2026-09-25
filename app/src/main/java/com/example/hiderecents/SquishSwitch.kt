package com.example.hiderecents

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.animation.OvershootInterpolator
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Android port of reactbits.dev SquishSwitch.
 * A toggle switch with a jelly-like "squish" effect on the thumb during drag.
 */
class SquishSwitch @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // --- Configurable properties ---
    var trackColor = 0xFF242830.toInt()
    var trackOnColor = 0xFF242830.toInt()
    var thumbColor = 0xFF5A6278.toInt()
    var thumbOnColor = 0xFF6C9CFF.toInt()
    var colorDuration = 320L
    var speed = 50          // 0..100, controls spring stiffness
    var stretchAmount = 36  // 0..100, max squish intensity
    var hoverScale = 1.035f

    // --- State ---
    var isChecked = false
        set(value) {
            if (field == value) return
            field = value
            animateToTarget()
            listener?.invoke(value)
        }
    var listener: ((Boolean) -> Unit)? = null

    // --- Dimensions (match MaterialSwitch default: ~52x32dp) ---
    private val density = context.resources.displayMetrics.density
    private val w = 52 * density
    private val h = 32 * density
    private val inset = max(2 * density, h * 0.1f)
    private val thumbSize = h - inset * 2
    private val trackRadius = min(16 * density, h / 2f)
    private val thumbRadius = max(2 * density, trackRadius - inset)
    private val minX = inset
    private val maxX = w - inset - thumbSize
    private val midX = (minX + maxX) / 2f

    // --- Paints ---
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val trackRect = RectF()
    private val thumbRect = RectF()

    // --- Animation state ---
    private var thumbX = if (isChecked) maxX else minX
    private var thumbScaleX = 1f
    private var thumbScaleY = 1f
    private var currentTrackColor = if (isChecked) trackOnColor else trackColor
    private var currentThumbColor = if (isChecked) thumbOnColor else thumbColor
    private var isDragging = false
    private var dragPointerId = -1
    private var grabOffset = 0f
    private var hasMoved = false
    private var startX = 0f
    private var pressOn = false
    private val slop = 8 * density
    private var velocityTracker = 0f
    private var lastDragX = 0f
    private var lastDragTime = 0L
    private var springAnimator: ValueAnimator? = null
    private var colorAnimator: ValueAnimator? = null
    private var thumbColorAnimator: ValueAnimator? = null

    init {
        isClickable = true
        isFocusable = true
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desiredW = w.toInt() + paddingLeft + paddingRight
        val desiredH = h.toInt() + paddingTop + paddingBottom
        setMeasuredDimension(
            resolveSize(desiredW, widthMeasureSpec),
            resolveSize(desiredH, heightMeasureSpec)
        )
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateRects()
    }

    private fun updateRects() {
        val ox = paddingLeft.toFloat()
        val oy = paddingTop.toFloat()
        trackRect.set(ox, oy, ox + this.w, oy + this.h)
        val cx = ox + thumbX + thumbSize / 2f
        val cy = oy + h / 2f
        val halfW = thumbSize / 2f * thumbScaleX
        val halfH = thumbSize / 2f * thumbScaleY
        thumbRect.set(cx - halfW, cy - halfH, cx + halfW, cy + halfH)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        trackPaint.color = currentTrackColor
        canvas.drawRoundRect(trackRect, trackRadius, trackRadius, trackPaint)
        thumbPaint.color = currentThumbColor
        canvas.drawRoundRect(thumbRect, thumbRadius, thumbRadius, thumbPaint)
    }

    // --- Touch handling (pointer-based, supports drag) ---
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (isEnabled.not()) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (event.actionMasked == MotionEvent.ACTION_DOWN && event.getToolType(0) == MotionEvent.TOOL_TYPE_MOUSE && event.buttonState != MotionEvent.BUTTON_PRIMARY) return false
                isDragging = true
                dragPointerId = event.getPointerId(event.actionIndex)
                startX = event.rawX
                hasMoved = false
                grabOffset = localX(event) - thumbX
                pressOn = isChecked
                lastDragX = event.rawX
                lastDragTime = event.eventTime
                velocityTracker = 0f
                parent.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!isDragging || event.findPointerIndex(dragPointerId) < 0) return false
                val lx = localX(event)
                if (!hasMoved && abs(event.rawX - startX) > slop) hasMoved = true
                if (!hasMoved) return true
                val nx = (lx - grabOffset).coerceIn(minX, maxX)
                // Calculate velocity
                val dt = (event.eventTime - lastDragTime).toFloat().coerceAtLeast(1f)
                velocityTracker = (event.rawX - lastDragX) / dt * 16f // per-frame velocity
                lastDragX = event.rawX
                lastDragTime = event.eventTime
                thumbX = nx
                // Apply squish effect based on velocity
                val gain = stretchAmount.coerceIn(0, 100) / 100f
                val maxStretch = 0.4f
                val absVel = min(abs(velocityTracker) / 600f, 1f) * maxStretch * gain
                thumbScaleX = 1f + absVel
                thumbScaleY = 1f / thumbScaleX
                isChecked = nx > midX
                updateRects()
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!isDragging) return false
                isDragging = false
                parent.requestDisallowInterceptTouchEvent(false)
                val cancelled = event.actionMasked == MotionEvent.ACTION_CANCEL
                if (cancelled) {
                    isChecked = pressOn
                } else if (!hasMoved) {
                    isChecked = !isChecked
                }
                // Spring back thumb shape
                animateSquishBack()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun localX(event: MotionEvent): Float {
        return event.x - paddingLeft
    }

    private fun animateToTarget() {
        springAnimator?.cancel()
        val target = if (isChecked) maxX else minX
        val stiffness = 170f - (50f - speed.coerceIn(0, 100)) * 1.1f
        springAnimator = ValueAnimator.ofFloat(thumbX, target).apply {
            duration = (600 - stiffness.coerceAtLeast(50f) * 2).toLong().coerceIn(200, 600)
            interpolator = OvershootInterpolator(0.4f)
            addUpdateListener {
                thumbX = it.animatedValue as Float
                updateRects()
                invalidate()
            }
            start()
        }
        // Animate track color
        colorAnimator?.cancel()
        val fromColor = currentTrackColor
        val toColor = if (isChecked) trackOnColor else trackColor
        colorAnimator = ValueAnimator.ofArgb(fromColor, toColor).apply {
            duration = colorDuration
            addUpdateListener { currentTrackColor = it.animatedValue as Int; invalidate() }
            start()
        }
        // Animate thumb color
        thumbColorAnimator?.cancel()
        val fromThumb = currentThumbColor
        val toThumb = if (isChecked) thumbOnColor else thumbColor
        thumbColorAnimator = ValueAnimator.ofArgb(fromThumb, toThumb).apply {
            duration = colorDuration
            addUpdateListener { currentThumbColor = it.animatedValue as Int; invalidate() }
            start()
        }
    }

    private fun animateSquishBack() {
        val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 200
            addUpdateListener {
                val t = it.animatedValue as Float
                thumbScaleX = thumbScaleX + (1f - thumbScaleX) * t
                thumbScaleY = thumbScaleY + (1f - thumbScaleY) * t
                updateRects()
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    thumbScaleX = 1f
                    thumbScaleY = 1f
                    updateRects()
                    invalidate()
                }
            })
        }
        animator.start()
    }

    fun toggle() {
        isChecked = !isChecked
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        springAnimator?.cancel()
        colorAnimator?.cancel()
        thumbColorAnimator?.cancel()
    }
}
