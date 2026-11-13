// SPDX-License-Identifier: GPL-3.0-or-later
package com.simoneluconi.gcgallery.tv

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.simoneluconi.gcgallery.data.Photo
import com.simoneluconi.gcgallery.image.Orientation
import com.simoneluconi.gcgallery.image.RegionSource
import com.simoneluconi.gcgallery.ui.widget.PhotoPagerView
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** What hdmid reports: whether a TV is on, its mode, and the modes the user can pick. */
class TvState(
    val connected: Boolean,
    val mode: String,
    val width: Int,
    val height: Int,
    val darW: Int,
    val darH: Int,
    val auto: Boolean,
    val dvi: Boolean,
    val sink: String,
    /** Every mode the output can drive, best first; true when the TV lists it. */
    val modes: List<Pair<String, Boolean>>,
) {
    companion object {
        fun parse(text: String): TvState {
            val kv = text.lines().mapNotNull { l -> l.indexOf('=').takeIf { it > 0 }?.let { l.substring(0, it) to l.substring(it + 1) } }.toMap()
            val dar = kv["dar"]?.split(':')
            return TvState(
                connected = kv["connected"] == "1",
                mode = kv["mode"] ?: "",
                width = kv["width"]?.toIntOrNull() ?: 0,
                height = kv["height"]?.toIntOrNull() ?: 0,
                darW = dar?.getOrNull(0)?.toIntOrNull() ?: 16,
                darH = dar?.getOrNull(1)?.toIntOrNull() ?: 9,
                auto = kv["auto"] == "1",
                dvi = kv["dvi"] == "1",
                sink = kv["sink"] ?: "",
                modes = kv["modes"]?.split(',')?.mapNotNull { m ->
                    val i = m.lastIndexOf(':')
                    if (i > 0) m.substring(0, i) to (m.substring(i + 1) == "1") else null
                } ?: emptyList(),
            )
        }
    }
}

/**
 * The photo being viewed on a TV, through hdmid (docs/HDMI.md). The picture is drawn here,
 * already scaled for the TV's mode, and sent over the daemon's socket. First from what the viewer already decoded; when the TV has more
 * pixels than that (1080p), a sharper decode follows once the photo has been still a moment.
 *
 * Main thread API; the drawing and the socket run on one background thread.
 */
class TvOut(private val resolver: ContentResolver, private val onState: (TvState) -> Unit) {

    /** The reader's posts (STATE, disconnection); cleared only by [stop]. */
    private val main = Handler(Looper.getMainLooper())
    /** The delayed sharp decode, cancelled by every newer picture. */
    private val sharpDelay = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "tv-out") }
    /** Bumped for every new picture: older work stops at its next check. */
    @Volatile private var token = 0
    @Volatile private var closed = false
    /** Main thread: the full-size picture last asked for, so asking again changes nothing. */
    private var wantedKey: Any? = null

    /** Set on the main thread; the worker reads it for live frames. */
    @Volatile var state: TvState? = null
        private set

    // Worker thread only (socket is read elsewhere to tell an old connection from the current one)
    @Volatile private var socket: LocalSocket? = null
    private var channel: FileChannel? = null
    private var target: Bitmap? = null
    private var pixels: ByteBuffer? = null
    /** The half-size frames of a moving zoom or pan have their own, so neither size is reallocated. */
    private var liveTarget: Bitmap? = null
    private var livePixels: ByteBuffer? = null
    private var sentKey: Any? = null
    /** The current photo's JPEG for the sharp decodes, kept while the photo stays. */
    private var regionSrc: RegionSource? = null
    private var regionUri: android.net.Uri? = null

    /** The latest frame wanted while a zoom or pan moves; older ones are skipped. */
    private val liveWanted = AtomicReference<Triple<Photo, PhotoPagerView.Page, RectF?>?>(null)
    private val liveQueued = AtomicBoolean(false)

    /** Connects to hdmid; its state arrives through [onState]. */
    fun start() = onWorker {
        val s = LocalSocket(LocalSocket.SOCKET_STREAM)
        try {
            s.connect(LocalSocketAddress(SOCKET, LocalSocketAddress.Namespace.RESERVED))
        } catch (e: IOException) {
            Log.i(TAG, "no hdmid: $e")
            runCatching { s.close() }
            main.post { reconnectLater() }
            return@onWorker
        }
        disconnect()
        socket = s
        channel = FileOutputStream(s.fileDescriptor).channel
        // hdmid sends its state on its own when a client connects: no HELLO needed
        Thread({ readLoop(s) }, "tv-in").start()
    }

    /**
     * Worker thread. Shut down first: that ends the connection for hdmid at once, whatever else
     * still refers to the descriptor (a close alone left hdmid's side connected).
     */
    private fun disconnect() {
        val s = socket ?: return
        runCatching { s.shutdownOutput() }
        runCatching { s.shutdownInput() }
        runCatching { s.close() }
        socket = null
        channel = null
        Log.d(TAG, "TV: disconnected")
    }

    /** Main thread: hdmid restarted or is not up yet. A few tries, further apart each time. */
    private fun reconnectLater() {
        if (closed || retries >= MAX_RETRIES) return
        retries++
        retry.postDelayed({ if (!closed) start() }, RETRY_MS * retries)
    }

    private val retry = Handler(Looper.getMainLooper())
    /** Main thread. */
    private var retries = 0

    /** Disconnects; the TV goes back to hdmid's notice. */
    fun stop() {
        closed = true
        token++
        main.removeCallbacksAndMessages(null)
        sharpDelay.removeCallbacksAndMessages(null)
        retry.removeCallbacksAndMessages(null)
        onWorker {
            disconnect()
            regionSrc?.close()
            regionSrc = null
            regionUri = null
            target?.recycle()
            target = null
            pixels = null
            liveTarget?.recycle()
            liveTarget = null
            livePixels = null
        }
        worker.shutdown()
    }

    /**
     * Puts [page] of [photo] on the TV, unless it is what the TV already shows: all of it, or
     * [region] (upright pixels of the file) while the viewer is zoomed in.
     */
    fun show(photo: Photo, page: PhotoPagerView.Page, region: RectF? = null) {
        val st = state ?: return
        if (!st.connected) return
        // The grid thumbnail blown up to 1080p took longer (1.4 s, RGB_565 at 4.5x) than the
        // screen decode it stands in for: the TV waits for that, with nothing of the last
        // photo still on its way
        if (page.preview) return cancel()
        // The whole photo, or the part of it on the camera's screen while zoomed in
        val r = RectF(region ?: RectF(0f, 0f, page.width.toFloat(), page.height.toFloat()))
        val key = listOf(photo.id, page.bitmap, page.orientation, st.mode, r)
        // Asked again (a tap, the end of a pinch): let the sharp decode on its way finish
        if (key == wantedKey) return
        wantedKey = key
        val t = ++token
        sharpDelay.removeCallbacksAndMessages(null)
        // The movement is over: no half-size frame after this one
        liveWanted.set(null)
        onWorker {
            if (t != token) return@onWorker
            if (key == sentKey) return@onWorker
            val (w, h) = targetSize(st, r.width(), r.height())
            val t0 = SystemClock.elapsedRealtime()
            val bmp = draw(w, h) { c, m -> pageMatrix(page, r, w, h, m); c.drawBitmap(page.bitmap, m, paint) } ?: return@onWorker
            val t1 = SystemClock.elapsedRealtime()
            if (t != token || !sendPicture(bmp)) return@onWorker
            sentKey = key
            Log.d(TAG, "TV ${st.mode}: ${photo.name} ${w}x$h${if (region != null) " (zoomed)" else ""} from ${page.bitmap.width}x${page.bitmap.height}: draw ${t1 - t0} ms, send ${SystemClock.elapsedRealtime() - t1} ms")
            // The viewer's decode is enough unless the TV has noticeably more pixels across the region
            if (r.width() * uprightWidth(page) / page.width >= w * 0.95f) return@onWorker
            sharpDelay.postDelayed({ if (t == token) onWorker { sharp(t, photo, page, st, r, w, h, key) } }, SHARP_DELAY_MS)
        }
    }

    /** Main thread: nothing pending for the TV any more (a photo with no decode to show yet). */
    fun cancel() {
        token++
        sharpDelay.removeCallbacksAndMessages(null)
        liveWanted.set(null)
        wantedKey = null
    }

    /**
     * A zoom or pan is moving: a half-size frame of [region] (null: all of the photo) from the
     * viewer's bitmap, which the mixer doubles. Frames are drawn one after the other, always
     * the latest wanted, so the TV follows as fast as the drawing allows and never lags behind.
     * The sharp picture comes with [show] once the movement settles.
     */
    fun live(photo: Photo, page: PhotoPagerView.Page, region: RectF?) {
        val st = state ?: return
        if (!st.connected || page.preview) return
        // Whatever full-size picture or sharp decode was pending is out of date, and the TV
        // will no longer show the last full-size one
        token++
        sharpDelay.removeCallbacksAndMessages(null)
        wantedKey = null
        liveWanted.set(Triple(photo, page, region?.let { RectF(it) }))
        if (liveQueued.compareAndSet(false, true)) onWorker { liveFrame() }
    }

    /** Worker thread. */
    private fun liveFrame() {
        liveQueued.set(false)
        if (closed) return
        val (photo, page, region) = liveWanted.getAndSet(null) ?: return
        val st = state?.takeIf { it.connected } ?: return
        val r = region ?: RectF(0f, 0f, page.width.toFloat(), page.height.toFloat())
        val (w, h) = targetSize(st, r.width(), r.height(), half = true)
        val t0 = SystemClock.elapsedRealtime()
        val bmp = draw(w, h, half = true) { c, m -> pageMatrix(page, r, w, h, m); c.drawBitmap(page.bitmap, m, paint) } ?: return
        if (sendPicture(bmp, half = true)) {
            // The TV no longer shows any full-size picture sent before
            sentKey = null
            Log.v(TAG, "TV live ${photo.name} ${w}x$h in ${SystemClock.elapsedRealtime() - t0} ms")
        }
        // Wanted again meanwhile: the next frame right away
        if (liveWanted.get() != null && liveQueued.compareAndSet(false, true)) onWorker { liveFrame() }
    }

    /** "auto" or a mode name; hdmid restarts the output and reports the new state. */
    fun setMode(name: String) {
        wantedKey = null
        onWorker {
            sentKey = null
            send(MSG_SET_MODE, 0, 0, ByteBuffer.wrap(name.toByteArray()))
        }
    }

    // --- Drawing

    /**
     * Plain bilinear. The mixer's pixel order (B, G, R, A) is hdmid's business: a colour
     * matrix here made Skia's software pipeline several times slower.
     */
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val m = Matrix()
    private val o = Matrix()

    /**
     * The picture's size in TV pixels: fitted to the screen, which is [TvState.darW]:[TvState.darH]
     * whatever its pixel count (480p and 576p have non-square pixels). The width is a multiple
     * of 4 (16 bytes) and the height even, for the mixer.
     */
    private fun targetSize(st: TvState, uprightW: Float, uprightH: Float, half: Boolean = false): Pair<Int, Int> {
        // A half-size frame is drawn for a layer of half the mode, doubled by the mixer
        val sw = if (half) st.width / 2 else st.width
        val sh = if (half) st.height / 2 else st.height
        val screenAspect = st.darW.toFloat() / st.darH
        val photoAspect = uprightW / uprightH
        // Display width units per TV pixel
        val par = screenAspect * sh / sw
        val (dw, dh) = if (photoAspect > screenAspect) {
            sw * par to sw * par / photoAspect
        } else {
            sh * photoAspect to sh.toFloat()
        }
        val w = ((dw / par).roundToInt() / 4 * 4).coerceIn(4, sw / 4 * 4)
        val h = (dh.roundToInt() / 2 * 2).coerceIn(2, sh)
        return w to h
    }

    private fun uprightWidth(p: PhotoPagerView.Page) = if (Orientation.swapsAxes(p.orientation)) p.bitmap.height else p.bitmap.width

    /** [page]'s bitmap → upright → region [r] of it at [w] x [h]. */
    private fun pageMatrix(page: PhotoPagerView.Page, r: RectF, w: Int, h: Int, out: Matrix) {
        Orientation.matrix(page.orientation, page.rawW.toFloat(), page.rawH.toFloat(), o)
        out.setScale(page.rawW.toFloat() / page.bitmap.width, page.rawH.toFloat() / page.bitmap.height)
        out.postConcat(o)
        out.postTranslate(-r.left, -r.top)
        out.postScale(w / r.width(), h / r.height())
    }

    /** A black [w] x [h] picture (reused while the size stays), drawn on by [block]. */
    private fun draw(w: Int, h: Int, half: Boolean = false, block: (Canvas, Matrix) -> Unit): Bitmap? {
        val old = if (half) liveTarget else target
        val bmp = old?.takeIf { it.width == w && it.height == h } ?: run {
            old?.recycle()
            val b = runCatching { Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888) }.getOrNull()
            if (half) liveTarget = b else target = b
            b ?: return null
        }
        val c = Canvas(bmp)
        c.drawColor(Color.BLACK)
        block(c, m)
        return bmp
    }

    /**
     * Region [region] (upright pixels; the whole photo at fit) of the full-resolution JPEG (or
     * a DNG's preview) decoded at the TV's size, in bands of stored rows so a large region at
     * half size never sits in memory at once.
     */
    private fun sharp(t: Int, photo: Photo, page: PhotoPagerView.Page, st: TvState, region: RectF, w: Int, h: Int, key: Any) {
        val t0 = SystemClock.elapsedRealtime()
        // One JPEG in memory per photo, not one read per zoom or pan
        if (regionUri != photo.uri) {
            regionSrc?.close()
            regionSrc = RegionSource.open(resolver, photo)
            regionUri = photo.uri
        }
        val src = regionSrc ?: return
        run {
            var sample = 1
            while (region.width() / (sample * 2) >= w && region.height() / (sample * 2) >= h) sample *= 2
            Orientation.matrix(page.orientation, page.rawW.toFloat(), page.rawH.toFloat(), o)
            // The region in stored pixels
            val inv = Matrix().also { o.invert(it) }
            val sf = RectF(region).also { inv.mapRect(it) }
            val s = Rect().also { sf.roundOut(it) }
            if (!s.intersect(0, 0, page.rawW, page.rawH)) return
            val sx = w / region.width()
            val sy = h / region.height()
            val band = BAND_ROWS * sample
            // Two pixels of overlap each side, so the filtering has neighbours at a band's edge
            val pad = 2 * sample
            val r = Rect()
            val clip = RectF()
            var complete = false
            val bmp = draw(w, h) { c, mm ->
                var y = s.top
                while (y < s.bottom) {
                    if (t != token || closed) return@draw
                    val y1 = min(s.bottom, y + band)
                    r.set(max(0, s.left - pad), max(0, y - pad), min(page.rawW, s.right + pad), min(page.rawH, y1 + pad))
                    val strip = src.decode(r, sample) ?: return@draw
                    mm.setScale(r.width().toFloat() / strip.width, r.height().toFloat() / strip.height)
                    mm.postTranslate(r.left.toFloat(), r.top.toFloat())
                    mm.postConcat(o)
                    mm.postTranslate(-region.left, -region.top)
                    mm.postScale(sx, sy)
                    // Only this band's own rows, wherever the orientation puts them
                    clip.set(s.left.toFloat(), y.toFloat(), s.right.toFloat(), y1.toFloat())
                    o.mapRect(clip)
                    clip.offset(-region.left, -region.top)
                    clip.set(clip.left * sx, clip.top * sy, clip.right * sx, clip.bottom * sy)
                    c.save()
                    c.clipRect(clip)
                    c.drawBitmap(strip, mm, paint)
                    c.restore()
                    strip.recycle()
                    y = y1
                }
                complete = true
            } ?: return
            // A band that failed to decode leaves the quick picture on the TV
            if (!complete || t != token || closed) return
            if (sendPicture(bmp)) {
                sentKey = key
                Log.d(TAG, "TV ${st.mode}: ${photo.name} sharp ${w}x$h of ${s.width()}x${s.height()} at 1/$sample in ${SystemClock.elapsedRealtime() - t0} ms")
            }
        }
    }

    // --- The socket (docs/HDMI.md: a 32-byte little-endian header, then the payload)

    private fun sendPicture(bmp: Bitmap, half: Boolean = false): Boolean {
        val len = bmp.byteCount
        val old = if (half) livePixels else pixels
        // Grown, never shrunk: a direct buffer is freed only by the GC. Up to 8 MB on the Java
        // heap: out of memory means no picture this time, not a crash
        val buf = old?.takeIf { it.capacity() >= len } ?: try {
            ByteBuffer.allocateDirect(len).also { if (half) livePixels = it else pixels = it }
        } catch (e: OutOfMemoryError) {
            Log.w(TAG, "TV: no memory for a ${bmp.width}x${bmp.height} picture")
            return false
        }
        buf.clear()
        buf.limit(len)
        bmp.copyPixelsToBuffer(buf)
        buf.flip()
        return send(MSG_SHOW, bmp.width, bmp.height, buf, if (half) SHOW_HALF else 0)
    }

    private fun send(type: Int, a0: Int, a1: Int, payload: ByteBuffer?, a2: Int = 0): Boolean {
        val ch = channel ?: return false
        val header = ByteBuffer.allocate(HEADER).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(MAGIC).putInt(type).putInt(payload?.remaining() ?: 0).putInt(a0).putInt(a1).putInt(a2)
        header.position(HEADER).flip()
        return try {
            while (header.hasRemaining()) ch.write(header)
            if (payload != null) while (payload.hasRemaining()) ch.write(payload)
            true
        } catch (e: IOException) {
            Log.w(TAG, "hdmid: $e")
            false
        }
    }

    private fun readLoop(s: LocalSocket) {
        val input = runCatching { s.inputStream }.getOrNull() ?: return
        val header = ByteArray(HEADER)
        /** On this connection: the same state again changes nothing (and must not resend 8 MB) */
        var last: String? = null
        try {
            while (!closed) {
                readFully(input, header)
                val b = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
                if (b.int != MAGIC) break
                val type = b.int
                val len = b.int
                if (len < 0 || len > 65536) break
                val payload = ByteArray(len)
                readFully(input, payload)
                if (type == MSG_STATE) {
                    val text = String(payload)
                    if (text == last) continue
                    last = text
                    val st = TvState.parse(text)
                    main.post {
                        if (closed || socket !== s) return@post
                        retries = 0
                        state = st
                        // A new mode or a new TV: whatever was sent is not on screen any more
                        wantedKey = null
                        onWorker { sentKey = null }
                        onState(st)
                    }
                }
            }
        } catch (e: IOException) {
            // Closed by stop(), or hdmid went away
        }
        // Replaced by a new connection, or closed on purpose: nothing to report
        if (closed || socket !== s) return
        main.post {
            if (closed || socket !== s) return@post
            state = DISCONNECTED
            onState(DISCONNECTED)
            reconnectLater()
        }
    }

    private fun readFully(input: InputStream, into: ByteArray) {
        var n = 0
        while (n < into.size) {
            val r = input.read(into, n, into.size - n)
            if (r < 0) throw IOException("hdmid closed the connection")
            n += r
        }
    }

    private fun onWorker(block: () -> Unit) {
        try {
            worker.execute(block)
        } catch (e: RejectedExecutionException) {
            // Stopped
        }
    }

    companion object {
        private const val TAG = "GcGallery"
        private const val SOCKET = "hdmid"
        private const val MAGIC = 0x44484347   // "GCHD"
        private const val HEADER = 32
        private const val MSG_SHOW = 2
        private const val MSG_SET_MODE = 4
        private const val MSG_STATE = 16
        /** SHOW flag: a picture for a half-size layer that the mixer doubles. */
        private const val SHOW_HALF = 1
        /** How long a photo stays before the sharper decode starts (a swipe through photos skips it). */
        private const val SHARP_DELAY_MS = 300L
        /** Decoded rows per band of the sharp decode: 2304 x 256 x 4 bytes at 1/2 of a 16 MP shot. */
        private const val BAND_ROWS = 256
        private const val MAX_RETRIES = 5
        private const val RETRY_MS = 2000L
        private val DISCONNECTED = TvState(false, "", 0, 0, 16, 9, false, false, "", emptyList())
    }
}
