// SPDX-License-Identifier: GPL-3.0-or-later
package com.simoneluconi.gcgallery

import android.app.Application
import android.content.Context
import com.simoneluconi.gcgallery.data.MediaIndex
import com.simoneluconi.gcgallery.data.RatingStore
import com.simoneluconi.gcgallery.thumbs.ThumbLoader
import com.simoneluconi.gcgallery.transfer.GalleryPhotoSource
import com.simoneluconi.gctransfer.PhotoSource
import com.simoneluconi.gctransfer.TransferHost

/**
 * Process-wide state: the photo index and the thumbnail cache outlive the activities. Also the
 * host of the Wi-Fi transfer (the :transfer module), which serves these photos.
 */
class GalleryApp : Application(), TransferHost {

    val ratings by lazy { RatingStore(this) }
    val index by lazy { MediaIndex(this) }
    val thumbs by lazy { ThumbLoader(this, ratings) }

    override val photoSource: PhotoSource by lazy { GalleryPhotoSource(this) }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        thumbs.trimMemory(level)
    }

    companion object {
        fun of(context: Context) = context.applicationContext as GalleryApp
    }
}
