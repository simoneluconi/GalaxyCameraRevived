package com.simoneluconi.gccommon.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.simoneluconi.gccommon.R

/**
 * Luminance histogram, with clipping marked at both ends. [red], [green] and [blue] are
 * drawn as lines over it when [showRgb] is set. Fill the arrays, then [invalidate].
 */
class HistogramView(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    val luma = IntArray(BINS)
    val red = IntArray(BINS)
    val green = IntArray(BINS)
    val blue = IntArray(BINS)
    var showRgb = false
        set(v) {
            field = v
            invalidate()
        }

    private val bg = fillPaint(R.color.surface_variant)
    private val fill = fillPaint(R.color.on_surface_muted).apply { alpha = 150 }
    private val clip = fillPaint(R.color.warning)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.2f)
    }
    private val path = Path()
    private val rect = RectF()
    private val radius = dp(10f)

    fun clear() {
        luma.fill(0)
        red.fill(0)
        green.fill(0)
        blue.fill(0)
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        rect.set(0f, 0f, w, h)
        c.drawRoundRect(rect, radius, radius, bg)
        val pad = dp(6f)
        val left = pad
        val right = w - pad
        val bottom = h - pad
        val usable = h - 2 * pad
        val max = scaleOf(luma)
        fillCurve(luma, max, left, right, bottom, usable, close = true)
        c.drawPath(path, fill)
        if (showRgb) {
            drawLine(c, red, Color.rgb(239, 68, 68), left, right, bottom, usable)
            drawLine(c, green, Color.rgb(34, 197, 94), left, right, bottom, usable)
            drawLine(c, blue, Color.rgb(59, 130, 246), left, right, bottom, usable)
        }
        val total = luma.sum().coerceAtLeast(1)
        if (luma[0] * 200 > total) c.drawRect(left, pad, left + dp(2f), bottom, clip)
        if (luma[luma.lastIndex] * 200 > total) c.drawRect(right - dp(2f), pad, right, bottom, clip)
    }

    private fun drawLine(c: Canvas, bins: IntArray, rgb: Int, l: Float, r: Float, b: Float, u: Float) {
        fillCurve(bins, scaleOf(bins), l, r, b, u, close = false)
        line.color = rgb
        line.alpha = 200
        c.drawPath(path, line)
    }

    /** Ignore the end bins for scaling, or a clipped sky flattens everything else. */
    private fun scaleOf(bins: IntArray): Int {
        var max = 1
        for (i in 1 until bins.size - 1) if (bins[i] > max) max = bins[i]
        return max
    }

    private fun fillCurve(bins: IntArray, max: Int, left: Float, right: Float, bottom: Float, usable: Float, close: Boolean) {
        val step = (right - left) / (bins.size - 1)
        path.reset()
        if (close) path.moveTo(left, bottom)
        for (i in bins.indices) {
            val v = (bins[i].toFloat() / max).coerceAtMost(1f)
            val x = left + i * step
            val y = bottom - v * usable
            if (!close && i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        if (close) {
            path.lineTo(right, bottom)
            path.close()
        }
    }

    companion object {
        const val BINS = 64
    }
}
