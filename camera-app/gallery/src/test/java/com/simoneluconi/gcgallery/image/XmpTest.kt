// SPDX-License-Identifier: GPL-3.0-or-later
package com.simoneluconi.gcgallery.image

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class XmpTest {

    /** The JPEG XMP packet of GC100_0179.jpg, byte for byte (GC Camera, 2026-11-04). */
    private val camera: String = javaClass.getResource("/gc100-jpeg.xmp")!!.readBytes().toString(Charsets.ISO_8859_1)

    @Test
    fun readsCameraFields() {
        assertEquals(0, Xmp.rating(camera))
        val gc = Xmp.gcFields(camera)
        assertEquals("auto", gc["Pasm"])
        assertEquals("True", gc["OIS"])
        assertEquals("15", gc["ZoomMax"])
    }

    @Test
    fun ratingKeepsLength() {
        for (r in 0..5) {
            val patched = Xmp.withRating(camera, r)
            assertNotNull(patched)
            assertEquals(camera.length, patched!!.length)
            assertEquals(r, Xmp.rating(patched))
            // Nothing else changed
            assertEquals(Xmp.gcFields(camera), Xmp.gcFields(patched))
        }
    }

    @Test
    fun insertsRatingIntoPadding() {
        val noRating = camera.replace(Regex("""\s*xmp:Rating="0""""), "")
        val patched = Xmp.withRating(noRating, 4)
        assertNotNull(patched)
        assertEquals(noRating.length, patched!!.length)
        assertEquals(4, Xmp.rating(patched))
        assertEquals(Xmp.gcFields(camera), Xmp.gcFields(patched))
    }

    @Test
    fun refusesPacketWithoutRoom() {
        val tight = "<?xpacket begin=\"\"?><x:xmpmeta><rdf:RDF><rdf:Description rdf:about=\"\"/></rdf:RDF></x:xmpmeta><?xpacket end=\"w\"?>"
        assertNull(Xmp.withRating(tight, 3))
    }

    private val pad = " ".repeat(200)

    /** xmp declared on the second Description only: the rating goes there, the prefix stays bound. */
    @Test
    fun ratingGoesWhereTheXmpPrefixIsDeclared() {
        val packet = "<?xpacket begin=\"\"?><x:xmpmeta><rdf:RDF>" +
            "<rdf:Description rdf:about=\"\" xmlns:dc=\"http://purl.org/dc/elements/1.1/\"/>" +
            "<rdf:Description rdf:about=\"\" xmlns:xmp=\"http://ns.adobe.com/xap/1.0/\" xmp:CreatorTool=\"x\"/>" +
            "</rdf:RDF></x:xmpmeta>$pad<?xpacket end=\"w\"?>"
        val patched = Xmp.withRating(packet, 3)!!
        assertEquals(packet.length, patched.length)
        val second = patched.indexOf("<rdf:Description", patched.indexOf("<rdf:Description") + 1)
        assertEquals(true, patched.indexOf("xmp:Rating=\"3\"") > second)
        assertEquals(3, Xmp.rating(patched))
    }

    /** No Description declares xmp: the first one gets the attribute and the declaration. */
    @Test
    fun ratingDeclaresTheXmpPrefixWhenNoneDoes() {
        val packet = "<?xpacket begin=\"\"?><x:xmpmeta><rdf:RDF><rdf:Description rdf:about=\"\"/></rdf:RDF></x:xmpmeta>$pad<?xpacket end=\"w\"?>"
        val patched = Xmp.withRating(packet, 2)!!
        assertEquals(true, patched.contains("<rdf:Description xmlns:xmp=\"http://ns.adobe.com/xap/1.0/\" xmp:Rating=\"2\""))
        assertEquals(packet.length, patched.length)
    }
}
