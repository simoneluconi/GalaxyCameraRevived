package com.simoneluconi.gccommon.ui

import android.graphics.Paint
import android.graphics.Typeface
import android.util.TypedValue
import android.view.View

fun View.dp(v: Float) = v * resources.displayMetrics.density

fun View.sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

fun View.color(id: Int) = context.getColor(id)

val MEDIUM: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)

/** A text paint with tabular digits, so changing values do not shift. */
fun View.textPaint(sizeSp: Float, colorId: Int, medium: Boolean = false, align: Paint.Align = Paint.Align.LEFT) =
    Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp(sizeSp)
        color = color(colorId)
        typeface = if (medium) MEDIUM else Typeface.DEFAULT
        textAlign = align
        fontFeatureSettings = "tnum"
    }

fun View.fillPaint(colorId: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = color(colorId) }

fun View.strokePaint(colorId: Int, widthDp: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = color(colorId)
    style = Paint.Style.STROKE
    strokeWidth = dp(widthDp)
}

/** Baseline that centres [p]'s text vertically on [cy]. */
fun centreBaseline(p: Paint, cy: Float) = cy - (p.descent() + p.ascent()) / 2f
