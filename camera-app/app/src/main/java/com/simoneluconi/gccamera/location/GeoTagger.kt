package com.simoneluconi.gccamera.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/**
 * GPS for geotagging, on only while the viewfinder is open. There are no Google services, so
 * no network location: a cold fix on the BCM4752 takes minutes, and a shot never waits for it.
 *
 * [State.FIX] means exactly "the next shot gets coordinates": it holds only while the last
 * fix is younger than the max age, and falls back to SEARCHING when it gets too old.
 */
class GeoTagger(private val context: Context, private val onState: (State) -> Unit) : LocationListener {

    enum class State { OFF, SEARCHING, FIX }

    private val lm = context.getSystemService(LocationManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var last: Location? = null
    private var running = false
    private var maxAgeMs = 0L
    var state = State.OFF
        private set

    /** A fix just went too old for a shot; the next one will bring it back. */
    private val expire = Runnable { if (running) setState(fixState()) }

    fun start(maxAgeMs: Long) {
        this.maxAgeMs = maxAgeMs
        if (running) {
            // The max age may have changed in the settings
            refreshFix()
            return
        }
        if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED ||
            !lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
        ) {
            setState(State.OFF)
            return
        }
        try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, UPDATE_MS, 0f, this, Looper.getMainLooper())
            running = true
            last = last ?: lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            refreshFix()
        } catch (_: SecurityException) {
            setState(State.OFF)
        }
    }

    fun stop() {
        if (running) lm.removeUpdates(this)
        running = false
        main.removeCallbacks(expire)
        setState(State.OFF)
    }

    /** The last fix if it is recent enough to describe where the shot was taken. */
    fun location(): Location? = last?.takeIf { ageMs(it) in 0..maxAgeMs }

    override fun onLocationChanged(location: Location) {
        last = location
        refreshFix()
    }

    override fun onProviderDisabled(provider: String) {
        main.removeCallbacks(expire)
        setState(State.OFF)
    }

    override fun onProviderEnabled(provider: String) = refreshFix()

    private fun ageMs(l: Location) = (SystemClock.elapsedRealtimeNanos() - l.elapsedRealtimeNanos) / 1_000_000

    private fun fixState() = if (location() != null) State.FIX else State.SEARCHING

    /** Sets FIX or SEARCHING from the last fix's age, and times the fix's expiry. */
    private fun refreshFix() {
        main.removeCallbacks(expire)
        setState(fixState())
        last?.let { l ->
            val left = maxAgeMs - ageMs(l)
            if (left >= 0) main.postDelayed(expire, left + 1)
        }
    }

    private fun setState(s: State) {
        if (s == state) return
        state = s
        onState(s)
    }

    companion object {
        private const val UPDATE_MS = 2000L
    }
}
