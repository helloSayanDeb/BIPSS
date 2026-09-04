package com.bipss.app.ui.dashboard

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bipss.app.data.SensorRepository
import com.bipss.app.data.UserPreferencesRepository
import com.bipss.app.data.connectivityFlow
import com.bipss.app.data.db.entity.RawReadingEntity
import com.bipss.app.data.environmental.DisasterAlertRepository
import com.bipss.app.domain.RiskLevel
import com.bipss.app.domain.RiskScore
import com.bipss.app.domain.RiskScoreCalculator
import com.bipss.app.domain.environmental.EnvironmentalSnapshot
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

enum class HealthStatus { NORMAL, MONITOR, AT_RISK }

data class DashboardUiState(
    val heartRate:        Int                    = 0,
    val spO2:             Int                    = 0,
    val skinTempC:        Float                  = 0f,
    val stepCount:        Int                    = 0,

    // Sparkline history (last 10 readings per metric)
    val heartRateHistory: List<Float>            = emptyList(),
    val spO2History:      List<Float>            = emptyList(),
    val skinTempHistory:  List<Float>            = emptyList(),

    // 7-day trend (placeholder — populated when Room has enough history)
    val trendData:        List<Float>            = emptyList(),
    val showingToday:     Boolean                = true,

    val riskScores:       List<RiskScore>        = emptyList(),
    val healthStatus:     HealthStatus           = HealthStatus.NORMAL,
    val env:              EnvironmentalSnapshot  = EnvironmentalSnapshot.PLACEHOLDER,
    val isOnline:         Boolean                = true,
    val isDegraded:       Boolean                = false  // true = offline + stale data
)

class DashboardViewModel(
    private val sensorRepo:    SensorRepository,
    private val envRepo:       DisasterAlertRepository,
    private val prefsRepo:     UserPreferencesRepository,
    private val calculator:    RiskScoreCalculator,
    private val context:       Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(DashboardUiState())
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()

    init {
        observeSensorData()
        observeConnectivity()
        loadCachedEnv()
    }

    private fun observeSensorData() {
        viewModelScope.launch {
            sensorRepo.sensorEvents.collect { reading ->
                _uiState.update { s ->
                    val hr    = reading.heartRate  ?: s.heartRate
                    val spo2  = reading.spO2       ?: s.spO2
                    val temp  = reading.skinTempC  ?: s.skinTempC

                    // Maintain rolling sparkline (last 10)
                    fun List<Float>.appendRolling(v: Float?) =
                        if (v != null) (this + v).takeLast(10) else this

                    s.copy(
                        heartRate        = hr,
                        spO2             = spo2,
                        skinTempC        = temp,
                        heartRateHistory = s.heartRateHistory.appendRolling(reading.heartRate?.toFloat()),
                        spO2History      = s.spO2History.appendRolling(reading.spO2?.toFloat()),
                        skinTempHistory  = s.skinTempHistory.appendRolling(reading.skinTempC)
                    )
                }
                recomputeRiskScores()
            }
        }
    }

    private fun observeConnectivity() {
        viewModelScope.launch {
            connectivityFlow(context).collect { online ->
                _uiState.update { it.copy(isOnline = online, isDegraded = !online) }
            }
        }
    }

    private fun loadCachedEnv() {
        viewModelScope.launch {
            val cached = envRepo.getCachedSnapshot()
            if (cached != null) _uiState.update { it.copy(env = cached) }

            // If online, try a fresh fetch
            if (_uiState.value.isOnline) {
                try {
                    val fresh = envRepo.fetchAndCache()
                    _uiState.update { it.copy(env = fresh) }
                } catch (e: Exception) { /* silent — use cache */ }
            }
            recomputeRiskScores()
        }
    }

    private suspend fun recomputeRiskScores() {
        val s = _uiState.value
        val currentHR = s.heartRate.takeIf { it > 0 } ?: 72
        val currentSpO2 = s.spO2.takeIf { it > 0 } ?: 98
        val currentTemp = s.skinTempC.takeIf { it > 0f } ?: 36.5f

        val baseStats = sensorRepo.baselineStatsDao.getByType("HR")
        val baselineHR    = baseStats?.mean   ?: currentHR.toDouble()
        val baselineHRStd = baseStats?.stdDev ?: 5.0
        val hoursSince    = prefsRepo.hoursSinceLastWaterLog()

        val scores = calculator.computeAll(
            heartRate              = currentHR,
            spO2                   = currentSpO2,
            skinTempC              = currentTemp,
            baselineHR             = baselineHR,
            baselineHRStd          = baselineHRStd,
            env                    = s.env,
            hoursSinceLastWaterLog = hoursSince
        )

        val maxLevel = scores.maxOfOrNull { it.level.ordinal }?.let { RiskLevel.entries[it] }
            ?: RiskLevel.LOW
        val status = when (maxLevel) {
            RiskLevel.LOW    -> HealthStatus.NORMAL
            RiskLevel.MEDIUM -> HealthStatus.MONITOR
            RiskLevel.HIGH   -> HealthStatus.AT_RISK
        }

        _uiState.update { it.copy(riskScores = scores, healthStatus = status) }
    }

    fun onTrendTabSelected(showToday: Boolean) {
        _uiState.update { it.copy(showingToday = showToday) }
    }
}
