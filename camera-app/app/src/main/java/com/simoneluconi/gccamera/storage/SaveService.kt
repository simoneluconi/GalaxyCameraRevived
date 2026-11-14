package com.simoneluconi.gccamera.storage

import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import com.simoneluconi.gccamera.R
import java.util.concurrent.atomic.AtomicInteger

/**
 * Keeps the process alive while [PhotoWriter] has saves in flight. A RAW shot's DNG takes
 * 6-11 s after the JPEG; an activity that leaves for the gallery or Home turns the process
 * cached (adj 700-900), and this device's lowmemorykiller then SIGKILLs it mid-write, leaving
 * a truncated .pending DNG (field report 2026-11-05). A foreground service holds adj 200.
 *
 * The service does no work itself. Android defers a foreground service's notification for
 * ~10 s, so a JPEG-only save never shows one; without POST_NOTIFICATIONS it is not shown at all.
 */
class SaveService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // startForeground() first, always: a service started with startForegroundService()
        // that stops without it crashes the app
        startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        instance = this
        if (inFlight.get() == 0) stop()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        if (instance === this) {
            instance = null
            started = false
        }
        super.onDestroy()
    }

    private fun stop() {
        instance = null
        started = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, getString(R.string.saving_channel), NotificationManager.IMPORTANCE_LOW)
            )
        }
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_saving)
            .setContentTitle(getString(R.string.saving_photos))
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "GcCamera"
        private const val CHANNEL = "saving"
        private const val NOTIFICATION_ID = 1

        private val main = Handler(Looper.getMainLooper())
        private val inFlight = AtomicInteger()

        // Touched on the main thread only
        private var instance: SaveService? = null
        /** The service was started and has not stopped: apart from [inFlight], since a start can fail. */
        private var started = false

        /**
         * Work starts that must outlive the activity: a capture (from its start, while the
         * activity is surely in front: a 16 s exposure, then Home, is too late) or a save.
         * Call on the main thread; every begin needs its [end].
         */
        fun begin(context: Context) {
            inFlight.incrementAndGet()
            // A start refused earlier (in the background) is tried again, not taken as running
            if (started) return
            try {
                context.startForegroundService(Intent(context, SaveService::class.java))
                started = true
            } catch (e: ForegroundServiceStartNotAllowedException) {
                // Already in the background: the save still runs, only unprotected
                Log.w(TAG, "cannot keep the process alive for the save", e)
            } catch (e: IllegalStateException) {
                Log.w(TAG, "cannot keep the process alive for the save", e)
            }
        }

        /** A save ended, on any thread. */
        fun end() {
            if (inFlight.decrementAndGet() > 0) return
            main.post { if (inFlight.get() == 0) instance?.stop() }
        }
    }
}
