// SPDX-License-Identifier: GPL-3.0-or-later
package com.simoneluconi.gcgallery.image

/**
 * The few XMP properties the gallery reads and writes. Packets are edited as ISO-8859-1 text
 * (one char per byte) and keep their length: what is added comes out of the packet's padding,
 * so the file is patched in place instead of rewritten.
 */
object Xmp {
    const val GC_NS = "https://github.com/simoneluconi/GalaxyCameraRevived/ns/gc/1.0/"
    private const val XMP_NS = "http://ns.adobe.com/xap/1.0/"

    private val RATING_ATTR = Regex("""xmp:Rating\s*=\s*(["'])(-?\d+)\1""")
    private val RATING_ELEM = Regex("""<xmp:Rating>\s*(-?\d+)\s*</xmp:Rating>""")
    private val GC_ATTR = Regex("""gc:(\w+)\s*=\s*(["'])([^"']*)\2""")
    private val GC_ELEM = Regex("""<gc:(\w+)>([^<]*)</gc:\1>""")

    /** 0..5, or null when the packet has no rating. Rejected (-1) reads as 0. */
    fun rating(xmp: String?): Int? {
        xmp ?: return null
        val v = RATING_ATTR.find(xmp)?.groupValues?.get(2) ?: RATING_ELEM.find(xmp)?.groupValues?.get(1) ?: return null
        return v.toIntOrNull()?.coerceIn(0, 5)
    }

    /** GC Camera's gc: fields (docs/GALLERY-APP.md), by local name. */
    fun gcFields(xmp: String?): Map<String, String> {
        xmp ?: return emptyMap()
        val out = LinkedHashMap<String, String>()
        for (m in GC_ATTR.findAll(xmp)) out[m.groupValues[1]] = m.groupValues[3]
        for (m in GC_ELEM.findAll(xmp)) out[m.groupValues[1]] = m.groupValues[2].trim()
        return out
    }

    /** [packet] with xmp:Rating set to [rating], the same length, or null when it does not fit. */
    fun withRating(packet: String, rating: Int): String? {
        val value = rating.toString()
        RATING_ATTR.find(packet)?.let { m ->
            val g = m.groups[2]!!.range
            return fitLength(packet.replaceRange(g, value), packet.length)
        }
        RATING_ELEM.find(packet)?.let { m ->
            val g = m.groups[1]!!.range
            return fitLength(packet.replaceRange(g, value), packet.length)
        }
        // No rating yet: an attribute on the rdf:Description that declares the xmp prefix, so the
        // prefix is bound where it is used. With none, on the first one, declaring it there too.
        val tag = "<rdf:Description"
        val starts = generateSequence(packet.indexOf(tag).takeIf { it >= 0 }) { from ->
            packet.indexOf(tag, from + tag.length).takeIf { it >= 0 }
        }.toList()
        if (starts.isEmpty()) return null
        fun declaresXmp(start: Int): Boolean {
            val end = packet.indexOf('>', start).takeIf { it >= 0 } ?: return false
            return packet.substring(start, end).contains("xmlns:xmp=")
        }
        val d = starts.firstOrNull(::declaresXmp)
        var insert = " xmp:Rating=\"$value\""
        if (d == null) insert = " xmlns:xmp=\"$XMP_NS\"$insert"
        val at = (d ?: starts.first()) + tag.length
        return fitLength(packet.substring(0, at) + insert + packet.substring(at), packet.length)
    }

    /** Takes [text] back to [length] by trimming or adding whitespace before the packet end. */
    private fun fitLength(text: String, length: Int): String? {
        val diff = text.length - length
        if (diff == 0) return text
        val end = text.lastIndexOf("<?xpacket end")
        if (end < 0) return null
        if (diff < 0) return text.substring(0, end) + " ".repeat(-diff) + text.substring(end)
        var start = end
        while (start > 0 && end - start < diff && text[start - 1].isWhitespace()) start--
        if (end - start < diff) return null
        return text.substring(0, start) + text.substring(end)
    }
}
