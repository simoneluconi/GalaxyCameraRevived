package com.simoneluconi.gcgallery

import android.app.Application
import android.content.Context
import com.simoneluconi.gcgallery.data.MediaIndex
import com.simoneluconi.gcgallery.data.RatingStore
import com.simoneluconi.gcgallery.thumbs.ThumbLoader

/** Process-wide state: the photo index and the thumbnail cache outlive the activities. */
class GalleryApp : Application() {

    val ratings by lazy { RatingStore(this) }
    val index by lazy { MediaIndex(this) }
    val thumbs by lazy { ThumbLoader(this, ratings) }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        thumbs.trimMemory(level)
    }

    companion object {
        fun of(context: Context) = context.applicationContext as GalleryApp
    }
}
