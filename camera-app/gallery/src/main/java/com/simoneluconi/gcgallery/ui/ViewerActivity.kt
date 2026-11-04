package com.simoneluconi.gcgallery.ui

import android.Manifest
import android.animation.ValueAnimator
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentUris
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.text.format.DateFormat
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.animation.DecelerateInterpolator
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import com.simoneluconi.gccommon.R as C
import com.simoneluconi.gccommon.ui.HistogramView
import com.simoneluconi.gcgallery.GalleryApp
import com.simoneluconi.gcgallery.R
import com.simoneluconi.gcgallery.data.MediaIndex
import com.simoneluconi.gcgallery.data.Photo
import com.simoneluconi.gcgallery.edit.Edits
import com.simoneluconi.gcgallery.image.Decode
import com.simoneluconi.gcgallery.image.Orientation
import com.simoneluconi.gcgallery.image.RegionSource
import com.simoneluconi.gcgallery.meta.PhotoInfo
import com.simoneluconi.gcgallery.ui.widget.PhotoPagerView
import com.simoneluconi.gcgallery.ui.widget.StarsView
import java.nio.ByteBuffer
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import kotlin.math.max

/**
 * One photo full screen, swiping through its album. The cached thumbnail shows at once and a
 * screen-size decode replaces it; zooming past that decodes the visible region at full resolution.
 */
class ViewerActivity : Activity(), PhotoPagerView.Callback {

    private lateinit var app: GalleryApp
    private lateinit var pager: PhotoPagerView
    private lateinit var topBar: View
    private lateinit var titleView: TextView
    private lateinit var subtitleView: TextView
    private lateinit var rateButton: View
    private lateinit var rateIcon: ImageView
    private lateinit var rateCount: TextView
    private var rating = 0
    private lateinit var infoPanel: View
    private lateinit var histogram: HistogramView
    private lateinit var histRgb: TextView
    private lateinit var clipping: TextView
    private lateinit var exposureLine: TextView
    private lateinit var infoSections: LinearLayout
    private lateinit var zoomLabel: TextView
    private lateinit var message: MessageBar
    private lateinit var access: MediaAccess

    @Volatile private var items: List<Photo> = emptyList()
    /** False for a picture another app handed over (not in MediaStore): view and share only. */
    private var editable = true

    private val pages = HashMap<Long, PhotoPagerView.Page>()
    private val full = HashSet<Long>()
    /** Screen decodes queued or running (main thread). */
    private val loading = HashSet<Long>()
    /** The file orientation after a turn made in this viewer, by id (main thread). */
    private val turned = HashMap<Long, Int>()
    private val infos = HashMap<Long, PhotoInfo>()
    private val main = Handler(Looper.getMainLooper())
    private val decoder = executor("viewer-decode")
    private val detailer = executor("viewer-detail")
    private val reader = executor("viewer-info")
    private val editor = executor("viewer-edit")
    @Volatile private var detailToken = 0
    /** Touched on [detailer] only. */
    private var region: RegionSource? = null
    private var regionId = Long.MIN_VALUE

    private var chromeShown = true
    private var infoOpen = false
    private var clipOn = false
    private var clipAnimator: ValueAnimator? = null
    private var screenW = 0
    private var screenH = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        app = GalleryApp.of(this)
        setContentView(R.layout.activity_viewer)
        pager = findViewById(R.id.pager)
        topBar = findViewById(R.id.top_bar)
        titleView = findViewById(R.id.title)
        subtitleView = findViewById(R.id.subtitle)
        rateButton = findViewById(R.id.rate)
        rateIcon = findViewById(R.id.rate_icon)
        rateCount = findViewById(R.id.rate_count)
        infoPanel = findViewById(R.id.info_panel)
        histogram = findViewById(R.id.histogram)
        histRgb = findViewById(R.id.hist_rgb)
        clipping = findViewById(R.id.clipping)
        exposureLine = findViewById(R.id.exposure_line)
        infoSections = findViewById(R.id.info_sections)
        zoomLabel = findViewById(R.id.zoom_label)
        message = MessageBar(findViewById(R.id.message), findViewById(R.id.message_text), findViewById(R.id.message_action))
        access = MediaAccess(this)
        screenW = max(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels)
        screenH = minOf(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels)

        window.setDecorFitsSystemWindows(false)
        window.insetsController?.let {
            it.hide(WindowInsets.Type.systemBars())
            it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        findViewById<View>(R.id.root).setOnApplyWindowInsetsListener { _, insets ->
            val b = insets.getInsetsIgnoringVisibility(WindowInsets.Type.displayCutout())
            val m = (8 * resources.displayMetrics.density).toInt()
            (topBar.layoutParams as FrameLayout.LayoutParams).setMargins(b.left + m, b.top + m, b.right + m, m)
            topBar.requestLayout()
            insets
        }

        findViewById<View>(R.id.back).setOnClickListener { finish() }
        findViewById<View>(R.id.rotate).setOnClickListener { showRotatePopup(it) }
        findViewById<View>(R.id.share).setOnClickListener { share() }
        findViewById<View>(R.id.trash).setOnClickListener { trash() }
        findViewById<View>(R.id.info).setOnClickListener { setInfoOpen(!infoOpen) }
        rateButton.setOnClickListener { showRatingPopup() }
        histRgb.setOnClickListener {
            histRgb.isActivated = !histRgb.isActivated
            histogram.showRgb = histRgb.isActivated
        }
        clipping.setOnClickListener { setClipping(!clipOn) }

        pager.callback = this
        infoOpen = savedInstanceState?.getBoolean(STATE_INFO) ?: getSharedPreferences(GridActivity.PREFS, MODE_PRIVATE).getBoolean(PREF_INFO, false)
        resolveItems(savedInstanceState?.getLong(STATE_ID, Long.MIN_VALUE) ?: Long.MIN_VALUE)
    }

    override fun onDestroy() {
        app.index.removeWaiter(onIndexLoaded)
        // Queued neighbour decodes and info reads are not wanted any more; an edit in progress
        // still finishes (shutdown lets the running task end), and the region source is closed last
        for (e in listOf(decoder, reader)) e.shutdownNow()
        detailer.execute { region?.close() }
        detailer.shutdown()
        editor.shutdown()
        clipAnimator?.cancel()
        super.onDestroy()
    }

    /** Back on the main thread, unless the activity is gone by then. */
    private fun ui(block: () -> Unit) {
        main.post { if (!isDestroyed) block() }
    }

    /** Background work that is dropped once the activity is destroyed. */
    private fun ExecutorService.launch(block: () -> Unit) {
        if (isDestroyed || isShutdown) return
        try {
            execute(block)
        } catch (e: RejectedExecutionException) {
            // Shut down between the check and the call
        }
    }

    override fun onSaveInstanceState(out: Bundle) {
        super.onSaveInstanceState(out)
        current()?.let { out.putLong(STATE_ID, it.id) }
        out.putBoolean(STATE_INFO, infoOpen)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (!access.onActivityResult(requestCode, resultCode)) @Suppress("DEPRECATION") super.onActivityResult(requestCode, resultCode, data)
    }

    // --- What to show

    private fun resolveItems(savedId: Long) {
        val action = intent.action
        val data = intent.data
        val fromCamera = action == MediaStore.ACTION_REVIEW || action == MediaStore.ACTION_REVIEW_SECURE
        if (data != null && (action == Intent.ACTION_VIEW || fromCamera)) {
            val id = if (data.authority == MediaStore.AUTHORITY) runCatching { ContentUris.parseId(data) }.getOrDefault(-1L) else -1L
            if (id >= 0 && canReadAll()) {
                afterIndex {
                    val p = app.index.find(id)
                    // A shot from a moment ago may still be on its way into the index
                    if (p == null && fromCamera) app.index.reload { if (!isDestroyed) openIn(app.index.find(id), data) } else openIn(p, data)
                }
            } else {
                single(data)
            }
            return
        }
        val album = intent.getStringExtra(EXTRA_ALBUM) ?: MediaIndex.ALL
        val id = if (savedId != Long.MIN_VALUE) savedId else intent.getLongExtra(EXTRA_ID, -1)
        afterIndex {
            val scope = intent.getStringExtra(EXTRA_SCOPE)?.let { MediaIndex.Scope.valueOf(it) } ?: MediaIndex.Scope.SD
            val list = app.index.album(album, scope, app.ratings)
            if (list.isEmpty()) finish() else setItems(list, list.indexOfFirst { it.id == id }.coerceAtLeast(0))
        }
    }

    private var pendingResolve: (() -> Unit)? = null
    /** Held by the index until it loads; removed in onDestroy so a closed viewer is not kept. */
    private val onIndexLoaded: () -> Unit = {
        val r = pendingResolve
        pendingResolve = null
        if (!isDestroyed) r?.invoke()
    }

    private fun afterIndex(block: () -> Unit) {
        pendingResolve = block
        app.index.start()
        app.index.whenLoaded(onIndexLoaded)
    }

    private fun openIn(p: Photo?, data: Uri) {
        if (p == null) return single(data)
        // The shot's own folder on its own card
        val list = app.index.album(if (p.trashed) MediaIndex.TRASH else p.folder, MediaIndex.scopeOf(p), app.ratings)
        setItems(list, list.indexOfFirst { it.id == p.id }.coerceAtLeast(0))
    }

    /** A picture from another app: one item, view and share only. */
    private fun single(uri: Uri) {
        editable = false
        val name = runCatching {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull() ?: uri.lastPathSegment ?: ""
        val mime = contentResolver.getType(uri) ?: intent.type ?: "image/*"
        setItems(listOf(Photo(-1, uri, "", name, "", mime, 0, 0, 0, 0, 0, false)), 0)
    }

    private fun canReadAll() = checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED

    private fun setItems(list: List<Photo>, position: Int) {
        items = list
        for (id in intArrayOf(R.id.rotate, R.id.trash)) {
            findViewById<View>(id).visibility = if (editable) View.VISIBLE else View.GONE
        }
        rateButton.visibility = if (editable) View.VISIBLE else View.GONE
        pager.show(position)
        onPageChanged(position)
        if (infoOpen) setInfoOpen(true, animate = false)
    }

    private fun current(): Photo? = items.getOrNull(pager.index)

    // --- Pager

    override fun pageCount() = items.size

    override fun page(index: Int): PhotoPagerView.Page? = items.getOrNull(index)?.let { pages[it.id] }

    override fun onPageChanged(index: Int) {
        val p = items.getOrNull(index) ?: return
        titleView.text = p.name
        updateSubtitle(p)
        showRating(app.ratings.get(p.id))
        detailToken++
        // Not the previous photo's while this one's is computed
        histogram.clear()
        loadAround(index)
        loadInfo(p)
        if (clipOn) buildClipMask()
    }

    override fun onTap() = setChrome(!chromeShown)

    override fun onZoomChanged(percent: Int) {
        if (pager.isAtFit()) {
            zoomLabel.visibility = View.GONE
            return
        }
        zoomLabel.text = "$percent%"
        zoomLabel.visibility = View.VISIBLE
        zoomLabel.removeCallbacks(hideZoom)
        zoomLabel.postDelayed(hideZoom, 1200)
    }

    private val hideZoom = Runnable { zoomLabel.visibility = View.GONE }

    override fun onDetailWanted(index: Int, region: Rect, sample: Int) {
        val p = items.getOrNull(index) ?: return
        val token = ++detailToken
        detailer.launch {
            if (token != detailToken) return@launch
            if (regionId != p.id) {
                this.region?.close()
                this.region = RegionSource.open(contentResolver, p)
                regionId = p.id
            }
            val t0 = SystemClock.elapsedRealtime()
            val bmp = this.region?.decode(region, sample) ?: return@launch
            Log.d(TAG, "detail ${region.width()}x${region.height()} /$sample: ${SystemClock.elapsedRealtime() - t0} ms")
            ui { if (token == detailToken) pager.setDetail(index, bmp, region) }
        }
    }

    /** Keeps the current photo and its neighbours decoded; current first. */
    private fun loadAround(index: Int) {
        val wanted = listOf(index, index + 1, index - 1).mapNotNull { items.getOrNull(it) }
        val ids = wanted.map { it.id }.toSet()
        pages.keys.retainAll(ids)
        full.retainAll(ids)
        for (p in wanted) {
            if (p.id in full || !loading.add(p.id)) continue
            val needThumb = pages[p.id] == null
            decoder.launch {
                try {
                    decodeScreen(p, needThumb)
                } finally {
                    ui { loading.remove(p.id) }
                }
            }
        }
    }

    /** On [decoder]: the thumbnail as a stand-in, then the screen-size decode. */
    private fun decodeScreen(p: Photo, needThumb: Boolean) {
        if (!isWanted(p)) return
        val t0 = SystemClock.elapsedRealtime()
        val d = try {
            if (needThumb) {
                app.thumbs.loadNow(p)?.let { thumb ->
                    // Thumbnails are stored upright
                    ui { if (isWanted(p) && pages[p.id] == null) putPage(p, PhotoPagerView.Page(thumb, thumb.width, thumb.height, 1, preview = true)) }
                }
            }
            Decode.screen(contentResolver, p, screenW, screenH)
        } catch (e: Throwable) {
            // Gone since the index loaded, a corrupt file, OOM…: whatever is up stays up
            Log.w(TAG, "decode ${p.name} failed: $e")
            null
        } ?: return
        Log.d(TAG, "screen ${p.name} ${d.bitmap.width}x${d.bitmap.height}: ${SystemClock.elapsedRealtime() - t0} ms")
        ui {
            if (!isWanted(p)) return@ui
            full.add(p.id)
            // A turn made while this decode ran is newer than what it read from the file
            val o = turned[p.id] ?: d.orientation
            putPage(p, PhotoPagerView.Page(d.bitmap, d.rawW, d.rawH, o))
            if (p === current()) {
                if (infoOpen) computeHistogram()
                if (clipOn) buildClipMask()
            }
        }
    }

    /** Main thread or decoder: [items] and the pager index are only replaced on the main thread. */
    private fun isWanted(p: Photo): Boolean {
        val i = pager.index
        for (k in i - 1..i + 1) if (items.getOrNull(k)?.id == p.id) return true
        return false
    }

    private fun putPage(p: Photo, page: PhotoPagerView.Page) {
        pages[p.id] = page
        val i = items.indexOfFirst { it.id == p.id }
        if (i >= 0) pager.pageUpdated(i)
    }

    // --- Info, histogram, clipping

    private fun loadInfo(p: Photo) {
        infos[p.id]?.let { return showInfo(p, it) }
        reader.launch {
            val info = try {
                PhotoInfo.read(this, p)
            } catch (e: Exception) {
                Log.w(TAG, "info ${p.name} failed: $e")
                null
            } ?: return@launch
            info.rating?.let { app.ratings.learn(p.id, it) }
            ui {
                if (infos.size > 16) infos.clear()
                infos[p.id] = info
                if (current() === p) showInfo(p, info)
            }
        }
    }

    private fun updateSubtitle(p: Photo, info: PhotoInfo? = infos[p.id]) {
        // Only what fits: date, the exposure triangle, RAW. The rest is in the info panel
        val date = if (p.dateTaken > 0) {
            DateFormat.format(DateFormat.getBestDateTimePattern(Locale.getDefault(), "dMMMHHmm"), p.dateTaken).toString()
        } else null
        subtitleView.text = listOfNotNull(date, info?.barLine, if (p.raw != null) "RAW" else null).joinToString("  ·  ")
    }

    private fun showInfo(p: Photo, info: PhotoInfo) {
        updateSubtitle(p, info)
        // The store has the file's value unless the user just changed it
        showRating(app.ratings.get(p.id))
        exposureLine.text = info.exposureLine ?: ""
        exposureLine.visibility = if (info.exposureLine != null) View.VISIBLE else View.GONE
        infoSections.removeAllViews()
        for (s in info.sections) {
            infoSections.addView(text(s.title.uppercase(Locale.getDefault()), 11f, C.color.accent, medium = true, top = 18f).apply {
                letterSpacing = 0.08f
            })
            for (r in s.rows) infoSections.addView(row(r.label, r.value))
            if (s.isLocation && info.latitude != null && info.longitude != null) infoSections.addView(locationActions(info.latitude, info.longitude))
        }
        if (infoOpen) computeHistogram()
    }

    private fun row(label: String, value: String): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(0, dp(5), 0, dp(5))
        addView(text(label, 13f, C.color.on_surface_muted).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.42f)
        })
        addView(text(value, 13f, C.color.on_surface).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.58f)
            gravity = Gravity.END
            setTextIsSelectable(true)
        })
    }

    private fun locationActions(lat: Double, lon: Double): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(0, dp(6), 0, 0)
        val coords = "%.6f,%.6f".format(Locale.US, lat, lon)
        addView(button(getString(R.string.open_in_maps)) {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:$coords?q=$coords")))
            } catch (e: ActivityNotFoundException) {
                message.show(getString(R.string.no_maps_app))
            }
        })
        addView(button(getString(R.string.copy_coordinates)) {
            getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("GPS", coords))
            message.show(getString(R.string.coordinates_copied))
        }.apply { (layoutParams as LinearLayout.LayoutParams).marginStart = dp(6) })
    }

    private fun button(label: String, onClick: () -> Unit) = TextView(this, null, 0, R.style.TextButton).apply {
        text = label
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(36))
        setOnClickListener { onClick() }
    }

    private fun text(s: String, sizeSp: Float, colorId: Int, medium: Boolean = false, top: Float = 0f) = TextView(this).apply {
        text = s
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        setTextColor(getColor(colorId))
        fontFeatureSettings = "tnum"
        if (medium) typeface = com.simoneluconi.gccommon.ui.MEDIUM
        if (top > 0) setPadding(0, dp(top.toInt()), 0, dp(4))
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    /** From the screen decode, every other pixel: 64 bins of luma and of each channel. */
    private fun computeHistogram() {
        val p = current() ?: return
        val bmp = pages[p.id]?.bitmap ?: return
        if (p.id !in full) return
        reader.launch {
            val luma = IntArray(HistogramView.BINS)
            val red = IntArray(HistogramView.BINS)
            val green = IntArray(HistogramView.BINS)
            val blue = IntArray(HistogramView.BINS)
            val w = bmp.width
            val row = IntArray(w)
            var y = 0
            while (y < bmp.height) {
                if (bmp.isRecycled) return@launch
                bmp.getPixels(row, 0, w, 0, y, w, 1)
                var x = 0
                while (x < w) {
                    val c = row[x]
                    val r = (c shr 16) and 0xFF
                    val g = (c shr 8) and 0xFF
                    val b = c and 0xFF
                    luma[(r * 54 + g * 183 + b * 19) shr 10]++
                    red[r shr 2]++
                    green[g shr 2]++
                    blue[b shr 2]++
                    x += 2
                }
                y += 2
            }
            ui {
                if (current() !== p) return@ui
                luma.copyInto(histogram.luma)
                red.copyInto(histogram.red)
                green.copyInto(histogram.green)
                blue.copyInto(histogram.blue)
                histogram.invalidate()
            }
        }
    }

    private fun setClipping(on: Boolean) {
        clipOn = on
        clipping.isActivated = on
        clipAnimator?.cancel()
        if (!on) {
            pager.setClipMask(null, null)
            return
        }
        buildClipMask()
        clipAnimator = ValueAnimator.ofInt(0, 220).apply {
            duration = 450
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener { pager.setMaskAlpha(it.animatedValue as Int) }
            start()
        }
    }

    /** Marks pixels with any channel at 252 or more. */
    private fun buildClipMask() {
        val p = current() ?: return
        if (p.id !in full) return
        val bmp = pages[p.id]?.bitmap ?: return
        reader.launch {
            val w = bmp.width
            val h = bmp.height
            val mask = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8)
            val stride = mask.rowBytes
            val bytes = ByteArray(stride * h)
            val row = IntArray(w)
            for (y in 0 until h) {
                if (bmp.isRecycled) return@launch
                bmp.getPixels(row, 0, w, 0, y, w, 1)
                val base = y * stride
                for (x in 0 until w) {
                    val c = row[x]
                    if ((c shr 16) and 0xFF >= CLIP || (c shr 8) and 0xFF >= CLIP || c and 0xFF >= CLIP) bytes[base + x] = -1
                }
            }
            mask.copyPixelsFromBuffer(ByteBuffer.wrap(bytes))
            ui { if (clipOn && current() === p) pager.setClipMask(bmp, mask) }
        }
    }

    private fun setInfoOpen(open: Boolean, animate: Boolean = true) {
        infoOpen = open
        findViewById<View>(R.id.info).isActivated = open
        getSharedPreferences(GridActivity.PREFS, MODE_PRIVATE).edit().putBoolean(PREF_INFO, open).apply()
        val width = resources.getDimension(R.dimen.info_panel_width) + 16 * resources.displayMetrics.density
        if (open) {
            infoPanel.visibility = View.VISIBLE
            computeHistogram()
        }
        // With the panel open the photo fits the space left of it and below the bar
        val top = (infoPanel.layoutParams as FrameLayout.LayoutParams).topMargin.toFloat()
        val from = if (width > 0) pager.insetRight / width else 0f
        val to = if (open) 1f else 0f
        infoPanel.animate().cancel()
        if (!animate) {
            infoPanel.translationX = if (open) 0f else width
            pager.setInsets(to * width, to * top)
            if (!open) infoPanel.visibility = View.INVISIBLE
            return
        }
        infoPanel.translationX = if (open) width else 0f
        infoPanel.animate().translationX(if (open) 0f else width).setDuration(220).setInterpolator(DecelerateInterpolator())
            .withEndAction { if (!infoOpen) infoPanel.visibility = View.INVISIBLE }.start()
        ValueAnimator.ofFloat(from, to).apply {
            duration = 220
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                val f = it.animatedValue as Float
                pager.setInsets(f * width, f * top)
            }
            start()
        }
    }

    private fun setChrome(show: Boolean) {
        chromeShown = show
        val h = topBar.height + topBar.top.toFloat()
        topBar.animate().translationY(if (show) 0f else -h).alpha(if (show) 1f else 0f).setDuration(180).start()
        if (!show && infoOpen) setInfoOpen(false)
    }

    // --- Edits

    private fun rotate(op: Int) {
        val p = current() ?: return
        if (!editable) return message.show(getString(R.string.not_editable))
        access.write(p.files().map { it.uri }) { ok ->
            if (!ok) return@write
            // Turned on screen at once; the file follows
            pages[p.id]?.let { putPage(p, it.turned(Orientation.compose(op, it.orientation))) }
            if (clipOn) buildClipMask()
            editor.launch {
                val o = Edits.rotate(contentResolver, p, op)
                app.thumbs.invalidate(p)
                ui {
                    infos.remove(p.id)
                    if (o < 0) {
                        message.show(getString(R.string.edit_failed))
                    } else {
                        // What the file now says wins over any screen decode still in flight
                        turned[p.id] = o
                        pages[p.id]?.takeIf { !it.preview && it.orientation != o }?.let { putPage(p, it.turned(o)) }
                    }
                    if (current() === p) loadInfo(p)
                }
            }
        }
    }

    /** The bar's single star: outlined, or filled with the count. */
    private fun showRating(r: Int) {
        rating = r
        rateIcon.setImageResource(if (r > 0) R.drawable.ic_star_filled else R.drawable.ic_star)
        rateCount.text = r.toString()
        rateCount.visibility = if (r > 0) View.VISIBLE else View.GONE
    }

    private fun showRatingPopup() {
        val (content, popup) = popupUnder(rateButton, R.layout.rating_popup)
        val stars = content.findViewById<StarsView>(R.id.stars)
        stars.rating = rating
        stars.onRate = { r ->
            rate(r)
            // Long enough to see the choice land
            content.postDelayed({ popup.dismiss() }, 250)
        }
    }

    /** Left, right, flip; stays open so a photo can be turned twice in a row. */
    private fun showRotatePopup(anchor: View) {
        val (content, _) = popupUnder(anchor, R.layout.rotate_popup)
        content.findViewById<View>(R.id.rotate_left).setOnClickListener { rotate(Orientation.ROTATE_CCW) }
        content.findViewById<View>(R.id.rotate_right).setOnClickListener { rotate(Orientation.ROTATE_CW) }
        content.findViewById<View>(R.id.flip).setOnClickListener { rotate(Orientation.FLIP_H) }
    }

    private fun popupUnder(anchor: View, layout: Int): Pair<View, PopupWindow> {
        val content = layoutInflater.inflate(layout, null)
        // Not focusable, so the shutter and the zoom lever still reach the activity while it is open
        val popup = PopupWindow(content, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, false).apply {
            elevation = 6 * resources.displayMetrics.density
            isOutsideTouchable = true
        }
        content.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        popup.showAsDropDown(anchor, (anchor.width - content.measuredWidth) / 2, dp(6))
        return content to popup
    }

    private fun rate(rating: Int) {
        val p = current() ?: return
        if (!editable) return
        showRating(rating)
        app.ratings.set(p.id, rating)
        access.write(p.files().map { it.uri }) { ok ->
            if (!ok) return@write
            editor.launch {
                val written = Edits.setRating(contentResolver, p, rating)
                ui {
                    infos.remove(p.id)
                    if (!written) message.show(getString(R.string.rating_store_only))
                }
            }
            if (access.silent) access.favorite(listOf(p.uri), rating > 0) {}
        }
    }

    private fun trash() {
        val p = current() ?: return
        if (!editable) return
        val uris = p.files().map { it.uri }
        access.trash(uris, !p.trashed) { ok ->
            if (!ok) return@trash
            val i = pager.index
            items = items.filter { it.id != p.id }
            pages.remove(p.id)
            full.remove(p.id)
            if (items.isEmpty()) return@trash finish()
            val next = i.coerceAtMost(items.size - 1)
            pager.show(next)
            onPageChanged(next)
            val text = if (p.trashed) getString(R.string.restored, 1) else getString(R.string.moved_to_trash, 1)
            message.show(text, if (p.trashed) null else getString(R.string.undo)) {
                access.trash(uris, false) {}
            }
        }
    }

    private fun share() {
        val p = current() ?: return
        val intent = Intent(Intent.ACTION_SEND)
            .setType(p.mime)
            .putExtra(Intent.EXTRA_STREAM, p.uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(intent, getString(R.string.share)))
    }

    // --- Keys

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when {
            Keys.isShutter(keyCode) -> {
                if (event.repeatCount == 0) Keys.openCamera(this)
                return true
            }
            keyCode == KeyEvent.KEYCODE_ZOOM_IN -> {
                if (Keys.isFast(event)) {
                    if (event.repeatCount == 0 && pager.isAtFit()) pager.toggleZoom()
                } else pager.zoomBy(1.15f)
                return true
            }
            keyCode == KeyEvent.KEYCODE_ZOOM_OUT -> {
                when {
                    // Wide at fit goes back to the index, as on a camera's playback
                    pager.isAtFit() -> if (event.repeatCount == 0 && !Keys.isFast(event)) finish()
                    Keys.isFast(event) -> if (event.repeatCount == 0) pager.fitAnimated()
                    else -> pager.zoomBy(1 / 1.15f)
                }
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (Keys.isShutter(keyCode) || keyCode == KeyEvent.KEYCODE_ZOOM_IN || keyCode == KeyEvent.KEYCODE_ZOOM_OUT) return true
        return super.onKeyUp(keyCode, event)
    }

    private fun executor(name: String): ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_DEFAULT)
            r.run()
        }, name)
    }

    companion object {
        const val EXTRA_ALBUM = "album"
        const val EXTRA_ID = "id"
        const val EXTRA_SCOPE = "scope"
        private const val STATE_ID = "id"
        private const val STATE_INFO = "info"
        private const val PREF_INFO = "info_open"
        private const val TAG = "GcGallery"
        private const val CLIP = 252
    }
}
