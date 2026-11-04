package com.simoneluconi.gcgallery.data

import android.content.Context

/**
 * Star ratings by MediaStore id. The file's XMP (xmp:Rating) is the truth; this copy lets the
 * grid draw stars and filter "Rated" without opening files. It learns from every thumbnail and
 * info read, and keeps a rating the file could not take (no room in the XMP).
 */
class RatingStore(context: Context) {

    private val sp = context.getSharedPreferences("ratings", Context.MODE_PRIVATE)
    private val map = HashMap<Long, Int>()

    init {
        for ((k, v) in sp.all) {
            val id = k.toLongOrNull() ?: continue
            if (v is Int && v > 0) map[id] = v
        }
    }

    /** Ids the user rated in this process: a file read that was already in flight must not undo it. */
    private val userSet = HashSet<Long>()

    @Synchronized
    fun get(id: Long) = map[id] ?: 0

    /** The user's choice. */
    @Synchronized
    fun set(id: Long, rating: Int) {
        if (id >= 0) userSet.add(id)
        store(id, rating)
    }

    /** What a file says (thumbnail or info read); ignored once the user has rated the photo. */
    @Synchronized
    fun learn(id: Long, rating: Int) {
        if (id !in userSet) store(id, rating)
    }

    private fun store(id: Long, rating: Int) {
        if (id < 0 || get(id) == rating) return
        if (rating <= 0) map.remove(id) else map[id] = rating
        sp.edit().apply {
            if (rating <= 0) remove(id.toString()) else putInt(id.toString(), rating)
        }.apply()
    }
}
