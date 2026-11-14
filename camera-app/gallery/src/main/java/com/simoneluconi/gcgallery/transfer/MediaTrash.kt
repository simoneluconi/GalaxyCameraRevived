// SPDX-License-Identifier: GPL-3.0-or-later
package com.simoneluconi.gcgallery.transfer

import android.app.ActivityOptions
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import com.simoneluconi.gcgallery.data.Photo

/**
 * MediaStore's trash for the transfer page, with no activity of ours on screen. GC Gallery can
 * restore what lands there, as after a trash in the app. Off the main thread.
 */
object MediaTrash {

    /** Each photo with its DNG. False when MediaStore did not let us. */
    fun trash(context: Context, photos: List<Photo>): Boolean {
        val uris = photos.flatMap { it.files() }.map { it.uri }
        if (uris.isEmpty()) return true
        // The system gallery role writes other apps' photos directly
        try {
            val v = ContentValues().apply { put(MediaStore.MediaColumns.IS_TRASHED, 1) }
            for (u in uris) context.contentResolver.update(u, v, null)
            Log.i(TAG, "trash: ${uris.size} files directly")
            return allTrashed(context, uris)
        } catch (e: SecurityException) {
            Log.i(TAG, "trash: no direct write ($e), trying the MANAGE_MEDIA request")
        }
        // MANAGE_MEDIA approves the request without a dialog, but it is still an activity start
        return try {
            val pi = MediaStore.createTrashRequest(context.contentResolver, uris, true)
            val opts = ActivityOptions.makeBasic()
                .setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                .toBundle()
            pi.send(context, 0, null, null, null, null, opts)
            // No result comes back to a service: look at what MediaStore says
            for (i in 0 until 10) {
                Thread.sleep(300)
                if (allTrashed(context, uris)) return true.also { Log.i(TAG, "trash: ${uris.size} files via the request") }
            }
            Log.w(TAG, "trash: the request did not complete")
            false
        } catch (e: Exception) {
            Log.w(TAG, "trash failed", e)
            false
        }
    }

    private fun allTrashed(context: Context, uris: List<Uri>): Boolean {
        val args = Bundle().apply { putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE) }
        return uris.all { u ->
            context.contentResolver.query(u, arrayOf(MediaStore.MediaColumns.IS_TRASHED), args, null)?.use { c ->
                c.moveToFirst() && c.getInt(0) == 1
            } == true
        }
    }

    private const val TAG = "GcGallery"
}
