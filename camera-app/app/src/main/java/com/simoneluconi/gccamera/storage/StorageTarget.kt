package com.simoneluconi.gccamera.storage

import android.content.Context
import android.os.Environment
import android.os.StatFs
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import android.provider.MediaStore
import java.io.File

/**
 * Where photos go. The microSD card is the default and the only automatic choice: the
 * internal storage is small and shared with the system, so it is used only when the user
 * picked it in the settings.
 */
class StorageTarget(context: Context) {

    data class Target(val volumeName: String, val removable: Boolean, val directory: File?) {
        fun freeBytes(): Long? = directory?.let { runCatching { StatFs(it.path).availableBytes }.getOrNull() }
    }

    private val sm = context.getSystemService(StorageManager::class.java)
    private var callback: StorageManager.StorageVolumeCallback? = null

    fun current(useInternal: Boolean): Target? {
        if (useInternal) {
            return Target(MediaStore.VOLUME_EXTERNAL_PRIMARY, false, sm.primaryStorageVolume.directory)
        }
        val sd = sm.storageVolumes.firstOrNull { it.isRemovable && it.state == Environment.MEDIA_MOUNTED }
            ?: return null
        val name = sd.mediaStoreVolumeName ?: return null
        return Target(name, true, sd.directory)
    }

    /** Calls [onChange] on the main thread when a card is inserted, removed or changes state. */
    fun watch(context: Context, onChange: () -> Unit) {
        val cb = object : StorageManager.StorageVolumeCallback() {
            override fun onStateChanged(volume: StorageVolume) = onChange()
        }
        sm.registerStorageVolumeCallback(context.mainExecutor, cb)
        callback = cb
    }

    fun unwatch() {
        callback?.let(sm::unregisterStorageVolumeCallback)
        callback = null
    }
}
