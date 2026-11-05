package com.simoneluconi.gccamera.ui.widget

import android.graphics.Paint
import android.graphics.Typeface
import android.util.TypedValue
import android.view.View

internal fun View.dp(v: Float) = v * resources.displayMetrics.density

internal fun View.sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

internal fun View.color(id: Int) = context.getColor(id)

internal val MEDIUM: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)

/** A text paint with tabular digits, so changing values do not shift. */
internal fun View.textPaint(sizeSp: Float, colorId: Int, medium: Boolean = false, align: Paint.Align = Paint.Align.LEFT) =
    Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp(sizeSp)
        color = color(colorId)
        typeface = if (medium) MEDIUM else Typeface.DEFAULT
        textAlign = align
        fontFeatureSettings = "tnum"
    }

internal fun View.fillPaint(colorId: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = color(colorId) }

internal fun View.strokePaint(colorId: Int, widthDp: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = color(colorId)
    style = Paint.Style.STROKE
    strokeWidth = dp(widthDp)
}

/** [degrees] as quarter turns: 0 upright, 1 = 90° clockwise, 2 = upside down, 3 = 90° anticlockwise. */
internal fun quarterOf(degrees: Float) = (Math.round(degrees / 90f) % 4 + 4) % 4

/**
 * +1 when the screen's right still points to the user's "more" end (right, or up when the
 * camera is held vertically), -1 when it points to the "less" end. Screen-right turns into
 * the user's up at a clockwise quarter (canvas rotate 90) and into the user's down at an
 * anticlockwise one.
 */
internal fun screenRightSign(degrees: Float) = if (quarterOf(degrees) <= 1) 1 else -1

/** Baseline that centres [p]'s text vertically on [cy]. */
internal fun centreBaseline(p: Paint, cy: Float) = cy - (p.descent() + p.ascent()) / 2f
