package com.simoneluconi.gcgallery.ui

import android.view.View
import android.widget.TextView

/** A short message at the bottom with one optional action (Undo, Settings…), gone after a few seconds. */
class MessageBar(private val root: View, private val text: TextView, private val action: TextView) {

    private val hide = Runnable { dismiss() }

    fun show(message: CharSequence, actionLabel: CharSequence? = null, durationMs: Long = 4000, onAction: (() -> Unit)? = null) {
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
        root.postDelayed(hide, durationMs)
    }

    fun dismiss() {
        root.removeCallbacks(hide)
        if (root.visibility != View.VISIBLE) return
        root.animate().alpha(0f).setDuration(150).withEndAction { root.visibility = View.GONE }.start()
    }
}
