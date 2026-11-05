// SPDX-License-Identifier: GPL-3.0-or-later
package com.simoneluconi.gcgallery.ui

import android.app.Activity
import android.content.res.Configuration
import android.view.View
import android.view.ViewGroup

/**
 * The activities follow the device into all four positions and handle the change themselves
 * (manifest configChanges), so the selection, the zoom and the open photo survive a turn.
 */
val Activity.portrait get() = resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT

/** Moves this view into [to] at [index] (the end by default), unless it is there already. */
fun View.moveTo(to: ViewGroup, index: Int = -1) {
    val from = parent as? ViewGroup
    if (from === to) return
    from?.removeView(this)
    to.addView(this, index)
}
