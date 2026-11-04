package com.simoneluconi.gccamera.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.ExifInterface
import kotlin.math.abs
import kotlin.math.atan2

/**
 * Roll from the accelerometer, for the level, the EXIF orientation and the UI rotation.
 * The sensors HAL already reports in the landscape frame the display uses: x right, y up,
 * and an axis pointing up reads +g.
 */
class OrientationSensor(
    context: Context,
    private val onRoll: (Float) -> Unit,
    private val onUiRotation: (Int) -> Unit,
) : SensorEventListener {

    private val sm = context.getSystemService(SensorManager::class.java)
    private val accel = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private var gx = 0f
    private var gy = 9.8f

    /**
     * Degrees. 0 = level in normal landscape. Turning the camera clockwise (as its user sees
     * it) gives negative values: the right edge goes down, so x reads −g at −90.
     */
    var roll = 0f
        private set

    /** One of ExifInterface.ORIENTATION_*, with hysteresis between the four positions. */
    var exifOrientation = ExifInterface.ORIENTATION_NORMAL
        private set

    /** How far UI elements turn (View.rotation degrees) to read upright: 0, 90, -90 or 180. */
    val uiRotation get() = uiRotationFor(exifOrientation)

    fun start() {
        accel?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
    }

    fun stop() = sm.unregisterListener(this)

    override fun onSensorChanged(e: SensorEvent) {
        // Light low-pass so the level does not jitter
        gx += (e.values[0] - gx) * SMOOTHING
        gy += (e.values[1] - gy) * SMOOTHING
        roll = Math.toDegrees(atan2(gx, gy).toDouble()).toFloat()
        val o = bucket(roll, exifOrientation)
        if (o != exifOrientation) {
            exifOrientation = o
            onUiRotation(uiRotationFor(o))
        }
        onRoll(roll)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun bucket(r: Float, current: Int): Int {
        // Clockwise by 90° (roll −90): the scene's top is the image's left edge, so the
        // image must turn 90° clockwise to display: ORIENTATION_ROTATE_90
        val target = when {
            abs(r) <= 45f -> ExifInterface.ORIENTATION_NORMAL
            r < -45f && r >= -135f -> ExifInterface.ORIENTATION_ROTATE_90
            r > 45f && r <= 135f -> ExifInterface.ORIENTATION_ROTATE_270
            else -> ExifInterface.ORIENTATION_ROTATE_180
        }
        if (target == current) return current
        // Only switch once well past the boundary
        var d = abs(r - rollOf(target))
        if (d > 180f) d = 360f - d
        return if (d < 45f - HYSTERESIS) target else current
    }

    private fun rollOf(o: Int) = when (o) {
        ExifInterface.ORIENTATION_NORMAL -> 0f
        ExifInterface.ORIENTATION_ROTATE_90 -> -90f
        ExifInterface.ORIENTATION_ROTATE_270 -> 90f
        else -> 180f
    }

    private fun uiRotationFor(o: Int) = rollOf(o).toInt()

    companion object {
        private const val SMOOTHING = 0.25f
        private const val HYSTERESIS = 10f
    }
}
