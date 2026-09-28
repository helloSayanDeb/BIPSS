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
import androidx.room.withTransaction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlin.math.sqrt


/** Metric type keys used in raw_readings. */
object MetricTypes {
    const val HR         = "HR"
    const val SPO2       = "SPO2"
    const val SKIN_TEMP  = "SKIN_TEMP"
    /** Pulse rate variability (RMSSD of pulse intervals, ms). Stored under the legacy key before schema v7 (see MIGRATION_6_7). */
    const val PRV        = "PRV"
    /** Movement over one second, mg*s (band firmware v2). */
    const val ACTIVITY   = "ACTIVITY"
    /** Legacy accelerometer magnitude; only Demo Mode ever wrote it. Kept so old rows stay readable. */
    const val ACCEL_MAG  = "ACCEL_MAG"
    const val AMBIENT    = "AMBIENT_TEMP"
    const val HUMIDITY   = "HUMIDITY"

    /** Metrics that have their own detail screen. */
    val displayable = listOf(HR, SPO2, SKIN_TEMP, PRV)
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

    /** Latest ENS160 reading on its UBA 1..5 scale, or null when no band is streaming. */
    private val _bandSensorAqi = MutableStateFlow<Int?>(null)
    val bandSensorAqi: StateFlow<Int?> = _bandSensorAqi.asStateFlow()

    private val _sensorEvents = MutableSharedFlow<SensorReading>(replay = 1, extraBufferCapacity = 64)

    /** The single flow of band readings consumed by the screens. */
    val sensorEvents: SharedFlow<SensorReading> = _sensorEvents.asSharedFlow()

    private var started = false
    @Volatile private var firstReadingRecorded = false

    /**
     * Starts persisting band readings, independent of any screen.
     *
     * Persistence used to sit upstream of `shareIn(WhileSubscribed)`, so it
     * only ran while some view model was collecting: close the app overnight
     * with the band still connected (the foreground service keeps the link up)
     * and nothing reached the database - exactly the night sleep analysis
     * needed. Called once from the Application.
     */
    @Synchronized
    fun start() {
        if (started) return
        started = true
        // "Delete my data" clears the first-reading stamp mid-process; the
        // next reading after that must stamp it again.
        appScope.launch {
            prefsRepo.firstReadingMs.collect { if (it == null) firstReadingRecorded = false }
        }
        appScope.launch {
            bleGattManager.sensorReadings.collect { reading ->
                runCatching { persistAndUpdateBaseline(reading) }
                    .onFailure { android.util.Log.e("SensorRepository", "Persist failed", it) }
                _sensorEvents.emit(reading)
            }
        }
    }

    /** One transaction per reading: a single commit instead of one per metric. */
    private suspend fun persistAndUpdateBaseline(reading: SensorReading) =
        db.withTransaction { persistReading(reading) }

    private suspend fun persistReading(reading: SensorReading) {
        val ts = reading.timestamp

        // Stamp the first reading so the baseline schedule counts from real
        // data rather than from install time. Once per process is enough -
        // it used to be a DataStore write on every single sample.
        if (!firstReadingRecorded) {
            prefsRepo.recordFirstReadingIfNeeded(ts)
            firstReadingRecorded = true
        }

        reading.heartRate?.let      { updateMetric(MetricTypes.HR, it.toDouble(), ts) }
        reading.spO2?.let           { updateMetric(MetricTypes.SPO2, it.toDouble(), ts) }
        reading.skinTempC?.let      { updateMetric(MetricTypes.SKIN_TEMP, it.toDouble(), ts) }
        reading.bandPrvRmssdMs?.let { updateMetric(MetricTypes.PRV, it.toDouble(), ts) }
        reading.activityCount?.let  { updateMetric(MetricTypes.ACTIVITY, it.toDouble(), ts) }
        reading.ambientTempC?.let   { updateMetric(MetricTypes.AMBIENT, it.toDouble(), ts) }
        reading.humidityPct?.let    { updateMetric(MetricTypes.HUMIDITY, it.toDouble(), ts) }

        _bandSensorAqi.value = reading.ensUba
    }

    /**
     * Physiological / sensor bounds. A reading outside them is a fault, not a
     * measurement, and is kept out of the baseline and the statistics.
     *
     * There is deliberately no longer a "3 sigma from the all-time mean" gate:
     * against a lifetime average of resting and sleeping heart rate, any real
     * workout sits beyond 3 sigma, so exercise was being flagged anomalous and
     * then silently dropped from the Week/Month charts and the aggregates -
     * erasing the very data Strain needs. Signal quality from the band
     * pipeline, not distance from a mean, decides whether a pulse reading is
     * trustworthy.
     */
    companion object {
        val SANITY_BOUNDS: Map<String, ClosedFloatingPointRange<Double>> = mapOf(
            MetricTypes.HR        to 30.0..230.0,
            MetricTypes.SPO2      to 70.0..100.0,
            MetricTypes.SKIN_TEMP to 30.0..42.0,
            MetricTypes.PRV       to 5.0..300.0,
            MetricTypes.ACTIVITY  to 0.0..65_534.0,
            MetricTypes.AMBIENT   to -10.0..55.0,
            MetricTypes.HUMIDITY  to 0.0..100.0,
            MetricTypes.ACCEL_MAG to 0.0..20.0
        )
    }

    private suspend fun updateMetric(type: String, value: Double, timestampMs: Long) {
        val existing = baselineStatsDao.getByType(type)

        val bounds = SANITY_BOUNDS[type]
        val isAnomaly = bounds != null && value !in bounds

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
