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
import kotlin.math.sqrt

const val ANOMALY_THRESHOLD_SIGMA = 3.0   // readings > 3 sigma from baseline are anomalous
const val BASELINE_MIN_READINGS   = 10    // minimum readings before baseline is used

/** Metric type keys used in raw_readings. */
object MetricTypes {
    const val HR         = "HR"
    const val SPO2       = "SPO2"
    const val SKIN_TEMP  = "SKIN_TEMP"
    const val HRV        = "HRV"
    const val ACCEL_MAG  = "ACCEL_MAG"
    const val AMBIENT    = "AMBIENT_TEMP"
    const val HUMIDITY   = "HUMIDITY"

    /** Metrics that have their own detail screen. */
    val displayable = listOf(HR, SPO2, SKIN_TEMP, HRV)
}

/** Connectivity as a Flow, shared between the dashboard and the environmental repository. */
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
    val current = cm.activeNetwork?.let {
        cm.getNetworkCapabilities(it)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    } ?: false
    trySend(current)
    awaitClose { cm.unregisterNetworkCallback(callback) }
}.distinctUntilChanged()

/**
 * Bridges band notifications to Room persistence and the Welford baseline.
 *
 * Two changes matter here. Readings are stamped with the band's own sample time
 * rather than arrival time, so a buffered replay does not pile thousands of
 * samples onto one instant. And raw data is retained for seven days rather than
 * pruned blindly - anything older is rolled into minute aggregates by the
 * aggregation worker, which is what makes a 30-day history view possible
 * without storing tens of millions of rows.
 */
class SensorRepository(
    private val bleGattManager: BleGattManager,
    private val db:             AppDatabase,
    private val prefsRepo:      UserPreferencesRepository,
    private val appScope:       CoroutineScope
) {
    val rawReadingDao       = db.rawReadingDao()
    val baselineStatsDao    = db.baselineStatsDao()
    val minuteAggregateDao  = db.minuteAggregateDao()

    /** Band battery percent, straight from the BLE manager. Null until the band reports it. */
    val batteryPercent: StateFlow<Int?> = bleGattManager.batteryPercent

    /** Latest band-sensor air quality index, or null when no band is streaming. */
    private val _bandSensorAqi = MutableStateFlow<Int?>(null)
    val bandSensorAqi: StateFlow<Int?> = _bandSensorAqi.asStateFlow()

    /** The single flow of band readings consumed by the dashboard. */
    val sensorEvents: SharedFlow<SensorReading> = bleGattManager.sensorReadings
        .onEach { reading -> persistAndUpdateBaseline(reading) }
        .shareIn(appScope, SharingStarted.WhileSubscribed(5_000), replay = 1)

    private suspend fun persistAndUpdateBaseline(reading: SensorReading) {
        val ts = reading.timestamp

        // Stamp the first reading so the baseline schedule counts from real
        // data rather than from install time.
        prefsRepo.recordFirstReadingIfNeeded(ts)

        reading.heartRate?.let      { updateMetric(MetricTypes.HR, it.toDouble(), ts) }
        reading.spO2?.let           { updateMetric(MetricTypes.SPO2, it.toDouble(), ts) }
        reading.skinTempC?.let      { updateMetric(MetricTypes.SKIN_TEMP, it.toDouble(), ts) }
        reading.hrvRmssd?.let       { updateMetric(MetricTypes.HRV, it.toDouble(), ts) }
        reading.accelMagnitude?.let { updateMetric(MetricTypes.ACCEL_MAG, it.toDouble(), ts) }
        reading.ambientTempC?.let   { updateMetric(MetricTypes.AMBIENT, it.toDouble(), ts) }
        reading.humidityPct?.let    { updateMetric(MetricTypes.HUMIDITY, it.toDouble(), ts) }

        _bandSensorAqi.value = reading.aqiRaw
    }

    /**
     * Absolute sanity bounds used when the baseline has too few readings for
     * statistical anomaly detection. These prevent impossible first samples
     * from poisoning the Welford mean permanently. Once the baseline matures,
     * the existing 3-sigma gate takes over.
     */
    private val SANITY_BOUNDS = mapOf(
        MetricTypes.HR        to 40.0..180.0,
        MetricTypes.SPO2      to 70.0..100.0,
        MetricTypes.SKIN_TEMP to 30.0..42.0,
        MetricTypes.HRV       to 5.0..300.0,
        MetricTypes.AMBIENT   to -10.0..55.0,
        MetricTypes.HUMIDITY  to 0.0..100.0,
        MetricTypes.ACCEL_MAG to 0.0..20.0
    )

    private suspend fun updateMetric(type: String, value: Double, timestampMs: Long) {
        val existing = baselineStatsDao.getByType(type)

        val isAnomaly = if (existing != null && existing.count >= BASELINE_MIN_READINGS && existing.stdDev > 0) {
            // Stage B: baseline-relative anomaly detection.
            kotlin.math.abs(value - existing.mean) / existing.stdDev > ANOMALY_THRESHOLD_SIGMA
        } else {
            // Stage A: no baseline yet — use hard physiological sanity bounds
            // so an impossible first reading cannot poison the Welford seed.
            val bounds = SANITY_BOUNDS[type]
            bounds != null && value !in bounds
        }

        rawReadingDao.insert(
            RawReadingEntity(
                metricType  = type,
                value       = value,
                timestampMs = timestampMs,
                isAnomalous = isAnomaly
            )
        )

        // Welford update, skipped for anomalies so one spike cannot drag the mean.
        if (!isAnomaly) {
            val stats  = existing ?: BaselineStatsEntity(type, 0.0, 0.0, 0, 0.0)
            val count  = stats.count + 1
            val delta  = value - stats.mean
            val mean   = stats.mean + delta / count
            val delta2 = value - mean
            val m2     = stats.m2 + delta * delta2
            val stdDev = if (count > 1) sqrt(m2 / (count - 1)) else 0.0
            baselineStatsDao.upsert(BaselineStatsEntity(type, mean, m2, count, stdDev))
        }
    }

    /** Resting proxy: mean of the lowest samples in the window. Null when there is no data. */
    suspend fun restingHeartRate(windowMs: Long = 24 * 3_600_000L): Double? {
        val now = System.currentTimeMillis()
        return rawReadingDao.getRestingAverage(MetricTypes.HR, now - windowMs, now)
    }
}
