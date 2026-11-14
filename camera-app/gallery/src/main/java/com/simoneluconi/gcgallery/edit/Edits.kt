// SPDX-License-Identifier: GPL-3.0-or-later
package com.simoneluconi.gcgallery.edit

import android.content.ContentResolver
import android.util.Log
import com.simoneluconi.gcgallery.data.Photo
import com.simoneluconi.gcgallery.image.Fd
import com.simoneluconi.gcgallery.image.JpegSegments
import com.simoneluconi.gcgallery.image.Orientation
import com.simoneluconi.gcgallery.image.Tiff
import com.simoneluconi.gcgallery.image.Xmp

/**
 * Lossless edits, written into the files themselves so other apps see them. Only the bytes of
 * a tag that is already there are patched, in place: a file is never rewritten. A rewrite over
 * FUSE takes seconds, and a process killed half-way would leave a truncated photo. What has no
 * tag to patch (some other apps' photos) is refused, or kept in the gallery only. Off the main
 * thread; the caller holds write access (MediaAccess.write).
 */
object Edits {
    private const val TAG = "GcGallery"

    /** A turn: [orientation] is [Photo]'s new one; [complete] is false when its DNG did not turn. */
    class Turned(val orientation: Int, val complete: Boolean)

    /** Turns [p] (and its DNG) by [op]; null when [p] itself could not be turned. */
    fun rotate(resolver: ContentResolver, p: Photo, op: Int): Turned? {
        var result = -1
        var complete = true
        for (file in p.files()) {
            val o = try {
                resolver.openFileDescriptor(file.uri, "rw")?.use { pfd -> rotateFile(Fd(pfd.fileDescriptor), file, op) } ?: -1
            } catch (e: Throwable) {
                // OOM on a damaged file included: the edit thread must survive
                Log.e(TAG, "rotate ${file.name} failed", e)
                -1
            }
            if (file === p) result = o else if (o < 0) complete = false
        }
        return if (result < 0) null else Turned(result, complete)
    }

    /** Patches the Orientation SHORT of IFD0; -1 when the file has none (it is then left alone). */
    private fun rotateFile(f: Fd, file: Photo, op: Int): Int {
        val tiff = tiffOf(f, file) ?: return -1
        val entry = tiff.ifd(tiff.firstIfd)?.find(Tiff.TAG_ORIENTATION)?.takeIf { it.type == Tiff.TYPE_SHORT } ?: return -1
        val next = Orientation.compose(op, tiff.ints(entry, 1).firstOrNull()?.toInt() ?: 1)
        f.write(entry.valuePos, tiff.shortBytes(next))
        return next
    }

    /**
     * Sets xmp:Rating in [p] and its DNG, in place. Returns false when no file has a packet with
     * room for it (the rating then lives only in RatingStore).
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
        // No packet, or one without room (other apps' photos: motion photo, HDR gain map,
        // Lightroom…): never added or replaced, which would mean rewriting the file. The rating
        // then stays in RatingStore. GC Camera's own files always have a padded packet.
        Log.d(TAG, "rating ${file.name}: no XMP packet with room, kept in the gallery")
        return false
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
