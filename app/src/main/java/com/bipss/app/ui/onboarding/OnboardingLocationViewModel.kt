package com.bipss.app.ui.onboarding

import com.bipss.app.localization.AppText
import com.bipss.app.R

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bipss.app.data.UserPreferencesRepository
import com.bipss.app.data.environmental.CpcbAqiFetcher
import com.bipss.app.data.environmental.DisasterAlertRepository
import com.bipss.app.sos.LocationFix
import com.bipss.app.sos.LocationHelper
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt

/**
 * Location setup after the permission is granted: a short high-accuracy
 * calibration window (live accuracy on screen, stopping early once the fix is
 * within 50 m), then one real fetch of every local source, each reported as
 * it actually came back. There is no timer pretending to be progress: every
 * state on screen is something that is happening.
 */
class OnboardingLocationViewModel(
    private val locationHelper: LocationHelper,
    private val envRepo:        DisasterAlertRepository,
    private val cpcb:           CpcbAqiFetcher,
    private val prefs:          UserPreferencesRepository
) : ViewModel() {

    enum class SourceStatus { OK, NOT_AVAILABLE_HERE, FAILED }
    data class SourceResult(val name: String, val status: SourceStatus, val detail: String)

    sealed interface Phase {
        object Idle : Phase
        data class Calibrating(val accuracyM: Int?, val approximate: Boolean) : Phase
        object Fetching : Phase
        data class Done(val sources: List<SourceResult>, val approximate: Boolean) : Phase {
            val anyFailed: Boolean get() = sources.any { it.status == SourceStatus.FAILED }
        }
    }

    private val _phase = MutableStateFlow<Phase>(Phase.Idle)
    val phase: StateFlow<Phase> = _phase.asStateFlow()

    private var job: Job? = null

    fun startIfIdle() { if (_phase.value is Phase.Idle) start() }

    /** Runs calibration then fetch-and-verify. Safe to call again for Retry. */
    fun start() {
        if (job?.isActive == true) return
        job = viewModelScope.launch {
            // This screen is the setup a later grant is waiting for.
            prefs.setLocationSetupPending(false)
            val approximate = !locationHelper.hasFinePermission()
            _phase.value = Phase.Calibrating(null, approximate)

            var best: LocationFix? = null
            var fixes = 0
            val calibrationStart = System.currentTimeMillis()
            withTimeoutOrNull(CALIBRATION_MAX_MS) {
                locationHelper.calibrationFixes()
                    // Good enough: stop early (after showing that fix) rather than run out the clock.
                    .transformWhile { fix -> emit(fix); (fix.accuracyM ?: Float.MAX_VALUE) > GOOD_ENOUGH_M }
                    .collect { fix ->
                        fixes++
                        if (best == null || (fix.accuracyM ?: Float.MAX_VALUE) < (best?.accuracyM ?: Float.MAX_VALUE)) best = fix
                        _phase.value = Phase.Calibrating(best?.accuracyM?.roundToInt(), approximate)
                    }
            }
            Log.d(TAG, "Calibration: $fixes fix(es), best ±${best?.accuracyM?.roundToInt()} m, " +
                "${System.currentTimeMillis() - calibrationStart} ms")
            best?.let { fix ->
                val (district, state) = locationHelper.resolveDistrictAndState(fix.lat, fix.lon)
                prefs.cacheLocation(fix.lat, fix.lon, district, state, fix.timeMs)
            }

            _phase.value = Phase.Fetching
            val snapshot = withTimeoutOrNull(FETCH_TIMEOUT_MS) { envRepo.fetchAndCache(force = true) }
            _phase.value = Phase.Done(
                sources = if (snapshot == null) {
                    // The sync as a whole ran out of time; which sources had
                    // answered by then is not known, so none is claimed.
                    listOf(AppText.text(R.string.copy_a0bba6381246, "Weather"), AppText.text(R.string.copy_0e9cbafcdd80, "Air quality"), AppText.text(R.string.copy_57192b3add92, "Official alerts")).map {
                        SourceResult(it, SourceStatus.FAILED, AppText.text(R.string.copy_6e8a355058ca, "Didn't finish within %1\$s seconds", FETCH_TIMEOUT_MS / 1000))
                    }
                } else listOf(
                    weather(snapshot.ambientTempC),
                    airQuality(snapshot),
                    alerts(!snapshot.alertCheckFailed && snapshot.alertsCheckedAtMs > 0, snapshot.activeAlerts.size)
                ),
                approximate = approximate
            )
        }
    }

    private fun weather(tempC: Float?) = if (tempC != null)
        SourceResult(AppText.text(R.string.copy_a0bba6381246, "Weather"), SourceStatus.OK, AppText.text(R.string.copy_e0f57f147fcc, "%1\$s °C now", tempC.roundToInt()))
    else SourceResult(AppText.text(R.string.copy_a0bba6381246, "Weather"), SourceStatus.FAILED, AppText.text(R.string.copy_741bf6f832e3, "The weather service isn't responding right now"))

    private fun airQuality(s: com.bipss.app.domain.environmental.EnvironmentalSnapshot): SourceResult = when {
        s.aqiValue != null && s.aqiSource == com.bipss.app.domain.environmental.AqiSource.CPCB ->
            SourceResult(AppText.text(R.string.copy_0e9cbafcdd80, "Air quality"), SourceStatus.OK,
                AppText.text(R.string.copy_85e865f05ac0, "AQI %1\$s at %2\$s", s.aqiValue, s.aqiStationName ?: "the nearest station") +
                    (s.aqiStationDistanceKm?.let { AppText.text(R.string.copy_71236f96c7a0, ", %1\$s km away", it.roundToInt()) } ?: ""))
        // No station reading: the CAMS model, said plainly.
        s.aqiValue != null && s.aqiSource == com.bipss.app.domain.environmental.AqiSource.MODEL ->
            SourceResult(AppText.text(R.string.copy_0e9cbafcdd80, "Air quality"), SourceStatus.OK,
                AppText.text(R.string.copy_5892f64ac98c, "Estimated AQI %1\$s for your area", s.aqiValue))
        else -> when (cpcb.lastOutcome) {
            CpcbAqiFetcher.Outcome.NO_STATION_NEARBY ->
                SourceResult(AppText.text(R.string.copy_0e9cbafcdd80, "Air quality"), SourceStatus.NOT_AVAILABLE_HERE, AppText.text(R.string.copy_ac83a1a82620, "No government monitoring station within 50 km"))
            CpcbAqiFetcher.Outcome.NOT_CONFIGURED ->
                SourceResult(AppText.text(R.string.copy_0e9cbafcdd80, "Air quality"), SourceStatus.NOT_AVAILABLE_HERE, AppText.text(R.string.copy_db6470ad34bc, "Air quality is unavailable right now"))
            CpcbAqiFetcher.Outcome.OK ->
                // A station answered but reported too few pollutants for an official index.
                SourceResult(AppText.text(R.string.copy_0e9cbafcdd80, "Air quality"), SourceStatus.NOT_AVAILABLE_HERE, AppText.text(R.string.copy_2f6ecf61fed2, "The nearest station isn't reporting a full index right now"))
            else -> SourceResult(AppText.text(R.string.copy_0e9cbafcdd80, "Air quality"), SourceStatus.FAILED, AppText.text(R.string.copy_af881ee23b7d, "The government air-quality service isn't responding right now"))
        }
    }

    private fun alerts(checked: Boolean, count: Int) = if (checked)
        SourceResult(AppText.text(R.string.copy_57192b3add92, "Official alerts"), SourceStatus.OK,
            if (count == 0) AppText.text(R.string.copy_45e5b33530f0, "None active for your area") else AppText.text(R.string.copy_04a8e1ad7688, "%1\$s active for your area", count))
    else SourceResult(AppText.text(R.string.copy_57192b3add92, "Official alerts"), SourceStatus.FAILED, AppText.text(R.string.copy_f07659c98d57, "The alert feeds aren't responding right now"))

    private companion object {
        const val TAG = "OnboardingLocation"
        const val CALIBRATION_MAX_MS = 5_000L
        const val GOOD_ENOUGH_M = 50f
        const val FETCH_TIMEOUT_MS = 20_000L
    }
}
