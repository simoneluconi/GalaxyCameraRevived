// SPDX-License-Identifier: GPL-3.0-or-later
package com.simoneluconi.gcgallery.ui

import android.view.View
import android.widget.TextView

/** A short message at the bottom with one optional action (Undo, Settings…), gone after a few seconds. */
class MessageBar(val root: View, private val text: TextView, private val action: TextView) {

    private val hide = Runnable { dismiss() }

    /** A message is up (or fading in), not on its way out. */
    var showing = false
        private set

    /**
     * Where the message rests, as a translation above its place: the activities lift it over
     * their bottom bars and panels, also while it shows (the viewer moves it with its panel).
     * Only the alpha is animated, so nothing animates the position back to 0.
     */
    var baseY = 0f
        set(v) {
            field = v
            root.translationY = v
        }

    /** [durationMs]: 0 = 4 s for a message, 6 s when there is an action (time to reach Undo). */
    fun show(message: CharSequence, actionLabel: CharSequence? = null, durationMs: Long = 0, onAction: (() -> Unit)? = null) {
        val duration = if (durationMs > 0) durationMs else if (onAction != null) 6000L else 4000L
        text.text = message
        if (actionLabel != null && onAction != null) {
            action.text = actionLabel
            action.visibility = View.VISIBLE
            action.setOnClickListener {
                dismiss()
                onAction()
            }
        } else {
            action.visibility = View.GONE
        }
        root.removeCallbacks(hide)
        showing = true
        if (root.visibility != View.VISIBLE) {
            root.alpha = 0f
            root.visibility = View.VISIBLE
        }
        root.translationY = baseY
        root.animate().alpha(1f).setDuration(180).start()
        root.postDelayed(hide, duration)
    }

    fun dismiss() {
        root.removeCallbacks(hide)
        showing = false
        if (root.visibility != View.VISIBLE) return
        root.animate().alpha(0f).setDuration(150).withEndAction { root.visibility = View.GONE }.start()
    }
}
