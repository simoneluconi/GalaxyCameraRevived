package com.simoneluconi.gcgallery.ui.widget

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.OverScroller
import com.simoneluconi.gccommon.ui.dp
import com.simoneluconi.gcgallery.image.Orientation
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Full-screen photos with swipe between them, pinch and double-tap zoom up to 300%, and a
 * sharper "detail" bitmap of the visible area once zoomed past the screen decode. Pictures are
 * drawn through their EXIF orientation; nothing is rotated in memory.
 */
class PhotoPagerView(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    /** [bitmap] holds [rawW] x [rawH] stored pixels scaled down; [orientation] turns them upright. */
    class Page(
        val bitmap: Bitmap,
        val rawW: Int,
        val rawH: Int,
        val orientation: Int,
        /** The grid thumbnail standing in until the screen decode lands: no detail decodes. */
        val preview: Boolean = false,
    ) {
        val width get() = if (Orientation.swapsAxes(orientation)) rawH else rawW
        val height get() = if (Orientation.swapsAxes(orientation)) rawW else rawH

        fun turned(o: Int) = Page(bitmap, rawW, rawH, o, preview)
    }

    interface Callback {
        fun pageCount(): Int
        fun page(index: Int): Page?
        fun onPageChanged(index: Int)
        fun onTap()
        /** The view settled zoomed in: decode [region] (stored pixels) of page [index] at [sample]. */
        fun onDetailWanted(index: Int, region: Rect, sample: Int)
        fun onZoomChanged(percent: Int) = Unit
        /** At rest after a zoom or a pan: [visibleRegion] is what is on screen now. */
        fun onSettled(index: Int) = Unit
        /** While a zoom or a pan moves (every step): [visibleRegion] has changed. */
        fun onViewChanged(index: Int) = Unit
    }

    var callback: Callback? = null
    var index = 0
        private set

    /** Width kept free on the right (the info panel, held level). */
    var insetRight = 0f
        private set
    /** Height kept free at the top (the bar, while the info panel is open). */
    var insetTop = 0f
        private set
    /** Height kept free at the bottom (the info panel, held vertically). */
    var insetBottom = 0f
        private set

    fun setInsets(right: Float, top: Float, bottom: Float = 0f) {
        // Whether it was at fit before the space changed: after, the old scale no longer is
        val wasAtFit = isAtFit()
        insetRight = right
        insetTop = top
        insetBottom = bottom
        if (wasAtFit) fit() else clampPan()
        invalidate()
    }

    // Current page: upright px → view px is scale, then (tx, ty)
    private var scale = 1f
    private var tx = 0f
    private var ty = 0f
    private var pageOffset = 0f
    private var curW = 0
    private var curH = 0
    private var curOrientation = 0
    private var fitted = false

    private var detail: Bitmap? = null
    private val detailRect = Rect()
    private var mask: Bitmap? = null
    private var maskSource: Bitmap? = null

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val maskPaint = Paint().apply { color = Color.rgb(255, 45, 85) }
    private val m = Matrix()
    private val o = Matrix()
    private val inv = Matrix()
    private val rf = RectF()
    private val scroller = OverScroller(context)
    private var flinging = false
    private var animator: ValueAnimator? = null
    private val gap = dp(16f)
    private val contentW get() = width - insetRight
    private val contentH get() = height - insetTop - insetBottom

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean {
            scroller.forceFinished(true)
            flinging = false
            return true
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            if (scaleDetector.isInProgress || e2.pointerCount > 1) return true
            if (animator?.isRunning == true) return true
            if (pageOffset != 0f || isAtFit()) {
                val n = callback?.pageCount() ?: 0
                val atEnd = (index == 0 && pageOffset - dx > 0) || (index >= n - 1 && pageOffset - dx < 0)
                pageOffset -= if (atEnd) dx * 0.35f else dx
            } else {
                tx -= dx
                ty -= dy
                clampPan()
                callback?.onViewChanged(index)
            }
            invalidate()
            return true
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            if (scaleDetector.isInProgress) return false
            if (pageOffset != 0f || isAtFit()) {
                flingPage(vx)
                return true
            }
            val (minX, maxX) = panRange(curW * scale, contentW)
            val (minY, maxY) = panRangeY(curH * scale)
            scroller.fling(tx.toInt(), ty.toInt(), vx.toInt(), vy.toInt(), minX.toInt(), maxX.toInt(), minY.toInt(), maxY.toInt())
            flinging = true
            postInvalidateOnAnimation()
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            toggleZoom(e.x, e.y)
            return true
        }

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            callback?.onTap()
            return true
        }
    })

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(d: ScaleGestureDetector) = pageOffset == 0f && animator?.isRunning != true

        override fun onScale(d: ScaleGestureDetector): Boolean {
            zoomAround(scale * d.scaleFactor, d.focusX, d.focusY)
            return true
        }

        override fun onScaleEnd(d: ScaleGestureDetector) = settled()
    })

    /** Jumps to [i] at fit (start, or after the current photo was removed). */
    fun show(i: Int) {
        animator?.cancel()
        index = i
        pageOffset = 0f
        fitted = false
        clearDetail()
        adopt()
        invalidate()
    }

    /** Page [i] has a new bitmap (sharper, or turned). */
    fun pageUpdated(i: Int) {
        if (i == index) adopt()
        invalidate()
    }

    fun setDetail(i: Int, bmp: Bitmap, region: Rect) {
        if (i != index || callback?.page(i)?.preview != false) return
        detail = bmp
        detailRect.set(region)
        invalidate()
    }

    /** Blinking highlight-clipping overlay: an ALPHA_8 mask the size of [source]. */
    fun setClipMask(source: Bitmap?, alphaMask: Bitmap?) {
        maskSource = source
        mask = alphaMask
        invalidate()
    }

    fun setMaskAlpha(a: Int) {
        maskPaint.alpha = a
        if (mask != null) invalidate()
    }

    fun zoomBy(f: Float) {
        if (pageOffset != 0f) return
        zoomAround(scale * f, contentW / 2, insetTop + contentH / 2)
        removeCallbacks(settleLater)
        postDelayed(settleLater, 250)
    }

    /**
     * The part of the current photo on screen, in upright pixels of the file (0..width,
     * 0..height); null at fit, when all of it is. Zoomed in, the photo may pan under the bars.
     */
    fun visibleRegion(): RectF? {
        if (isAtFit() || curW == 0) return null
        return RectF(
            max(0f, -tx / scale), max(0f, -ty / scale),
            min(curW.toFloat(), (contentW - tx) / scale), min(curH.toFloat(), (height - insetBottom - ty) / scale),
        ).takeIf { it.width() > 0 && it.height() > 0 }
    }

    fun isAtFit(): Boolean {
        val p = callback?.page(index) ?: return true
        return scale <= fitScale(p.width, p.height) * 1.01f
    }

    /** 100% (one stored pixel per screen pixel) from fit; fit from anything else. */
    fun toggleZoom(fx: Float = contentW / 2, fy: Float = insetTop + contentH / 2) {
        if (isAtFit()) zoomToDetail(fx, fy) else fitAnimated(fx, fy)
    }

    /** 100% (or twice fit for a small picture), from wherever the zoom is. */
    fun zoomToDetail(fx: Float = contentW / 2, fy: Float = insetTop + contentH / 2) {
        // Not mid-swipe: the zoom would cancel the page's settle and leave it half-way
        if (pageOffset != 0f) return
        val p = callback?.page(index) ?: return
        val fit = fitScale(p.width, p.height)
        animateZoom((if (fit < 1f) 1f else fit * 2f).coerceAtMost(maxScale(fit)), fx, fy)
    }

    /** A small picture is already past MAX_SCALE at fit: then fit is also the most. */
    private fun maxScale(fit: Float) = max(fit, MAX_SCALE)

    fun fitAnimated(fx: Float = contentW / 2, fy: Float = insetTop + contentH / 2) {
        if (pageOffset != 0f) return
        val p = callback?.page(index) ?: return
        animateZoom(fitScale(p.width, p.height), fx, fy)
    }

    // --- Geometry

    private fun fitScale(w: Int, h: Int) = if (w <= 0 || h <= 0 || width == 0) 1f else min(contentW / w, contentH / h)

    /** Takes over the current page's size, keeping what is shown when only the resolution changed. */
    private fun adopt() {
        val p = callback?.page(index) ?: return
        if (!fitted || curW == 0 || p.orientation != curOrientation && Orientation.swapsAxes(p.orientation) != Orientation.swapsAxes(curOrientation)) {
            curW = p.width
            curH = p.height
            curOrientation = p.orientation
            fitted = true
            clearDetail()
            fit()
            return
        }
        curOrientation = p.orientation
        if (p.width != curW) {
            scale *= curW.toFloat() / p.width
            curW = p.width
            curH = p.height
            clearDetail()
        }
        clampPan()
    }

    private fun fit() {
        if (curW == 0 || width == 0) return
        scale = fitScale(curW, curH)
        tx = (contentW - curW * scale) / 2
        ty = insetTop + (contentH - curH * scale) / 2
        callback?.onZoomChanged((scale * 100).toInt())
    }

    private fun panRange(size: Float, view: Float): Pair<Float, Float> =
        if (size <= view) ((view - size) / 2).let { it to it } else (view - size) to 0f

    /** Centred between the insets when it fits; zoomed in, it may pan up under the bar. */
    private fun panRangeY(size: Float): Pair<Float, Float> =
        if (size <= contentH) (insetTop + (contentH - size) / 2).let { it to it } else (height - insetBottom - size) to insetTop

    private fun clampPan() {
        val (minX, maxX) = panRange(curW * scale, contentW)
        val (minY, maxY) = panRangeY(curH * scale)
        tx = tx.coerceIn(minX, maxX)
        ty = ty.coerceIn(minY, maxY)
    }

    private fun zoomAround(target: Float, fx: Float, fy: Float) {
        val p = callback?.page(index) ?: return
        val fit = fitScale(p.width, p.height)
        val ns = target.coerceIn(fit, maxScale(fit))
        val k = ns / scale
        tx = fx - (fx - tx) * k
        ty = fy - (fy - ty) * k
        scale = ns
        clampPan()
        invalidate()
        callback?.onZoomChanged((scale * 100).toInt())
        callback?.onViewChanged(index)
    }

    private fun animateZoom(target: Float, fx: Float, fy: Float) {
        animator?.cancel()
        animator = ValueAnimator.ofFloat(scale, target).apply {
            duration = 220
            interpolator = DecelerateInterpolator()
            addUpdateListener { zoomAround(it.animatedValue as Float, fx, fy) }
            doOnEnd { settled() }
            start()
        }
    }

    private fun flingPage(vx: Float) {
        val n = callback?.pageCount() ?: return
        val w = contentW + gap
        val fast = abs(vx) > dp(600f)
        val target = when {
            fast && vx < 0 && index + 1 < n -> -w
            fast && vx > 0 && index > 0 -> w
            !fast && pageOffset < -contentW / 3 && index + 1 < n -> -w
            !fast && pageOffset > contentW / 3 && index > 0 -> w
            else -> 0f
        }
        settlePage(target)
    }

    private fun settlePage(target: Float) {
        animator?.cancel()
        animator = ValueAnimator.ofFloat(pageOffset, target).apply {
            duration = (180 + 120 * abs(target - pageOffset) / max(1f, contentW)).toLong()
            interpolator = DecelerateInterpolator(1.5f)
            addUpdateListener {
                pageOffset = it.animatedValue as Float
                invalidate()
            }
            doOnEnd {
                pageOffset = 0f
                if (target != 0f) {
                    index += if (target < 0) 1 else -1
                    fitted = false
                    clearDetail()
                    setClipMask(null, null)
                    adopt()
                    callback?.onPageChanged(index)
                }
                invalidate()
            }
            start()
        }
    }

    private val settleLater = Runnable { settled() }

    /** At rest: ask for a sharper bitmap of what is visible if the screen decode is being enlarged. */
    private fun settled() {
        if (pageOffset != 0f || animator?.isRunning == true || flinging) return
        val p = callback?.page(index) ?: return
        callback?.onSettled(index)
        // A stand-in thumbnail's coordinates are not the file's
        if (p.preview) return
        // Screen pixels per bitmap pixel
        val enlarge = scale * p.rawW / p.bitmap.width.toFloat()
        if (enlarge <= 1.15f) {
            clearDetail()
            return
        }
        rf.set(
            max(0f, -tx / scale), max(0f, -ty / scale),
            min(curW.toFloat(), (contentW - tx) / scale), min(curH.toFloat(), (height - insetBottom - ty) / scale),
        )
        Orientation.matrix(p.orientation, p.rawW.toFloat(), p.rawH.toFloat(), o)
        o.invert(inv)
        inv.mapRect(rf)
        val r = Rect()
        rf.roundOut(r)
        if (!r.intersect(0, 0, p.rawW, p.rawH) || r.isEmpty) return
        var sample = 1
        while (sample * 2 <= 1f / scale) sample *= 2
        callback?.onDetailWanted(index, r, sample)
    }

    private fun clearDetail() {
        detail = null
    }

    // --- Drawing

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        if (!fitted) return
        // A turn of the device: start again from fit, the old region no longer matches
        clearDetail()
        fit()
    }

    override fun computeScroll() {
        if (!flinging) return
        if (scroller.computeScrollOffset()) {
            tx = scroller.currX.toFloat()
            ty = scroller.currY.toFloat()
            clampPan()
            callback?.onViewChanged(index)
            postInvalidateOnAnimation()
        } else {
            flinging = false
            settled()
        }
    }

    override fun onDraw(c: Canvas) {
        val cb = callback ?: return
        val n = cb.pageCount()
        if (n == 0) return
        cb.page(index)?.let { p ->
            if (!fitted) adopt()
            drawPage(c, p, scale, tx + pageOffset, ty, current = true)
        }
        if (pageOffset < 0 && index + 1 < n) cb.page(index + 1)?.let { drawFitted(c, it, contentW + gap + pageOffset) }
        if (pageOffset > 0 && index > 0) cb.page(index - 1)?.let { drawFitted(c, it, -(contentW + gap) + pageOffset) }
    }

    private fun drawFitted(c: Canvas, p: Page, dx: Float) {
        val s = fitScale(p.width, p.height)
        drawPage(c, p, s, dx + (contentW - p.width * s) / 2, insetTop + (contentH - p.height * s) / 2, current = false)
    }

    private fun drawPage(c: Canvas, p: Page, s: Float, x: Float, y: Float, current: Boolean) {
        Orientation.matrix(p.orientation, p.rawW.toFloat(), p.rawH.toFloat(), o)
        m.setScale(p.rawW.toFloat() / p.bitmap.width, p.rawH.toFloat() / p.bitmap.height)
        m.postConcat(o)
        m.postScale(s, s)
        m.postTranslate(x, y)
        c.drawBitmap(p.bitmap, m, paint)
        if (!current) return
        detail?.let { d ->
            m.setScale(detailRect.width().toFloat() / d.width, detailRect.height().toFloat() / d.height)
            m.postTranslate(detailRect.left.toFloat(), detailRect.top.toFloat())
            m.postConcat(o)
            m.postScale(s, s)
            m.postTranslate(x, y)
            c.drawBitmap(d, m, paint)
        }
        val mk = mask
        if (mk != null && maskSource === p.bitmap && maskPaint.alpha > 0) {
            m.setScale(p.rawW.toFloat() / mk.width, p.rawH.toFloat() / mk.height)
            m.postConcat(o)
            m.postScale(s, s)
            m.postTranslate(x, y)
            c.drawBitmap(mk, m, maskPaint)
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(e)
        gestures.onTouchEvent(e)
        if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) {
            if (animator?.isRunning != true) {
                if (pageOffset != 0f) settlePage(0f) else if (!flinging) settled()
            }
        }
        return true
    }

    private inline fun ValueAnimator.doOnEnd(crossinline block: () -> Unit) {
        addListener(object : android.animation.AnimatorListenerAdapter() {
            private var cancelled = false
            override fun onAnimationCancel(animation: android.animation.Animator) {
                cancelled = true
            }

            override fun onAnimationEnd(animation: android.animation.Animator) {
                if (!cancelled) block()
            }
        })
    }

    companion object {
        /** 300%: enough to judge focus on a 4.8" screen. */
        const val MAX_SCALE = 3f
    }
}
