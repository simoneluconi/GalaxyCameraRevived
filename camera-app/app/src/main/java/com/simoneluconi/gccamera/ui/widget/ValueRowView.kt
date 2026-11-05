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

    /**
     * How the camera is held, in degrees. Upright or upside down the row turns as a whole; held
     * vertically the user sees a tall narrow box, so it stacks: ‹ on top, label, value, › below.
     * The drag stays along the screen's x, which is then the user's up and down.
     */
    var contentRotation = 0f
        set(v) {
            if (field != v) {
                field = v
                invalidate()
            }
        }

    private val bg = fillPaint(R.color.surface)
    private val bgDrag = fillPaint(R.color.surface_variant)
    private val border = strokePaint(R.color.outline, 1f)
    private val labelPaint = textPaint(11f, R.color.on_surface_muted)
    private val labelCentred = textPaint(11f, R.color.on_surface_muted, false, Paint.Align.CENTER)
    private val valuePaint = textPaint(17f, R.color.on_surface, true, Paint.Align.RIGHT)
    private val valueMuted = textPaint(17f, R.color.on_surface_muted, false, Paint.Align.RIGHT)
    private val valueCentred = textPaint(17f, R.color.on_surface, true, Paint.Align.CENTER)
    private val valueMutedCentred = textPaint(17f, R.color.on_surface_muted, false, Paint.Align.CENTER)
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
        c.save()
        c.rotate(contentRotation, w / 2, h / 2)
        if (quarterOf(contentRotation) % 2 == 0) drawRow(c, w, h) else drawStacked(c, w, h)
        c.restore()
    }

    private fun drawRow(c: Canvas, w: Float, h: Float) {
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

    /** The user's box is [h] wide and [w] tall, centred on the view. */
    private fun drawStacked(c: Canvas, w: Float, h: Float) {
        val cx = w / 2
        val top = h / 2 - w / 2
        val bottom = h / 2 + w / 2
        val label = top + dp(if (active) 34f else 22f)
        c.drawText(this.label, cx, centreBaseline(labelCentred, label), labelCentred)
        val paint = if (active) valueCentred else valueMutedCentred
        val vy = h / 2 + dp(8f)
        // "1/1000" at 17sp is wider than the row is tall
        val s = ((h - dp(14f)) / paint.measureText(value).coerceAtLeast(1f)).coerceAtMost(1f)
        c.save()
        c.scale(s, s, cx, vy)
        c.drawText(value, cx, centreBaseline(paint, vy), paint)
        c.restore()
        if (active) {
            // ‹ and › turned a quarter clockwise point up and down
            drawTurned(c, "‹", cx, top + dp(12f))
            drawTurned(c, "›", cx, bottom - dp(12f))
        }
    }

    private fun drawTurned(c: Canvas, s: String, x: Float, y: Float) {
        c.save()
        c.rotate(90f, x, y)
        c.drawText(s, x, centreBaseline(chevron, y), chevron)
        c.restore()
    }

    // Taps left of the value's centre step down, right of it step up
    private var valueCentre = 0f

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!active) return false
        // The "more" end is the user's right or top, whichever way the screen is turned
        val sign = screenRightSign(contentRotation)
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
                    onStep?.invoke(steps * sign)
                }
            }
            MotionEvent.ACTION_UP -> {
                if (!stepped && abs(e.x - downX) < stepPx / 2) {
                    val centre = when (quarterOf(contentRotation)) {
                        0 -> valueCentre
                        2 -> width - valueCentre
                        else -> width / 2f
                    }
                    onStep?.invoke(if (e.x >= centre) sign else -sign)
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
