package com.simoneluconi.gcgallery.image

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Rect
import android.os.SystemClock
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import com.simoneluconi.gcgallery.data.Photo
import java.io.ByteArrayInputStream
import kotlin.math.min

/** A decoded picture: [bitmap] is [rawW] x [rawH] stored pixels scaled down, shown through [orientation]. */
class Decoded(val bitmap: Bitmap, val rawW: Int, val rawH: Int, val orientation: Int)

/**
 * Decoding for the grid and the viewer. JPEGs go through BitmapFactory: with inSampleSize 2, 4
 * or 8 libjpeg-turbo scales in the DCT, so a 1/8 decode of a 16 MP shot never builds the full
 * image. A DNG shows its embedded ISP JPEG.
 */
object Decode {

    /** The short side of a grid thumbnail, in pixels. */
    const val THUMB = 256

    /** Largest power of two that keeps [size] / sample >= [target]. */
    fun sampleFor(size: Int, target: Int): Int {
        var s = 1
        while (size / (s * 2) >= target) s *= 2
        return s
    }

    /** An upright thumbnail, and the rating found in the file's XMP (if any). */
    fun thumbnail(resolver: ContentResolver, p: Photo, onRating: (Int) -> Unit): Bitmap? = when {
        p.isJpeg -> resolver.openFileDescriptor(p.uri, "r")?.use { pfd ->
            val f = Fd(pfd.fileDescriptor)
            val t0 = SystemClock.elapsedRealtime()
            val head = f.read(0, HEAD)
            val exif = exifOf(head)
            exif?.getAttribute(ExifInterface.TAG_XMP)?.let { Xmp.rating(it) }?.let(onRating)
            val o = exif?.getAttributeInt(ExifInterface.TAG_ORIENTATION, 1) ?: 1
            // The EXIF thumbnail (GC Camera embeds one) is free; take it when it is big enough
            val embedded = runCatching { exif?.thumbnailBitmap }.getOrNull()
            if (embedded != null && min(embedded.width, embedded.height) >= THUMB / 2) return@use upright(embedded, o, THUMB)
            val jpeg = readAll(f, pfd.statSize, head)
            val t1 = SystemClock.elapsedRealtime()
            val b = boundsOf(jpeg) ?: return@use null
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sampleFor(min(b.first, b.second), THUMB)
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            val bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, opts)?.let { upright(it, o, THUMB) }
            Log.d(TAG, "thumb ${p.name}: read ${t1 - t0} ms, decode /${opts.inSampleSize} ${SystemClock.elapsedRealtime() - t1} ms")
            bmp
        }
        p.isRaw -> resolver.openFileDescriptor(p.uri, "r")?.use { pfd ->
            val fd = pfd.fileDescriptor
            val exif = runCatching { ExifInterface(fd) }.getOrNull()
            val o = exif?.getAttributeInt(ExifInterface.TAG_ORIENTATION, 1) ?: 1
            exif?.getAttribute(ExifInterface.TAG_XMP)?.let { Xmp.rating(it) }?.let(onRating)
            // The EXIF thumbnail (GC Camera embeds one) is free; take it when it is big enough
            val embedded = runCatching { exif?.thumbnailBitmap }.getOrNull()
            if (embedded != null && min(embedded.width, embedded.height) >= THUMB / 2) {
                upright(embedded, o, THUMB)
            } else {
                dngPreview(Fd(fd))?.let { jpeg ->
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
                    val opts = BitmapFactory.Options().apply { inSampleSize = sampleFor(min(bounds.outWidth, bounds.outHeight), THUMB) }
                    BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, opts)?.let { upright(it, o, THUMB) }
                }
            }
        }
        // PNG, WebP, HEIF…: ImageDecoder applies the orientation itself
        else -> ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, p.uri)) { d, info, _ ->
            d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            d.setTargetSampleSize(sampleFor(min(info.size.width, info.size.height), THUMB))
        }.let { upright(it, 1, THUMB) }
    }

    /** Decoded to cover a [viewW] x [viewH] screen at fit. */
    fun screen(resolver: ContentResolver, p: Photo, viewW: Int, viewH: Int): Decoded? = when {
        p.isJpeg -> jpegBytes(resolver, p)?.let { jpeg ->
            val o = exifOf(jpeg)?.getAttributeInt(ExifInterface.TAG_ORIENTATION, 1) ?: 1
            val (w, h) = boundsOf(jpeg) ?: return@let null
            val opts = BitmapFactory.Options().apply { inSampleSize = fitSample(w, h, o, viewW, viewH) }
            BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, opts)?.let { Decoded(it, w, h, o) }
        }
        p.isRaw -> resolver.openFileDescriptor(p.uri, "r")?.use { pfd ->
            val fd = pfd.fileDescriptor
            val o = runCatching { ExifInterface(fd).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1) }.getOrDefault(1)
            val jpeg = dngPreview(Fd(fd)) ?: return@use null
            val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, b)
            if (b.outWidth <= 0) return@use null
            val opts = BitmapFactory.Options().apply { inSampleSize = fitSample(b.outWidth, b.outHeight, o, viewW, viewH) }
            BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, opts)?.let { Decoded(it, b.outWidth, b.outHeight, o) }
        }
        else -> {
            var w = 0
            var h = 0
            val bmp = ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, p.uri)) { d, info, _ ->
                d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                w = info.size.width
                h = info.size.height
                d.setTargetSampleSize(fitSample(w, h, 1, viewW, viewH))
            }
            Decoded(bmp, w, h, 1)
        }
    }

    /** The sample that keeps the decode at least as big as the picture shown at fit. */
    private fun fitSample(w: Int, h: Int, o: Int, viewW: Int, viewH: Int): Int {
        val ow = if (Orientation.swapsAxes(o)) h else w
        val oh = if (Orientation.swapsAxes(o)) w else h
        val fit = min(viewW.toFloat() / ow, viewH.toFloat() / oh)
        if (fit >= 1f) return 1
        var s = 1
        while (s * 2 <= 1f / fit) s *= 2
        return s
    }

    /**
     * The whole JPEG in memory. Over FUSE (no passthrough on kernel 3.4) every read is a round
     * trip through MediaProvider, and BitmapFactory reads in small chunks: one big pread is
     * several times faster than decoding from the descriptor.
     */
    fun jpegBytes(resolver: ContentResolver, p: Photo): ByteArray? =
        resolver.openFileDescriptor(p.uri, "r")?.use { pfd -> readAll(Fd(pfd.fileDescriptor), pfd.statSize, null) }

    private fun readAll(f: Fd, size: Long, head: ByteArray?): ByteArray {
        if (size in 1..MAX_JPEG) {
            if (head != null && head.size.toLong() == size) return head
            return f.read(0, size.toInt())
        }
        // Unknown size: read until EOF
        val out = java.io.ByteArrayOutputStream()
        var pos = 0L
        while (true) {
            val b = f.read(pos, 1 shl 20)
            if (b.isEmpty()) break
            out.write(b)
            pos += b.size
            if (b.size < 1 shl 20) break
        }
        return out.toByteArray()
    }

    /** EXIF and XMP from the APP segments at the start of a JPEG (they all come before the scan). */
    private fun exifOf(bytes: ByteArray): ExifInterface? = runCatching { ExifInterface(ByteArrayInputStream(bytes)) }.getOrNull()

    private fun boundsOf(jpeg: ByteArray): Pair<Int, Int>? {
        val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, b)
        return if (b.outWidth > 0 && b.outHeight > 0) b.outWidth to b.outHeight else null
    }

    private const val TAG = "GcGallery"
    /** EXIF (with its thumbnail) and XMP fit in the first 128 KB: APP segments are 64 KB at most. */
    private const val HEAD = 128 * 1024
    private const val MAX_JPEG = 64L * 1024 * 1024

    /**
     * Scaled to [short] on the short side (never up), turned upright, RGB_565 for the cache.
     * Cropped to 2:1 at most: the grid centre-crops to a square anyway, and a panorama would
     * waste the cache (and pass the Mali-400's 4096 px texture limit).
     */
    private fun upright(src: Bitmap, o: Int, short: Int): Bitmap {
        val sw = src.width
        val sh = src.height
        val w = min(sw, sh * 2)
        val h = min(sh, sw * 2)
        val s = (short.toFloat() / min(w, h)).coerceAtMost(1f)
        val m = Matrix()
        Orientation.matrix(o, 0f, 0f, m)
        m.preScale(s, s)
        val out = Bitmap.createBitmap(src, (sw - w) / 2, (sh - h) / 2, w, h, m, true)
        if (out !== src) src.recycle()
        if (out.config == Bitmap.Config.RGB_565) return out
        return out.copy(Bitmap.Config.RGB_565, false).also { out.recycle() }
    }

    /**
     * The biggest JPEG a DNG embeds (GC Camera's is the full-size ISP JPEG), from IFD0 and its
     * SubIFDs, or null.
     */
    fun dngPreview(f: Fd): ByteArray? {
        val tiff = Tiff.at(f, 0) ?: return null
        var best: Pair<Long, Int>? = null
        var bestWidth = 0L
        for (ifd in tiff.allIfds()) {
            val compression = ifd.find(Tiff.TAG_COMPRESSION)?.let { tiff.ints(it, 1).firstOrNull() } ?: continue
            if (compression != 6L && compression != 7L) continue
            if (ifd.find(Tiff.TAG_PHOTOMETRIC)?.let { tiff.ints(it, 1).firstOrNull() } == Tiff.PHOTOMETRIC_CFA.toLong()) continue
            val width = ifd.find(Tiff.TAG_IMAGE_WIDTH)?.let { tiff.ints(it, 1).firstOrNull() } ?: 0L
            val off = (ifd.find(Tiff.TAG_JPEG_OFFSET) ?: ifd.find(Tiff.TAG_STRIP_OFFSETS))?.let { tiff.ints(it, 1).firstOrNull() } ?: continue
            val len = (ifd.find(Tiff.TAG_JPEG_LENGTH) ?: ifd.find(Tiff.TAG_STRIP_BYTE_COUNTS))?.let { tiff.ints(it, 1).firstOrNull() } ?: continue
            if (len <= 0 || len > MAX_PREVIEW) continue
            if (best == null || width > bestWidth) {
                best = off to len.toInt()
                bestWidth = width
            }
        }
        val (off, len) = best ?: return null
        return f.read(off, len).takeIf { it.size == len }
    }

    private const val MAX_PREVIEW = 24L * 1024 * 1024
}

/**
 * Regions of one picture at full or reduced resolution, for pixel peeping. Coordinates are
 * stored pixels. Not thread-safe: use it from one thread.
 */
class RegionSource private constructor(private val decoder: BitmapRegionDecoder) : AutoCloseable {

    fun decode(rect: Rect, sample: Int): Bitmap? {
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return runCatching { decoder.decodeRegion(rect, opts) }.getOrNull()
    }

    override fun close() = decoder.recycle()

    companion object {
        fun open(resolver: ContentResolver, p: Photo): RegionSource? = runCatching {
            when {
                // From memory: region decodes re-read the file, and FUSE reads are slow
                p.isJpeg -> {
                    val jpeg = Decode.jpegBytes(resolver, p) ?: return null
                    RegionSource(BitmapRegionDecoder.newInstance(jpeg, 0, jpeg.size))
                }
                p.isRaw -> resolver.openFileDescriptor(p.uri, "r")?.use { pfd ->
                    val jpeg = Decode.dngPreview(Fd(pfd.fileDescriptor)) ?: return null
                    RegionSource(BitmapRegionDecoder.newInstance(jpeg, 0, jpeg.size))
                }
                else -> null
            }
        }.getOrNull()
    }
}
