// SPDX-License-Identifier: GPL-3.0-or-later
package com.simoneluconi.gcgallery.transfer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.text.format.Formatter
import android.util.Log
import com.simoneluconi.gcgallery.GalleryApp
import com.simoneluconi.gcgallery.R
import com.simoneluconi.gcgallery.data.Photo
import com.simoneluconi.gcgallery.ui.TransferActivity
import java.net.Inet4Address
import java.util.concurrent.Executors

/**
 * Runs the Wi-Fi transfer server while the user wants it, and no longer: it stops on its own
 * after [IDLE_MS] without requests, when the Wi-Fi goes, or from the notification. While it
 * runs it holds a partial wake lock and a Wi-Fi lock, so a copy goes on with the screen off;
 * when it stops the camera suspends as before.
 */
class TransferService : Service() {

    private val main = Handler(Looper.getMainLooper())
    /** Certificate and socket set-up, then the server's stop; one per run, since stop() shuts it down. */
    private var starter = newStarter()
    private var running: Running? = null
    private var wake: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var network: Network? = null
    private var callbackRegistered = false
    private var stopped = false
    private var run = 0
    @Volatile private var lastRequest = 0L
    private var files = 0
    private var bytes = 0L
    private var url: String? = null
    private var ipUrl: String? = null

    private val wifiCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onLost(n: Network) {
            main.post { if (n == network) stop(R.string.transfer_stopped_wifi) }
        }
    }

    private val idleCheck = object : Runnable {
        override fun run() {
            val r = running ?: return
            if (r.server.streaming.get() == 0 && SystemClock.elapsedRealtime() - lastRequest > IDLE_MS) {
                stop(R.string.transfer_stopped_idle)
                return
            }
            main.postDelayed(this, CHECK_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            if (Transfer.state.phase == Transfer.Phase.OFF) stopSelf() else stop(0)
            return START_NOT_STICKY
        }
        if (Transfer.state.phase == Transfer.Phase.OFF) begin()
        return START_NOT_STICKY
    }

    private fun newStarter() = Executors.newSingleThreadExecutor { r -> Thread(r, "transfer-start") }

    private fun begin() {
        stopped = false
        files = 0
        bytes = 0
        val thisRun = ++run
        if (starter.isShutdown) starter = newStarter()
        startForeground(NOTIFICATION, notification(getString(R.string.transfer_starting)), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        Transfer.update(Transfer.State(Transfer.Phase.STARTING))
        val (net, address) = wifi() ?: return stop(R.string.transfer_no_wifi)
        network = net
        val cm = getSystemService(ConnectivityManager::class.java)
        cm.registerNetworkCallback(NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), wifiCallback)
        callbackRegistered = true
        wake = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "GcGallery:transfer").apply {
            setReferenceCounted(false)
            // A safety net only: the idle stop ends a normal run long before
            acquire(MAX_WAKE_MS)
        }
        val wm = getSystemService(WifiManager::class.java)
        @Suppress("DEPRECATION")
        wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "GcGallery:transfer").apply {
            setReferenceCounted(false)
            acquire()
        }
        // The Wi-Fi driver drops multicast with the screen off unless asked: mDNS needs it
        multicastLock = wm.createMulticastLock("GcGallery:mdns").apply {
            setReferenceCounted(false)
            acquire()
        }
        val app = GalleryApp.of(this)
        app.index.start()
        val events = object : WebServer.Events {
            override fun onRequest() {
                lastRequest = SystemClock.elapsedRealtime()
            }

            override fun onSent(name: String, bytes: Long) {
                main.post {
                    files++
                    this@TransferService.bytes += bytes
                    publish()
                }
            }

            override fun trash(photos: List<Photo>): Boolean = MediaTrash.trash(this@TransferService, photos)
        }
        val name = hostName()
        starter.execute {
            val r = try {
                // The first time: an EC key and a certificate (~3.5 s on this CPU)
                val ssl = TlsCert.context(this, "$name.local", address)
                val server = WebServer(this, ssl, TransferAuth(this), { app.index.photos }, app.thumbs, events).also { it.start(address, PORT) }
                val mdns = runCatching { MdnsResponder(name, address).also { it.start() } }
                    .onFailure { Log.w(TAG, "mdns did not start: $it") }.getOrNull()
                Running(server, mdns)
            } catch (e: Exception) {
                Log.e(TAG, "transfer server did not start", e)
                null
            }
            main.post {
                when {
                    // Stopped (and maybe started again) while this one was being set up
                    thisRun != run || stopped -> r?.let { Thread({ it.stop() }, "transfer-stop").start() }
                    r == null -> stop(R.string.transfer_failed)
                    else -> {
                        running = r
                        url = "https://$name.local:$PORT"
                        ipUrl = "https://${address.hostAddress}:$PORT"
                        lastRequest = SystemClock.elapsedRealtime()
                        Log.i(TAG, "transfer on at $url ($ipUrl)")
                        publish()
                        main.postDelayed(idleCheck, CHECK_MS)
                    }
                }
            }
        }
    }

    private class Running(val server: WebServer, val mdns: MdnsResponder?) {
        fun stop() {
            mdns?.stop()
            server.stop()
        }
    }

    /** "gc100" for the EK-GC100: short to type, and a GC200 on the same network gets its own. */
    private fun hostName(): String =
        Build.MODEL.lowercase().removePrefix("ek-").replace(Regex("[^a-z0-9-]"), "").ifEmpty { "galaxycamera" }

    /** The Wi-Fi network and its IPv4 address, or null when the camera is not on Wi-Fi. */
    private fun wifi(): Pair<Network, Inet4Address>? {
        val cm = getSystemService(ConnectivityManager::class.java)
        @Suppress("DEPRECATION")
        for (n in cm.allNetworks) {
            if (cm.getNetworkCapabilities(n)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) != true) continue
            val a = cm.getLinkProperties(n)?.linkAddresses?.map { it.address }?.filterIsInstance<Inet4Address>()?.firstOrNull() ?: continue
            return n to a
        }
        return null
    }

    private fun publish() {
        if (running == null) return
        Transfer.update(Transfer.State(Transfer.Phase.ON, url, ipUrl, files, bytes))
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION, notification(text()))
    }

    private fun text(): String {
        val s = Transfer.state
        val base = getString(R.string.transfer_notification, s.url, s.ipUrl)
        return if (s.files == 0) base else base + "\n" + getString(R.string.transfer_sent, s.files, Formatter.formatShortFileSize(this, s.bytes))
    }

    private fun stop(reason: Int) {
        if (stopped) return
        stopped = true
        main.removeCallbacks(idleCheck)
        running?.let { r -> starter.execute { r.stop() } }
        running = null
        starter.shutdown()
        if (callbackRegistered) {
            runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(wifiCallback) }
            callbackRegistered = false
        }
        wake?.release()
        wake = null
        wifiLock?.release()
        wifiLock = null
        multicastLock?.release()
        multicastLock = null
        Log.i(TAG, "transfer off ($files files, $bytes bytes)")
        Transfer.update(Transfer.State(Transfer.Phase.OFF, files = files, bytes = bytes, reason = reason))
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        // Killed without stop() (rare: the system took the service down)
        stop(0)
        super.onDestroy()
    }

    private fun notification(text: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.transfer_title), NotificationManager.IMPORTANCE_LOW))
        }
        val open = PendingIntent.getActivity(this, 0, Intent(this, TransferActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, TransferService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_wifi_transfer)
            .setContentTitle(getString(R.string.transfer_title))
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(Notification.Action.Builder(null, getString(R.string.transfer_stop), stop).build())
            .build()
    }

    companion object {
        const val ACTION_STOP = "com.simoneluconi.gcgallery.transfer.STOP"
        private const val TAG = "GcGallery"
        private const val CHANNEL = "transfer"
        private const val NOTIFICATION = 1
        const val PORT = 8443
        const val IDLE_MS = 10 * 60 * 1000L
        private const val CHECK_MS = 30_000L
        private const val MAX_WAKE_MS = 4 * 3600 * 1000L
    }
}
