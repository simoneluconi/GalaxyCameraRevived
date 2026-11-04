package com.simoneluconi.gcgallery.ui

import android.app.Activity
import android.app.PendingIntent
import android.net.Uri
import android.provider.MediaStore
import android.util.Log

/**
 * MediaStore's consent requests for files other apps own (GC Camera's shots). With the
 * MANAGE_MEDIA app-op they complete without showing anything; without it the system asks.
 * The owning activity forwards [onActivityResult].
 */
class MediaAccess(private val activity: Activity) {

    private var pending: ((Boolean) -> Unit)? = null

    val silent get() = MediaStore.canManageMedia(activity)

    fun write(uris: List<Uri>, then: (Boolean) -> Unit) =
        launch(uris, then) { MediaStore.createWriteRequest(activity.contentResolver, it) }

    fun trash(uris: List<Uri>, trashed: Boolean, then: (Boolean) -> Unit) =
        launch(uris, then) { MediaStore.createTrashRequest(activity.contentResolver, it, trashed) }

    fun delete(uris: List<Uri>, then: (Boolean) -> Unit) =
        launch(uris, then) { MediaStore.createDeleteRequest(activity.contentResolver, it) }

    fun favorite(uris: List<Uri>, favorite: Boolean, then: (Boolean) -> Unit) =
        launch(uris, then) { MediaStore.createFavoriteRequest(activity.contentResolver, it, favorite) }

    fun onActivityResult(requestCode: Int, resultCode: Int): Boolean {
        if (requestCode != REQUEST) return false
        val then = pending
        pending = null
        then?.invoke(resultCode == Activity.RESULT_OK)
        return true
    }

    private fun launch(uris: List<Uri>, then: (Boolean) -> Unit, make: (List<Uri>) -> PendingIntent) {
        if (uris.isEmpty()) return then(true)
        if (pending != null) return then(false)
        try {
            val pi = make(uris)
            pending = then
            activity.startIntentSenderForResult(pi.intentSender, REQUEST, null, 0, 0, 0)
        } catch (e: Exception) {
            // IntentSender.SendIntentException, or a uri MediaStore does not know
            Log.e("GcGallery", "media request failed", e)
            pending = null
            then(false)
        }
    }

    private companion object {
        const val REQUEST = 0x6C00
    }
}
