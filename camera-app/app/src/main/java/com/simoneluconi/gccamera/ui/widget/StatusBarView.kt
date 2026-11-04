package com.simoneluconi.gccamera.ui.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.simoneluconi.gccamera.R

/**
 * The app's own status bar (the system bars are hidden, and later the app is the launcher):
 * battery, clock, storage and shots left, GPS, Wi-Fi, stabilizer and file format.
 */
class StatusBarView(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    enum class Gps { OFF, SEARCHING, FIX }

    var batteryPct = -1
    var charging = false
    var time = ""
    var storageLabel = ""
    var storageMissing = false
    var shotsLeft = -1
    var gps = Gps.OFF
    var wifi = false
    var ois = false
    var format = ""

    private val bg = fillPaint(R.color.status_bar_bg)
    private val text = textPaint(12f, R.color.on_surface, true)
    private val muted = textPaint(12f, R.color.on_surface_muted)
    private val accent = textPaint(12f, R.color.accent, true)
    private val warn = textPaint(12f, R.color.error, true)
    private val batteryLow = fillPaint(R.color.error)
    private val batteryFill = fillPaint(R.color.on_surface)
    private val batteryCharge = fillPaint(R.color.ok)
    private val batteryOutline = strokePaint(R.color.on_surface, 1.2f)
    private val boltFill = fillPaint(R.color.on_accent)
    private val boltEdge = strokePaint(R.color.on_surface, 1.5f).apply { strokeJoin = Paint.Join.ROUND }
    private val bolt = Path()
    private val rect = RectF()

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        c.drawRect(0f, 0f, w, h, bg)
        val cy = h / 2
        val base = centreBaseline(text, cy)
        val gap = dp(12f)

        // Left: battery and clock
        var x = dp(10f)
        if (batteryPct >= 0) {
            val bw = dp(20f)
            val bh = dp(10f)
            rect.set(x, cy - bh / 2, x + bw, cy + bh / 2)
            c.drawRoundRect(rect, dp(2f), dp(2f), batteryOutline)
            c.drawRect(x + bw, cy - bh / 4, x + bw + dp(2f), cy + bh / 4, batteryFill)
            val inner = dp(2f)
            val fillW = (bw - 2 * inner) * batteryPct / 100f
            val p = when {
                charging -> batteryCharge
                batteryPct <= 15 -> batteryLow
                else -> batteryFill
            }
            c.drawRect(x + inner, cy - bh / 2 + inner, x + inner + fillW, cy + bh / 2 - inner, p)
            if (charging) drawBolt(c, x + bw / 2, cy, bh)
            x += bw + dp(6f)
            val pct = "$batteryPct%"
            c.drawText(pct, x, base, if (batteryPct <= 15 && !charging) warn else text)
            x += text.measureText(pct) + gap
        }
        c.drawText(time, x, base, text)

        // Right, from the edge inwards: storage, GPS, Wi-Fi, OIS, format
        var r = w - dp(10f)
        r = drawRight(c, storageText(), r, base, if (storageMissing) warn else text) - gap
        if (gps != Gps.OFF) r = drawRight(c, "GPS", r, base, if (gps == Gps.FIX) accent else muted) - gap
        if (wifi) r = drawRight(c, "Wi-Fi", r, base, text) - gap
        if (ois) r = drawRight(c, "OIS", r, base, muted) - gap
        if (format.isNotEmpty()) drawRight(c, format, r, base, muted)
    }

    /** A lightning bolt across the battery, outlined so it reads on the green fill. */
    private fun drawBolt(c: Canvas, cx: Float, cy: Float, size: Float) {
        val s = size * 0.75f
        bolt.reset()
        bolt.moveTo(cx + s * 0.15f, cy - s)
        bolt.lineTo(cx - s * 0.45f, cy + s * 0.12f)
        bolt.lineTo(cx - s * 0.02f, cy + s * 0.12f)
        bolt.lineTo(cx - s * 0.15f, cy + s)
        bolt.lineTo(cx + s * 0.45f, cy - s * 0.12f)
        bolt.lineTo(cx + s * 0.02f, cy - s * 0.12f)
        bolt.close()
        c.drawPath(bolt, boltEdge)
        c.drawPath(bolt, boltFill)
    }

    private fun storageText() = if (shotsLeft >= 0 && !storageMissing) "$storageLabel  $shotsLeft" else storageLabel

    private fun drawRight(c: Canvas, s: String, right: Float, base: Float, p: Paint): Float {
        val w = p.measureText(s)
        c.drawText(s, right - w, base, p)
        return right - w
    }
}
