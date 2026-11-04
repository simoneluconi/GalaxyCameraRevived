package com.simoneluconi.gcgallery.edit

import android.content.ContentResolver
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import com.simoneluconi.gcgallery.data.Photo
import com.simoneluconi.gcgallery.image.Fd
import com.simoneluconi.gcgallery.image.JpegSegments
import com.simoneluconi.gcgallery.image.Orientation
import com.simoneluconi.gcgallery.image.Tiff
import com.simoneluconi.gcgallery.image.Xmp

/**
 * Lossless edits, written into the files themselves so other apps see them. Where the tag
 * already exists, only its bytes are patched: a 5 MB JPEG or a 35 MB DNG is never rewritten
 * (a rewrite over FUSE to the microSD takes seconds). Off the main thread; the caller holds
 * write access (MediaAccess.write).
 */
object Edits {
    private const val TAG = "GcGallery"

    /** Turns [p] (and its DNG) by [op]; returns the new orientation of [p], or -1. */
    fun rotate(resolver: ContentResolver, p: Photo, op: Int): Int {
        var result = -1
        for (file in p.files()) {
            val o = try {
                resolver.openFileDescriptor(file.uri, "rw")?.use { pfd -> rotateFile(Fd(pfd.fileDescriptor), file, op) } ?: -1
            } catch (e: Throwable) {
                // OOM on a damaged file included: the edit thread must survive
                Log.e(TAG, "rotate ${file.name} failed", e)
                -1
            }
            if (file === p) result = o
        }
        return result
    }

    private fun rotateFile(f: Fd, file: Photo, op: Int): Int {
        val tiff = tiffOf(f, file)
        val entry = tiff?.ifd(tiff.firstIfd)?.find(Tiff.TAG_ORIENTATION)?.takeIf { it.type == Tiff.TYPE_SHORT }
        if (tiff != null && entry != null) {
            val next = Orientation.compose(op, tiff.ints(entry, 1).firstOrNull()?.toInt() ?: 1)
            f.write(entry.valuePos, tiff.shortBytes(next))
            return next
        }
        // No Orientation tag to patch: only a JPEG can take a rewrite
        if (!file.isJpeg) return -1
        val exif = ExifInterface(f.fd)
        val next = Orientation.compose(op, exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, 1))
        exif.setAttribute(ExifInterface.TAG_ORIENTATION, next.toString())
        exif.saveAttributes()
        return next
    }

    /**
     * Sets xmp:Rating in [p] and its DNG. Returns false when no file could take it in place or by
     * rewrite (the rating then lives only in RatingStore).
     */
    fun setRating(resolver: ContentResolver, p: Photo, rating: Int): Boolean {
        var any = false
        for (file in p.files()) {
            val ok = try {
                resolver.openFileDescriptor(file.uri, "rw")?.use { pfd -> rateFile(Fd(pfd.fileDescriptor), file, rating) } ?: false
            } catch (e: Throwable) {
                Log.e(TAG, "rating ${file.name} failed", e)
                false
            }
            any = any || ok
        }
        return any
    }

    private fun rateFile(f: Fd, file: Photo, rating: Int): Boolean {
        val packetAt = xmpPacket(f, file)
        if (packetAt != null) {
            val (pos, len) = packetAt
            if (len <= 0 || len > MAX_XMP) return false
            val packet = String(f.read(pos, len), Charsets.ISO_8859_1)
            val patched = Xmp.withRating(packet, rating) ?: return false
            if (patched.length != packet.length) return false
            f.write(pos, patched.toByteArray(Charsets.ISO_8859_1))
            return true
        }
        // A packet without room (other apps' photos: motion photo, HDR gain map, Lightroom…) is
        // never replaced; the rating then stays in RatingStore. Only a JPEG with no XMP at all
        // gets one, padded so the next change is in place
        if (!file.isJpeg) return false
        Log.d(TAG, "rating ${file.name}: no XMP, rewriting the file")
        val exif = ExifInterface(f.fd)
        if (exif.getAttribute(ExifInterface.TAG_XMP) != null) return false
        exif.setAttribute(ExifInterface.TAG_XMP, Xmp.newPacket(rating))
        exif.saveAttributes()
        return true
    }

    /** No real packet is this big; a bigger "count" is a damaged file. */
    private const val MAX_XMP = 1 shl 20

    private fun tiffOf(f: Fd, file: Photo): Tiff? =
        if (file.isJpeg) JpegSegments.scan(f)?.exifBase?.takeIf { it >= 0 }?.let { Tiff.at(f, it) } else Tiff.at(f, 0)

    /** The XMP packet: a JPEG's own APP1 segment, else the TIFF tag 700 (DNG, or EXIF IFD0). */
    private fun xmpPacket(f: Fd, file: Photo): Pair<Long, Int>? {
        if (file.isJpeg) {
            val seg = JpegSegments.scan(f) ?: return null
            if (seg.xmpPos >= 0 && seg.xmpLen > 0) return seg.xmpPos to seg.xmpLen
        }
        val tiff = tiffOf(f, file) ?: return null
        val e = tiff.ifd(tiff.firstIfd)?.find(Tiff.TAG_XMP) ?: return null
        return e.valuePos to e.count
    }
}
