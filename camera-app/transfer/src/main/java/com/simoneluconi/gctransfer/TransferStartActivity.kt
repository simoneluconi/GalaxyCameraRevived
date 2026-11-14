// SPDX-License-Identifier: GPL-3.0-or-later
package com.simoneluconi.gctransfer

import android.app.Activity
import android.os.Bundle

/**
 * Starts the transfer for the Quick Settings tile and goes away, showing nothing. Android 14
 * does not let a tile start a foreground service from a background app; an activity of ours
 * that is starting may.
 */
class TransferStartActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Transfer.state.phase == Transfer.Phase.OFF) Transfer.start(this)
        finish()
    }
}
