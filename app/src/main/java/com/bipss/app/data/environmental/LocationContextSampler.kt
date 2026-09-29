package com.bipss.app.data.environmental

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

private const val TAG = "LocationContextSampler"

/**
 * Listens to the GNSS receiver for a short window: how many satellites are
 * heard, how strongly, how many make the fix and how accurate it is. These
 * are what a building changes (spec 5.9); see EnvContextClassifier.
 *
 * Needs precise location: an approximate grant gives no satellite detail, so
 * it gets no sample at all (the context stays unknown). Callers also have to
 * be allowed to use location right now - in the foreground, or in the band
 * service while it runs with the location type - otherwise a silent receiver
 * would read as "indoors".
 */
class LocationContextSampler(private val context: Context) {

    data class Observation(
        val satellitesVisible: Int,
        val satellitesUsed: Int,
        val topCn0DbHz: Double?,
        val accuracyM: Double?,
        val speedMps: Double?,
        val lat: Double?,
        val lon: Double?
    )

    fun canSample(): Boolean {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED) return false
        val lm = context.getSystemService(LocationManager::class.java) ?: return false
        return runCatching { lm.isProviderEnabled(LocationManager.GPS_PROVIDER) }.getOrDefault(false)
    }

    /** Null when the GNSS receiver cannot be used now (no precise permission, GPS off, refused). */
    @SuppressLint("MissingPermission")
    suspend fun observe(windowMs: Long = WINDOW_MS): Observation? {
        if (!canSample()) return null
        val lm = context.getSystemService(LocationManager::class.java) ?: return null
        val lock = Any()
        var visible = 0
        var used = 0
        var topCn0: Double? = null
        val fixes = mutableListOf<Location>()

        val status = object : GnssStatus.Callback() {
            override fun onSatelliteStatusChanged(s: GnssStatus) {
                var v = 0
                var u = 0
                val cn0s = ArrayList<Double>(s.satelliteCount)
                for (i in 0 until s.satelliteCount) {
                    val c = s.getCn0DbHz(i).toDouble()
                    if (c > 0.0) { v++; cn0s += c }
                    if (s.usedInFix(i)) u++
                }
                val top = cn0s.sortedDescending().take(4).takeIf { it.isNotEmpty() }?.average()
                synchronized(lock) {
                    // The best moment of the window: a building only ever takes signal away.
                    if (v > visible) visible = v
                    if (u > used) used = u
                    if (top != null && (topCn0 == null || top > topCn0!!)) topCn0 = top
                }
            }
        }
        val listener = LocationListener { loc -> synchronized(lock) { fixes += loc } }
        val main = Handler(Looper.getMainLooper())
        try {
            @Suppress("DEPRECATION")
            lm.registerGnssStatusCallback(status, main)
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1_000L, 0f, listener, Looper.getMainLooper())
            val started = System.currentTimeMillis()
            withTimeoutOrNull(windowMs) {
                while (true) {
                    delay(1_000L)
                    val clearSky = synchronized(lock) {
                        used >= 6 && (topCn0 ?: 0.0) >= 35.0 && fixes.any { it.hasAccuracy() && it.accuracy <= 15f }
                    }
                    // Open sky is unambiguous quickly; "indoors" needs the whole window.
                    if (clearSky && System.currentTimeMillis() - started >= 4_000L) break
                }
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "GNSS not available to this process now: ${e.message}")
            return null
        } finally {
            runCatching { lm.removeUpdates(listener) }
            runCatching { lm.unregisterGnssStatusCallback(status) }
        }

        return synchronized(lock) {
            val best = fixes.filter { it.hasAccuracy() }.minByOrNull { it.accuracy }
            val speeds = fixes.filter { it.hasSpeed() }.map { it.speed.toDouble() }.sorted()
            Observation(
                satellitesVisible = visible,
                satellitesUsed = used,
                topCn0DbHz = topCn0,
                accuracyM = best?.accuracy?.toDouble(),
                speedMps = speeds.getOrNull(speeds.size / 2),
                lat = (best ?: fixes.lastOrNull())?.latitude,
                lon = (best ?: fixes.lastOrNull())?.longitude
            )
        }
    }

    companion object {
        const val WINDOW_MS = 12_000L
    }
}
