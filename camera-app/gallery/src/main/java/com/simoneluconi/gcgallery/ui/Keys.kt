package com.simoneluconi.gcgallery.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.provider.MediaStore
import android.util.Log
import android.view.KeyEvent

/** The camera's own keys (docs/CAMERA-APP.md §4), shared by the grid and the viewer. */
object Keys {
    /** The zoom lever's fast stage; it arrives nested inside the slow one (533/534). */
    private const val SCAN_TELE_FAST = 545
    private const val SCAN_WIDE_FAST = 546

    fun isFast(e: KeyEvent) = e.scanCode == SCAN_TELE_FAST || e.scanCode == SCAN_WIDE_FAST

    fun isShutter(keyCode: Int) = keyCode == KeyEvent.KEYCODE_FOCUS || keyCode == KeyEvent.KEYCODE_CAMERA

    /** Half press in playback goes back to shooting, as on a real camera. */
    fun openCamera(activity: Activity) {
        try {
            activity.startActivity(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA))
        } catch (e: ActivityNotFoundException) {
            Log.w("GcGallery", "no camera app: $e")
        }
    }
}
