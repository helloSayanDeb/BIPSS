package com.bipss.app.ui.dashboard

import com.bipss.app.localization.AppText
import com.bipss.app.R

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
import com.bipss.app.domain.environmental.AqiDisplay
import com.bipss.app.domain.environmental.EnvContext
import com.bipss.app.domain.environmental.EnvContextKind
import com.bipss.app.domain.environmental.Reconciliation
import com.bipss.app.domain.environmental.EnvironmentalSnapshot
import com.bipss.app.domain.environmental.RankedAlert
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

enum class HealthStatus { NORMAL, MONITOR, AT_RISK }

/** Median RMSSD over clean, low-motion epochs - the PRV figure worth featuring. */
data class RestingPrv(val rmssdMs: Int, val minutes: Int, val latestMs: Long)

data class DashboardUiState(
    /** Null means no reading. Never a substituted value. */
    val heartRate:        Int?                   = null,
    val spO2:             Int?                   = null,
    val skinTempC:        Float?                 = null,
    val prvRmssd:         Int?                   = null,
    /** Heart rate is the phone pipeline's (v2 band), not the band's own figure. */
    val hrFromPipeline:   Boolean                = false,
    /** Resting PRV from clean, still epochs; null until there are enough. */
    val restingPrv:       RestingPrv?            = null,
    val stepCount:        Int?                   = null,
    val batteryPercent:   Int?                   = null,

    val heartRateHistory: List<Float>            = emptyList(),
    val spO2History:      List<Float>            = emptyList(),
    val skinTempHistory:  List<Float>            = emptyList(),

    val riskScores:       List<RiskScore>        = emptyList(),
    val rankedAlerts:     List<RankedAlert>      = emptyList(),
    /** Indoors or out, and which air reading to trust (spec 5.9). */
    val airContext:       AirContextUi?          = null,
    val healthStatus:     HealthStatus           = HealthStatus.NORMAL,
    val env:              EnvironmentalSnapshot  = EnvironmentalSnapshot.EMPTY,

    val dataMode:         DataMode               = DataMode.CACHED,
    /** Null until the first scoring load; never a placeholder zero. */
    val baselineDays:     Int?                   = null,
    val lastWaterLogMs:   Long?                  = null,
    val waterLogTotalMlToday: Int                = 0,
    /** Set once the user has answered the indoor/outdoor question this session. */
    val contextFeedbackGiven: Boolean            = false,

    val isOnline:         Boolean                = true,
    val isRefreshing:     Boolean                = false,

    /** A band is connected, whether or not it has a measured heart rate right now. */
    val bandConnected:    Boolean                = false
) {
    val hasBand: Boolean get() = bandConnected

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
                .filter { it.type.isHazard && it.type != RiskScoreType.FLOOD && it.hasNumber }
                .maxByOrNull { it.score }
            ?: riskScores.firstOrNull { it.type == RiskScoreType.READINESS }

    val wellnessPillars: List<RiskScore>
        get() = riskScores.filter { it.type.isWellness && it.type != RiskScoreType.READINESS }

    val hazardDomains: List<RiskScore>
        get() = riskScores.filter { it.type.isHazard }

    /** Experimental indicators: shown apart from the hazards, never ranked with them. */
    val experimentalScores: List<RiskScore>
        get() = riskScores.filter { it.type.category == com.bipss.app.domain.ScoreCategory.EXPERIMENTAL }
}

/**
 * The latest indoor/outdoor check and the air-quality display it decides.
 * [context] is null once the check is too old to describe now.
 */
data class AirContextUi(
    val context:        EnvContext?,
    val ageMs:          Long?,
    val display:        AqiDisplay,
    val reconciliation: Reconciliation,
    /** The readings stored with the check, recorded with any correction the user gives. */
    val checkBandUba:   Int?,
    val checkCpcbAqi:   Int?
)

class DashboardViewModel(
    private val sensorRepo: SensorRepository,
    private val waterRepo:  com.bipss.app.data.WaterRepository,
    private val envRepo:    DisasterAlertRepository,
    private val prefsRepo:  UserPreferencesRepository,
    private val sleepRepo:  SleepRepository,
    private val feedbackDao: ContextFeedbackDao,
    private val calculator: RiskScoreCalculator,
    private val signalEpochDao: com.bipss.app.data.db.dao.SignalEpochDao,
    private val scoring:    com.bipss.app.data.ScoringRepository,
    private val aqiContext: com.bipss.app.data.environmental.AqiContextRepository,
    private val context:    Context,
    private val dailyActivity: com.bipss.app.data.DailyActivityRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(DashboardUiState())
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()

    // Declared before init: init starts collectors that can call
    // recomputeRiskScores() immediately (a replayed reading), and Kotlin runs
    // property initialisers in declaration order.
    // Null until the first load completes: until then the scores are simply
    // not shown, rather than computed from placeholder "day 0, no night" inputs.
    @Volatile private var scoringInputs: com.bipss.app.data.ScoringRepository.Snapshot? = null
    private var lastDayHealth: com.bipss.app.data.DayHealth? = null
    private var lastHealthDay: Long? = null

    init {
        observeSensorData()
        observeConnectivity()
        observeBattery()
        observeWaterLog()
        observeRestingPrv()
        observeScoringInputs()
        observeBandConnection()
        loadCachedEnv()
        observeAirContext()
    }

    /**
     * Follows the latest indoor/outdoor check (taken by the band service) and
     * the band's air reading; re-decided every minute, so an old check stops
     * counting as "now". The reconciliation is rebuilt when a new check lands.
     */
    private fun observeAirContext() {
        viewModelScope.launch {
            var rec = Reconciliation.EMPTY
            var recFor: Long? = -1L
            val ticks = flow { while (true) { emit(Unit); kotlinx.coroutines.delay(60_000L) } }
            combine(aqiContext.latest, sensorRepo.bandSensorAqi, ticks) { latest, uba, _ -> latest to uba }
                .collect { (latest, uba) ->
                    if (latest?.id != recFor) {
                        rec = runCatching { aqiContext.reconciliation() }.getOrDefault(Reconciliation.EMPTY)
                        recFor = latest?.id
                    }
                    val now = System.currentTimeMillis()
                    val age = latest?.let { now - it.takenAtMs }
                    val context = latest?.takeIf { age!! <= AqiDisplay.CONTEXT_MAX_AGE_MS }?.let {
                        EnvContext(
                            kind = runCatching { EnvContextKind.valueOf(it.context) }.getOrDefault(EnvContextKind.UNKNOWN),
                            confidence = it.confidence,
                            evidence = it.evidence.split('\n').filter { line -> line.isNotBlank() }
                        )
                    }
                    val display = AqiDisplay.decide(context, age, uba, _uiState.value.env.aqiValue, rec)
                    _uiState.update {
                        it.copy(airContext = AirContextUi(context, age, display, rec, latest?.bandUba, latest?.cpcbAqi))
                    }
                }
        }
    }

    /**
     * PRV varies with posture, breathing and movement, so a live value mostly
     * reflects what you are doing. The featured figure is the median of the
     * last day's clean, still epochs instead - refreshed once a minute.
     */
    private fun observeRestingPrv() {
        viewModelScope.launch {
            while (true) {
                val since = System.currentTimeMillis() - 24 * 3_600_000L
                val epochs = runCatching {
                    signalEpochDao.restingPrvEpochs(since, com.bipss.app.domain.signal.SignalConfig.MOTION_FREEZE_G2, 40)
                }.getOrDefault(emptyList())
                val values = epochs.mapNotNull { it.rmssdMs }.sorted()
                val summary = if (values.size >= MIN_RESTING_PRV_EPOCHS) RestingPrv(
                    rmssdMs  = kotlin.math.round(
                        if (values.size % 2 == 1) values[values.size / 2]
                        else (values[values.size / 2 - 1] + values[values.size / 2]) / 2
                    ).toInt(),
                    minutes  = values.size / 2,
                    latestMs = epochs.maxOf { it.startMs }
                ) else null
                _uiState.update { it.copy(restingPrv = summary) }
                kotlinx.coroutines.delay(60_000L)
            }
        }
    }

    /**
     * Strain, recovery, the Autonomic Load Indicator and the baseline day are
     * database work, so they are refreshed once a minute and cached here -
     * not recomputed on every 1 Hz reading.
     */
    private fun observeScoringInputs() {
        viewModelScope.launch {
            while (true) {
                runCatching { scoringInputs = loadScoringInputs() }
                    .onFailure { android.util.Log.w(AppText.text(R.string.copy_019fdbaec6de, "DashboardViewModel"), AppText.text(R.string.copy_5afa4f7edd47, "Scoring inputs failed"), it) }
                recomputeRiskScores()
                kotlinx.coroutines.delay(60_000L)
            }
        }
    }

    private suspend fun loadScoringInputs() = scoring.snapshot()

    private fun observeBandConnection() {
        viewModelScope.launch {
            sensorRepo.bandConnected.collect { c -> _uiState.update { it.copy(bandConnected = c) } }
        }
    }

    private fun observeSensorData() {
        viewModelScope.launch {
            sensorRepo.sensorEvents.collect { reading ->
                _uiState.update { s ->
                    fun List<Float>.appendRolling(v: Float?) =
                        if (v != null) (this + v).takeLast(20) else this

                    // From the pipeline a missing value means "no measured value
                    // right now" (poor signal, off the skin): show a dash, never
                    // the last number under a Production badge.
                    val fromPipeline = reading.pulseSource == com.bipss.app.ble.PulseSource.PIPELINE
                    s.copy(
                        heartRate        = if (fromPipeline) reading.heartRate else reading.heartRate ?: s.heartRate,
                        spO2             = reading.spO2      ?: s.spO2,
                        skinTempC        = reading.skinTempC ?: s.skinTempC,
                        prvRmssd         = if (fromPipeline) reading.prvRmssdMs else reading.prvRmssdMs ?: s.prvRmssd,
                        hrFromPipeline   = fromPipeline,
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
                    recomputeRiskScores()
                }
        }
    }

    /**
     * The environment shown is always the cache, followed as it changes: a
     * sync from any screen or the background job appears everywhere, and a
     * cache cleared because location was turned off empties the scores that
     * depended on it instead of leaving them on screen.
     */
    private fun loadCachedEnv() {
        viewModelScope.launch {
            envRepo.cachedSnapshots.collect { snapshot ->
                _uiState.update { it.copy(env = snapshot) }
                recomputeRiskScores()
            }
        }
        refreshEnvironment()
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
                // The result reaches the screen through the cache (loadCachedEnv).
                envRepo.fetchAndCache(force = force)
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

        val inputs = scoringInputs ?: return
        val baselineDays = inputs.personal.dayN
        val hoursSinceWater = waterRepo.hoursSinceLastLog()   // null when never logged

        val dataMode = when {
            s.hasBand && s.isOnline  -> DataMode.FULL
            s.hasBand && !s.isOnline -> DataMode.BAND_ONLY
            !s.hasBand && s.isOnline -> DataMode.ENVIRONMENT_ONLY
            else                     -> DataMode.CACHED
        }

        val env = s.env.takeIf { it.fetchedAtMs > 0L }

        val currentDay = com.bipss.app.domain.time.LocalDay.epochDay(System.currentTimeMillis())
        val latestSleep = sleepRepo.sessionsNewestLast().filter {
            com.bipss.app.domain.time.LocalDay.epochDay(it.endMs) == currentDay
        }.maxByOrNull { it.totalSleepMs }
        val scores = calculator.computeAll(
            hr              = s.heartRate,
            spo2            = s.spO2,
            skinTempC       = s.skinTempC,
            personal        = inputs.personal,
            env             = env,
            hoursSinceWater = hoursSinceWater,
            sleepScore      = latestSleep?.let(com.bipss.app.data.SleepRepository::score),
            recovery        = inputs.recovery,
            strain          = inputs.strain,
            ali             = inputs.ali,
            dataMode        = dataMode
        ).map { score ->
            if (score.type != RiskScoreType.SLEEP || !score.hasNumber || latestSleep == null) score else score.copy(inputs = listOf(
                com.bipss.app.domain.InsightInput("duration", AppText.text(R.string.copy_f44280e29037, "Time asleep"), latestSleep.totalSleepMs / 60000.0, "min", AppText.text(R.string.copy_1751830938cb, "60% of the score, up to 8 hours")),
                com.bipss.app.domain.InsightInput("efficiency", AppText.text(R.string.copy_9a3252beb466, "Sleep efficiency"), latestSleep.efficiencyPct.toDouble(), "%", AppText.text(R.string.copy_187c4c07be68, "40% of the score")),
                com.bipss.app.domain.InsightInput("interruptions", AppText.text(R.string.copy_5f1f15d3b632, "Interruptions"), latestSleep.awakenings.toDouble(), detail = AppText.text(R.string.copy_110e4664d1fc, "Supporting sleep context")),
                com.bipss.app.domain.InsightInput("awake", AppText.text(R.string.copy_6da9a0e51e28, "Awake after falling asleep"), latestSleep.wakeAfterOnsetMs / 60000.0, "min", AppText.text(R.string.copy_110e4664d1fc, "Supporting sleep context")),
                com.bipss.app.domain.InsightInput("session", AppText.text(R.string.copy_74d324f6bca8, "Sleep ended"), isNumeric = false, detail = java.time.Instant.ofEpochMilli(latestSleep.endMs)
                    .atZone(java.time.ZoneId.systemDefault()).format(java.time.format.DateTimeFormatter.ofPattern("d MMM, h:mm a")))
            ))
        }
        dailyActivity.recordInsights(currentDay, scores.map(com.bipss.app.domain.InsightRecord::from))
        fun value(type: RiskScoreType) = scores.firstOrNull { it.type == type && it.hasNumber }
        val health = com.bipss.app.data.DayHealth(value(RiskScoreType.SLEEP)?.score,
            value(RiskScoreType.RECOVERY)?.score, value(RiskScoreType.STRAIN)?.valueLabel?.let(com.bipss.app.localization.LocalNumbers::floatOrNull))
        if (lastDayHealth != health || lastHealthDay != currentDay) {
            dailyActivity.recordHealth(currentDay, health)
            lastDayHealth = health
            lastHealthDay = currentDay
        }

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

        // Status reflects hazards only - a low Readiness is not a health
        // alarm, and the experimental Autonomic Load Indicator never drives it.
        val worstHazard = scores
            .filter { it.type.isHazard && it.availability == ScoreAvailability.AVAILABLE }
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
                healthStatus     = status,
                dataMode         = dataMode,
                baselineDays     = baselineDays
            )
        }
    }

    /**
     * Records whether the indoor/outdoor call was right. These corrections are
     * what the GNSS thresholds get tuned against later; the tuning itself is
     * not implemented and the UI does not claim it is.
     */
    fun submitContextFeedback(wasCorrect: Boolean) {
        val air = _uiState.value.airContext ?: return
        val context = air.context ?: return
        viewModelScope.launch {
            runCatching {
                feedbackDao.insert(
                    ContextFeedbackEntity(
                        timestampMs     = System.currentTimeMillis(),
                        inferredContext = context.kind.name,
                        sensorAqi       = air.checkBandUba,
                        apiAqi          = air.checkCpcbAqi,
                        userSaidCorrect = wasCorrect
                    )
                )
            }
            _uiState.update { it.copy(contextFeedbackGiven = true) }
        }
    }

    fun baselineStageLabel(): String = _uiState.value.baselineDays?.let { BaselineSchedule.stageLabel(it) } ?: ""
}

/** Four epochs = two minutes of clean, still signal. */
private const val MIN_RESTING_PRV_EPOCHS = 4
