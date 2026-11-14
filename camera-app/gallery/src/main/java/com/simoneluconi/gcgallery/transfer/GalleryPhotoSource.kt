// SPDX-License-Identifier: GPL-3.0-or-later
package com.simoneluconi.gcgallery.transfer

import android.content.Context
import android.os.ParcelFileDescriptor
import com.simoneluconi.gcgallery.GalleryApp
import com.simoneluconi.gcgallery.data.MediaIndex
import com.simoneluconi.gcgallery.data.Photo
import com.simoneluconi.gcgallery.image.Decode
import com.simoneluconi.gcgallery.image.Fd
import com.simoneluconi.gcgallery.meta.PhotoInfo
import com.simoneluconi.gctransfer.PhotoSource
import com.simoneluconi.gctransfer.TransferFile
import com.simoneluconi.gctransfer.TransferInfo
import com.simoneluconi.gctransfer.TransferItem
import java.io.File

/**
 * GC Gallery's photos for the Wi-Fi transfer: its MediaStore index (no trash), its thumbnail
 * cache, its info panel and MediaStore's trash. Each [TransferFile] carries its [Photo].
 */
class GalleryPhotoSource(private val app: GalleryApp) : PhotoSource {

    /** The index's list and what it became, so the same list gives the same items. */
    @Volatile private var cache: Pair<List<Photo>, List<TransferItem>> = emptyList<Photo>() to emptyList()

    override fun start() = app.index.start()

    override fun items(): List<TransferItem> {
        val photos = app.index.photos
        val c = cache
        if (c.first === photos) return c.second
        val items = photos.map { p ->
            val storage = if (MediaIndex.scopeOf(p) == MediaIndex.Scope.SD) "sd" else "internal"
            TransferItem(file(p), p.dateTaken, p.dateModified, p.width, p.height, p.folder, storage, p.raw?.let(::file))
        }
        cache = photos to items
        return items
    }

    private fun file(p: Photo) = TransferFile(p.id, p.name, p.mime, p.size, p)

    private fun photo(f: TransferFile) = f.key as Photo

    /** The grid's own disk cache: upright, 256 px, made once. */
    override fun thumbnail(item: TransferItem): File? = app.thumbs.file(photo(item.file))

    override fun open(file: TransferFile): ParcelFileDescriptor? = app.contentResolver.openFileDescriptor(photo(file).uri, "r")

    /** A lone DNG shows its embedded ISP JPEG. */
    override fun preview(item: TransferItem): ByteArray? {
        val p = photo(item.file)
        if (!p.isRaw) return null
        return app.contentResolver.openFileDescriptor(p.uri, "r")?.use { Decode.dngPreview(Fd(it.fileDescriptor)) }
    }

    override fun info(item: TransferItem, context: Context): TransferInfo? {
        val i = PhotoInfo.read(context, photo(item.file)) ?: return null
        val sections = i.sections.map { s -> TransferInfo.Section(s.title, s.rows.map { it.label to it.value }, s.isLocation) }
        return TransferInfo(i.exposureLine, i.barLine, i.latitude, i.longitude, i.rating, sections)
    }

    override fun trash(items: List<TransferItem>): Boolean = MediaTrash.trash(app, items.map { photo(it.file) })
}
