package com.simoneluconi.gccamera.ui.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.simoneluconi.gccamera.R
import com.simoneluconi.gccamera.camera.CameraController

/**
 * Luminance histogram of the preview, with clipping marked at both ends. It sits over the
 * viewfinder, so it is drawn like the other overlays: light on a translucent dark plate.
 */
class HistogramView(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    /** Filled by the activity from CameraController.copyHistogram, then [invalidate]d. */
    val bins = IntArray(CameraController.HIST_BINS)

    private val bg = fillPaint(R.color.overlay_mask)
    private val border = strokePaint(R.color.overlay_shadow, 1f)
    private val fill = fillPaint(R.color.overlay_line).apply { alpha = 170 }
    private val clip = fillPaint(R.color.warning)
    private val path = Path()
    private val rect = RectF()
    private val radius = dp(10f)

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        rect.set(0.5f, 0.5f, w - 0.5f, h - 0.5f)
        c.drawRoundRect(rect, radius, radius, bg)
        c.drawRoundRect(rect, radius, radius, border)
        var max = 1
        // Ignore the end bins for scaling, or a clipped sky flattens everything else
        for (i in 1 until bins.size - 1) if (bins[i] > max) max = bins[i]
        val pad = dp(6f)
        val left = pad
        val right = w - pad
        val bottom = h - pad
        val usable = h - 2 * pad
        val step = (right - left) / (bins.size - 1)
        path.reset()
        path.moveTo(left, bottom)
        for (i in bins.indices) {
            val v = (bins[i].toFloat() / max).coerceAtMost(1f)
            path.lineTo(left + i * step, bottom - v * usable)
        }
        path.lineTo(right, bottom)
        path.close()
        c.drawPath(path, fill)
        val total = bins.sum().coerceAtLeast(1)
        if (bins[0] * 200 > total) c.drawRect(left, pad, left + dp(2f), bottom, clip)
        if (bins[bins.lastIndex] * 200 > total) c.drawRect(right - dp(2f), pad, right, bottom, clip)
    }
}
