package com.simoneluconi.gccamera.storage

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.location.Location
import android.net.Uri
import android.os.Bundle
import android.os.Process
import android.provider.MediaStore
import android.util.Log
import com.simoneluconi.gccamera.camera.CameraModel
import com.simoneluconi.gccamera.camera.Pasm
import com.simoneluconi.gccamera.camera.RawLayout
import com.simoneluconi.gccamera.camera.ShotInfo
import com.simoneluconi.gccamera.raw.NativeDng
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/** Everything the EXIF needs besides the JPEG itself. */
class ShotMeta(
    val info: ShotInfo,
    val pasm: Pasm,
    val takenAt: Long,
    val exifOrientation: Int,
    val location: Location?,
    val flashFired: Boolean,
    val flashMode: String,
    val whiteBalanceAuto: Boolean,
    val metering: String,
    val artist: String?,
    val copyright: String?,
    val software: String,
    val capture: CaptureTags,
)

/** Capture settings the EXIF has no tag for, in the XMP's gc: namespace ([JpegMeta.GC_NS]). */
class CaptureTags(
    val ois: Boolean,
    /** The HAL focus-mode: auto, macro, infinity. */
    val focusMode: String,
    /** center, multi, face, or touch for a tapped point. */
    val afArea: String,
    /** single, aeb or hdr. */
    val drive: String,
    val bracketEv: String?,
    val frameIndex: Int,
    val frameCount: Int,
    val timer: Int,
    /** The pop-up flash's state, null when the HAL does not report it. */
    val flashPopup: Boolean?,
    val zoomStep: Int,
    val zoomMax: Int,
)

/**
 * Saves shots through MediaStore on a low-priority thread, so the viewfinder never waits for
 * the card. The ISP JPEG has no metadata (docs/CAMERA-APP.md §3.2); [JpegMeta] adds it.
 * One per process ([get]): saves outlive the activity that took the shot.
 */
class PhotoWriter private constructor(private val context: Context) {

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            r.run()
        }, "photo-writer")
    }

    init {
        // First in the queue, so it only ever sees rows no save of this process owns
        executor.execute(::dropStalePending)
    }

    /**
     * Saves [baseName].jpg and, with [raw], [baseName].dng next to it. Call on the main thread.
     * [jpegSaved] gets the JPEG's uri (null on failure) as soon as it is written, so the
     * last-shot button need not wait for the DNG; [done] then says whether a requested DNG failed.
     */
    fun saveShot(
        target: StorageTarget.Target,
        folder: String,
        baseName: String,
        jpeg: ByteArray,
        raw: ByteArray?,
        layout: RawLayout?,
        meta: ShotMeta,
        jpegSaved: (jpeg: Uri?) -> Unit,
        done: (dngFailed: Boolean) -> Unit,
    ) {
        SaveService.begin(context)
        executor.execute {
            var jpegReported = false
            var dngFailed = raw != null
            // [done] always runs: it hands the RAW buffer back and tells the user
            try {
                val uri = writeJpeg(target, folder, "$baseName.jpg", jpeg, meta)
                jpegReported = true
                jpegSaved(uri)
                if (raw != null && layout != null) {
                    dngFailed = writeDng(target, folder, "$baseName.dng", raw, layout, jpeg, meta) < 0
                }
            } catch (e: Exception) {
                Log.e(TAG, "save failed", e)
            } finally {
                if (!jpegReported) jpegSaved(null)
                done(dngFailed)
                SaveService.end()
            }
        }
    }

    /**
     * Deletes this app's MediaStore rows still pending from an earlier process: saves cut short
     * by a kill (0 B or truncated DNGs). The RAW frame died with the process, so there is
     * nothing to recover; MediaStore itself would only drop them after about a week.
     */
    private fun dropStalePending() {
        val resolver = context.contentResolver
        val args = Bundle().apply {
            putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_ONLY)
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, "${MediaStore.MediaColumns.OWNER_PACKAGE_NAME} = ?")
            putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf(context.packageName))
        }
        for (volume in MediaStore.getExternalVolumeNames(context)) {
            try {
                val files = MediaStore.Files.getContentUri(volume)
                val stale = ArrayList<Pair<Long, String?>>()
                resolver.query(files, arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME), args, null)?.use { c ->
                    while (c.moveToNext()) stale += c.getLong(0) to c.getString(1)
                }
                for ((id, name) in stale) {
                    Log.w(TAG, "dropping unfinished save $name on $volume")
                    resolver.delete(ContentUris.withAppendedId(files, id), null, null)
                }
            } catch (e: Exception) {
                Log.w(TAG, "pending clean-up failed on $volume", e)
            }
        }
    }

    /** Returns the DNG's size, or -1 on failure. */
    private fun writeDng(
        target: StorageTarget.Target,
        folder: String,
        fileName: String,
        raw: ByteArray,
        layout: RawLayout,
        preview: ByteArray,
        m: ShotMeta,
    ): Long {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/x-adobe-dng")
            put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/$folder")
            put(MediaStore.Images.Media.DATE_TAKEN, m.takenAt)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = try {
            resolver.insert(MediaStore.Images.Media.getContentUri(target.volumeName), values)
        } catch (e: Exception) {
            Log.e(TAG, "DNG insert failed", e)
            null
        } ?: return -1
        val date = Date(m.takenAt)
        val i = m.info
        val error = try {
            resolver.openFileDescriptor(uri, "w")!!.use { pfd ->
                NativeDng.write(
                    raw = raw,
                    layout = intArrayOf(layout.width, layout.height, layout.left, layout.top, layout.right, layout.bottom),
                    levels = doubleArrayOf(layout.black, layout.white),
                    cfa = layout.cfa,
                    exposure = doubleArrayOf(
                        (i.iso ?: 0).toDouble(),
                        i.exposureSeconds ?: 0.0,
                        i.fNumber ?: 0.0,
                        i.focalMm ?: 0.0,
                        (i.focal35mm ?: 0).toDouble(),
                        i.biasEv ?: 0.0,
                        JpegMeta.exposureProgram(m.pasm).toDouble(),
                        m.exifOrientation.toDouble(),
                    ),
                    dateTime = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).format(date),
                    offsetTime = SimpleDateFormat("XXX", Locale.US).format(date),
                    software = m.software,
                    artist = m.artist,
                    copyright = m.copyright,
                    model = CameraModel.model,
                    xmp = JpegMeta.xmpProperties(m),
                    gps = m.location?.let { l ->
                        doubleArrayOf(
                            l.latitude,
                            l.longitude,
                            if (l.hasAltitude()) 1.0 else 0.0,
                            l.altitude,
                            l.time.toDouble(),
                            if (l.hasAccuracy()) l.accuracy.toDouble() else 0.0,
                        )
                    },
                    preview = preview,
                    fd = pfd.fd,
                )
            }
        } catch (e: Throwable) {
            // UnsatisfiedLinkError included: a missing native library must not kill the app
            e.toString()
        }
        if (error != null) {
            Log.e(TAG, "DNG write failed: $error")
            runCatching { resolver.delete(uri, null, null) }
            return -1
        }
        values.clear()
        values.put(MediaStore.Images.Media.IS_PENDING, 0)
        try {
            resolver.update(uri, values, null, null)
        } catch (e: Exception) {
            Log.e(TAG, "DNG publish failed", e)
            runCatching { resolver.delete(uri, null, null) }
            return -1
        }
        return layout.width.toLong() * layout.height * 2 + preview.size
    }

    private fun writeJpeg(
        target: StorageTarget.Target,
        folder: String,
        fileName: String,
        jpeg: ByteArray,
        meta: ShotMeta,
    ): Uri? {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/$folder")
            put(MediaStore.Images.Media.DATE_TAKEN, meta.takenAt)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val collection = MediaStore.Images.Media.getContentUri(target.volumeName)
        val uri = try {
            resolver.insert(collection, values)
        } catch (e: Exception) {
            Log.e(TAG, "insert failed", e)
            null
        } ?: return null
        try {
            val thumb = thumbnail(jpeg)
            resolver.openOutputStream(uri, "w")!!.use { JpegMeta.write(it, jpeg, meta, thumb) }
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            return uri
        } catch (e: Exception) {
            Log.e(TAG, "write failed", e)
            runCatching { resolver.delete(uri, null, null) }
            return null
        }
    }

    /**
     * The EXIF thumbnail, [JpegMeta.THUMB_WIDTH] wide, for the gallery's grid. The decoder's
     * DCT scaling does the 1/8 (576x432 from 16 MP) cheaply; null if the JPEG does not decode.
     */
    private fun thumbnail(jpeg: ByteArray): ByteArray? {
        val opts = BitmapFactory.Options().apply {
            inSampleSize = 8
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        val eighth = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, opts) ?: return null
        val h = (JpegMeta.THUMB_WIDTH.toLong() * eighth.height / eighth.width).toInt()
        val small = Bitmap.createScaledBitmap(eighth, JpegMeta.THUMB_WIDTH, h, true)
        if (small !== eighth) eighth.recycle()
        return ByteArrayOutputStream(16 * 1024).also { small.compress(Bitmap.CompressFormat.JPEG, THUMB_QUALITY, it) }
            .toByteArray().also { small.recycle() }
    }

    companion object {
        private const val TAG = "GcCamera"
        private const val THUMB_QUALITY = 80

        @Volatile
        private var instance: PhotoWriter? = null

        fun get(context: Context): PhotoWriter =
            instance ?: synchronized(this) {
                instance ?: PhotoWriter(context.applicationContext).also { instance = it }
            }
    }
}
