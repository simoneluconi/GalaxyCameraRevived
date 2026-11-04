package com.simoneluconi.gcgallery.data

import android.net.Uri

/** One MediaStore image. A DNG shot together with a JPEG is folded into it as [raw]. */
class Photo(
    /** MediaStore _ID (one database for every external volume), or -1 for a uri from another app. */
    val id: Long,
    val uri: Uri,
    val volume: String,
    val name: String,
    /** RELATIVE_PATH, e.g. "DCIM/GC100/". */
    val folder: String,
    val mime: String,
    /** Milliseconds. */
    val dateTaken: Long,
    /** Seconds, as MediaStore keeps it; changes when the file is written. */
    val dateModified: Long,
    val width: Int,
    val height: Int,
    val size: Long,
    val trashed: Boolean,
) {
    var raw: Photo? = null

    val isRaw get() = mime == MIME_DNG
    val isJpeg get() = mime == MIME_JPEG
    /** In MediaStore, so it can be edited, trashed and found again. */
    val inMediaStore get() = id >= 0

    /** This photo and its DNG: what trash, rotate and rate act on. */
    fun files(): List<Photo> = listOfNotNull(this, raw)

    companion object {
        const val MIME_JPEG = "image/jpeg"
        const val MIME_DNG = "image/x-adobe-dng"
    }
}
