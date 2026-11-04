package com.simoneluconi.gccamera.ui.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.simoneluconi.gccamera.R

/** Segmented AUTO / P / A / S / M selector. */
class ModeSelectorView(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    var labels: List<String> = emptyList()
        set(v) {
            field = v
            requestLayout()
            invalidate()
        }

    // Segment edges, sized to the labels so "Auto" gets more room than "P"
    private var edges = FloatArray(0)

    var selected = 0
        set(v) {
            if (field != v) {
                field = v
                invalidate()
            }
        }

    var onSelect: ((Int) -> Unit)? = null

    /** Each label turns in place by this many degrees when the camera is held vertically. */
    var contentRotation = 0f
        set(v) {
            if (field != v) {
                field = v
                invalidate()
            }
        }

    private val bg = fillPaint(R.color.surface_variant)
    private val sel = fillPaint(R.color.accent)
    private val text = textPaint(12f, R.color.on_surface, true, Paint.Align.CENTER)
    private val textSel = textPaint(12f, R.color.on_accent, true, Paint.Align.CENTER)
    private val rect = RectF()
    private val inset = dp(3f)

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        val widths = labels.map { textSel.measureText(it) + dp(16f) }
        val scale = width / widths.sum().coerceAtLeast(1f)
        edges = FloatArray(labels.size + 1)
        for (i in widths.indices) edges[i + 1] = edges[i] + widths[i] * scale
    }

    override fun onDraw(c: Canvas) {
        if (labels.isEmpty() || edges.size != labels.size + 1) return
        val h = height.toFloat()
        rect.set(0f, 0f, width.toFloat(), h)
        c.drawRoundRect(rect, h / 2, h / 2, bg)
        val baseline = centreBaseline(text, h / 2)
        for (i in labels.indices) {
            if (i == selected) {
                rect.set(edges[i] + inset, inset, edges[i + 1] - inset, h - inset)
                c.drawRoundRect(rect, h / 2, h / 2, sel)
            }
            val cx = (edges[i] + edges[i + 1]) / 2
            c.save()
            c.rotate(contentRotation, cx, h / 2)
            c.drawText(labels[i], cx, baseline, if (i == selected) textSel else text)
            c.restore()
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.action == MotionEvent.ACTION_UP && labels.isNotEmpty() && edges.size == labels.size + 1) {
            var i = labels.lastIndex
            while (i > 0 && e.x < edges[i]) i--
            if (i != selected) {
                selected = i
                onSelect?.invoke(i)
            }
            performClick()
        }
        return true
    }

    override fun performClick() = super.performClick()
}
