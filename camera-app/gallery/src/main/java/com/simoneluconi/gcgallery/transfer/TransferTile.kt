// SPDX-License-Identifier: GPL-3.0-or-later
package com.simoneluconi.gcgallery.transfer

import android.app.PendingIntent
import android.content.Intent
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import com.simoneluconi.gcgallery.R

/** Quick Settings: Wi-Fi transfer on and off; a long press opens the transfer screen. */
class TransferTile : TileService() {

    private val onChange: () -> Unit = { refresh() }

    override fun onStartListening() {
        Transfer.addListener(onChange)
        refresh()
    }

    override fun onStopListening() {
        Transfer.removeListener(onChange)
    }

    override fun onClick() {
        if (Transfer.state.phase != Transfer.Phase.OFF) {
            Transfer.stop(this)
            return
        }
        try {
            Transfer.start(this)
        } catch (e: IllegalStateException) {
            // ForegroundServiceStartNotAllowedException, the usual case: the app is in the
            // background. An invisible activity of ours starts it instead.
            Log.d("GcGallery", "tile: $e")
            val pi = PendingIntent.getActivity(this, 0, Intent(this, TransferStartActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            startActivityAndCollapse(pi)
        }
    }

    private fun refresh() {
        val tile = qsTile ?: return
        val s = Transfer.state
        tile.state = if (s.phase == Transfer.Phase.OFF) Tile.STATE_INACTIVE else Tile.STATE_ACTIVE
        tile.subtitle = when (s.phase) {
            Transfer.Phase.OFF -> null
            Transfer.Phase.STARTING -> getString(R.string.transfer_starting)
            Transfer.Phase.ON -> s.url?.removePrefix("https://")?.substringBefore(':')
        }
        tile.updateTile()
    }
}
