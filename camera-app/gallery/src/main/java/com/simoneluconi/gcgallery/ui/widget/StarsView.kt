package com.simoneluconi.gcgallery.ui.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.simoneluconi.gccommon.R as C
import com.simoneluconi.gccommon.ui.fillPaint
import com.simoneluconi.gccommon.ui.strokePaint
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Five stars; tapping the current rating clears it. */
class StarsView(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    var rating = 0
        set(v) {
            field = v.coerceIn(0, 5)
            invalidate()
        }
    var onRate: ((Int) -> Unit)? = null

    private val on = fillPaint(C.color.rating)
    private val off = strokePaint(C.color.on_surface_muted, 1.4f).apply { strokeJoin = Paint.Join.ROUND }
    private val star = Path()

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val r = min(w / 5f, h.toFloat()) * 0.36f
        star.reset()
        for (i in 0 until 10) {
            val a = -PI / 2 + i * PI / 5
            val rr = if (i % 2 == 0) r else r * 0.45f
            val x = (cos(a) * rr).toFloat()
            val y = (sin(a) * rr).toFloat() + r * 0.1f
            if (i == 0) star.moveTo(x, y) else star.lineTo(x, y)
        }
        star.close()
    }

    override fun onDraw(c: Canvas) {
        val cw = width / 5f
        for (i in 0 until 5) {
            c.save()
            c.translate(cw * i + cw / 2, height / 2f)
            c.drawPath(star, if (i < rating) on else off)
            c.restore()
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_UP) {
            val i = (e.x / (width / 5f)).toInt().coerceIn(0, 4) + 1
            rating = if (i == rating) 0 else i
            onRate?.invoke(rating)
            performClick()
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()
}
