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
        val tag = "<rdf:Description"
        val d = packet.indexOf(tag)
        if (d < 0) return null
        var insert = " xmp:Rating=\"$value\""
        if (!packet.contains("xmlns:xmp=")) insert = " xmlns:xmp=\"$XMP_NS\"$insert"
        val at = d + tag.length
        return fitLength(packet.substring(0, at) + insert + packet.substring(at), packet.length)
    }

    /** A new packet with [rating] and room to grow, for files that have none. */
    fun newPacket(rating: Int): String {
        val body = "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"><rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">" +
            "<rdf:Description rdf:about=\"\" xmlns:xmp=\"$XMP_NS\" xmp:Rating=\"$rating\"/></rdf:RDF></x:xmpmeta>"
        val padding = buildString { repeat(PADDING / 100) { append(" ".repeat(99)).append('\n') } }
        // Empty begin (no BOM): ExifInterface writes TAG_XMP as ASCII
        return "<?xpacket begin=\"\" id=\"W5M0MpCehiHzreSzNTczkc9d\"?>$body$padding<?xpacket end=\"w\"?>"
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

    private const val PADDING = 2048
}
