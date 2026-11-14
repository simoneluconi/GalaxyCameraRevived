// SPDX-License-Identifier: GPL-3.0-or-later
package com.simoneluconi.gcgallery.transfer

import com.simoneluconi.gctransfer.TransferTileService

/**
 * The Wi-Fi transfer's Quick Settings tile under GC Gallery's own name: the ROM adds
 * com.simoneluconi.gcgallery/.transfer.TransferTile to the default tiles (gd1-setup.sh).
 */
class TransferTile : TransferTileService()
