// SPDX-License-Identifier: GPL-3.0-or-later
package com.simoneluconi.gctransfer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.text.format.Formatter
import android.util.Log
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
    private var running: Running? = null
    /** The latest start request: stopSelf with it, so a start that came in meanwhile keeps the service. */
    private var startId = 0
    private var wake: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var network: Network? = null
    private var address: Inet4Address? = null
    private var callbackRegistered = false
    private var stopped = false
    private var run = 0
    @Volatile private var lastRequest = 0L
    private var files = 0
    private var bytes = 0L
    private var url: String? = null
    private var ipUrl: String? = null
    /** The camera's own network, when there was no Wi-Fi to join. */
    private var hotspot: WifiManager.LocalOnlyHotspotReservation? = null
    private var ssid: String? = null
    private var password: String? = null

    private val wifiCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onLost(n: Network) {
            main.post { if (n == network) stop(R.string.transfer_stopped_wifi) }
        }

        // A new address from DHCP: the socket, the mDNS answers and the certificate are all for
        // the old one. Stop; the next start makes everything for the new address.
        override fun onLinkPropertiesChanged(n: Network, lp: LinkProperties) {
            val now = lp.linkAddresses.map { it.address }.filterIsInstance<Inet4Address>()
            main.post { if (n == network && address != null && address !in now) stop(R.string.transfer_stopped_wifi) }
        }
    }

    private val idleCheck = object : Runnable {
        override fun run() {
            if (running == null) return
            // Requests and every chunk of a copy count as use; a copy whose browser stopped
            // reading (and so never ends) does not
            if (SystemClock.elapsedRealtime() - lastRequest > IDLE_MS) {
                stop(R.string.transfer_stopped_idle)
                return
            }
            // In use: the wake lock's safety timeout starts again, so a copy of many hours goes on
            wake?.acquire(MAX_WAKE_MS)
            main.postDelayed(this, CHECK_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        this.startId = startId
        if (intent?.action == ACTION_STOP) {
            if (Transfer.state.phase == Transfer.Phase.OFF) stopSelf(startId) else stop(0)
            return START_NOT_STICKY
        }
        if (Transfer.state.phase == Transfer.Phase.OFF) begin()
        return START_NOT_STICKY
    }

    private fun begin() {
        stopped = false
        files = 0
        bytes = 0
        val thisRun = ++run
        startForeground(NOTIFICATION, notification(getString(R.string.transfer_starting)), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        Transfer.update(Transfer.State(Transfer.Phase.STARTING))
        acquireLocks()
        val wifi = wifi()
        if (wifi == null) {
            // Not on a Wi-Fi: the camera makes its own (local only: the 3G is never shared)
            startHotspot(thisRun)
            return
        }
        val (net, address) = wifi
        network = net
        val cm = getSystemService(ConnectivityManager::class.java)
        cm.registerNetworkCallback(NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), wifiCallback)
        callbackRegistered = true
        serve(thisRun, address)
    }

    private fun acquireLocks() {
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
    }

    /**
     * A local-only hotspot: an access point with no internet behind it, only the camera. On the
     * BCM4334 it replaces the client connection while it runs; Wi-Fi is back as before when it
     * closes. Its address comes from a random subnet, so it is read from the interface.
     */
    private fun startHotspot(thisRun: Int) {
        val wm = getSystemService(WifiManager::class.java)
        val callback = object : WifiManager.LocalOnlyHotspotCallback() {
            override fun onStarted(reservation: WifiManager.LocalOnlyHotspotReservation) {
                if (stopped || thisRun != run) return reservation.close()
                hotspot = reservation
                val cfg = reservation.softApConfiguration
                @Suppress("DEPRECATION")
                ssid = cfg.ssid
                password = cfg.passphrase
                // The interface gets its address a moment after the callback
                STARTER.execute {
                    val a = hotspotAddress()
                    main.post {
                        when {
                            stopped || thisRun != run -> Unit
                            a == null -> stop(R.string.transfer_hotspot_failed)
                            else -> serve(thisRun, a)
                        }
                    }
                }
            }

            override fun onStopped() {
                main.post { if (hotspot != null && thisRun == run) stop(R.string.transfer_hotspot_stopped) }
            }

            override fun onFailed(reason: Int) {
                Log.w(TAG, "hotspot failed: $reason")
                main.post { if (thisRun == run) stop(R.string.transfer_hotspot_failed) }
            }
        }
        // Granted with the ROM's defaults for this app; asked for nowhere else
        if (checkSelfPermission(android.Manifest.permission.NEARBY_WIFI_DEVICES) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            return stop(R.string.transfer_hotspot_permission)
        }
        try {
            wm.startLocalOnlyHotspot(callback, main)
        } catch (e: Exception) {
            // SecurityException without NEARBY_WIFI_DEVICES, IllegalStateException if one is already up
            Log.w(TAG, "hotspot: $e")
            stop(R.string.transfer_hotspot_failed)
        }
    }

    /** The hotspot's IPv4 address, once the interface has one (up to 5 s). Off the main thread. */
    private fun hotspotAddress(): Inet4Address? {
        repeat(25) {
            val a = runCatching {
                java.net.NetworkInterface.getNetworkInterfaces().toList()
                    .filter { it.isUp && !it.isLoopback && (it.name.startsWith("wlan") || it.name.startsWith("swlan") || it.name.startsWith("ap")) }
                    .flatMap { it.inetAddresses.toList() }
                    .filterIsInstance<Inet4Address>()
                    .firstOrNull { it.isSiteLocalAddress }
            }.getOrNull()
            if (a != null) return a
            Thread.sleep(200)
        }
        return null
    }

    private fun serve(thisRun: Int, address: Inet4Address) {
        this.address = address
        val host = TransferHost.of(this)
        val source = host.photoSource
        source.start()
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
        }
        val name = host.transferName
        STARTER.execute {
            val r = try {
                // The first time: an EC key and a certificate (~3.5 s on this CPU)
                val ssl = TlsCert.context(this, "$name.local")
                val server = WebServer(this, ssl, TransferAuth(this), source, events).also { it.start(address, PORT) }
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
                    thisRun != run || stopped -> r?.let { STARTER.execute { it.stop() } }
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
        Transfer.update(Transfer.State(Transfer.Phase.ON, url, ipUrl, files, bytes, ssid = ssid, password = password))
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION, notification(text()))
    }

    private fun text(): String {
        val s = Transfer.state
        val base = getString(R.string.transfer_notification, s.url, s.ipUrl) +
            (s.ssid?.let { "\n" + getString(R.string.transfer_hotspot_line, it, s.password ?: "") } ?: "")
        return if (s.files == 0) base else base + "\n" + getString(R.string.transfer_sent, s.files, Formatter.formatShortFileSize(this, s.bytes))
    }

    private fun stop(reason: Int) {
        if (stopped) return
        stopped = true
        main.removeCallbacks(idleCheck)
        // Queued before any later start's set-up, so the port and the certificate file are free for it
        running?.let { r -> STARTER.execute { r.stop() } }
        running = null
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
        // The camera's own network goes away, and its Wi-Fi client comes back
        hotspot?.close()
        hotspot = null
        ssid = null
        password = null
        Log.i(TAG, "transfer off ($files files, $bytes bytes)")
        Transfer.update(Transfer.State(Transfer.Phase.OFF, files = files, bytes = bytes, reason = reason))
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf(startId)
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
            .setSmallIcon(TransferHost.of(this).transferIcon)
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
        /** Renewed on every check while in use: only a hang can hold the camera awake this long. */
        private const val MAX_WAKE_MS = 30 * 60 * 1000L

        /**
         * Set-ups and stops for the whole process, in order: a quick stop and start, even on a
         * new service instance, never has two runs making the certificate or binding the port.
         */
        private val STARTER = Executors.newSingleThreadExecutor { r -> Thread(r, "transfer-start") }
    }
}
