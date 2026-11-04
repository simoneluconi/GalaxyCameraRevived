package com.simoneluconi.gccamera.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import android.os.SystemClock

/**
 * GPS for geotagging, on only while the viewfinder is open. There are no Google services, so
 * no network location: a cold fix on the BCM4752 takes minutes, and a shot never waits for it.
 */
class GeoTagger(private val context: Context, private val onState: (State) -> Unit) : LocationListener {

    enum class State { OFF, SEARCHING, FIX }

    private val lm = context.getSystemService(LocationManager::class.java)
    private var last: Location? = null
    private var running = false
    var state = State.OFF
        private set

    fun start() {
        if (running) return
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
            setState(State.SEARCHING)
        } catch (_: SecurityException) {
            setState(State.OFF)
        }
    }

    fun stop() {
        if (running) lm.removeUpdates(this)
        running = false
        setState(State.OFF)
    }

    /** The last fix if it is recent enough to describe where the shot was taken. */
    fun locationFor(maxAgeMs: Long): Location? {
        val l = last ?: return null
        val ageMs = (SystemClock.elapsedRealtimeNanos() - l.elapsedRealtimeNanos) / 1_000_000
        return l.takeIf { ageMs in 0..maxAgeMs }
    }

    override fun onLocationChanged(location: Location) {
        last = location
        setState(State.FIX)
    }

    override fun onProviderDisabled(provider: String) = setState(State.OFF)

    override fun onProviderEnabled(provider: String) = setState(State.SEARCHING)

    private fun setState(s: State) {
        if (s == state) return
        state = s
        onState(s)
    }

    companion object {
        private const val UPDATE_MS = 2000L
    }
}
