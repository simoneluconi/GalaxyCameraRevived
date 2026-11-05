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

    /**
     * How the camera is held, in degrees. Upside down the meter turns as a whole. Held
     * vertically the scale stays along the screen with +3 at the user's top, its numbers and
     * the value turn in place, and the title (too long to fit across) is left out.
     */
    var contentRotation = 0f
        set(v) {
            if (field != v) {
                field = v
                invalidate()
            }
        }
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
                val ev = ((e.x - pad) / scaleSpan() * 6f - 3f) * screenRightSign(contentRotation)
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

    /** Held vertically the value sits at the scale's end, so the scale is shorter. */
    private fun scaleSpan() = width - 2 * dp(8f) - if (quarterOf(contentRotation) % 2 == 1) dp(VALUE_ROOM_DP) else 0f

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val pad = dp(8f)
        val top = dp(12f)
        if (interactive) {
            rect.set(0.5f, 0.5f, w - 0.5f, h - 0.5f)
            c.drawRoundRect(rect, dp(10f), dp(10f), bg)
            c.drawRoundRect(rect, dp(10f), dp(10f), border)
        }
        val quarter = quarterOf(contentRotation)
        val vertical = quarter % 2 == 1
        // Held vertically anticlockwise, the screen's left is the user's top: +3 goes there
        val dir = if (quarter == 3) -1f else 1f
        c.save()
        if (quarter == 2) c.rotate(180f, w / 2, h / 2)
        val v = ev
        val valueText = when {
            v == null -> "—"
            v < -3f -> "< −3"
            v > 3f -> "> +3"
            estimated -> "≈ %+.1f".format(v)
            else -> "%+.1f".format(v)
        }
        val axisY = dp(26f)
        val span = scaleSpan()
        if (vertical) {
            // The value turned in place past the scale's end, the title left out
            val vx = w - pad - dp(VALUE_ROOM_DP) / 2
            c.save()
            c.rotate(contentRotation, vx, h / 2)
            valuePaint.textAlign = Paint.Align.CENTER
            c.drawText(valueText, vx, centreBaseline(valuePaint, h / 2), valuePaint)
            valuePaint.textAlign = Paint.Align.RIGHT
            c.restore()
        } else {
            c.drawText(title, pad, top, titlePaint)
            c.drawText(valueText, w - pad, top, valuePaint)
        }

        for (i in -9..9) {
            val x = pad + span * (dir * i + 9) / 18f
            val major = i % 3 == 0
            c.drawLine(x, axisY - dp(if (major) 6f else 3f), x, axisY, if (major) tickMajor else tick)
            if (major) {
                val ny = axisY + dp(11f)
                val label = if (i == 0) "0" else "%+d".format(i / 3)
                if (vertical) {
                    c.save()
                    c.rotate(contentRotation, x, ny - dp(3f))
                    c.drawText(label, x, centreBaseline(numberPaint, ny - dp(3f)), numberPaint)
                    c.restore()
                } else {
                    c.drawText(label, x, ny, numberPaint)
                }
            }
        }
        if (v == null) {
            c.restore()
            return
        }
        val clamped = v.coerceIn(-3f, 3f)
        val x = pad + span * (dir * clamped + 3f) / 6f
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
        c.restore()
    }

    companion object {
        private const val VALUE_ROOM_DP = 28f
    }
}
