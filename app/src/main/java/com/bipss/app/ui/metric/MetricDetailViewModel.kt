package com.bipss.app.ui.metric

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bipss.app.ble.SensorReading
import com.bipss.app.data.MetricTypes
import com.bipss.app.data.SensorRepository
import com.bipss.app.data.db.dao.BaselineStatsDao
import com.bipss.app.data.db.dao.MetricStats
import com.bipss.app.data.db.dao.MinuteAggregateDao
import com.bipss.app.data.db.dao.RawReadingDao
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn

/**
 * Backs one metric's history screen, live while it is on screen.
 *
 * Every range reads both history tables - raw rows for the last seven days,
 * minute aggregates for anything older - bucketed to the range's resolution
 * and merged (see [mergeBuckets]). Gaps where the band was not worn are marked
 * explicitly rather than bridged - see [insertGaps].
 *
 * The screen used to load once, so with the band streaming it sat still until
 * it was left and reopened. The chart is now re-read on a timer suited to the
 * range (the day view every few seconds), and the headline follows the band's
 * readings as they arrive. Both stop when the screen is not visible.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MetricDetailViewModel(
    private val metricType:  String,
    sensorRepo:              SensorRepository,
    private val rawDao:      RawReadingDao,
    private val aggDao:      MinuteAggregateDao,
    private val baselineDao: BaselineStatsDao
) : ViewModel() {

    data class UiState(
        val metricType:   String = "",
        val range:        TimeRange = TimeRange.DAY,
        val points:       List<GraphPoint> = emptyList(),
        val stats:        MetricStats? = null,
        val baseline:     Double? = null,
        val restingValue: Float? = null,
        /** The band's reading this moment, or the latest one in the chart. */
        val currentValue: Float? = null,
        /** True while [currentValue] is the band's live reading. */
        val isLive:       Boolean = false,
        /** When the chart was last read. */
        val updatedAtMs:  Long = 0L,
        val isLoading:    Boolean = true
    ) {
        val hasData: Boolean get() = points.any { !it.value.isNaN() }
    }

    private val range = MutableStateFlow(TimeRange.DAY)

    /** This metric's latest live value and its time. Readings without it leave the last one standing. */
    private val live = sensorRepo.sensorEvents
        .mapNotNull { r -> liveValue(metricType, r)?.let { it to r.timestamp } }
        .map<Pair<Float, Long>, Pair<Float, Long>?> { it }
        .onStart { emit(null) }

    /** Re-checks the live value's age when the band goes quiet and nothing else changes. */
    private val tick = flow { while (true) { emit(Unit); delay(5_000L) } }

    private val history = range.flatMapLatest { r ->
        flow {
            emit(UiState(metricType = metricType, range = r, isLoading = true))
            while (true) {
                emit(query(r))
                delay(r.refreshMs)
            }
        }
    }

    val uiState: StateFlow<UiState> = combine(history, live, tick) { h, l, _ ->
        val now = System.currentTimeMillis()
        val fresh = l?.takeIf { now - it.second <= LIVE_MAX_AGE_MS }
        if (fresh != null) h.copy(currentValue = fresh.first, isLive = true) else h
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState(metricType = metricType))

    fun setRange(r: TimeRange) { range.value = r }

    private suspend fun query(r: TimeRange): UiState {
        val now = System.currentTimeMillis()
        val start = now - r.durationMs
        val rawBuckets = rawDao.getBuckets(metricType, start, now, r.bucketMs)
        val aggBuckets = aggDao.getBuckets(metricType, start / 60_000L, now / 60_000L, r.bucketMs)
        val buckets = mergeBuckets(rawBuckets, aggBuckets)
        val points = bucketsToPoints(buckets, r.bucketMs)
        val stats = combineStats(buckets)
        // Raw rows only cover the last week, so the resting figure is
        // labelled as such on screen rather than implied to span the range.
        val resting = if (metricType == MetricTypes.HR)
            rawDao.getRestingAverage(metricType, maxOf(start, now - 7 * 86_400_000L), now)?.toFloat() else null
        return UiState(
            metricType   = metricType,
            range        = r,
            points       = insertGaps(points, r.gapThresholdMs),
            stats        = stats.takeIf { it.sampleCount > 0 },
            baseline     = baselineDao.getByType(metricType)?.mean,
            restingValue = resting,
            currentValue = points.lastOrNull()?.value,
            updatedAtMs  = now,
            isLoading    = false
        )
    }

    private companion object {
        /** A reading older than this is not "now". */
        const val LIVE_MAX_AGE_MS = 15_000L
    }
}

/** This metric's value in one band reading, or null when the reading has none. */
internal fun liveValue(metricType: String, r: SensorReading): Float? = when (metricType) {
    MetricTypes.HR        -> r.heartRate?.takeIf { it > 0 }?.toFloat()
    MetricTypes.SPO2      -> r.spO2?.takeIf { it > 0 }?.toFloat()
    MetricTypes.SKIN_TEMP -> r.skinTempC
    MetricTypes.PRV       -> r.prvRmssdMs?.takeIf { it > 0 }?.toFloat()
    MetricTypes.AMBIENT   -> r.ambientTempC
    MetricTypes.HUMIDITY  -> r.humidityPct
    MetricTypes.ACTIVITY  -> r.activityCount?.toFloat()
    else                  -> null
}

/** Display metadata for a metric key. Keeps units and colours consistent everywhere. */
data class MetricMeta(val label: String, val unit: String)

fun metricMeta(metricType: String): MetricMeta = when (metricType) {
    MetricTypes.HR        -> MetricMeta("Heart rate", "bpm")
    MetricTypes.SPO2      -> MetricMeta("Blood oxygen", "%")
    MetricTypes.SKIN_TEMP -> MetricMeta("Skin temperature", "°C")
    MetricTypes.PRV       -> MetricMeta("Pulse rate variability", "ms")
    MetricTypes.ACTIVITY  -> MetricMeta("Activity", "mg·s")
    MetricTypes.AMBIENT   -> MetricMeta("Ambient temperature", "°C")
    MetricTypes.HUMIDITY  -> MetricMeta("Humidity", "%")
    else                  -> MetricMeta(metricType, "")
}

/**
 * How each reading is made, in plain words - shown on the detail page in
 * place of a badge. Measured values are described as measured; the caveats
 * are the real ones for this band.
 */
fun metricMethodNote(metricType: String): String? = when (metricType) {
    MetricTypes.HR -> "Measured from your pulse by the band's optical sensor."
    MetricTypes.SPO2 ->
        "Measured by the band's red and infrared light sensor. It uses the standard conversion and has not been " +
            "calibrated for this band, so it can differ from a clinical pulse oximeter by a few percent; the trend " +
            "is more reliable than a single number."
    // The only place the older term appears: to say what PRV stands in for.
    MetricTypes.PRV ->
        "The variation in time between your pulse beats, measured at the wrist (a proxy for HRV). It's most " +
            "meaningful when you're still; movement makes it jump."
    MetricTypes.SKIN_TEMP ->
        "Measured on your skin by the band's temperature sensor. Skin runs cooler than your core temperature."
    else -> null
}
