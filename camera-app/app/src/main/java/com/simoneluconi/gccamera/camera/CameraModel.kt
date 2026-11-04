package com.simoneluconi.gccamera.camera

import android.os.Build

/**
 * Which Galaxy Camera this is, from the build's model (EK-GC100 now, the EK-GC200 later): the
 * EXIF/DNG model and the file names follow it instead of a hard-coded GC100.
 */
object CameraModel {
    /** "EK-GC100". */
    val model: String = Build.MODEL?.trim()?.ifEmpty { null } ?: "EK-GC100"

    /** "GC100": the default folder and the file prefix (GC100_0001.jpg). */
    val shortName: String = model.substringAfter("EK-").filter { it.isLetterOrDigit() }.ifEmpty { "GC" }
}
