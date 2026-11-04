package com.simoneluconi.gcgallery.thumbs

import android.content.ComponentCallbacks2
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.util.LruCache
import com.simoneluconi.gcgallery.data.Photo
import com.simoneluconi.gcgallery.data.RatingStore
import com.simoneluconi.gcgallery.image.Decode
import java.io.File
import java.io.FileOutputStream

/**
 * Grid thumbnails: a memory LRU (RGB_565), a disk cache of small JPEGs that survives restarts,
 * and two low-priority workers that decode what the grid asks for, most wanted first.
 * A thumbnail is keyed by id and DATE_MODIFIED, so an edited file gets a new one.
 */
class ThumbLoader(context: Context, private val ratings: RatingStore) {

    private class Thumb(val bitmap: Bitmap, val modified: Long)

    private val resolver = context.contentResolver
    private val dir = File(context.cacheDir, "thumbs").apply { mkdirs() }
    private val memory = object : LruCache<Long, Thumb>(budget()) {
        override fun sizeOf(key: Long, value: Thumb) = value.bitmap.byteCount
    }
    private val lock = Object()
    private val queue = ArrayDeque<Photo>()
    /** Warm-up: photos whose disk thumbnail is made when nothing is wanted, one worker at a time. */
    private val idle = ArrayDeque<Photo>()
    private var idleRunning = 0
    private val inFlight = HashSet<Long>()
    /** id → DATE_MODIFIED of files that did not decode, so they are not retried on every scroll. */
    private val failed = HashMap<Long, Long>()
    private val main = Handler(Looper.getMainLooper())
    private val listeners = ArrayList<(Long) -> Unit>()

    init {
        repeat(WORKERS) { n ->
            Thread({
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
                work()
            }, "thumb-$n").apply { isDaemon = true }.start()
        }
    }

    /** Main thread: told the id of each thumbnail that arrives. */
    fun addListener(l: (Long) -> Unit) = listeners.add(l)

    fun removeListener(l: (Long) -> Unit) = listeners.remove(l)

    fun get(p: Photo): Bitmap? {
        val t = memory.get(p.id) ?: return null
        return if (t.modified == p.dateModified) t.bitmap else null
    }

    /** Replaces the queue: [wanted] in priority order (visible cells first, then the prefetch). */
    fun setWanted(wanted: List<Photo>) = synchronized(lock) {
        queue.clear()
        for (p in wanted) {
            if (p.id in inFlight || get(p) != null || failed[p.id] == p.dateModified) continue
            queue.addLast(p)
        }
        lock.notifyAll()
    }

    /** Disk thumbnails to make ahead of scrolling, newest first; replaces the previous list. */
    fun setIdle(photos: List<Photo>) = synchronized(lock) {
        idle.clear()
        idle.addAll(photos)
        lock.notifyAll()
    }

    /** Blocking, off the main thread: memory, disk or a new decode. For the viewer's first frame. */
    fun loadNow(p: Photo): Bitmap? = get(p) ?: load(p)?.also { memory.put(p.id, Thumb(it, p.dateModified)) }

    /** After an edit that changes pixels (a turn): drop the cached copies of [p]. */
    fun invalidate(p: Photo) {
        memory.remove(p.id)
        synchronized(lock) { failed.remove(p.id) }
        File(dir, fileName(p)).delete()
    }

    /**
     * Deletes cached files of photos that are gone or changed, off the main thread. Only on
     * [mounted] volumes: a card that is out (or still mounting) keeps its thumbnails.
     */
    fun trimDisk(keep: Collection<Photo>, mounted: Set<String>) {
        val names = HashSet<String>(keep.size * 2)
        for (p in keep) names.add(fileName(p))
        var removed = 0
        dir.listFiles()?.forEach { f ->
            if (f.name in names) return@forEach
            // id_modified_volume.jpg; older names without a volume, and leftover .tmp files, just go
            val volume = f.name.split('_', limit = 3).getOrNull(2)?.removeSuffix(".jpg")
            if ((volume == null || volume in mounted || f.name.endsWith(".tmp")) && f.delete()) removed++
        }
        if (removed > 0) Log.d(TAG, "thumbs: removed $removed stale files")
    }

    fun trimMemory(level: Int) {
        if (level >= ComponentCallbacks2.TRIM_MEMORY_MODERATE) memory.evictAll()
        else if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) memory.trimToSize(memory.maxSize() / 2)
    }

    private fun work() {
        while (true) {
            var warmUp: Photo? = null
            val p = synchronized(lock) {
                while (queue.isEmpty() && (idle.isEmpty() || idleRunning > 0)) lock.wait()
                if (queue.isEmpty()) {
                    val w = idle.removeFirst()
                    // Skip what is cached, failed, or being decoded by the other worker
                    if (w.id in inFlight || failed[w.id] == w.dateModified) return@synchronized null
                    warmUp = w
                    idleRunning++
                    inFlight.add(w.id)
                    null
                } else queue.removeFirst().also { inFlight.add(it.id) }
            }
            warmUp?.let { w ->
                var ok = true
                try {
                    if (!File(dir, fileName(w)).exists()) ok = load(w) != null
                } catch (e: Throwable) {
                    Log.w(TAG, "warm-up failed: ${w.name}: $e")
                    ok = false
                } finally {
                    synchronized(lock) {
                        idleRunning--
                        inFlight.remove(w.id)
                        if (!ok) failed[w.id] = w.dateModified
                        lock.notifyAll()
                    }
                }
            }
            if (p == null) continue
            val bmp = if (get(p) != null) null else try {
                load(p)
            } catch (e: Throwable) {
                // OOM included: one bad file must not stop the grid
                Log.w(TAG, "thumb failed: ${p.name}: $e")
                null
            }
            synchronized(lock) {
                inFlight.remove(p.id)
                if (bmp == null && get(p) == null) failed[p.id] = p.dateModified
            }
            if (bmp != null) {
                memory.put(p.id, Thumb(bmp, p.dateModified))
                main.post { for (l in listeners) l(p.id) }
            }
        }
    }

    private fun load(p: Photo): Bitmap? {
        val file = File(dir, fileName(p))
        if (file.exists()) {
            val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 }
            BitmapFactory.decodeFile(file.path, opts)?.let { return it }
        }
        val t0 = SystemClock.elapsedRealtime()
        val bmp = Decode.thumbnail(resolver, p) { ratings.learn(p.id, it) } ?: return null
        // Written aside and renamed: a reader, or a kill mid-write, never sees half a file
        val tmp = File(dir, file.name + ".tmp")
        try {
            FileOutputStream(tmp).use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
            if (!tmp.renameTo(file)) tmp.delete()
        } catch (e: Exception) {
            Log.w(TAG, "thumb cache write failed: $e")
            tmp.delete()
        }
        Log.d(TAG, "thumb ${p.name}: ${SystemClock.elapsedRealtime() - t0} ms")
        return bmp
    }

    private fun fileName(p: Photo) = "${p.id}_${p.dateModified}_${p.volume}.jpg"

    companion object {
        private const val TAG = "GcGallery"
        private const val WORKERS = 2

        /** An eighth of the heap: about 60 cells against the 20-30 visible. */
        private fun budget() = (Runtime.getRuntime().maxMemory() / 8).toInt()
    }
}
