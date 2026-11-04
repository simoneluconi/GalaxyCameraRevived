package com.simoneluconi.gccamera.ui.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.AttributeSet
import android.view.View
import com.simoneluconi.gccamera.R
import kotlin.math.abs

/** Everything drawn over the live image: grid, framing, level, AF frame, countdown, messages. */
class ViewfinderOverlayView(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    enum class Af { NONE, FOCUSING, OK, FAILED }

    var grid = "off"
        set(v) {
            if (field != v) {
                field = v
                invalidate()
            }
        }

    /** Picture aspect; anything but 4:3 is framed inside the 4:3 preview. */
    var aspect = "4:3"
        set(v) {
            if (field != v) {
                field = v
                val (aw, ah) = v.split(':').map { it.toFloat() }
                aspectRatio = aw / ah
                invalidate()
            }
        }

    private var aspectRatio = 4f / 3f

    var levelEnabled = true
    var af = Af.NONE
        set(v) {
            if (field != v) {
                field = v
                invalidate()
            }
        }

    /** The AF point as fractions of the view, and whether the user placed it (touch AF). */
    private var afX = 0.5f
    private var afY = 0.5f
    var afTouched = false
        private set

    fun setAfPoint(x: Float, y: Float, touched: Boolean) {
        afX = x
        afY = y
        afTouched = touched
        invalidate()
    }

    /** Face count text (face AF mode), or null. */
    var faceLabel: String? = null
        set(v) {
            if (field != v) {
                field = v
                invalidate()
            }
        }

    var countdown = 0
        set(v) {
            if (field != v) {
                field = v
                invalidate()
            }
        }

    /** UI rotation (degrees, animated) for the countdown and the banner. */
    var contentRotation = 0f
        set(v) {
            if (field != v) {
                field = v
                invalidate()
            }
        }

    private var roll = 0f
    private var banner: String? = null
    private var bannerVertical = false
    private var bannerWarning = false
    private var bannerLayout: StaticLayout? = null
    private var blink = false

    fun setRoll(r: Float) {
        // Redraw only for visible changes
        if (abs(r - roll) < 0.3f) return
        roll = r
        if (levelEnabled) invalidate()
    }

    fun setBanner(text: String?, warning: Boolean = false) {
        if (text == banner && warning == bannerWarning) return
        banner = text
        bannerWarning = warning
        bannerLayout = null
        invalidate()
    }

    /** A short white flash on the shutter callback, like a blackout in a real camera. */
    fun shutterBlink() {
        blink = true
        invalidate()
        postDelayed({
            blink = false
            invalidate()
        }, 90)
    }

    private val line = strokePaint(R.color.overlay_line, 1f)
    private val shadow = strokePaint(R.color.overlay_shadow, 3f)
    private val levelOk = strokePaint(R.color.ok, 2f)
    private val levelLine = strokePaint(R.color.overlay_line, 2f)
    private val mask = fillPaint(R.color.overlay_mask)
    private val afPaint = strokePaint(R.color.overlay_line, 2f)
    private val afOk = strokePaint(R.color.ok, 2.5f)
    private val afFail = strokePaint(R.color.error, 2.5f)
    private val afIdle = strokePaint(R.color.overlay_line, 1.2f)
    private val facePaint = textPaint(12f, R.color.on_surface, true)
    private val countPaint = textPaint(96f, R.color.overlay_line, true, Paint.Align.CENTER).apply {
        setShadowLayer(dp(4f), 0f, 0f, color(R.color.overlay_shadow))
    }
    private val bannerBg = fillPaint(R.color.surface)
    private val bannerWarn = fillPaint(R.color.warning)
    private val bannerText = TextPaint(textPaint(14f, R.color.on_surface, true))
    private val blinkPaint = fillPaint(R.color.overlay_line)
    private val rect = RectF()

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (blink) {
            c.drawRect(0f, 0f, w, h, blinkPaint)
            return
        }
        // Frame of the picture inside the 4:3 preview
        var fl = 0f
        var ft = 0f
        var fr = w
        var fb = h
        if (aspectRatio > w / h + 0.01f) {
            val fh = w / aspectRatio
            ft = (h - fh) / 2
            fb = ft + fh
            c.drawRect(0f, 0f, w, ft, mask)
            c.drawRect(0f, fb, w, h, mask)
        } else if (aspectRatio < w / h - 0.01f) {
            val fw = h * aspectRatio
            fl = (w - fw) / 2
            fr = fl + fw
            c.drawRect(0f, 0f, fl, h, mask)
            c.drawRect(fr, 0f, w, h, mask)
        }
        drawGrid(c, fl, ft, fr, fb)
        if (levelEnabled) drawLevel(c, w, h)
        drawAf(c, w, h)
        if (countdown > 0) {
            c.save()
            c.rotate(contentRotation, w / 2, h / 2)
            c.drawText(countdown.toString(), w / 2, centreBaseline(countPaint, h / 2), countPaint)
            c.restore()
        }
        drawBanner(c, w, h)
    }

    private fun drawGrid(c: Canvas, l: Float, t: Float, r: Float, b: Float) {
        val f = when (grid) {
            "thirds" -> 1f / 3f
            "golden" -> 0.382f
            else -> return
        }
        val gw = r - l
        val gh = b - t
        vLine(c, l + gw * f, t, b)
        vLine(c, r - gw * f, t, b)
        hLine(c, t + gh * f, l, r)
        hLine(c, b - gh * f, l, r)
    }

    private fun vLine(c: Canvas, x: Float, t: Float, b: Float) {
        c.drawLine(x, t, x, b, shadow)
        c.drawLine(x, t, x, b, line)
    }

    private fun hLine(c: Canvas, y: Float, l: Float, r: Float) {
        c.drawLine(l, y, r, y, shadow)
        c.drawLine(l, y, r, y, line)
    }

    private fun drawLevel(c: Canvas, w: Float, h: Float) {
        // Relative to the nearest hold (landscape, portrait either way, upside down)
        val base = Math.round(roll / 90f) * 90f
        val dev = roll - base
        if (abs(dev) > 30f) return
        val half = h * 0.18f
        val gap = h * 0.07f
        val cx = w / 2
        val cy = h / 2
        val p = if (abs(dev) < 0.8f) levelOk else levelLine
        c.save()
        // A canvas line turned by roll stays parallel to the real horizon
        c.rotate(base, cx, cy)
        // Fixed reference ticks at the ends
        c.drawLine(cx - half - gap - dp(10f), cy, cx - half - gap - dp(4f), cy, line)
        c.drawLine(cx + half + gap + dp(4f), cy, cx + half + gap + dp(10f), cy, line)
        c.rotate(dev, cx, cy)
        c.drawLine(cx - half - gap, cy, cx - gap, cy, shadow)
        c.drawLine(cx + gap, cy, cx + half + gap, cy, shadow)
        c.drawLine(cx - half - gap, cy, cx - gap, cy, p)
        c.drawLine(cx + gap, cy, cx + half + gap, cy, p)
        c.restore()
    }

    private fun drawAf(c: Canvas, w: Float, h: Float) {
        faceLabel?.let {
            val tw = facePaint.measureText(it) + dp(20f)
            val top = dp(34f)
            rect.set(dp(10f), top, dp(10f) + tw, top + dp(24f))
            c.drawRoundRect(rect, dp(12f), dp(12f), bannerBg)
            c.drawText(it, rect.left + dp(10f), centreBaseline(facePaint, rect.centerY()), facePaint)
        }
        val p = when (af) {
            // A touched point stays marked, thinner, until it is reset
            Af.NONE -> if (afTouched) afIdle else return
            Af.FOCUSING -> afPaint
            Af.OK -> afOk
            Af.FAILED -> afFail
        }
        val s = h * 0.11f
        val cx = (w * afX).coerceIn(s, w - s)
        val cy = (h * afY).coerceIn(s, h - s)
        val k = s * 0.35f
        // Four corners of the centre AF area
        for ((sx, sy) in CORNERS) {
            val x = cx + sx * s
            val y = cy + sy * s
            c.drawLine(x, y, x - sx * k, y, p)
            c.drawLine(x, y, x, y - sy * k, p)
        }
    }

    private fun drawBanner(c: Canvas, w: Float, h: Float) {
        val text = banner ?: return
        val vertical = Math.round(contentRotation / 90f) % 2 != 0
        if (vertical != bannerVertical) {
            bannerVertical = vertical
            bannerLayout = null
        }
        val maxW = ((if (vertical) h else w) * 0.7f).toInt()
        val layout = bannerLayout ?: StaticLayout.Builder.obtain(text, 0, text.length, bannerText, maxW)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .build()
            .also { bannerLayout = it }
        val pad = dp(14f)
        val bw = layout.width + 2 * pad
        val bh = layout.height + 2 * pad
        val left = (w - bw) / 2
        // Below the centre in landscape; centred when turned, where "below" is a side
        val top = (if (contentRotation == 0f) h * 0.68f else h / 2) - bh / 2
        c.save()
        c.rotate(contentRotation, w / 2, top + bh / 2)
        rect.set(left, top, left + bw, top + bh)
        c.drawRoundRect(rect, dp(14f), dp(14f), bannerBg)
        if (bannerWarning) c.drawRect(left + dp(6f), top + pad, left + dp(9f), top + bh - pad, bannerWarn)
        c.translate(left + pad, top + pad)
        layout.draw(c)
        c.restore()
    }

    companion object {
        private val CORNERS = arrayOf(-1f to -1f, 1f to -1f, -1f to 1f, 1f to 1f)
    }
}
