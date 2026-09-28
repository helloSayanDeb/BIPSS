package com.bipss.app.ui.metric

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bipss.app.data.MetricTypes
import com.bipss.app.data.db.dao.BaselineStatsDao
import com.bipss.app.data.db.dao.MetricStats
import com.bipss.app.data.db.dao.MinuteAggregateDao
import com.bipss.app.data.db.dao.RawReadingDao
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Backs one metric's history screen.
 *
 * Every range reads both history tables - raw rows for the last seven days,
 * minute aggregates for anything older - bucketed to the range's resolution
 * and merged (see [mergeBuckets]). Gaps where the band was not worn are marked
 * explicitly rather than bridged - see [insertGaps].
 */
class MetricDetailViewModel(
    private val metricType:  String,
    private val rawDao:      RawReadingDao,
    private val aggDao:      MinuteAggregateDao,
    private val baselineDao: BaselineStatsDao
) : ViewModel() {

    data class UiState(
        val metricType:  String = "",
        val range:       TimeRange = TimeRange.DAY,
        val points:      List<GraphPoint> = emptyList(),
        val stats:       MetricStats? = null,
        val baseline:    Double? = null,
        val restingValue: Float? = null,
        val currentValue: Float? = null,
        val isLoading:   Boolean = true
    ) {
        val hasData: Boolean get() = points.any { !it.value.isNaN() }
    }

    private val _uiState = MutableStateFlow(UiState(metricType = metricType))
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init { load(TimeRange.DAY) }

    fun setRange(range: TimeRange) = load(range)

    private fun load(range: TimeRange) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, range = range) }

            val now = System.currentTimeMillis()
            val start = now - range.durationMs

            val rawBuckets = rawDao.getBuckets(metricType, start, now, range.bucketMs)
            val aggBuckets = aggDao.getBuckets(metricType, start / 60_000L, now / 60_000L, range.bucketMs)
            val buckets = mergeBuckets(rawBuckets, aggBuckets)
            val raw = bucketsToPoints(buckets, range.bucketMs)
            val stats = combineStats(buckets)

            // Raw rows only cover the last week, so the resting figure is
            // labelled as such on screen rather than implied to span the range.
            val resting = if (metricType == MetricTypes.HR)
                rawDao.getRestingAverage(metricType, maxOf(start, now - 7 * 86_400_000L), now)?.toFloat() else null

            _uiState.update {
                it.copy(
                    points       = insertGaps(raw, range.gapThresholdMs),
                    stats        = stats.takeIf { it.sampleCount > 0 },
                    baseline     = baselineDao.getByType(metricType)?.mean,
                    restingValue = resting,
                    currentValue = raw.lastOrNull()?.value,
                    isLoading    = false
                )
            }
        }
    }
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
