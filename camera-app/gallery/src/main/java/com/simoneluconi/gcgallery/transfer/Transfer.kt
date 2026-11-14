// SPDX-License-Identifier: GPL-3.0-or-later
package com.simoneluconi.gcgallery.transfer

import android.content.Context
import android.content.Intent

/**
 * The Wi-Fi transfer as the screen, the notification and the Quick Settings tile see it.
 * Main thread only.
 */
object Transfer {

    enum class Phase { OFF, STARTING, ON }

    class State(
        val phase: Phase,
        /** By name, "https://gc100.local:8443". */
        val url: String? = null,
        /** By address, for when the name does not resolve. */
        val ipUrl: String? = null,
        val files: Int = 0,
        val bytes: Long = 0,
        /** The camera's own network (local-only hotspot) to join, when it is not on a Wi-Fi. */
        val ssid: String? = null,
        val password: String? = null,
        /** Why it is off: a string resource, or 0. */
        val reason: Int = 0,
    )

    var state = State(Phase.OFF)
        private set
    private val listeners = ArrayList<() -> Unit>()

    fun addListener(l: () -> Unit) = listeners.add(l)

    fun removeListener(l: () -> Unit) = listeners.remove(l)

    internal fun update(s: State) {
        state = s
        for (l in listeners.toList()) l()
    }

    fun start(context: Context) {
        context.startForegroundService(Intent(context, TransferService::class.java))
    }

    fun stop(context: Context) {
        if (state.phase == Phase.OFF) return
        context.startService(Intent(context, TransferService::class.java).setAction(TransferService.ACTION_STOP))
    }
}
