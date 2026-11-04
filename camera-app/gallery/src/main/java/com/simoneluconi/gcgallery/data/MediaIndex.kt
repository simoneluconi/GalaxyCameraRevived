package com.simoneluconi.gcgallery.data

import android.content.ContentUris
import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.provider.MediaStore
import android.provider.MediaStore.Images.Media
import android.util.Log
import java.util.concurrent.Executors

/**
 * Every image on every mounted volume, from one MediaStore query, newest first, DNGs folded
 * into their JPEG. Re-queried when MediaStore changes (a new shot appears on its own).
 * Read on the main thread only.
 */
class MediaIndex(context: Context) {

    /** A folder ([key] = its RELATIVE_PATH), or one of the special keys. */
    class Album(val key: String, val title: String, val count: Int)

    private val resolver = context.contentResolver
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            r.run()
        }, "media-index")
    }
    private val listeners = ArrayList<() -> Unit>()
    private val waiting = ArrayList<() -> Unit>()
    private var started = false
    private var reloadQueued = false

    /**
     * Which card the gallery shows. A camera gallery: the microSD (where GC Camera saves) is the
     * default, the phone's own storage is one tap away.
     */
    enum class Scope { SD, PHONE }

    /** Not trashed, every volume. */
    var photos: List<Photo> = emptyList()
        private set
    var trash: List<Photo> = emptyList()
        private set
    /** Per scope: folders, camera folder first, then by newest photo. */
    private var folders: Map<Scope, List<Album>> = emptyMap()
    var loaded = false
        private set
    /** The last query worked: [photos] is the truth, not an empty list after an error. */
    var queryOk = false
        private set
    private var byId: Map<Long, Photo> = emptyMap()

    private val observer = object : ContentObserver(main) {
        override fun onChange(selfChange: Boolean) = scheduleReload()
    }

    fun start() {
        if (started) return
        started = true
        resolver.registerContentObserver(COLLECTION, true, observer)
        reload()
    }

    fun addListener(l: () -> Unit) = listeners.add(l)

    fun removeListener(l: () -> Unit) = listeners.remove(l)

    /** Runs [then] once the index has loaded (now, if it has). */
    fun whenLoaded(then: () -> Unit) {
        if (loaded) then() else waiting.add(then)
    }

    fun removeWaiter(then: () -> Unit) = waiting.remove(then)

    fun find(id: Long) = byId[id]

    fun folders(scope: Scope): List<Album> = folders[scope] ?: emptyList()

    fun count(scope: Scope) = folders(scope).sumOf { it.count }

    fun trash(scope: Scope) = trash.filter { scopeOf(it) == scope }

    fun album(key: String, scope: Scope, ratings: RatingStore): List<Photo> = when (key) {
        ALL -> photos.filter { scopeOf(it) == scope }
        TRASH -> trash(scope)
        RATED -> photos.filter { scopeOf(it) == scope && ratings.get(it.id) > 0 }
        else -> photos.filter { it.folder == key && scopeOf(it) == scope }
    }

    /** Coalesces the burst of notifications a shot (JPEG + DNG + rescan) produces. */
    fun scheduleReload() {
        if (!started || reloadQueued) return
        reloadQueued = true
        main.postDelayed({
            reloadQueued = false
            reload()
        }, RELOAD_DELAY_MS)
    }

    /** Queries again; [then] runs on the main thread once the new index is in. */
    fun reload(then: (() -> Unit)? = null) = worker.execute {
        val t0 = SystemClock.elapsedRealtime()
        val r = try {
            query()
        } catch (e: Exception) {
            Log.e(TAG, "query failed", e)
            null
        }
        Log.d(TAG, "index: ${r?.photos?.size} photos, ${r?.trash?.size} trashed, ${SystemClock.elapsedRealtime() - t0} ms")
        main.post {
            queryOk = r != null
            if (r != null) {
                photos = r.photos
                trash = r.trash
                folders = r.folders
                byId = r.byId
            }
            loaded = true
            for (l in waiting) l()
            waiting.clear()
            for (l in listeners.toList()) l()
            then?.invoke()
        }
    }

    private class Result(val photos: List<Photo>, val trash: List<Photo>, val folders: Map<Scope, List<Album>>, val byId: Map<Long, Photo>)

    private fun query(): Result {
        val live = ArrayList<Photo>()
        val trashed = ArrayList<Photo>()
        val bases = HashMap<String, Uri>()
        val args = Bundle().apply { putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE) }
        resolver.query(COLLECTION, PROJECTION, args, null)?.use { c ->
            val iId = c.getColumnIndexOrThrow(Media._ID)
            val iVolume = c.getColumnIndexOrThrow(Media.VOLUME_NAME)
            val iName = c.getColumnIndexOrThrow(Media.DISPLAY_NAME)
            val iPath = c.getColumnIndexOrThrow(Media.RELATIVE_PATH)
            val iMime = c.getColumnIndexOrThrow(Media.MIME_TYPE)
            val iTaken = c.getColumnIndexOrThrow(Media.DATE_TAKEN)
            val iModified = c.getColumnIndexOrThrow(Media.DATE_MODIFIED)
            val iWidth = c.getColumnIndexOrThrow(Media.WIDTH)
            val iHeight = c.getColumnIndexOrThrow(Media.HEIGHT)
            val iSize = c.getColumnIndexOrThrow(Media.SIZE)
            val iTrashed = c.getColumnIndexOrThrow(Media.IS_TRASHED)
            while (c.moveToNext()) {
                val volume = c.getString(iVolume) ?: continue
                val base = bases.getOrPut(volume) { Media.getContentUri(volume) }
                val id = c.getLong(iId)
                val modified = c.getLong(iModified)
                val p = Photo(
                    id = id,
                    uri = ContentUris.withAppendedId(base, id),
                    volume = volume,
                    name = c.getString(iName) ?: "",
                    folder = c.getString(iPath) ?: "",
                    mime = c.getString(iMime) ?: "",
                    dateTaken = if (c.isNull(iTaken)) modified * 1000 else c.getLong(iTaken),
                    dateModified = modified,
                    width = c.getInt(iWidth),
                    height = c.getInt(iHeight),
                    size = c.getLong(iSize),
                    trashed = c.getInt(iTrashed) != 0,
                )
                (if (p.trashed) trashed else live).add(p)
            }
        }
        val photos = sorted(fold(live))
        val trash = sorted(fold(trashed))

        // Photos are newest first, so a folder's first appearance is its newest photo
        val folders = Scope.entries.associateWith { scope ->
            val counts = LinkedHashMap<String, Int>()
            for (p in photos) if (scopeOf(p) == scope) counts[p.folder] = (counts[p.folder] ?: 0) + 1
            counts.entries
                .sortedBy { if (isCameraFolder(it.key)) 0 else 1 }
                .map { Album(it.key, titleOf(it.key), it.value) }
        }

        val byId = HashMap<Long, Photo>(photos.size + trash.size)
        for (p in photos) byId[p.id] = p
        for (p in trash) byId[p.id] = p
        return Result(photos, trash, folders, byId)
    }

    /** GC100_0042.dng goes inside GC100_0042.jpg when both sit in the same folder. */
    private fun fold(all: List<Photo>): List<Photo> {
        val jpegs = HashMap<String, Photo>()
        for (p in all) if (p.isJpeg) jpegs[pairKey(p)] = p
        val out = ArrayList<Photo>(all.size)
        for (p in all) {
            if (p.isRaw) {
                val jpeg = jpegs[pairKey(p)]
                if (jpeg != null) {
                    jpeg.raw = p
                    continue
                }
            }
            out.add(p)
        }
        return out
    }

    private fun pairKey(p: Photo) = p.volume + '|' + p.folder + '|' + p.name.substringBeforeLast('.').lowercase()

    private fun sorted(list: List<Photo>) =
        list.sortedWith(compareByDescending<Photo> { it.dateTaken }.thenByDescending { it.name })

    companion object {
        private const val TAG = "GcGallery"
        private const val RELOAD_DELAY_MS = 400L

        const val ALL = "*all"
        const val RATED = "*rated"
        const val TRASH = "*trash"
        /** Album key for "the camera's folder, whichever it is": resolves to the first album. */
        const val CAMERA = "*camera"
        private val CAMERA_FOLDER = Regex("""DCIM/GC\d{3}/""", RegexOption.IGNORE_CASE)

        /** GC Camera's folder: DCIM/GC100/ on the EK-GC100, DCIM/GC200/ on a Galaxy Camera 2. */
        fun isCameraFolder(folder: String) = CAMERA_FOLDER.matches(folder)

        private val COLLECTION: Uri = Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        private val PROJECTION = arrayOf(
            Media._ID, Media.VOLUME_NAME, Media.DISPLAY_NAME, Media.RELATIVE_PATH, Media.MIME_TYPE,
            Media.DATE_TAKEN, Media.DATE_MODIFIED, Media.WIDTH, Media.HEIGHT, Media.SIZE, Media.IS_TRASHED,
        )

        fun titleOf(folder: String) = folder.trimEnd('/').substringAfterLast('/').ifEmpty { folder }

        /** Every removable volume counts as the card; "external_primary" is the phone. */
        fun scopeOf(p: Photo) = if (p.volume == MediaStore.VOLUME_EXTERNAL_PRIMARY) Scope.PHONE else Scope.SD

        /** Whether a removable volume (the microSD) is mounted now. */
        fun sdMounted(context: Context) = context.getSystemService(android.os.storage.StorageManager::class.java)
            .storageVolumes.any {
                it.isRemovable && (it.state == android.os.Environment.MEDIA_MOUNTED || it.state == android.os.Environment.MEDIA_MOUNTED_READ_ONLY)
            }
    }
}
