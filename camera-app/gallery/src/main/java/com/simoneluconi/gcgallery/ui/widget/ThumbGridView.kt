package com.simoneluconi.gcgallery.ui.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.os.SystemClock
import android.text.format.DateFormat
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.OverScroller
import com.simoneluconi.gccommon.R as C
import com.simoneluconi.gccommon.ui.centreBaseline
import com.simoneluconi.gccommon.ui.dp
import com.simoneluconi.gccommon.ui.fillPaint
import com.simoneluconi.gccommon.ui.strokePaint
import com.simoneluconi.gccommon.ui.textPaint
import com.simoneluconi.gcgallery.R
import com.simoneluconi.gcgallery.data.Photo
import com.simoneluconi.gcgallery.data.RatingStore
import com.simoneluconi.gcgallery.thumbs.ThumbLoader
import java.util.Calendar
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * The photo grid as one Canvas view: day sections, square centre-cropped cells, RAW and star
 * badges, a fast scroller, drag selection and pinch for the column count. No per-cell views and
 * no allocations while scrolling.
 */
class ThumbGridView(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    interface Callback {
        /** [cell] in screen coordinates, for the opening animation. */
        fun onOpen(index: Int, cell: Rect)
        fun onSelectionChanged()
        fun onColumnsChanged(columns: Int) = Unit
    }

    var callback: Callback? = null
    var loader: ThumbLoader? = null
    var ratings: RatingStore? = null

    var photos: List<Photo> = emptyList()
        private set
    val selected = HashSet<Long>()
    var selecting = false
        private set
    var columns = 6
        private set

    private class Section(val start: Int, val end: Int, val title: String, val count: String) {
        var top = 0f
        var gridTop = 0f
        var bottom = 0f
    }

    private var sections: List<Section> = emptyList()
    private var cell = 0f
    private var contentHeight = 0f
    private var offset = 0f
    private val maxOffset get() = max(0f, contentHeight - height)

    private val gap = dp(2f)
    private val headerH = dp(44f)
    private val sectionGap = dp(6f)
    private val badgePad = dp(4f)

    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val placeholder = fillPaint(C.color.surface_variant)
    private val headerText = textPaint(15f, C.color.on_surface, medium = true)
    private val headerCount = textPaint(13f, C.color.on_surface_muted, align = Paint.Align.RIGHT)
    private val badgeBg = fillPaint(C.color.overlay_mask)
    private val badgeText = textPaint(9f, android.R.color.white, medium = true).apply { letterSpacing = 0.06f }
    private val starText = textPaint(10f, C.color.rating, medium = true, align = Paint.Align.RIGHT)
    private val selectFill = fillPaint(C.color.accent)
    private val selectRing = strokePaint(C.color.overlay_line, 2f)
    private val selectRingDark = strokePaint(C.color.on_surface_muted, 2f)
    private val selectShade = fillPaint(C.color.accent).apply { alpha = 40 }
    private val check = strokePaint(C.color.on_accent, 2.2f).apply {
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val scrollThumb = fillPaint(C.color.on_surface_muted)
    private val bubble = fillPaint(C.color.accent)
    private val bubbleText = textPaint(14f, C.color.on_accent, medium = true, align = Paint.Align.RIGHT)

    private val dst = RectF()
    private val src = Rect()
    private val tmp = RectF()
    private val checkPath = Path()

    /** Thumbnails that just arrived fade in: id → arrival time. */
    private val fading = HashMap<Long, Long>()

    private val scroller = OverScroller(context)
    private var scrollerShownAt = 0L
    private var fastDragging = false

    private var dragSelecting = false
    private var dragAnchor = -1
    private var dragAdd = true
    private var dragBase: Set<Long> = emptySet()
    private var dragY = 0f
    private var dragX = 0f

    private var wantedFirst = -2
    private var wantedLast = -2
    /** A scroll asked for before the first layout. */
    private var pendingIndex = -1

    /** The status bar's height: a band of background there, so photos do not scroll under the clock. */
    var topScrim = 0
        set(v) {
            field = v
            invalidate()
        }
    private val scrimPaint = fillPaint(C.color.background).apply { alpha = 235 }

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean {
            scroller.forceFinished(true)
            return true
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            if (e2.pointerCount > 1) return true
            scrollTo(offset + dy)
            return true
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            scroller.fling(0, offset.toInt(), 0, -vy.toInt(), 0, 0, 0, maxOffset.toInt())
            postInvalidateOnAnimation()
            return true
        }

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            tap(e.x, e.y)
            return true
        }

        override fun onLongPress(e: MotionEvent) = startDragSelect(e.x, e.y)
    })

    private var pinch = 1f
    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(d: ScaleGestureDetector): Boolean {
            pinch = 1f
            return true
        }

        override fun onScale(d: ScaleGestureDetector): Boolean {
            pinch *= d.scaleFactor
            if (pinch > 1.3f) {
                setColumns(columns - 1)
                pinch = 1f
            } else if (pinch < 0.77f) {
                setColumns(columns + 1)
                pinch = 1f
            }
            return true
        }
    })

    /** Set once every field above is initialised (see setPadding). */
    private var ready = true

    /** Shows [list]. With [keepPosition], the photo at the top stays there (a reload, not an album switch). */
    fun setPhotos(list: List<Photo>, keepPosition: Boolean) {
        val anchor = if (keepPosition && cell > 0f) photos.getOrNull(firstVisibleIndex())?.id else null
        if (!keepPosition) pendingIndex = -1
        // A drag selection goes on from the same photo, wherever the new list has it
        val anchorId = if (dragSelecting) photos.getOrNull(dragAnchor)?.id else null
        photos = list
        val ids = HashSet<Long>(list.size)
        for (p in list) ids.add(p.id)
        val shrunk = selected.retainAll(ids)
        if (dragSelecting) {
            dragAnchor = anchorId?.let { id -> list.indexOfFirst { it.id == id } } ?: -1
            if (dragAnchor < 0) {
                dragSelecting = false
                removeCallbacks(autoScroll)
            }
        }
        if (selecting && selected.isEmpty()) {
            selecting = false
            callback?.onSelectionChanged()
        } else if (shrunk) {
            // A selected photo went away elsewhere (trashed from the transfer page): the count changes
            callback?.onSelectionChanged()
        }
        sections = buildSections(list)
        layoutSections()
        wantedFirst = -2
        if (anchor != null) list.indexOfFirst { it.id == anchor }.takeIf { it >= 0 }?.let(::scrollToIndex)
        else if (!keepPosition) offset = 0f
        offset = offset.coerceIn(0f, maxOffset)
        invalidate()
    }

    fun setColumns(n: Int) {
        val c = n.coerceIn(MIN_COLUMNS, MAX_COLUMNS)
        if (c == columns) return
        val anchor = firstVisibleIndex()
        columns = c
        layoutSections()
        scrollToIndex(anchor)
        wantedFirst = -2
        invalidate()
        callback?.onColumnsChanged(c)
    }

    /** Asks the loader again for what is on screen, e.g. after cache entries were evicted. */
    fun refreshWanted() {
        wantedFirst = -2
        invalidate()
    }

    override fun setPadding(left: Int, top: Int, right: Int, bottom: Int) {
        // View's constructor may get here before this class's fields exist
        if (!ready) return super.setPadding(left, top, right, bottom)
        val changed = left != paddingLeft || top != paddingTop || right != paddingRight || bottom != paddingBottom
        val anchor = if (changed && cell > 0f) firstVisibleIndex() else -1
        super.setPadding(left, top, right, bottom)
        if (!changed) return
        // Cells and sections depend on the padding (insets can change after the first layout)
        layoutSections()
        if (anchor >= 0) scrollToIndex(anchor)
        wantedFirst = -2
    }

    fun onThumbArrived(id: Long) {
        fading[id] = SystemClock.uptimeMillis()
        invalidate()
    }

    fun selectAll() {
        selecting = true
        for (p in photos) selected.add(p.id)
        invalidate()
        callback?.onSelectionChanged()
    }

    fun clearSelection() {
        if (!selecting && selected.isEmpty()) return
        selected.clear()
        selecting = false
        invalidate()
        callback?.onSelectionChanged()
    }

    fun selectedPhotos(): List<Photo> = photos.filter { it.id in selected }

    fun firstVisibleIndex(): Int {
        if (photos.isEmpty() || sections.isEmpty()) return 0
        val y = offset + paddingTop
        val s = sections[sectionAt(y)]
        val row = max(0, floor((y - s.gridTop) / (cell + gap)).toInt())
        return min(s.start + row * columns, s.end - 1)
    }

    fun scrollToIndex(i: Int) {
        if (i !in photos.indices || sections.isEmpty()) return
        if (cell <= 0f) {
            pendingIndex = i
            return
        }
        val s = sections[sectionOf(i)]
        val row = (i - s.start) / columns
        val y = if (row == 0) s.top else s.gridTop + row * (cell + gap)
        offset = (y - paddingTop).coerceIn(0f, maxOffset)
        invalidate()
    }

    /** Where photo [i] sits on screen now, or null when it is scrolled away. */
    fun cellOnScreen(i: Int): Rect? {
        if (i !in photos.indices) return null
        val s = sections[sectionOf(i)]
        val local = i - s.start
        val x = paddingLeft + (local % columns) * (cell + gap)
        val y = s.gridTop + (local / columns) * (cell + gap) - offset
        if (y + cell < 0 || y > height) return null
        val loc = IntArray(2)
        getLocationOnScreen(loc)
        return Rect((loc[0] + x).toInt(), (loc[1] + y).toInt(), (loc[0] + x + cell).toInt(), (loc[1] + y + cell).toInt())
    }

    // --- Layout

    private fun buildSections(list: List<Photo>): List<Section> {
        val out = ArrayList<Section>()
        val cal = Calendar.getInstance()
        val today = Calendar.getInstance()
        val yesterday = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
        val pattern = DateFormat.getBestDateTimePattern(Locale.getDefault(), "EEEEdMMMMyyyy")
        var start = 0
        var key = -1
        fun close(end: Int) {
            if (end <= start) return
            cal.timeInMillis = list[start].dateTaken
            val title = when {
                sameDay(cal, today) -> context.getString(R.string.today)
                sameDay(cal, yesterday) -> context.getString(R.string.yesterday)
                else -> DateFormat.format(pattern, cal).toString().replaceFirstChar { it.titlecase() }
            }
            out.add(Section(start, end, title, (end - start).toString()))
        }
        for (i in list.indices) {
            cal.timeInMillis = list[i].dateTaken
            val k = cal.get(Calendar.YEAR) * 1000 + cal.get(Calendar.DAY_OF_YEAR)
            if (k != key) {
                close(i)
                start = i
                key = k
            }
        }
        close(list.size)
        return out
    }

    private fun sameDay(a: Calendar, b: Calendar) =
        a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)

    private fun layoutSections() {
        if (width == 0) return
        cell = (width - paddingLeft - paddingRight - gap * (columns - 1)) / columns
        var y = paddingTop.toFloat()
        for (s in sections) {
            s.top = y
            s.gridTop = y + headerH
            val rows = ceil((s.end - s.start) / columns.toFloat()).toInt()
            s.bottom = s.gridTop + rows * cell + (rows - 1) * gap
            y = s.bottom + sectionGap
        }
        contentHeight = y + paddingBottom
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        // Before the first layout every section sits at 0, so there is no position to keep
        val anchor = if (pendingIndex >= 0) pendingIndex else if (cell > 0f) firstVisibleIndex() else -1
        pendingIndex = -1
        layoutSections()
        if (anchor >= 0) scrollToIndex(anchor) else offset = offset.coerceIn(0f, maxOffset)
        wantedFirst = -2
    }

    /** The section whose span contains content y (the last one that starts above it). */
    private fun sectionAt(y: Float): Int {
        var lo = 0
        var hi = sections.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (sections[mid].top <= y) lo = mid else hi = mid - 1
        }
        return lo
    }

    private fun sectionOf(i: Int): Int {
        var lo = 0
        var hi = sections.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (sections[mid].start <= i) lo = mid else hi = mid - 1
        }
        return lo
    }

    // --- Drawing

    override fun onDraw(c: Canvas) {
        if (sections.isEmpty()) return
        val top = offset
        val bottom = offset + height
        val now = SystemClock.uptimeMillis()
        val rowH = cell + gap
        var first = -1
        var last = -1
        var animating = false
        var si = sectionAt(top)
        while (si < sections.size && sections[si].top < bottom) {
            val s = sections[si++]
            if (s.gridTop > top) drawHeader(c, s, s.top - offset)
            val rows = ceil((s.end - s.start) / columns.toFloat()).toInt()
            val firstRow = max(0, floor((top - s.gridTop) / rowH).toInt())
            val lastRow = min(rows - 1, floor((bottom - s.gridTop) / rowH).toInt())
            for (row in firstRow..lastRow) {
                for (col in 0 until columns) {
                    val i = s.start + row * columns + col
                    if (i >= s.end) break
                    if (first < 0) first = i
                    last = i
                    val x = paddingLeft + col * rowH
                    val y = s.gridTop + row * rowH - offset
                    if (drawCell(c, photos[i], x, y, now)) animating = true
                }
            }
        }
        updateWanted(first, last)
        if (topScrim > 0) c.drawRect(0f, 0f, width.toFloat(), topScrim.toFloat(), scrimPaint)
        if (drawFastScroller(c, now)) animating = true
        if (animating) postInvalidateOnAnimation()
    }

    private fun drawHeader(c: Canvas, s: Section, y: Float) {
        val base = centreBaseline(headerText, y + headerH / 2 + dp(2f))
        val left = paddingLeft + dp(4f)
        var right = width - paddingRight - dp(4f)
        if (selecting) {
            val r = dp(10f)
            val cx = right - r
            val cy = y + headerH / 2 + dp(2f)
            var all = true
            for (i in s.start until s.end) if (photos[i].id !in selected) {
                all = false
                break
            }
            drawCheck(c, cx, cy, r, all, onPhoto = false)
            right -= r * 2 + dp(10f)
        }
        c.drawText(s.title, left, base, headerText)
        c.drawText(s.count, right, base, headerCount)
    }

    /** Returns true while the cell is still fading in. */
    private fun drawCell(c: Canvas, p: Photo, x: Float, y: Float, now: Long): Boolean {
        val isSelected = selecting && p.id in selected
        dst.set(x, y, x + cell, y + cell)
        if (isSelected) {
            c.drawRect(dst, selectShade)
            dst.inset(cell * 0.08f, cell * 0.08f)
        }
        val bmp = loader?.get(p)
        var fadingIn = false
        if (bmp == null) {
            c.drawRect(dst, placeholder)
        } else {
            val bw = bmp.width
            val bh = bmp.height
            if (bw > bh) src.set((bw - bh) / 2, 0, (bw + bh) / 2, bh) else src.set(0, (bh - bw) / 2, bw, (bh + bw) / 2)
            val t = if (fading.isEmpty()) null else fading[p.id]
            if (t != null) {
                val a = (now - t) / FADE_MS.toFloat()
                if (a >= 1f) fading.remove(p.id) else {
                    c.drawRect(dst, placeholder)
                    bitmapPaint.alpha = (a * 255).toInt()
                    fadingIn = true
                }
            }
            c.drawBitmap(bmp, src, dst, bitmapPaint)
            bitmapPaint.alpha = 255
        }
        if (cell >= dp(56f)) {
            val badge = when {
                p.raw != null -> "RAW"
                p.isRaw -> "DNG"
                else -> null
            }
            if (badge != null) drawBadge(c, badge, dst.left + badgePad, dst.bottom - badgePad)
            val stars = ratings?.get(p.id) ?: 0
            if (stars > 0) drawStars(c, stars, dst.right - badgePad, dst.bottom - badgePad)
        }
        if (selecting) {
            val r = min(dp(10f), cell * 0.12f)
            drawCheck(c, dst.right - r - dp(5f), dst.top + r + dp(5f), r, isSelected, onPhoto = true)
        }
        return fadingIn
    }

    private fun drawBadge(c: Canvas, text: String, left: Float, bottom: Float) {
        val w = badgeText.measureText(text) + dp(8f)
        val h = dp(15f)
        tmp.set(left, bottom - h, left + w, bottom)
        c.drawRoundRect(tmp, dp(4f), dp(4f), badgeBg)
        c.drawText(text, left + dp(4f), centreBaseline(badgeText, tmp.centerY()), badgeText)
    }

    private fun drawStars(c: Canvas, n: Int, right: Float, bottom: Float) {
        val text = STAR_LABELS[n.coerceIn(1, 5)]
        val w = starText.measureText(text) + dp(8f)
        val h = dp(15f)
        tmp.set(right - w, bottom - h, right, bottom)
        c.drawRoundRect(tmp, dp(4f), dp(4f), badgeBg)
        c.drawText(text, right - dp(4f), centreBaseline(starText, tmp.centerY()), starText)
    }

    private fun drawCheck(c: Canvas, cx: Float, cy: Float, r: Float, on: Boolean, onPhoto: Boolean) {
        if (on) {
            c.drawCircle(cx, cy, r, selectFill)
            checkPath.reset()
            checkPath.moveTo(cx - r * 0.45f, cy + r * 0.02f)
            checkPath.lineTo(cx - r * 0.1f, cy + r * 0.38f)
            checkPath.lineTo(cx + r * 0.5f, cy - r * 0.35f)
            c.drawPath(checkPath, check)
        } else {
            if (onPhoto) c.drawCircle(cx, cy, r, badgeBg)
            c.drawCircle(cx, cy, r, if (onPhoto) selectRing else selectRingDark)
        }
    }

    /** Returns true while it is fading out. */
    private fun drawFastScroller(c: Canvas, now: Long): Boolean {
        if (contentHeight < height * 2.5f) return false
        val age = now - scrollerShownAt
        val alpha = when {
            fastDragging -> 1f
            age < SCROLLER_HOLD_MS -> 1f
            age < SCROLLER_HOLD_MS + SCROLLER_FADE_MS -> 1f - (age - SCROLLER_HOLD_MS) / SCROLLER_FADE_MS.toFloat()
            else -> return false
        }
        val (y, h) = scrollerThumb()
        val w = if (fastDragging) dp(8f) else dp(5f)
        val right = width - dp(3f)
        tmp.set(right - w, y, right, y + h)
        scrollThumb.alpha = (alpha * 200).toInt()
        c.drawRoundRect(tmp, w / 2, w / 2, scrollThumb)
        if (fastDragging) {
            val title = sections[sectionAt(offset + paddingTop)].title
            val tw = bubbleText.measureText(title)
            val bh = dp(36f)
            val cy = y + h / 2
            tmp.set(right - w - dp(16f) - tw - dp(28f), cy - bh / 2, right - w - dp(16f), cy + bh / 2)
            c.drawRoundRect(tmp, bh / 2, bh / 2, bubble)
            c.drawText(title, tmp.right - dp(14f), centreBaseline(bubbleText, cy), bubbleText)
        }
        return !fastDragging
    }

    private fun scrollerThumb(): Pair<Float, Float> {
        val h = dp(52f)
        val track = height - paddingTop - paddingBottom - h
        val y = paddingTop + track * (if (maxOffset > 0) offset / maxOffset else 0f)
        return y to h
    }

    private fun updateWanted(first: Int, last: Int) {
        if (first == wantedFirst && last == wantedLast) return
        wantedFirst = first
        wantedLast = last
        val l = loader ?: return
        val list = ArrayList<Photo>()
        if (first >= 0) {
            for (i in first..last) list.add(photos[i])
            // One screen ahead, half a screen behind
            val screen = last - first + 1
            for (i in last + 1..min(photos.size - 1, last + screen)) list.add(photos[i])
            for (i in first - 1 downTo max(0, first - screen / 2)) list.add(photos[i])
        }
        l.setWanted(list)
    }

    // --- Touch

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            offset = scroller.currY.toFloat().coerceIn(0f, maxOffset)
            scrollerShownAt = SystemClock.uptimeMillis()
            postInvalidateOnAnimation()
        }
    }

    private fun scrollTo(y: Float) {
        offset = y.coerceIn(0f, maxOffset)
        scrollerShownAt = SystemClock.uptimeMillis()
        invalidate()
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (fastScroll(e)) return true
        if (dragSelecting) {
            dragSelect(e)
            if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) gestures.onTouchEvent(e)
            return true
        }
        scaleDetector.onTouchEvent(e)
        if (!scaleDetector.isInProgress) gestures.onTouchEvent(e)
        return true
    }

    private fun fastScroll(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val visible = contentHeight >= height * 2.5f &&
                    SystemClock.uptimeMillis() - scrollerShownAt < SCROLLER_HOLD_MS + SCROLLER_FADE_MS
                if (!visible || e.x < width - dp(36f)) return false
                val (y, h) = scrollerThumb()
                if (e.y < y - dp(24f) || e.y > y + h + dp(24f)) return false
                scroller.forceFinished(true)
                fastDragging = true
                parent?.requestDisallowInterceptTouchEvent(true)
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> if (fastDragging) {
                val (_, h) = scrollerThumb()
                val track = height - paddingTop - paddingBottom - h
                val f = ((e.y - paddingTop - h / 2) / track).coerceIn(0f, 1f)
                scrollTo(f * maxOffset)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (fastDragging) {
                fastDragging = false
                scrollerShownAt = SystemClock.uptimeMillis()
                invalidate()
                return true
            }
        }
        return fastDragging
    }

    /** The photo under (x, y): -1 for none, -2 - section for a day header. */
    private fun hit(x: Float, y: Float, nearest: Boolean = false): Int {
        if (sections.isEmpty()) return -1
        val cy = y + offset
        val si = sectionAt(cy)
        val s = sections[si]
        if (cy < s.gridTop) return if (nearest) s.start else -2 - si
        if (cy > s.bottom && !nearest) return -1
        val col = floor((x - paddingLeft) / (cell + gap)).toInt().coerceIn(0, columns - 1)
        val row = max(0, floor((cy - s.gridTop) / (cell + gap)).toInt())
        val i = s.start + row * columns + col
        return if (i < s.end) i else if (nearest) s.end - 1 else -1
    }

    private fun tap(x: Float, y: Float) {
        val h = hit(x, y)
        if (h <= -2) {
            if (selecting) toggleSection(sections[-2 - h])
            return
        }
        if (h < 0) return
        if (selecting) {
            val id = photos[h].id
            if (!selected.remove(id)) selected.add(id)
            if (selected.isEmpty()) selecting = false
            invalidate()
            callback?.onSelectionChanged()
        } else {
            cellOnScreen(h)?.let { callback?.onOpen(h, it) } ?: callback?.onOpen(h, Rect())
        }
    }

    private fun toggleSection(s: Section) {
        var all = true
        for (i in s.start until s.end) if (photos[i].id !in selected) {
            all = false
            break
        }
        for (i in s.start until s.end) if (all) selected.remove(photos[i].id) else selected.add(photos[i].id)
        if (selected.isEmpty()) selecting = false
        invalidate()
        callback?.onSelectionChanged()
    }

    private fun startDragSelect(x: Float, y: Float) {
        val i = hit(x, y)
        if (i < 0) return
        selecting = true
        dragAnchor = i
        dragBase = HashSet(selected)
        dragAdd = photos[i].id !in selected
        dragSelecting = true
        dragX = x
        dragY = y
        applyDrag(i)
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
    }

    private fun dragSelect(e: MotionEvent) {
        when (e.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                dragX = e.x
                dragY = e.y
                val i = hit(e.x, e.y, nearest = true)
                if (i >= 0) applyDrag(i)
                if (edgeSpeed() != 0f) {
                    removeCallbacks(autoScroll)
                    postOnAnimation(autoScroll)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragSelecting = false
                removeCallbacks(autoScroll)
                if (selected.isEmpty()) {
                    selecting = false
                    invalidate()
                    callback?.onSelectionChanged()
                }
            }
        }
    }

    private fun applyDrag(i: Int) {
        if (dragAnchor !in photos.indices || i !in photos.indices) return
        selected.clear()
        selected.addAll(dragBase)
        for (k in min(dragAnchor, i)..max(dragAnchor, i)) {
            val id = photos[k].id
            if (dragAdd) selected.add(id) else selected.remove(id)
        }
        invalidate()
        callback?.onSelectionChanged()
    }

    /** Pixels per frame while a drag selection holds the finger near the top or bottom edge. */
    private fun edgeSpeed(): Float {
        val zone = dp(56f)
        return when {
            dragY < paddingTop + zone -> -dp(14f) * (1 - ((dragY - paddingTop) / zone).coerceIn(0f, 1f))
            dragY > height - zone -> dp(14f) * ((dragY - (height - zone)) / zone).coerceIn(0f, 1f)
            else -> 0f
        }
    }

    private val autoScroll = object : Runnable {
        override fun run() {
            if (!dragSelecting) return
            val v = edgeSpeed()
            if (v == 0f) return
            scrollTo(offset + v)
            val i = hit(dragX, dragY, nearest = true)
            if (i >= 0) applyDrag(i)
            postOnAnimation(this)
        }
    }

    companion object {
        const val MIN_COLUMNS = 3
        const val MAX_COLUMNS = 10
        private const val FADE_MS = 150L
        private const val SCROLLER_HOLD_MS = 1200L
        private const val SCROLLER_FADE_MS = 300L
        private val STAR_LABELS = Array(6) { "★$it" }
    }
}
