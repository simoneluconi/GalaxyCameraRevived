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
    private class Kept(val since: Long) {
        val dates = HashSet<Long>(4)
    }

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
    /** Wanted by the grid while already in flight (a warm-up): kept in memory when done. */
    private val waiting = HashSet<Long>()
    /**
     * id → DATE_MODIFIED of files that did not decode, so they are not retried on every scroll.
     * Only real failures: an OutOfMemoryError (or another Error) is retried on the next ask.
     */
    private val failed = HashMap<Long, Long>()
    /** id → the date whose thumbnail stays valid under a new date (see [keepThrough]). */
    private val kept = HashMap<Long, Kept>()
    private val main = Handler(Looper.getMainLooper())
    private val listeners = ArrayList<(Long) -> Unit>()
    private val webDecodes = java.util.concurrent.Semaphore(2)

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
        if (t.modified == p.dateModified) return t.bitmap
        // A rating changed the date but not the pixels: same bitmap, new key
        if (!keptFor(p, t.modified)) return null
        memory.put(p.id, Thumb(t.bitmap, p.dateModified))
        // The disk copy follows, or the next trim would take it for a stale one
        val file = File(dir, fileName(p))
        if (!file.exists()) rekeyDisk(p, file)
        // Re-keyed by a worker: the grid may have drawn an empty cell meanwhile. On the main
        // thread this is the grid's own draw, which shows the bitmap already.
        if (Looper.myLooper() != Looper.getMainLooper()) main.post { for (l in listeners) l(p.id) }
        return t.bitmap
    }

    /**
     * Before an edit that leaves the pixels alone (a rating): the new DATE_MODIFIED that
     * MediaStore gives the file keeps the cached thumbnail instead of decoding it again.
     * Accepted for [KEEP_MS]; a file changed by something else later is decoded as usual.
     */
    fun keepThrough(p: Photo) = synchronized(lock) {
        val k = kept[p.id]?.takeIf { fresh(it) } ?: Kept(SystemClock.elapsedRealtime()).also { kept[p.id] = it }
        k.dates.add(p.dateModified)
    }

    private fun fresh(k: Kept) = SystemClock.elapsedRealtime() - k.since <= KEEP_MS

    /** True when [old] is a date [keepThrough] vouched for; [p]'s date is then vouched for too. */
    private fun keptFor(p: Photo, old: Long): Boolean = synchronized(lock) {
        val k = kept[p.id] ?: return false
        if (!fresh(k)) {
            kept.remove(p.id)
            return false
        }
        if (old !in k.dates) return false
        // MediaStore may update the date more than once (our write, then the scan)
        k.dates.add(p.dateModified)
        true
    }

    /** A disk thumbnail under one of [p]'s vouched-for dates, renamed to its current one. */
    private fun rekeyDisk(p: Photo, file: File) {
        val dates = synchronized(lock) { kept[p.id]?.takeIf { fresh(it) }?.dates?.toList() } ?: return
        for (d in dates) {
            if (d == p.dateModified) continue
            val old = File(dir, fileName(p.id, d, p.volume))
            if (old.exists() && keptFor(p, d) && old.renameTo(file)) return
        }
    }

    /** Replaces the queue: [wanted] in priority order (visible cells first, then the prefetch). */
    fun setWanted(wanted: List<Photo>) = synchronized(lock) {
        queue.clear()
        waiting.clear()
        for (p in wanted) {
            if (get(p) != null || failed[p.id] == p.dateModified) continue
            // The warm-up is making it: it hands the result over instead of dropping it
            if (p.id in inFlight) waiting.add(p.id) else queue.addLast(p)
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

    /**
     * The disk thumbnail of [p], made now if missing, for the Wi-Fi transfer page. Blocking, off
     * the main thread; leaves the memory cache alone (the grid's cells are not the browser's).
     */
    fun file(p: Photo): File? {
        val file = File(dir, fileName(p))
        if (file.exists()) return file
        rekeyDisk(p, file)
        if (file.exists()) return file
        // A file that would not decode is not tried again on every page load
        if (synchronized(lock) { failed[p.id] == p.dateModified }) return null
        // Several browser connections ask at once: two decodes at a time, as the grid does
        webDecodes.acquire()
        var error = false
        try {
            if (file.exists()) return file
            val bmp = Decode.thumbnail(resolver, p) { ratings.learn(p.id, it) }
            if (bmp != null) {
                writeDisk(file, bmp)
                bmp.recycle()
            }
        } catch (e: Throwable) {
            Log.w(TAG, "web thumb failed: ${p.name}: $e")
            error = e is Error
        } finally {
            webDecodes.release()
        }
        return file.takeIf { it.exists() } ?: run {
            if (!error) synchronized(lock) { failed[p.id] = p.dateModified }
            null
        }
    }

    /** After an edit that changes pixels (a turn): drop the cached copies of [p]. */
    fun invalidate(p: Photo) {
        memory.remove(p.id)
        synchronized(lock) {
            failed.remove(p.id)
            kept.remove(p.id)
        }
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
                var bmp: Bitmap? = null
                try {
                    if (!File(dir, fileName(w)).exists()) {
                        bmp = load(w)
                        ok = bmp != null
                    }
                } catch (e: Throwable) {
                    Log.w(TAG, "warm-up failed: ${w.name}: $e")
                    // An Error (OOM) says nothing about the file: try again later
                    ok = e is Error
                } finally {
                    val wanted = synchronized(lock) {
                        idleRunning--
                        inFlight.remove(w.id)
                        if (!ok) failed[w.id] = w.dateModified
                        val wanted = waiting.remove(w.id)
                        // Wanted but cached on disk already (nothing decoded here): the grid asks again
                        if (wanted && bmp == null && ok) queue.addFirst(w)
                        lock.notifyAll()
                        wanted
                    }
                    bmp?.let {
                        if (wanted) {
                            memory.put(w.id, Thumb(it, w.dateModified))
                            main.post { for (l in listeners) l(w.id) }
                        } else it.recycle()
                    }
                }
            }
            if (p == null) continue
            var error = false
            val bmp = if (get(p) != null) null else try {
                load(p)
            } catch (e: Throwable) {
                // OOM included: one bad file must not stop the grid
                Log.w(TAG, "thumb failed: ${p.name}: $e")
                // An Error says nothing about the file: the next ask tries again
                error = e is Error
                null
            }
            synchronized(lock) {
                inFlight.remove(p.id)
                if (bmp == null && !error && get(p) == null) failed[p.id] = p.dateModified
            }
            if (bmp != null) {
                memory.put(p.id, Thumb(bmp, p.dateModified))
                main.post { for (l in listeners) l(p.id) }
            }
        }
    }

    private fun load(p: Photo): Bitmap? {
        val file = File(dir, fileName(p))
        if (!file.exists()) rekeyDisk(p, file)
        if (file.exists()) {
            val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 }
            BitmapFactory.decodeFile(file.path, opts)?.let { return it }
        }
        val t0 = SystemClock.elapsedRealtime()
        val bmp = Decode.thumbnail(resolver, p) { ratings.learn(p.id, it) } ?: return null
        writeDisk(file, bmp)
        Log.d(TAG, "thumb ${p.name}: ${SystemClock.elapsedRealtime() - t0} ms")
        return bmp
    }

    /**
     * Written aside and renamed: a reader, or a kill mid-write, never sees half a file. The
     * temporary name is per thread, since a grid worker and the transfer page may write the same one.
     */
    private fun writeDisk(file: File, bmp: Bitmap) {
        val tmp = File(dir, "${file.name}.${Thread.currentThread().id}.tmp")
        try {
            FileOutputStream(tmp).use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
            if (!tmp.renameTo(file)) tmp.delete()
        } catch (e: Exception) {
            Log.w(TAG, "thumb cache write failed: $e")
            tmp.delete()
        }
    }

    private fun fileName(p: Photo) = fileName(p.id, p.dateModified, p.volume)

    private fun fileName(id: Long, modified: Long, volume: String) = "${id}_${modified}_$volume.jpg"

    companion object {
        private const val TAG = "GcGallery"
        private const val WORKERS = 2
        /** How long a rating's new dates keep the thumbnail: the write and its scan, with margin. */
        private const val KEEP_MS = 60_000L

        /** An eighth of the heap: about 60 cells against the 20-30 visible. */
        private fun budget() = (Runtime.getRuntime().maxMemory() / 8).toInt()
    }
}
