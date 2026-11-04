package com.simoneluconi.gcgallery.image

import android.graphics.Matrix

/**
 * EXIF orientation (1..8) as the matrix that takes stored pixels to the upright picture.
 * Photos are always shown through it; bitmaps are never rotated in memory, except thumbnails.
 */
object Orientation {
    const val NORMAL = 1
    const val FLIP_H = 2
    const val ROTATE_CW = 6
    const val ROTATE_CCW = 8

    /** The 2x2 part of each orientation, row-major: x' = a x + b y, y' = c x + d y. */
    private val LINEAR = arrayOf(
        intArrayOf(1, 0, 0, 1),
        intArrayOf(-1, 0, 0, 1),
        intArrayOf(-1, 0, 0, -1),
        intArrayOf(1, 0, 0, -1),
        intArrayOf(0, 1, 1, 0),
        intArrayOf(0, -1, 1, 0),
        intArrayOf(0, -1, -1, 0),
        intArrayOf(0, 1, -1, 0),
    )

    fun valid(o: Int) = if (o in 1..8) o else NORMAL

    fun swapsAxes(o: Int) = valid(o) >= 5

    /** Stored [w] x [h] pixels → upright, into [m]. With w = h = 0 it is the rotation alone. */
    fun matrix(o: Int, w: Float, h: Float, m: Matrix) {
        val l = LINEAR[valid(o) - 1]
        // The translation brings the turned image back to the positive quadrant
        val tx = if (l[0] < 0 || l[1] < 0) (if (l[0] != 0) w else h) else 0f
        val ty = if (l[2] < 0 || l[3] < 0) (if (l[2] != 0) w else h) else 0f
        m.setValues(floatArrayOf(l[0].toFloat(), l[1].toFloat(), tx, l[2].toFloat(), l[3].toFloat(), ty, 0f, 0f, 1f))
    }

    /** The orientation of a picture shown as [o] and then turned by [op] (ROTATE_CW, ROTATE_CCW, FLIP_H). */
    fun compose(op: Int, o: Int): Int {
        val a = LINEAR[valid(op) - 1]
        val b = LINEAR[valid(o) - 1]
        val r = intArrayOf(
            a[0] * b[0] + a[1] * b[2], a[0] * b[1] + a[1] * b[3],
            a[2] * b[0] + a[3] * b[2], a[2] * b[1] + a[3] * b[3],
        )
        return LINEAR.indexOfFirst { it.contentEquals(r) } + 1
    }

    fun degrees(o: Int) = when (valid(o)) {
        3, 4 -> 180
        5, 6 -> 90
        7, 8 -> 270
        else -> 0
    }

    fun mirrored(o: Int) = valid(o) in intArrayOf(2, 4, 5, 7)
}
