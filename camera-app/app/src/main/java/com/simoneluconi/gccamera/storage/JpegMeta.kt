package com.simoneluconi.gccamera.storage

import android.location.Location
import com.simoneluconi.gccamera.camera.CameraModel
import com.simoneluconi.gccamera.camera.Pasm
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.TreeMap
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToLong

/**
 * The shot's metadata, written in front of the ISP JPEG in one pass: SOI, an EXIF APP1 (with a
 * small JPEG thumbnail in IFD1), an XMP APP1, then the ISP's own segments and scan. It replaces
 * an ExifInterface pass that rewrote the whole 4 MB file through FUSE (3–13 s per shot).
 */
object JpegMeta {

    /** Our XMP namespace (docs/GALLERY-APP.md): capture settings the EXIF has no tag for. */
    const val GC_NS = "https://github.com/simoneluconi/GalaxyCameraRevived/ns/gc/1.0/"

    /** Thumbnail width; the height follows the frame's aspect. */
    const val THUMB_WIDTH = 320

    /**
     * Writes [jpeg] with our metadata to [out]. Metadata segments already in [jpeg] (EXIF, XMP)
     * are dropped. A thumbnail that does not fit the 64 KB segment is left out.
     */
    fun write(out: OutputStream, jpeg: ByteArray, meta: ShotMeta, thumbnail: ByteArray?) {
        val src = parse(jpeg)
        val exif = exifSegment(meta, src.width, src.height, thumbnail) ?: exifSegment(meta, src.width, src.height, null)!!
        val xmp = xmpPacket(meta).toByteArray(Charsets.UTF_8)
        out.write(byteArrayOf(0xFF.toByte(), 0xD8.toByte()) + exif + segment(0xE1, XMP_ID + xmp)!!)
        for (r in src.keep) out.write(jpeg, r.first, r.last - r.first + 1)
    }

    /**
     * The JPEG's XMP packet: [xmpProperties] and ~2 KB of padding, so the gallery can set stars
     * in place without rewriting the file. gccore's DNG writer wraps the same properties.
     */
    private fun xmpPacket(m: ShotMeta): String = buildString {
        append("<?xpacket begin=\"﻿\" id=\"W5M0MpCehiHzreSzNTczkc9d\"?>\n")
        append("<x:xmpmeta xmlns:x=\"adobe:ns:meta/\">\n")
        append(" <rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">\n")
        append("  <rdf:Description rdf:about=\"\"\n")
        append(xmpProperties(m))
        append("  />\n </rdf:RDF>\n</x:xmpmeta>\n")
        repeat(XMP_PADDING_LINES) { append(" ".repeat(99)).append('\n') }
        append("<?xpacket end=\"w\"?>")
    }

    /** The rdf:Description's namespaces and attributes: xmp:Rating 0, then the gc: capture tags. */
    fun xmpProperties(m: ShotMeta): String = buildString {
        append("    xmlns:xmp=\"http://ns.adobe.com/xap/1.0/\"\n")
        append("    xmlns:gc=\"").append(GC_NS).append("\"\n")
        fun attr(name: String, value: Any?) {
            if (value != null) append("   ").append(name).append("=\"").append(escape(value.toString())).append("\"\n")
        }
        attr("xmp:Rating", 0)
        attr("xmp:CreatorTool", m.software)
        attr("xmp:CreateDate", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).format(Date(m.takenAt)))
        val c = m.capture
        attr("gc:Pasm", m.pasm.value)
        attr("gc:OIS", if (c.ois) "True" else "False")
        attr("gc:AFMode", c.focusMode)
        attr("gc:AFArea", c.afArea)
        attr("gc:Drive", c.drive)
        if (c.frameCount > 1) {
            attr("gc:BracketEV", c.bracketEv)
            attr("gc:BracketFrame", c.frameIndex + 1)
            attr("gc:BracketCount", c.frameCount)
        }
        attr("gc:Timer", c.timer)
        attr("gc:FlashPopup", c.flashPopup?.let { if (it) "up" else "down" })
        attr("gc:ZoomStep", c.zoomStep)
        attr("gc:ZoomMax", c.zoomMax)
    }

    // --- EXIF ---

    private fun exifSegment(m: ShotMeta, width: Int, height: Int, thumb: ByteArray?): ByteArray? {
        val date = Date(m.takenAt)
        val stamp = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).format(date)
        val offset = SimpleDateFormat("XXX", Locale.US).format(date)
        val i = m.info

        val ifd0 = Ifd().apply {
            ascii(0x010F, "Samsung")
            ascii(0x0110, CameraModel.model)
            short(0x0112, m.exifOrientation)
            rational(0x011A, 72, 1)
            rational(0x011B, 72, 1)
            short(0x0128, 2)
            ascii(0x0131, m.software)
            ascii(0x0132, stamp)
            ascii(0x013B, m.artist)
            short(0x0213, 1)
            ascii(0x8298, m.copyright)
        }
        val exif = Ifd().apply {
            i.exposureSeconds?.let { rational(0x829A, *exposureRational(it)) }
            i.fNumber?.let { rational(0x829D, (it * 10).roundToLong(), 10) }
            short(0x8822, exposureProgram(m.pasm))
            i.iso?.let { short(0x8827, it) }
            undefined(0x9000, "0232".toByteArray(Charsets.US_ASCII))
            ascii(0x9003, stamp)
            ascii(0x9004, stamp)
            ascii(0x9010, offset)
            ascii(0x9011, offset)
            ascii(0x9012, offset)
            undefined(0x9101, byteArrayOf(1, 2, 3, 0))
            i.biasEv?.let { srational(0x9204, (it * 100).roundToLong(), 100) }
            short(0x9207, when (m.metering) {
                "spot" -> 3
                "matrix" -> 5
                else -> 2
            })
            // Flash: bit 0 fired, bits 3-4 mode (1 = on, 2 = off, 3 = auto)
            val mode = when (m.flashMode) {
                "on" -> 1
                "auto" -> 3
                else -> 2
            }
            short(0x9209, (mode shl 3) or (if (m.flashFired) 1 else 0))
            i.focalMm?.let { rational(0x920A, (it * 100).roundToLong(), 100) }
            undefined(0xA000, "0100".toByteArray(Charsets.US_ASCII))
            short(0xA001, 1)
            long(0xA002, width.toLong())
            long(0xA003, height.toLong())
            short(0xA402, if (m.pasm == Pasm.MANUAL) 1 else 0)
            short(0xA403, if (m.whiteBalanceAuto) 0 else 1)
            i.focal35mm?.let { short(0xA405, it) }
            rational(0xA432, 41, 10, 861, 10, 28, 10, 59, 10)
            ascii(0xA433, "Samsung")
            ascii(0xA434, "4.1-86.1mm f/2.8-5.9")
        }
        val gps = m.location?.let(::gpsIfd)
        val ifd1 = thumb?.let {
            Ifd().apply {
                short(0x0103, 6)
                rational(0x011A, 72, 1)
                rational(0x011B, 72, 1)
                short(0x0128, 2)
                long(0x0201, 0)
                long(0x0202, it.size.toLong())
            }
        }
        // Placeholders first, so every IFD's size is final before the offsets are known
        ifd0.long(TAG_EXIF_IFD, 0)
        if (gps != null) ifd0.long(TAG_GPS_IFD, 0)
        val o0 = 8
        val oExif = o0 + ifd0.size
        val oGps = oExif + exif.size
        val o1 = oGps + (gps?.size ?: 0)
        val oThumb = o1 + (ifd1?.size ?: 0)
        ifd0.long(TAG_EXIF_IFD, oExif.toLong())
        if (gps != null) ifd0.long(TAG_GPS_IFD, oGps.toLong())
        ifd1?.long(0x0201, oThumb.toLong())

        val tiff = ByteBuffer.allocate(oThumb + (thumb?.size ?: 0)).order(ByteOrder.LITTLE_ENDIAN)
        tiff.put('I'.code.toByte()).put('I'.code.toByte()).putShort(42).putInt(o0)
        ifd0.write(tiff, o0, if (ifd1 != null) o1 else 0)
        exif.write(tiff, oExif, 0)
        gps?.write(tiff, oGps, 0)
        ifd1?.write(tiff, o1, 0)
        thumb?.let(tiff::put)
        return segment(0xE1, EXIF_ID + tiff.array())
    }

    private fun gpsIfd(l: Location) = Ifd().apply {
        byte(0x0000, 2, 3, 0, 0)
        ascii(0x0001, if (l.latitude >= 0) "N" else "S")
        rational(0x0002, *dms(l.latitude))
        ascii(0x0003, if (l.longitude >= 0) "E" else "W")
        rational(0x0004, *dms(l.longitude))
        if (l.hasAltitude()) {
            byte(0x0005, if (l.altitude >= 0) 0 else 1)
            rational(0x0006, (abs(l.altitude) * 100).roundToLong(), 100)
        }
        val utc = TimeZone.getTimeZone("UTC")
        val fix = Date(l.time)
        val (h, min, s) = SimpleDateFormat("H:m:s", Locale.US).apply { timeZone = utc }.format(fix).split(':').map { it.toLong() }
        rational(0x0007, h, 1, min, 1, s, 1)
        ascii(0x0012, "WGS-84")
        ascii(0x001D, SimpleDateFormat("yyyy:MM:dd", Locale.US).apply { timeZone = utc }.format(fix))
        if (l.hasAccuracy()) rational(0x001F, (l.accuracy * 100.0).roundToLong(), 100)
    }

    /** 1/250 below half a second, tenths above (0.7", 1.5", 16"). */
    private fun exposureRational(s: Double) =
        if (s < 0.5) longArrayOf(1, (1 / s).roundToLong()) else longArrayOf((s * 10).roundToLong(), 10)

    private fun dms(v: Double): LongArray {
        val a = abs(v)
        val d = floor(a)
        val mf = (a - d) * 60
        val m = floor(mf)
        return longArrayOf(d.toLong(), 1, m.toLong(), 1, ((mf - m) * 60 * 1000).roundToLong(), 1000)
    }

    /** EXIF ExposureProgram (the DNG's too). */
    fun exposureProgram(p: Pasm) = when (p) {
        Pasm.MANUAL -> 1
        Pasm.APERTURE -> 3
        Pasm.SHUTTER -> 4
        else -> 2
    }

    /** One IFD of a little-endian TIFF; entries stay sorted by tag, as TIFF requires. */
    private class Ifd {
        private class Entry(val type: Int, val count: Int, val data: ByteArray)

        private val entries = TreeMap<Int, Entry>()

        fun byte(tag: Int, vararg v: Int) = put(tag, BYTE, v.size, ByteArray(v.size) { v[it].toByte() })
        fun ascii(tag: Int, s: String?) {
            if (s != null) (s + "\u0000").toByteArray(Charsets.UTF_8).let { put(tag, ASCII, it.size, it) }
        }
        fun short(tag: Int, vararg v: Int) = put(tag, SHORT, v.size, le(v.size * 2) { v.forEach { putShort(it.toShort()) } })
        fun long(tag: Int, vararg v: Long) = put(tag, LONG, v.size, le(v.size * 4) { v.forEach { putInt(it.toInt()) } })
        /** Numerator, denominator pairs. */
        fun rational(tag: Int, vararg v: Long) = put(tag, RATIONAL, v.size / 2, le(v.size * 4) { v.forEach { putInt(it.toInt()) } })
        fun srational(tag: Int, num: Long, den: Long) = put(tag, SRATIONAL, 1, le(8) { putInt(num.toInt()).putInt(den.toInt()) })
        fun undefined(tag: Int, b: ByteArray) = put(tag, UNDEFINED, b.size, b)

        private fun put(tag: Int, type: Int, count: Int, data: ByteArray) {
            entries[tag] = Entry(type, count, data)
        }

        private fun le(n: Int, fill: ByteBuffer.() -> Unit): ByteArray =
            ByteBuffer.allocate(n).order(ByteOrder.LITTLE_ENDIAN).apply(fill).array()

        /** Bytes the IFD takes: the entry table, the next-IFD link and the values over 4 bytes. */
        val size get() = 2 + 12 * entries.size + 4 + entries.values.sumOf { if (it.data.size > 4) even(it.data.size) else 0 }

        /** Writes the IFD at [offset], which must be [out]'s position (TIFF offsets). */
        fun write(out: ByteBuffer, offset: Int, next: Int) {
            var data = offset + 2 + 12 * entries.size + 4
            out.putShort(entries.size.toShort())
            for ((tag, e) in entries) {
                out.putShort(tag.toShort()).putShort(e.type.toShort()).putInt(e.count)
                if (e.data.size <= 4) {
                    out.put(e.data)
                    repeat(4 - e.data.size) { out.put(0) }
                } else {
                    out.putInt(data)
                    data += even(e.data.size)
                }
            }
            out.putInt(next)
            for (e in entries.values) {
                if (e.data.size <= 4) continue
                out.put(e.data)
                if (e.data.size % 2 == 1) out.put(0)
            }
        }

        private fun even(n: Int) = n + (n and 1)
    }

    // --- JPEG structure ---

    private class Source(val width: Int, val height: Int, val keep: List<IntRange>)

    /** The frame size, and the byte ranges to copy: every segment but EXIF/XMP, then the scan. */
    private fun parse(j: ByteArray): Source {
        require(j.size > 4 && j[0] == 0xFF.toByte() && j[1] == 0xD8.toByte()) { "not a JPEG" }
        var width = 0
        var height = 0
        val keep = ArrayList<IntRange>()
        var p = 2
        while (p + 4 <= j.size && j[p] == 0xFF.toByte()) {
            val marker = j[p + 1].toInt() and 0xFF
            if (marker == 0xDA) break
            val len = ((j[p + 2].toInt() and 0xFF) shl 8) or (j[p + 3].toInt() and 0xFF)
            val end = p + 2 + len
            require(end <= j.size) { "truncated segment" }
            if (marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC) {
                height = ((j[p + 5].toInt() and 0xFF) shl 8) or (j[p + 6].toInt() and 0xFF)
                width = ((j[p + 7].toInt() and 0xFF) shl 8) or (j[p + 8].toInt() and 0xFF)
            }
            val metadata = marker == 0xE1 && (startsWith(j, p + 4, EXIF_ID) || startsWith(j, p + 4, XMP_ID))
            if (!metadata) keep += p until end
            p = end
        }
        keep += p until j.size
        return Source(width, height, keep)
    }

    private fun startsWith(j: ByteArray, at: Int, id: ByteArray) =
        at + id.size <= j.size && id.indices.all { j[at + it] == id[it] }

    /** A marker segment, or null when [payload] is over the 64 KB a segment can hold. */
    private fun segment(marker: Int, payload: ByteArray): ByteArray? {
        val len = payload.size + 2
        if (len > 0xFFFF) return null
        return byteArrayOf(0xFF.toByte(), marker.toByte(), (len shr 8).toByte(), len.toByte()) + payload
    }

    private fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace("\"", "&quot;")

    private val EXIF_ID = "Exif\u0000\u0000".toByteArray(Charsets.US_ASCII)
    private val XMP_ID = "http://ns.adobe.com/xap/1.0/\u0000".toByteArray(Charsets.US_ASCII)
    private const val XMP_PADDING_LINES = 20
    private const val TAG_EXIF_IFD = 0x8769
    private const val TAG_GPS_IFD = 0x8825
    private const val BYTE = 1
    private const val ASCII = 2
    private const val SHORT = 3
    private const val LONG = 4
    private const val RATIONAL = 5
    private const val UNDEFINED = 7
    private const val SRATIONAL = 10
}
