// SPDX-License-Identifier: GPL-3.0-or-later
package com.simoneluconi.gcgallery.transfer

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class QrCodeTest {

    @Test
    fun versionsGrowWithTheText() {
        assertEquals(21, QrCode.encode("WIFI")!!.size)
        // A typical hotspot string: version 3 or 4
        val wifi = QrCode.encode("WIFI:T:WPA;S:AndroidShare_4821;P:k3s9x2m7q1zt;;")!!
        assertEquals(true, wifi.size in 29..33)
        assertNotNull(QrCode.encode("x".repeat(213)))
        assertNull(QrCode.encode("x".repeat(214)))
    }

    /**
     * Writes the matrices of a few texts (versions 1, 3, 4, 7 and 10) as 0/1 rows, for a real
     * decoder to read: GC_QR_OUT=<dir> ./gradlew :gallery:testDebugUnitTest, then a script.
     */
    @Test
    fun dumpForDecoding() {
        val dir = System.getenv("GC_QR_OUT")?.let(::File) ?: return
        dir.mkdirs()
        val texts = listOf(
            "WIFI",
            "WIFI:T:WPA;S:AndroidShare_4821;P:k3s9x2m7q1zt;;",
            "WIFI:T:WPA;S:Galaxy Camera \\; test;P:p\\:a\\\\ss;;",
            "https://gc100.local:8443 " + "a".repeat(80),
            "é€ " + "z".repeat(180),
        )
        for ((i, t) in texts.withIndex()) {
            val q = QrCode.encode(t)!!
            File(dir, "qr$i.txt").writeText(t + "\n" + (0 until q.size).joinToString("\n") { y ->
                (0 until q.size).joinToString("") { x -> if (q[x, y]) "1" else "0" }
            })
        }
    }
}
