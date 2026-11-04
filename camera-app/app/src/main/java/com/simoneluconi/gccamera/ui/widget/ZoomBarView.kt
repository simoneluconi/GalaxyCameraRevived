package com.simoneluconi.gccamera.ui.widget

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import com.simoneluconi.gccamera.R
import kotlin.math.roundToInt

/**
 * Focal length in 35mm terms. At rest a small pill with the text only; while the zoom moves it
 * grows into a bar showing the position along the 21x range, then shrinks back.
 */
class ZoomBarView(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    private var label = ""
    private var fraction = 0f
    private var zoom = -1

    /** 0 = text-only pill, 1 = the full bar. */
    private var expansion = 0f
    private var anim: ValueAnimator? = null
    private val collapse = Runnable { animateTo(0f, COLLAPSE_MS) }

    fun set(zoom: Int, maxZoom: Int, focal35mm: Double, ratio: Int) {
        val l = "${focal35mm.roundToInt()} mm  ·  %.1f×".format(ratio / 100f)
        val f = if (maxZoom > 0) zoom.toFloat() / maxZoom else 0f
        // The first value (camera open) just shows; later changes are the lever moving
        val moved = this.zoom >= 0 && zoom != this.zoom
        this.zoom = zoom
        if (l == label && f == fraction && !moved) return
        label = l
        fraction = f
        if (moved) {
            animateTo(1f, EXPAND_MS)
            removeCallbacks(collapse)
            postDelayed(collapse, HOLD_MS)
        }
        invalidate()
    }

    private fun animateTo(target: Float, ms: Long) {
        if (expansion == target && anim?.isRunning != true) return
        anim?.cancel()
        anim = ValueAnimator.ofFloat(expansion, target).apply {
            duration = ms
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                expansion = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(collapse)
        anim?.cancel()
        super.onDetachedFromWindow()
    }

    private val bg = fillPaint(R.color.status_bar_bg)
    private val text = textPaint(12f, R.color.on_surface, true, Paint.Align.CENTER)
    private val track = fillPaint(R.color.outline)
    private val knob = fillPaint(R.color.accent)
    private val rect = RectF()

    override fun onDraw(c: Canvas) {
        if (label.isEmpty()) return
        val w = width.toFloat()
        val h = height.toFloat()
        val t = expansion
        // The pill grows from the text's size to the whole view, anchored at the bottom
        val compactW = text.measureText(label) + dp(28f)
        val compactH = dp(24f)
        val pw = compactW + (w - compactW) * t
        val ph = compactH + (h - compactH) * t
        val left = (w - pw) / 2
        val top = h - ph
        rect.set(left, top, left + pw, h)
        c.drawRoundRect(rect, ph / 2, ph / 2, bg)
        // Text centred in the compact pill, moving up to make room for the track
        val textY = top + ph * (0.5f - 0.14f * t)
        c.drawText(label, w / 2, centreBaseline(text, textY), text)
        if (t <= 0.01f) return
        val alpha = (t * 255).toInt()
        track.alpha = alpha
        knob.alpha = alpha
        // Inset well past the knob, so it never touches the pill's rounded ends
        val pad = dp(22f)
        val ty = top + ph * 0.74f
        rect.set(left + pad, ty - dp(1.5f), left + pw - pad, ty + dp(1.5f))
        c.drawRoundRect(rect, dp(1.5f), dp(1.5f), track)
        c.drawCircle(left + pad + (pw - 2 * pad) * fraction, ty, dp(4.5f), knob)
    }

    companion object {
        private const val EXPAND_MS = 180L
        private const val COLLAPSE_MS = 350L
        private const val HOLD_MS = 2000L
    }
}
