package com.simoneluconi.gcgallery.image

import android.system.Os
import java.io.FileDescriptor
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Positioned reads and writes on a file descriptor (pread/pwrite: no shared offset to manage). */
class Fd(val fd: FileDescriptor) {

    fun read(pos: Long, n: Int): ByteArray {
        val b = ByteArray(n)
        var got = 0
        while (got < n) {
            val r = Os.pread(fd, b, got, n - got, pos + got)
            if (r <= 0) break
            got += r
        }
        return if (got == n) b else b.copyOf(got)
    }

    fun write(pos: Long, b: ByteArray) {
        var done = 0
        while (done < b.size) {
            val w = Os.pwrite(fd, b, done, b.size - done, pos + done)
            if (w <= 0) throw IOException("pwrite returned $w")
            done += w
        }
    }
}

/**
 * Just enough TIFF to patch tags in place and read the DNG facts ExifInterface does not expose.
 * [base] is where the TIFF header starts (0 for a DNG, after "Exif\0\0" in a JPEG's APP1).
 */
class Tiff private constructor(private val f: Fd, private val base: Long, val order: ByteOrder) {

    /** [valuePos] is the absolute file offset of the value, inline or not. */
    class Entry(val tag: Int, val type: Int, val count: Int, val valuePos: Long)

    class Ifd(val entries: List<Entry>, val next: Long) {
        fun find(tag: Int) = entries.firstOrNull { it.tag == tag }
    }

    val firstIfd: Long

    init {
        val h = buf(f.read(base + 4, 4))
        firstIfd = base + (h.int.toLong() and 0xFFFFFFFFL)
    }

    /** The IFD at absolute offset [pos], or null if it does not fit the file. */
    fun ifd(pos: Long): Ifd? {
        if (pos <= base) return null
        val head = f.read(pos, 2)
        if (head.size < 2) return null
        val n = buf(head).short.toInt() and 0xFFFF
        if (n == 0 || n > 1000) return null
        val raw = f.read(pos + 2, n * 12 + 4)
        if (raw.size < n * 12 + 4) return null
        val b = buf(raw)
        val entries = ArrayList<Entry>(n)
        for (i in 0 until n) {
            val at = i * 12
            val tag = b.getShort(at).toInt() and 0xFFFF
            val type = b.getShort(at + 2).toInt() and 0xFFFF
            val count = b.getInt(at + 4)
            val bytes = typeSize(type).toLong() * count
            val valuePos = if (bytes <= 4) pos + 2 + at + 8 else base + (b.getInt(at + 8).toLong() and 0xFFFFFFFFL)
            entries.add(Entry(tag, type, count, valuePos))
        }
        val next = b.getInt(n * 12).toLong() and 0xFFFFFFFFL
        return Ifd(entries, if (next == 0L) 0L else base + next)
    }

    /** SHORT, LONG or BYTE values as Longs. */
    fun ints(e: Entry, max: Int = 64): LongArray {
        val n = e.count.coerceAtMost(max)
        val size = typeSize(e.type)
        val b = buf(f.read(e.valuePos, n * size))
        return LongArray(n) {
            when (e.type) {
                TYPE_SHORT -> b.getShort(it * 2).toLong() and 0xFFFF
                8 -> b.getShort(it * 2).toLong()
                TYPE_LONG, 13 -> b.getInt(it * 4).toLong() and 0xFFFFFFFFL
                9 -> b.getInt(it * 4).toLong()
                TYPE_SRATIONAL, TYPE_RATIONAL -> 0L
                else -> b.get(it).toLong() and 0xFF
            }
        }
    }

    /** RATIONAL/SRATIONAL (or integer types) as Doubles. */
    fun doubles(e: Entry, max: Int = 64): DoubleArray {
        if (e.type != TYPE_RATIONAL && e.type != TYPE_SRATIONAL) return ints(e, max).map { it.toDouble() }.toDoubleArray()
        val n = e.count.coerceAtMost(max)
        val b = buf(f.read(e.valuePos, n * 8))
        return DoubleArray(n) {
            val num = b.getInt(it * 8)
            val den = b.getInt(it * 8 + 4)
            if (e.type == TYPE_RATIONAL) {
                val d = den.toLong() and 0xFFFFFFFFL
                if (d == 0L) 0.0 else (num.toLong() and 0xFFFFFFFFL).toDouble() / d
            } else if (den == 0) 0.0 else num.toDouble() / den
        }
    }

    fun bytes(e: Entry, max: Int): ByteArray = f.read(e.valuePos, e.count.coerceAtMost(max))

    fun shortBytes(v: Int): ByteArray = ByteBuffer.allocate(2).order(order).putShort(v.toShort()).array()

    /** IFD0 and the SubIFDs it lists (where a DNG keeps its raw data and previews). */
    fun allIfds(): List<Ifd> {
        val out = ArrayList<Ifd>()
        var pos = firstIfd
        var guard = 0
        while (pos != 0L && guard++ < 8) {
            val ifd = ifd(pos) ?: break
            out.add(ifd)
            ifd.find(TAG_SUB_IFDS)?.let { e -> for (off in ints(e, 8)) ifd(base + off)?.let(out::add) }
            pos = ifd.next
        }
        return out
    }

    private fun buf(b: ByteArray) = ByteBuffer.wrap(b).order(order)

    companion object {
        const val TYPE_SHORT = 3
        const val TYPE_LONG = 4
        const val TYPE_RATIONAL = 5
        const val TYPE_SRATIONAL = 10

        const val TAG_IMAGE_WIDTH = 256
        const val TAG_IMAGE_LENGTH = 257
        const val TAG_COMPRESSION = 259
        const val TAG_PHOTOMETRIC = 262
        const val TAG_STRIP_OFFSETS = 273
        const val TAG_ORIENTATION = 274
        const val TAG_STRIP_BYTE_COUNTS = 279
        const val TAG_XMP = 700
        const val TAG_SUB_IFDS = 330
        const val TAG_JPEG_OFFSET = 513
        const val TAG_JPEG_LENGTH = 514
        const val TAG_CFA_PATTERN = 33422
        const val TAG_BLACK_LEVEL = 50714
        const val TAG_WHITE_LEVEL = 50717
        const val TAG_ACTIVE_AREA = 50829
        const val PHOTOMETRIC_CFA = 32803

        fun typeSize(type: Int) = when (type) {
            3, 8 -> 2
            4, 9, 11, 13 -> 4
            5, 10, 12 -> 8
            else -> 1
        }

        fun at(f: Fd, base: Long): Tiff? {
            val h = f.read(base, 4)
            if (h.size < 4) return null
            val order = when {
                h[0] == 'I'.code.toByte() && h[1] == 'I'.code.toByte() -> ByteOrder.LITTLE_ENDIAN
                h[0] == 'M'.code.toByte() && h[1] == 'M'.code.toByte() -> ByteOrder.BIG_ENDIAN
                else -> return null
            }
            val magic = ByteBuffer.wrap(h, 2, 2).order(order).short.toInt()
            return if (magic == 42) Tiff(f, base, order) else null
        }
    }
}

/** Where a JPEG keeps its EXIF TIFF and its XMP packet (APP1 segments before the scan). */
class JpegSegments(val exifBase: Long, val xmpPos: Long, val xmpLen: Int) {

    companion object {
        private val EXIF_ID = "Exif\u0000\u0000".toByteArray(Charsets.ISO_8859_1)
        private val XMP_ID = "http://ns.adobe.com/xap/1.0/\u0000".toByteArray(Charsets.ISO_8859_1)

        fun scan(f: Fd): JpegSegments? {
            val soi = f.read(0, 2)
            if (soi.size < 2 || soi[0] != 0xFF.toByte() || soi[1] != 0xD8.toByte()) return null
            var pos = 2L
            var exif = -1L
            var xmp = -1L
            var xmpLen = 0
            while (true) {
                val m = f.read(pos, 4)
                if (m.size < 4 || m[0] != 0xFF.toByte()) break
                val marker = m[1].toInt() and 0xFF
                if (marker == 0xFF) {
                    pos++
                    continue
                }
                // Start of scan or end of image: no more metadata
                if (marker == 0xDA || marker == 0xD9) break
                val len = ((m[2].toInt() and 0xFF) shl 8) or (m[3].toInt() and 0xFF)
                if (marker == 0xE1) {
                    val id = f.read(pos + 4, XMP_ID.size)
                    if (startsWith(id, EXIF_ID)) exif = pos + 4 + EXIF_ID.size
                    else if (startsWith(id, XMP_ID)) {
                        xmp = pos + 4 + XMP_ID.size
                        xmpLen = len - 2 - XMP_ID.size
                    }
                }
                pos += 2 + len
            }
            return JpegSegments(exif, xmp, xmpLen)
        }

        private fun startsWith(b: ByteArray, prefix: ByteArray): Boolean {
            if (b.size < prefix.size) return false
            for (i in prefix.indices) if (b[i] != prefix[i]) return false
            return true
        }
    }
}
