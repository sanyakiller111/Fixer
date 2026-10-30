package com.october.fixer

import android.animation.ArgbEvaluator
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * A cloud of dots around the button; grey and slow when off, blue and faster when on.
 * The dots are drawn once into a hardware layer and the whole view is rotated, so spinning costs
 * the GPU a texture rotation instead of 140 circles redrawn on the CPU every frame.
 */
class DotRingView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    private class Dot(val angle: Double, val radius: Float, val size: Float, val alpha: Int)

    private val density = resources.displayMetrics.density
    private val rnd = Random(7)
    private val dots = List(140) {
        Dot(
            angle = Math.toRadians(rnd.nextDouble() * 360),
            radius = rnd.nextFloat(), // 0..1 across the band, mapped in onDraw
            size = (0.8f + rnd.nextFloat() * 1.6f) * density,
            alpha = 60 + rnd.nextInt(170),
        )
    }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var color = OFF
    private var colorAnim: ValueAnimator? = null
    private var spin: ObjectAnimator? = null
    private var periodMs = PERIOD_OFF

    init {
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    fun setActive(on: Boolean) {
        val target = if (on) ON else OFF
        val period = if (on) PERIOD_ON else PERIOD_OFF
        if (period != periodMs) {
            periodMs = period
            if (spin != null) startSpin()
        }
        if (target == color && colorAnim == null) return
        colorAnim?.cancel()
        colorAnim = ValueAnimator.ofObject(ArgbEvaluator(), color, target).apply {
            duration = 600
            addUpdateListener {
                color = it.animatedValue as Int
                invalidate() // the layer is re-rendered only during this short fade
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: android.animation.Animator) {
                    colorAnim = null
                }
            })
            start()
        }
    }

    private fun startSpin() {
        spin?.cancel()
        val from = rotation % 360f
        spin = ObjectAnimator.ofFloat(this, ROTATION, from, from + 360f).apply {
            duration = periodMs
            interpolator = LinearInterpolator()
            repeatCount = ValueAnimator.INFINITE
            start()
        }
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        // nothing animates while the app is in the background
        if (isVisible) startSpin() else { spin?.cancel(); spin = null }
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val outer = min(cx, cy)
        val inner = outer * 0.70f
        paint.color = color
        for (d in dots) {
            val r = inner + (outer - inner) * d.radius
            paint.alpha = d.alpha
            canvas.drawCircle(cx + (r * cos(d.angle)).toFloat(), cy + (r * sin(d.angle)).toFloat(), d.size, paint)
        }
    }

    private companion object {
        const val PERIOD_OFF = 40_000L
        const val PERIOD_ON = 7_000L
        val OFF = Color.rgb(0x8A, 0x8A, 0x90)
        val ON = Color.rgb(0x5B, 0x9B, 0xFF)
    }
}
