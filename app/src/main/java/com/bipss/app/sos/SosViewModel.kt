package com.bipss.app.sos

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bipss.app.data.UserPreferencesRepository
import com.bipss.app.data.db.dao.EmergencyContactDao
import com.bipss.app.data.db.dao.SosEventDao
import com.bipss.app.data.db.entity.SosEventEntity
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

sealed class SosUiState {
    object Idle : SosUiState()
    data class Countdown(
        val secondsLeft:  Int,
        val totalSeconds: Int             = SOS_COUNTDOWN_SECONDS,
        val source:       SosTriggerSource = SosTriggerSource.MANUAL
    ) : SosUiState() {
        val fraction: Float get() = secondsLeft.toFloat() / totalSeconds.toFloat()
    }
    object FetchingLocation : SosUiState()
    object SendingSms       : SosUiState()
    data class Sent(val recipientCount: Int, val locationIncluded: Boolean) : SosUiState()
    data class FalseAlarm(val cancelledAtSecond: Int) : SosUiState()
    data class Error(val message: String) : SosUiState()
}

class SosViewModel(
    private val emergencyContactDao: EmergencyContactDao,
    private val sosEventDao:         SosEventDao,
    private val locationHelper:      LocationHelper,
    private val smsSender:           SmsSender,
    private val prefsRepo:           UserPreferencesRepository
) : ViewModel() {

    private val _state = MutableStateFlow<SosUiState>(SosUiState.Idle)
    val state: StateFlow<SosUiState> = _state.asStateFlow()

    private var countdownJob: Job? = null
    private var triggerStartMs: Long = 0L

    /**
     * Primary trigger entry point.
     * Manual: called by SOS FAB on DashboardTodayScreen.
     *
     * TODO: Also called by fall-detection ML classifier once integrated.
     * See [SosConstants] for integration sketch.
     */
    fun triggerSos(source: SosTriggerSource = SosTriggerSource.MANUAL) {
        if (_state.value !is SosUiState.Idle) return

        triggerStartMs = System.currentTimeMillis()
        countdownJob = viewModelScope.launch {
            for (remaining in SOS_COUNTDOWN_SECONDS downTo 1) {
                _state.value = SosUiState.Countdown(remaining, source = source)
                delay(1_000L)
                if (_state.value !is SosUiState.Countdown) return@launch
            }
            dispatchSos(source)
        }
    }

    fun cancelSos() {
        val current = _state.value as? SosUiState.Countdown ?: return
        countdownJob?.cancel()
        countdownJob = null
        val cancelledAt = current.secondsLeft
        _state.value = SosUiState.FalseAlarm(cancelledAt)

        viewModelScope.launch {
            sosEventDao.insert(SosEventEntity(
                triggeredAtMs  = triggerStartMs,
                source         = current.source.name,
                outcome        = SosOutcome.CANCELLED.name,
                lat            = null, lng = null, recipientCount = 0,
                notes          = "Cancelled at $cancelledAt s remaining"
            ))
            delay(2_000L)
            _state.value = SosUiState.Idle
        }
    }

    fun dismissResult() { _state.value = SosUiState.Idle }

    private suspend fun dispatchSos(source: SosTriggerSource) {
        val contacts = emergencyContactDao.all().first()
        if (contacts.isEmpty()) {
            _state.value = SosUiState.Error("No emergency contacts stored. Add contacts in Settings.")
            sosEventDao.insert(SosEventEntity(triggeredAtMs = triggerStartMs, source = source.name, outcome = SosOutcome.NO_CONTACTS.name, lat = null, lng = null, recipientCount = 0))
            return
        }

        _state.value = SosUiState.FetchingLocation
        val location = locationHelper.getCurrentLocation()

        _state.value = SosUiState.SendingSms
        val userName  = prefsRepo.userProfile.first()?.name ?: "A BIPSS user"
        val timestamp = SimpleDateFormat("dd MMM yyyy, HH:mm z", Locale.ENGLISH)
                            .format(Date(triggerStartMs))

        val sentCount = smsSender.sendSosMessages(
            contacts  = contacts,
            userName  = userName,
            lat       = location?.first,
            lng       = location?.second,
            timestamp = timestamp
        )

        val outcome = if (sentCount > 0) {
            if (location == null) SosOutcome.LOCATION_DENIED else SosOutcome.SENT
        } else SosOutcome.SEND_FAILED

        sosEventDao.insert(SosEventEntity(
            triggeredAtMs  = triggerStartMs,
            source         = source.name,
            outcome        = outcome.name,
            lat            = location?.first,
            lng            = location?.second,
            recipientCount = sentCount
        ))

        _state.value = SosUiState.Sent(recipientCount = sentCount, locationIncluded = location != null)
    }
}
