package com.bipss.app.ui.dashboard

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bipss.app.data.MetricTypes
import com.bipss.app.data.SensorRepository
import com.bipss.app.data.SleepRepository
import com.bipss.app.data.UserPreferencesRepository
import com.bipss.app.data.db.dao.ContextFeedbackDao
import com.bipss.app.data.db.entity.ContextFeedbackEntity
import com.bipss.app.data.connectivityFlow
import com.bipss.app.data.environmental.DisasterAlertRepository
import com.bipss.app.domain.AlertRanker
import com.bipss.app.domain.BaselineSchedule
import com.bipss.app.domain.DataMode
import com.bipss.app.domain.RiskLevel
import com.bipss.app.domain.RiskScore
import com.bipss.app.domain.RiskScoreCalculator
import com.bipss.app.domain.RiskScoreType
import com.bipss.app.domain.ScoreAvailability
import com.bipss.app.domain.environmental.ContextInference
import com.bipss.app.domain.environmental.ContextInferencer
import com.bipss.app.domain.environmental.EnvironmentalSnapshot
import com.bipss.app.domain.environmental.RankedAlert
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

enum class HealthStatus { NORMAL, MONITOR, AT_RISK }

data class DashboardUiState(
    /** Null means no reading. Never a substituted value. */
    val heartRate:        Int?                   = null,
    val spO2:             Int?                   = null,
    val skinTempC:        Float?                 = null,
    val hrvRmssd:         Int?                   = null,
    val stepCount:        Int?                   = null,
    val batteryPercent:   Int?                   = null,

    val heartRateHistory: List<Float>            = emptyList(),
    val spO2History:      List<Float>            = emptyList(),
    val skinTempHistory:  List<Float>            = emptyList(),

    val riskScores:       List<RiskScore>        = emptyList(),
    val rankedAlerts:     List<RankedAlert>      = emptyList(),
    val contextInference: ContextInference?      = null,
    val healthStatus:     HealthStatus           = HealthStatus.NORMAL,
    val env:              EnvironmentalSnapshot  = EnvironmentalSnapshot.EMPTY,

    val dataMode:         DataMode               = DataMode.CACHED,
    val baselineDays:     Int                    = 0,
    val lastWaterLogMs:   Long?                  = null,
    val waterLogTotalMlToday: Int                = 0,
    /** Set once the user has answered the indoor/outdoor question this session. */
    val contextFeedbackGiven: Boolean            = false,

    val isOnline:         Boolean                = true,
    val isRefreshing:     Boolean                = false,

    val locationPermissionSkipped: Boolean       = false
) {
    val hasBand: Boolean get() = heartRate != null

    /**
     * The headline score.
     *
     * Readiness first, then the worst *physiological* hazard. Flood is
     * deliberately excluded: it measures regional weather rather than anything
     * about the wearer, and during monsoon it pins near its maximum, so
     * allowing it to head the screen buries the body's own numbers under a
     * thunderstorm warning that applies to several million people equally.
     * It still appears in Risk domains and still drags Readiness down.
     */
    val heroScore: RiskScore?
        get() = riskScores.firstOrNull { it.type == RiskScoreType.READINESS && it.hasNumber }
            ?: riskScores
                .filter { !it.type.isWellness && it.type != RiskScoreType.FLOOD && it.hasNumber }
                .maxByOrNull { it.score }
            ?: riskScores.firstOrNull { it.type == RiskScoreType.READINESS }

    val wellnessPillars: List<RiskScore>
        get() = riskScores.filter { it.type.isWellness && it.type != RiskScoreType.READINESS }

    val hazardDomains: List<RiskScore>
        get() = riskScores.filter { !it.type.isWellness }
}

class DashboardViewModel(
    private val sensorRepo: SensorRepository,
    private val waterRepo:  com.bipss.app.data.WaterRepository,
    private val envRepo:    DisasterAlertRepository,
    private val prefsRepo:  UserPreferencesRepository,
    private val sleepRepo:  SleepRepository,
    private val feedbackDao: ContextFeedbackDao,
    private val calculator: RiskScoreCalculator,
    private val context:    Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(DashboardUiState())
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()

    init {
        observeSensorData()
        observeConnectivity()
        observeBattery()
        observeWaterLog()
        observePermissionState()
        loadCachedEnv()
    }

    private fun observePermissionState() {
        viewModelScope.launch {
            prefsRepo.locationPermissionSkipped.collect { skipped ->
                _uiState.update { it.copy(locationPermissionSkipped = skipped) }
            }
        }
    }

    fun skipLocationPermission() {
        viewModelScope.launch {
            prefsRepo.setLocationPermissionSkipped(true)
        }
    }

    private fun observeSensorData() {
        viewModelScope.launch {
            sensorRepo.sensorEvents.collect { reading ->
                _uiState.update { s ->
                    fun List<Float>.appendRolling(v: Float?) =
                        if (v != null) (this + v).takeLast(20) else this

                    s.copy(
                        heartRate        = reading.heartRate ?: s.heartRate,
                        spO2             = reading.spO2      ?: s.spO2,
                        skinTempC        = reading.skinTempC ?: s.skinTempC,
                        hrvRmssd         = reading.hrvRmssd  ?: s.hrvRmssd,
                        heartRateHistory = s.heartRateHistory.appendRolling(reading.heartRate?.toFloat()),
                        spO2History      = s.spO2History.appendRolling(reading.spO2?.toFloat()),
                        skinTempHistory  = s.skinTempHistory.appendRolling(reading.skinTempC)
                    )
                }
                envRepo.updateBandSensorAqi(reading.aqiRaw)
                recomputeRiskScores()
            }
        }
    }

    private fun observeConnectivity() {
        viewModelScope.launch {
            connectivityFlow(context).collect { online ->
                _uiState.update { it.copy(isOnline = online) }
                recomputeRiskScores()
            }
        }
    }

    private fun observeBattery() {
        viewModelScope.launch {
            sensorRepo.batteryPercent.collect { pct ->
                _uiState.update { it.copy(batteryPercent = pct) }
            }
        }
    }

    private fun observeWaterLog() {
        viewModelScope.launch {
            combine(waterRepo.lastLogMs, waterRepo.todayTotals) { last, today -> last to today.totalMl }
                .collect { (last, totalMl) ->
                    _uiState.update { it.copy(lastWaterLogMs = last, waterLogTotalMlToday = totalMl) }
                }
        }
    }

    private fun loadCachedEnv() {
        viewModelScope.launch {
            envRepo.getCachedSnapshot()?.let { cached ->
                _uiState.update { it.copy(env = cached) }
            }
            recomputeRiskScores()
            refreshEnvironment()
        }
    }

    /**
     * Fetches fresh environmental data. [force] skips the repository's
     * one-minute throttle - used by the explicit Refresh action. The only
     * progress signal is [DashboardUiState.isRefreshing], which is true for
     * exactly as long as the real request runs.
     */
    fun refreshEnvironment(force: Boolean = false) {
        viewModelScope.launch {
            if (!_uiState.value.isOnline || _uiState.value.isRefreshing) return@launch
            _uiState.update { it.copy(isRefreshing = true) }
            try {
                val fresh = envRepo.fetchAndCache(force = force)
                _uiState.update { it.copy(env = fresh) }
            } catch (e: Exception) {
                // Cached data stays on screen with its age stamp - failing to
                // refresh must never blank out a warning the user can still act on.
            } finally {
                _uiState.update { it.copy(isRefreshing = false) }
                recomputeRiskScores()
            }
        }
    }

    /** Logs a drink and immediately recomputes, so the score visibly responds. */
    fun logWaterIntake(amountMl: Int) {
        viewModelScope.launch {
            waterRepo.log(amountMl)
            recomputeRiskScores()
        }
    }

    /**
     * Recomputes every score from whatever data actually exists.
     *
     * There are NO defaults here. The previous version substituted HR 72,
     * SpO2 98 and skin temp 36.5 whenever the band was absent, so the app
     * displayed a confident "Heat Stress 82 - At risk" derived from vitals it
     * did not have, directly above tiles that honestly read "Waiting for
     * readings". Passing null lets the calculator report what it could and
     * could not measure instead of inventing the difference.
     */
    private suspend fun recomputeRiskScores() {
        val s = _uiState.value

        val baseStats    = sensorRepo.baselineStatsDao.getByType(MetricTypes.HR)
        val baselineDays = prefsRepo.daysOfData()
        val hoursSinceWater = waterRepo.hoursSinceLastLog()   // null when never logged

        val dataMode = when {
            s.hasBand && s.isOnline  -> DataMode.FULL
            s.hasBand && !s.isOnline -> DataMode.BAND_ONLY
            !s.hasBand && s.isOnline -> DataMode.ENVIRONMENT_ONLY
            else                     -> DataMode.CACHED
        }

        val env = s.env.takeIf { it.fetchedAtMs > 0L }

        val scores = calculator.computeAll(
            hr              = s.heartRate,
            spo2            = s.spO2,
            skinTempC       = s.skinTempC,
            hrvRmssd        = s.hrvRmssd,
            baselineHR      = baseStats?.mean,
            baselineHRStd   = baseStats?.stdDev,
            baselineDays    = baselineDays,
            env             = env,
            hoursSinceWater = hoursSinceWater,
            // Null means the pillar reports "needs data" rather than zero.
            // Recovery is derived inside the calculator from HRV and resting
            // HR; strain awaits the activity pipeline and stays honest until then.
            sleepScore      = sleepRepo.lastNightScore(),
            recoveryScore   = null,
            strainScore     = null,
            dataMode        = dataMode
        )

        // Personalised alert ranking. Only scores the app could actually
        // compute feed the vulnerability weighting.
        val ranked = AlertRanker.rank(
            alerts       = s.env.activeAlerts,
            riskScores   = scores,
            userDistrict = s.env.districtName,
            userState    = s.env.stateName,
            userLat      = s.env.latitude,
            userLon      = s.env.longitude
        ).let { AlertRanker.topRelevant(it) }

        val inference = ContextInferencer.infer(
            sensorAqi   = sensorRepo.bandSensorAqi.value,
            apiAqi      = s.env.regionalAqiValue,
            sensorTempC = null,
            apiTempC    = s.env.ambientTempC
        )

        // Status reflects hazards only - a low Readiness is not a health alarm.
        val worstHazard = scores
            .filter { !it.type.isWellness && it.availability == ScoreAvailability.AVAILABLE }
            .maxByOrNull { it.score }
        val status = when (worstHazard?.level) {
            RiskLevel.HIGH   -> HealthStatus.AT_RISK
            RiskLevel.MEDIUM -> HealthStatus.MONITOR
            else             -> HealthStatus.NORMAL
        }

        _uiState.update {
            it.copy(
                riskScores       = scores,
                rankedAlerts     = ranked,
                contextInference = inference.takeIf { inf -> inf.isConclusive },
                healthStatus     = status,
                dataMode         = dataMode,
                baselineDays     = baselineDays
            )
        }
    }

    /**
     * Records whether the indoor/outdoor call was right.
     *
     * Collecting this IS the feature: it is the on-device learning loop the
     * problem statement asks for, and it gives the ratio threshold in
     * ContextInferencer something real to be tuned against later. The tuning
     * itself is not implemented yet and the UI does not claim it is.
     */
    fun submitContextFeedback(wasCorrect: Boolean) {
        val inference = _uiState.value.contextInference ?: return
        viewModelScope.launch {
            runCatching {
                feedbackDao.insert(
                    ContextFeedbackEntity(
                        timestampMs     = System.currentTimeMillis(),
                        inferredContext = inference.context.name,
                        sensorAqi       = inference.sensorAqi,
                        apiAqi          = inference.apiAqi,
                        userSaidCorrect = wasCorrect
                    )
                )
            }
            _uiState.update { it.copy(contextFeedbackGiven = true) }
        }
    }

    fun baselineStageLabel(): String = BaselineSchedule.stageLabel(_uiState.value.baselineDays)
}
