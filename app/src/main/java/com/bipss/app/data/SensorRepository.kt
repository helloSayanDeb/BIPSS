package com.bipss.app.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.bipss.app.ble.BleGattManager
import com.bipss.app.ble.SensorReading
import com.bipss.app.data.db.AppDatabase
import com.bipss.app.data.db.entity.BaselineStatsEntity
import com.bipss.app.data.db.entity.RawReadingEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlin.math.sqrt

const val ANOMALY_THRESHOLD_SIGMA = 3.0   // readings > 3σ from baseline are anomalous
const val BASELINE_MIN_READINGS   = 10    // minimum readings before baseline is used

/**
 * Connectivity state as a Flow — shared between DashboardViewModel and DisasterAlertRepository.
 */
fun connectivityFlow(context: Context): Flow<Boolean> = callbackFlow {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { trySend(true) }
        override fun onLost(network: Network)      { trySend(false) }
    }
    val request = NetworkRequest.Builder()
        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        .build()
    cm.registerNetworkCallback(request, callback)
    // Emit current state immediately
    val current = cm.activeNetwork?.let {
        cm.getNetworkCapabilities(it)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    } ?: false
    trySend(current)
    awaitClose { cm.unregisterNetworkCallback(callback) }
}.distinctUntilChanged()

/**
 * Bridges BleGattManager notifications → Room persistence + Welford baseline update.
 * Exposes a [Flow<SensorReading>] for DashboardViewModel to collect.
 */
class SensorRepository(
    private val bleGattManager: BleGattManager,
    private val db:             AppDatabase,
    private val appScope:       CoroutineScope
) {
    val rawReadingDao    = db.rawReadingDao()
    val baselineStatsDao = db.baselineStatsDao()

    val sensorEvents: SharedFlow<SensorReading> = bleGattManager.sensorReadings
        .onEach { reading -> persistAndUpdateBaseline(reading) }
        .shareIn(appScope, SharingStarted.WhileSubscribed(5_000), replay = 1)

    private suspend fun persistAndUpdateBaseline(reading: SensorReading) {
        reading.heartRate?.let { updateMetric("HR",        it.toDouble()) }
        reading.spO2?.let      { updateMetric("SPO2",      it.toDouble()) }
        reading.skinTempC?.let { updateMetric("SKIN_TEMP", it.toDouble()) }
        reading.accelX?.let    { updateMetric("ACCEL_X",   it.toDouble()) }
        reading.accelY?.let    { updateMetric("ACCEL_Y",   it.toDouble()) }
        reading.accelZ?.let    { updateMetric("ACCEL_Z",   it.toDouble()) }
    }

    private suspend fun updateMetric(type: String, value: Double) {
        val existing = baselineStatsDao.getByType(type)
        val isAnomaly: Boolean

        if (existing != null && existing.count >= BASELINE_MIN_READINGS) {
            val sigma = if (existing.stdDev > 0) kotlin.math.abs(value - existing.mean) / existing.stdDev else 0.0
            isAnomaly = sigma > ANOMALY_THRESHOLD_SIGMA
        } else {
            isAnomaly = false
        }

        // Persist raw reading
        rawReadingDao.insert(RawReadingEntity(
            metricType  = type,
            value       = value,
            timestampMs = System.currentTimeMillis(),
            isAnomalous = isAnomaly
        ))

        // Update Welford baseline only on non-anomalous readings
        if (!isAnomaly) {
            val stats = existing ?: BaselineStatsEntity(type, 0.0, 0.0, 0, 0.0)
            val count  = stats.count + 1
            val delta  = value - stats.mean
            val mean   = stats.mean + delta / count
            val delta2 = value - mean
            val m2     = stats.m2 + delta * delta2
            val stdDev = if (count > 1) sqrt(m2 / (count - 1)) else 0.0

            baselineStatsDao.upsert(BaselineStatsEntity(type, mean, m2, count, stdDev))
        }

        // Prune readings older than 7 days
        val sevenDaysAgo = System.currentTimeMillis() - 7 * 24 * 60 * 60 * 1000L
        rawReadingDao.pruneOlderThan(sevenDaysAgo)
    }
}
