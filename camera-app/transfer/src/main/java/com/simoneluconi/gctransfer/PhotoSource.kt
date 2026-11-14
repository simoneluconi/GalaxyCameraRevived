// SPDX-License-Identifier: GPL-3.0-or-later
package com.simoneluconi.gctransfer

import android.content.Context
import android.os.ParcelFileDescriptor
import java.io.File

/**
 * What the host app gives the transfer server: its photos, and how to read, show and trash
 * them. Every call comes from the server's own threads, never the main thread, and may block.
 * The server only serves what [items] lists, so the host decides what is reachable.
 */
interface PhotoSource {

    /** Called when the transfer starts, on the main thread: a chance to start loading [items]. */
    fun start() {}

    /**
     * Newest first. Return the same list instance while nothing changed: the server indexes
     * it by id once per instance, and a page load asks for every thumbnail.
     */
    fun items(): List<TransferItem>

    /** A small upright JPEG for the grid (about 256 px on the short side), or null. */
    fun thumbnail(item: TransferItem): File?

    fun open(file: TransferFile): ParcelFileDescriptor?

    /**
     * A JPEG to show for an item that is not a JPEG itself (a DNG's embedded preview), or null.
     * JPEG items are streamed from [open] instead.
     */
    fun preview(item: TransferItem): ByteArray?

    /** The details panel; [context] is localised to the browser's language when possible. */
    fun info(item: TransferItem, context: Context): TransferInfo?

    /** To the host's trash, with the item's RAW; false when the host may not. */
    fun trash(items: List<TransferItem>): Boolean
}

/** One file the page can download. [key] is the host's own object for it, untouched here. */
class TransferFile(val id: Long, val name: String, val mime: String, val size: Long, val key: Any? = null) {
    val isJpeg get() = mime == "image/jpeg"
    val isDng get() = mime == "image/x-adobe-dng"
}

/**
 * One photo on the page: its file and, for a JPEG shot with a DNG, the DNG as [raw].
 * [storage] is "sd" or "internal": the page offers a switch when both have photos.
 */
class TransferItem(
    val file: TransferFile,
    /** Milliseconds. */
    val taken: Long,
    /** Changes when the file does (seconds or any counter): it versions the cached thumbnail and view. */
    val modified: Long,
    val width: Int,
    val height: Int,
    val folder: String,
    val storage: String,
    val raw: TransferFile? = null,
) {
    val id get() = file.id

    fun files(): List<TransferFile> = listOfNotNull(file, raw)
}

/** The details panel: a headline, an exposure line, and titled sections of label/value rows. */
class TransferInfo(
    val exposure: String?,
    val line: String?,
    val latitude: Double?,
    val longitude: Double?,
    val rating: Int?,
    val sections: List<Section>,
) {
    class Section(val title: String, val rows: List<Pair<String, String>>, val isLocation: Boolean = false)
}

/**
 * Implemented by the host's Application. The service, the tile and the screen find the host
 * through it, so the library needs no setup call.
 */
interface TransferHost {

    val photoSource: PhotoSource

    /**
     * The name on the network, without ".local": letters, digits and dashes. "gc100" on an
     * EK-GC100. It also names the certificate, so keep it stable.
     */
    val transferName: String
        get() = android.os.Build.MODEL.lowercase().removePrefix("ek-").replace(Regex("[^a-z0-9-]"), "").ifEmpty { "camera" }

    /** The notification's small icon. */
    val transferIcon: Int
        get() = R.drawable.ic_wifi_transfer

    companion object {
        fun of(context: Context): TransferHost =
            context.applicationContext as? TransferHost
                ?: error("${context.applicationContext.javaClass.name} must implement TransferHost to host the Wi-Fi transfer")
    }
}
