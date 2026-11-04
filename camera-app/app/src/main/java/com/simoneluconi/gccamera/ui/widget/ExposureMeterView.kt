package com.simoneluconi.gccamera.ui.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import com.simoneluconi.gccamera.R
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The exposure scale, −3…+3 EV in 1/3 steps. In M it is the meter (how far the set exposure
 * is from what the ISP measures). In P/A/S it is the compensation control: drag or tap.
 */
class ExposureMeterView(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    /** The scale sets the compensation; [onSet] gets the new value in 1/3 EV steps. */
    var interactive = false
        set(v) {
            if (field != v) {
                field = v
                invalidate()
            }
        }

    var onSet: ((Int) -> Unit)? = null
    private var lastSteps = Int.MIN_VALUE
    private var lastTapUp = 0L

    private var title = ""
    private var ev: Float? = null
    private var estimated = false

    /** [estimated]: the value comes from the preview, not from the ISP's meter. */
    fun set(title: String, ev: Float?, estimated: Boolean) {
        // Snap to 1/3 EV so preview noise does not redraw the meter every frame
        val snapped = ev?.let { (it * 3).roundToInt() / 3f }
        if (title == this.title && snapped == this.ev && estimated == this.estimated) return
        this.title = title
        this.ev = snapped
        this.estimated = estimated
        invalidate()
    }

    private val titlePaint = textPaint(10f, R.color.on_surface_muted)
    private val numberPaint = textPaint(9f, R.color.on_surface_muted, false, Paint.Align.CENTER)
    private val tick = strokePaint(R.color.on_surface_muted, 1f)
    private val tickMajor = strokePaint(R.color.on_surface, 1.5f)
    private val marker = fillPaint(R.color.accent)
    private val markerOut = fillPaint(R.color.warning)
    private val markerHollow = strokePaint(R.color.accent, 1.5f)
    private val valuePaint = textPaint(10f, R.color.on_surface, true, Paint.Align.RIGHT)
    private val path = Path()
    private val rect = RectF()
    private val bg = fillPaint(R.color.surface)
    private val border = strokePaint(R.color.outline, 1f)

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!interactive) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                val pad = dp(8f)
                val ev = (e.x - pad) / (width - 2 * pad) * 6f - 3f
                val steps = (ev * 3).roundToInt().coerceIn(-9, 9)
                if (steps != lastSteps) {
                    lastSteps = steps
                    onSet?.invoke(steps)
                }
            }
            MotionEvent.ACTION_UP -> {
                lastSteps = Int.MIN_VALUE
                // Double tap: back to 0
                if (e.eventTime - lastTapUp < ViewConfiguration.getDoubleTapTimeout()) {
                    onSet?.invoke(0)
                    lastTapUp = 0L
                } else {
                    lastTapUp = e.eventTime
                }
                performClick()
            }
            MotionEvent.ACTION_CANCEL -> lastSteps = Int.MIN_VALUE
        }
        return true
    }

    override fun performClick() = super.performClick()

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val pad = dp(8f)
        val top = dp(12f)
        if (interactive) {
            rect.set(0.5f, 0.5f, w - 0.5f, height - 0.5f)
            c.drawRoundRect(rect, dp(10f), dp(10f), bg)
            c.drawRoundRect(rect, dp(10f), dp(10f), border)
        }
        c.drawText(title, pad, top, titlePaint)
        val v = ev
        val valueText = when {
            v == null -> "—"
            v < -3f -> "< −3"
            v > 3f -> "> +3"
            estimated -> "≈ %+.1f".format(v)
            else -> "%+.1f".format(v)
        }
        c.drawText(valueText, w - pad, top, valuePaint)

        val axisY = dp(26f)
        val span = w - 2 * pad
        for (i in -9..9) {
            val x = pad + span * (i + 9) / 18f
            val major = i % 3 == 0
            c.drawLine(x, axisY - dp(if (major) 6f else 3f), x, axisY, if (major) tickMajor else tick)
            if (major) c.drawText(if (i == 0) "0" else "%+d".format(i / 3), x, axisY + dp(11f), numberPaint)
        }
        if (v == null) return
        val clamped = v.coerceIn(-3f, 3f)
        val x = pad + span * (clamped + 3f) / 6f
        path.reset()
        val s = dp(5f)
        path.moveTo(x, axisY - dp(7f))
        path.lineTo(x - s, axisY - dp(7f) - s * 1.4f)
        path.lineTo(x + s, axisY - dp(7f) - s * 1.4f)
        path.close()
        val paint = when {
            abs(v) > 3f -> markerOut
            estimated -> markerHollow
            else -> marker
        }
        c.drawPath(path, paint)
    }
}
