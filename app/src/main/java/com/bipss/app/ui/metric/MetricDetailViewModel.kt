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
 * Day reads raw samples; Week and Month read minute aggregates, because a
 * 30-day window at 1 Hz is millions of rows. Gaps where the band was not worn
 * are marked explicitly rather than bridged - see [insertGaps].
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

            val raw = if (range == TimeRange.DAY) {
                rawDao.getRange(metricType, start, now)
                    .map { GraphPoint(it.timestampMs, it.value.toFloat()) }
            } else {
                aggDao.getRange(metricType, start / 60_000L, now / 60_000L)
                    .map { GraphPoint(it.minuteEpoch * 60_000L, it.avg) }
            }

            val stats = if (range == TimeRange.DAY) rawDao.getStats(metricType, start, now)
                        else aggDao.getStats(metricType, start / 60_000L, now / 60_000L)

            val resting = if (metricType == MetricTypes.HR)
                rawDao.getRestingAverage(metricType, start, now)?.toFloat() else null

            _uiState.update {
                it.copy(
                    points       = insertGaps(raw, range.gapThresholdMs),
                    stats        = stats,
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
    MetricTypes.HRV       -> MetricMeta("Heart rate variability", "ms")
    MetricTypes.AMBIENT   -> MetricMeta("Ambient temperature", "°C")
    MetricTypes.HUMIDITY  -> MetricMeta("Humidity", "%")
    else                  -> MetricMeta(metricType, "")
}
