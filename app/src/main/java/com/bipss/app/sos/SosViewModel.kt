package com.bipss.app.sos

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bipss.app.data.UserPreferencesRepository
import com.bipss.app.data.db.dao.EmergencyContactDao
import com.bipss.app.data.db.dao.SosEventDao
import com.bipss.app.data.db.entity.SosEventEntity
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.text.SimpleDateFormat
import java.util.*

/** Something that will stop an SOS from reaching anyone unless it is fixed before the countdown ends. */
enum class SosBlocker { NO_SMS_PERMISSION, NO_CONTACTS, NO_TELEPHONY }

/** Why an SOS ended without any message leaving the phone. */
enum class SosNotSentReason { NO_SMS_PERMISSION, NO_CONTACTS, NO_TELEPHONY, ALL_SENDS_FAILED }

sealed class SosUiState {
    object Idle : SosUiState()
    data class Countdown(
        val secondsLeft:  Int,
        val totalSeconds: Int              = SOS_COUNTDOWN_SECONDS,
        val source:       SosTriggerSource = SosTriggerSource.MANUAL,
        /** Re-checked every second, so granting SMS mid-countdown clears the warning. */
        val blockers:     Set<SosBlocker>  = emptySet(),
        val contactCount: Int              = 0,
        /** Whether any location permission is granted, i.e. whether the SMS can say where the user is. */
        val locationAllowed: Boolean       = true
    ) : SosUiState() {
        val fraction: Float get() = secondsLeft.toFloat() / totalSeconds.toFloat()
    }
    object Sending : SosUiState()
    /**
     * At least one message was confirmed sent by the radio, or at least one is
     * still unconfirmed (handed over, no verdict yet). Never shown for a run
     * where every message definitely failed - that is [NotSent].
     */
    data class Sent(
        val sentCount:         Int,
        val totalCount:        Int,
        val failedNames:       List<String>,
        val unconfirmedNames:  List<String>,
        val locationIncluded:  Boolean,
        val locationLastKnown: Boolean,
        val locationAgeMinutes: Long? = null
    ) : SosUiState()
    /** Nothing left the phone. Never presented as success. */
    data class NotSent(val reason: SosNotSentReason, val detail: String? = null) : SosUiState()
    data class FalseAlarm(val cancelledAtSecond: Int) : SosUiState()
}

/**
 * The SOS state machine: countdown, cancel window, location, SMS, audit log.
 *
 * Tapping SOS starts the countdown **immediately and unconditionally**. The
 * previous version first raised the SMS permission dialog instead, so a user's
 * first tap in an emergency started nothing. Anything missing (SMS permission,
 * emergency contacts) is shown inside the countdown with a way to fix it and a
 * dial-112 fallback, rather than blocking the tap. Location is requested the
 * moment the countdown starts, not after it ends, so the fix has the full 15
 * seconds to arrive.
 */
class SosViewModel(
    private val emergencyContactDao: EmergencyContactDao,
    private val sosEventDao:         SosEventDao,
    private val locationHelper:      LocationHelper,
    private val smsSender:           SmsSender,
    private val prefsRepo:           UserPreferencesRepository
) : ViewModel() {

    private val _state = MutableStateFlow<SosUiState>(SosUiState.Idle)
    val state: StateFlow<SosUiState> = _state.asStateFlow()

    private val _readiness = MutableStateFlow<Set<SosBlocker>>(emptySet())
    /**
     * What would stop an SOS right now, for the persistent Home warning.
     * Contacts are observed; SMS permission cannot be, so the screen calls
     * [refreshReadiness] when it resumes.
     */
    val readiness: StateFlow<Set<SosBlocker>> = _readiness.asStateFlow()

    init {
        viewModelScope.launch {
            emergencyContactDao.all().collect { refreshReadiness() }
        }
    }

    fun refreshReadiness() {
        viewModelScope.launch { _readiness.value = checkBlockers().first }
    }

    private var countdownJob: Job? = null
    private var locationJob: Deferred<LocationFix?>? = null
    private var triggerStartMs: Long = 0L

    /**
     * Primary trigger entry point, called by the SOS button.
     *
     * TODO: Also called by a fall-detection classifier once one exists.
     * See [SosConstants] for the integration sketch.
     */
    fun triggerSos(source: SosTriggerSource = SosTriggerSource.MANUAL) {
        // Only an SOS already in flight blocks a new one; a finished or failed
        // one never leaves the button dead.
        if (_state.value is SosUiState.Countdown || _state.value is SosUiState.Sending) return
        // Claim the state synchronously: a double tap must not start two
        // countdowns (and send the SMS twice) before the coroutine's first tick.
        _state.value = SosUiState.Countdown(SOS_COUNTDOWN_SECONDS, source = source,
            locationAllowed = locationHelper.hasAnyPermission())

        triggerStartMs = System.currentTimeMillis()
        locationJob = viewModelScope.async { locationHelper.getCurrentFix(timeoutMs = 20_000L) }
        countdownJob = viewModelScope.launch {
            for (remaining in SOS_COUNTDOWN_SECONDS downTo 1) {
                val (blockers, contacts) = checkBlockers()
                _state.value = SosUiState.Countdown(
                    remaining, source = source, blockers = blockers, contactCount = contacts,
                    locationAllowed = locationHelper.hasAnyPermission()
                )
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
        locationJob?.cancel()
        locationJob = null
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
            if (_state.value is SosUiState.FalseAlarm) _state.value = SosUiState.Idle
        }
    }

    fun dismissResult() { _state.value = SosUiState.Idle }

    private suspend fun checkBlockers(): Pair<Set<SosBlocker>, Int> {
        val contacts = emergencyContactDao.all().first().size
        val blockers = buildSet {
            when (smsSender.capability()) {
                SmsCapability.NO_TELEPHONY  -> add(SosBlocker.NO_TELEPHONY)
                SmsCapability.NO_PERMISSION -> add(SosBlocker.NO_SMS_PERMISSION)
                SmsCapability.OK            -> Unit
            }
            if (contacts == 0) add(SosBlocker.NO_CONTACTS)
        }
        return blockers to contacts
    }

    private suspend fun dispatchSos(source: SosTriggerSource) {
        val (blockers, _) = checkBlockers()
        val blockedBy = when {
            SosBlocker.NO_TELEPHONY in blockers      -> SosNotSentReason.NO_TELEPHONY
            SosBlocker.NO_CONTACTS in blockers       -> SosNotSentReason.NO_CONTACTS
            SosBlocker.NO_SMS_PERMISSION in blockers -> SosNotSentReason.NO_SMS_PERMISSION
            else -> null
        }
        if (blockedBy != null) {
            locationJob?.cancel()
            sosEventDao.insert(SosEventEntity(
                triggeredAtMs = triggerStartMs, source = source.name,
                outcome = when (blockedBy) {
                    SosNotSentReason.NO_CONTACTS       -> SosOutcome.NO_CONTACTS
                    SosNotSentReason.NO_SMS_PERMISSION -> SosOutcome.NO_SMS_PERMISSION
                    else                               -> SosOutcome.NO_TELEPHONY
                }.name,
                lat = null, lng = null, recipientCount = 0
            ))
            _state.value = SosUiState.NotSent(blockedBy)
            return
        }

        _state.value = SosUiState.Sending
        val location = resolveLocation()
        val contacts = emergencyContactDao.all().first()
        val userName  = prefsRepo.userProfile.first()?.name?.takeIf { it.isNotBlank() } ?: "A BIPSS user"
        val timestamp = SimpleDateFormat("dd MMM yyyy, HH:mm z", Locale.ENGLISH).format(Date(triggerStartMs))

        val body = SosMessageBuilder.contactMessage(userName, timestamp, location)
        val results = smsSender.send(contacts.map { SmsRecipient(it.name, it.phoneNumber) }, body)
        val sent = results.count { it.sent }
        val unconfirmed = results.count { it.unconfirmed }

        val outcome = when {
            sent == 0 && unconfirmed == 0 -> SosOutcome.SEND_FAILED
            sent == 0                     -> SosOutcome.UNCONFIRMED
            sent < results.size           -> SosOutcome.PARTIALLY_SENT
            location == null              -> SosOutcome.LOCATION_DENIED
            else                          -> SosOutcome.SENT
        }
        sosEventDao.insert(SosEventEntity(
            triggeredAtMs  = triggerStartMs,
            source         = source.name,
            outcome        = outcome.name,
            lat            = location?.lat,
            lng            = location?.lng,
            recipientCount = sent,
            notes          = results.filterNot { it.sent }
                .joinToString("; ") { "${it.recipient.name}: ${it.error}" }
        ))

        _state.value = if (sent == 0 && unconfirmed == 0) {
            SosUiState.NotSent(SosNotSentReason.ALL_SENDS_FAILED, results.firstOrNull()?.error)
        } else {
            SosUiState.Sent(
                sentCount          = sent,
                totalCount         = results.size,
                failedNames        = results.filter { !it.sent && !it.unconfirmed }.map { it.recipient.name },
                unconfirmedNames   = results.filter { it.unconfirmed }.map { it.recipient.name },
                locationIncluded   = location != null,
                locationLastKnown  = location?.isLastKnown == true,
                locationAgeMinutes = location?.ageMinutes
            )
        }
    }

    /**
     * The fix requested at countdown start, given a few more seconds if still
     * pending; failing that, the last cached position - but only while
     * location permission is still granted, only if it is under a day old
     * (the same limit the environment sync applies), and labelled with its age.
     */
    private suspend fun resolveLocation(): SosLocation? {
        val fix = withTimeoutOrNull(5_000L) { locationJob?.await() }
        locationJob = null
        if (fix != null) return SosLocation(fix.lat, fix.lon, isLastKnown = false, isApproximate = fix.isApproximate)
        if (!locationHelper.hasAnyPermission()) return null
        val cached = prefsRepo.cachedLocation.first() ?: return null
        val atMs = cached.atMs ?: return null
        val ageMs = triggerStartMs - atMs
        if (ageMs !in 0..MAX_LAST_KNOWN_AGE_MS) return null
        return SosLocation(
            cached.lat, cached.lon, isLastKnown = true,
            isApproximate = !locationHelper.hasFinePermission(),
            ageMinutes = ageMs / 60_000L
        )
    }

    private companion object {
        const val MAX_LAST_KNOWN_AGE_MS = 24 * 3_600_000L
    }
}
