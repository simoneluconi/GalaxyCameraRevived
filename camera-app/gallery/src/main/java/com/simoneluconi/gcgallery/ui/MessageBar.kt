package com.simoneluconi.gcgallery.ui

import android.view.View
import android.widget.TextView

/** A short message at the bottom with one optional action (Undo, Settings…), gone after a few seconds. */
class MessageBar(val root: View, private val text: TextView, private val action: TextView) {

    private val hide = Runnable { dismiss() }

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
        if (root.visibility != View.VISIBLE) {
            root.alpha = 0f
            root.translationY = root.resources.displayMetrics.density * 24
            root.visibility = View.VISIBLE
        }
        root.animate().alpha(1f).translationY(0f).setDuration(180).start()
        root.postDelayed(hide, duration)
    }

    fun dismiss() {
        root.removeCallbacks(hide)
        if (root.visibility != View.VISIBLE) return
        root.animate().alpha(0f).setDuration(150).withEndAction { root.visibility = View.GONE }.start()
    }
}
