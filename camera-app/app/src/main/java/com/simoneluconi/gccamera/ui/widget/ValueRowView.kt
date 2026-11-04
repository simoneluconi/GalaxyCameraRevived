package com.simoneluconi.gccamera.ui.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.simoneluconi.gccamera.R
import kotlin.math.abs

/**
 * One exposure value (shutter, aperture, ISO, EV) as a dial: drag sideways to step it, or tap
 * either side of the value (‹ ›). When the camera chooses the value itself, the row shows what
 * the ISP used, greyed, and ignores touches.
 */
class ValueRowView(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    var label = ""
        set(v) {
            if (field != v) {
                field = v
                invalidate()
            }
        }

    var value = ""
        set(v) {
            if (field != v) {
                field = v
                invalidate()
            }
        }

    /** The user sets this value in the current mode. */
    var active = true
        set(v) {
            if (field != v) {
                field = v
                invalidate()
            }
        }

    var onStep: ((Int) -> Unit)? = null

    private val bg = fillPaint(R.color.surface)
    private val bgDrag = fillPaint(R.color.surface_variant)
    private val border = strokePaint(R.color.outline, 1f)
    private val labelPaint = textPaint(11f, R.color.on_surface_muted)
    private val valuePaint = textPaint(17f, R.color.on_surface, true, Paint.Align.RIGHT)
    private val valueMuted = textPaint(17f, R.color.on_surface_muted, false, Paint.Align.RIGHT)
    private val chevron = textPaint(15f, R.color.accent, true, Paint.Align.CENTER)
    private val rect = RectF()
    private val radius = dp(10f)
    private val stepPx = dp(22f)
    private var downX = 0f
    private var lastStepX = 0f
    private var stepped = false
    private var dragging = false

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        rect.set(0.5f, 0.5f, w - 0.5f, h - 0.5f)
        c.drawRoundRect(rect, radius, radius, if (dragging) bgDrag else bg)
        c.drawRoundRect(rect, radius, radius, border)
        val pad = dp(10f)
        val cy = h / 2
        c.drawText(label, pad, centreBaseline(labelPaint, cy), labelPaint)
        val right = if (active) w - dp(22f) else w - pad
        c.drawText(value, right, centreBaseline(valuePaint, cy), if (active) valuePaint else valueMuted)
        if (active) {
            val left = right - valuePaint.measureText(value)
            valueCentre = (left + right) / 2
            c.drawText("‹", left - dp(11f), centreBaseline(chevron, cy), chevron)
            c.drawText("›", w - dp(11f), centreBaseline(chevron, cy), chevron)
        }
    }

    // Taps left of the value's centre step down, right of it step up
    private var valueCentre = 0f

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!active) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x
                lastStepX = e.x
                stepped = false
                dragging = true
                parent?.requestDisallowInterceptTouchEvent(true)
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = e.x - lastStepX
                if (abs(dx) >= stepPx) {
                    val steps = (dx / stepPx).toInt()
                    lastStepX += steps * stepPx
                    stepped = true
                    onStep?.invoke(steps)
                }
            }
            MotionEvent.ACTION_UP -> {
                if (!stepped && abs(e.x - downX) < stepPx / 2) {
                    onStep?.invoke(if (e.x >= valueCentre) 1 else -1)
                    performClick()
                }
                dragging = false
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> {
                dragging = false
                invalidate()
            }
        }
        return true
    }

    override fun performClick() = super.performClick()
}
