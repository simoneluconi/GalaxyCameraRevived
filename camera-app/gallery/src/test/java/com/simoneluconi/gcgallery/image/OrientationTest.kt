package com.simoneluconi.gcgallery.image

import org.junit.Assert.assertEquals
import org.junit.Test

class OrientationTest {

    @Test
    fun fourTurnsAreIdentity() {
        for (o in 1..8) {
            var x = o
            repeat(4) { x = Orientation.compose(Orientation.ROTATE_CW, x) }
            assertEquals(o, x)
        }
    }

    @Test
    fun leftUndoesRight() {
        for (o in 1..8) {
            assertEquals(o, Orientation.compose(Orientation.ROTATE_CCW, Orientation.compose(Orientation.ROTATE_CW, o)))
        }
    }

    @Test
    fun flipTwiceIsIdentity() {
        for (o in 1..8) assertEquals(o, Orientation.compose(Orientation.FLIP_H, Orientation.compose(Orientation.FLIP_H, o)))
    }

    @Test
    fun knownValues() {
        // Upright turned right is EXIF 6 (rotate 90 CW); turned right again, upside down (3)
        assertEquals(6, Orientation.compose(Orientation.ROTATE_CW, 1))
        assertEquals(3, Orientation.compose(Orientation.ROTATE_CW, 6))
        assertEquals(8, Orientation.compose(Orientation.ROTATE_CCW, 1))
        assertEquals(2, Orientation.compose(Orientation.FLIP_H, 1))
        assertEquals(90, Orientation.degrees(6))
    }
}
