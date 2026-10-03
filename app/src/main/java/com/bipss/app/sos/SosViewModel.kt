package com.bipss.app.sos

import com.bipss.app.localization.AppText
import com.bipss.app.R

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
        val locationAllowed: Boolean       = true,
        val deadlineElapsedMs: Long = android.os.SystemClock.elapsedRealtime() + totalSeconds * 1000L
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
        val locationAgeMinutes: Long? = null,
        /** One plain line per hospital (or why there are none), as it actually went. */
        val hospitalLines:     List<String> = emptyList()
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
    private val prefsRepo:           UserPreferencesRepository,
    private val hospitalLocator:     HospitalLocator
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
    /** Started with the countdown, so the nearest hospitals are usually known by the time it ends. */
    private var hospitalJob: Deferred<HospitalLookup?>? = null
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
        val deadline = android.os.SystemClock.elapsedRealtime() + SOS_COUNTDOWN_SECONDS * 1000L
        _state.value = SosUiState.Countdown(SOS_COUNTDOWN_SECONDS, source = source,
            locationAllowed = locationHelper.hasAnyPermission(), deadlineElapsedMs = deadline)

        triggerStartMs = System.currentTimeMillis()
        locationJob = viewModelScope.async { locationHelper.getCurrentFix(timeoutMs = 20_000L) }
        hospitalJob = viewModelScope.async { lookUpHospitals() }
        countdownJob = viewModelScope.launch {
            while (android.os.SystemClock.elapsedRealtime() < deadline) {
                val (blockers, contacts) = checkBlockers()
                val remainingMs = (deadline - android.os.SystemClock.elapsedRealtime()).coerceAtLeast(0L)
                if (remainingMs == 0L) break
                _state.value = SosUiState.Countdown(
                    CountdownClock.secondsLeft(remainingMs), source = source, blockers = blockers, contactCount = contacts,
                    locationAllowed = locationHelper.hasAnyPermission(), deadlineElapsedMs = deadline
                )
                delay(minOf(250L, remainingMs))
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
        hospitalJob?.cancel()
        hospitalJob = null
        val cancelledAt = current.secondsLeft
        _state.value = SosUiState.FalseAlarm(cancelledAt)

        viewModelScope.launch {
            sosEventDao.insert(SosEventEntity(
                triggeredAtMs  = triggerStartMs,
                source         = current.source.name,
                outcome        = SosOutcome.CANCELLED.name,
                lat            = null, lng = null, recipientCount = 0,
                notes          = AppText.text(R.string.copy_44a40862d91f, "Cancelled at %1\$s s remaining", cancelledAt)
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
            hospitalJob?.cancel()
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
        val dispatchStartMs = System.currentTimeMillis()
        val location = resolveLocation()
        val contacts = emergencyContactDao.all().first()
        val recipients = contacts.map { SmsRecipient(it.name, it.phoneNumber) }
        val userName  = prefsRepo.userProfile.first()?.name?.takeIf { it.isNotBlank() } ?: AppText.text(R.string.copy_dce812ce68b1, "A BIPSS user")
        val timestamp = SimpleDateFormat("dd MMM yyyy, HH:mm z", Locale.getDefault()).format(Date(triggerStartMs))

        // Hospitals: whatever the lookup started with the countdown has found,
        // given until HOSPITAL_WAIT_MS after the countdown ended. The contacts
        // are never held longer than that; a lookup still running is dropped.
        val deadline = dispatchStartMs + HOSPITAL_WAIT_MS
        val lookup = withTimeoutOrNull(remainingMs(deadline)) { hospitalJob?.await() }
        hospitalJob?.cancel()
        hospitalJob = null
        val hospitals = lookup?.hospitals.orEmpty()
        val textable = hospitals.filter { it.mobile != null }
        val hospitalSend = if (textable.isEmpty()) null else viewModelScope.async {
            smsSender.send(
                textable.map { SmsRecipient(it.name, it.mobile!!) },
                SosMessageBuilder.hospitalMessage(userName, timestamp, location, recipients)
            )
        }
        // The network's verdict, if it arrives within the same window, decides
        // whether the contacts are told the hospitals "were sent" the alert.
        val early = hospitalSend?.let { withTimeoutOrNull(remainingMs(deadline)) { it.await() } }
        fun verdictFor(h: Hospital) = early?.firstOrNull { it.recipient.phoneNumber == h.mobile }
        val notice = SosMessageBuilder.HospitalNotice(
            alerted      = textable.filter { verdictFor(it)?.sent == true },
            beingAlerted = textable.filter { early == null || verdictFor(it)?.unconfirmed == true },
            toCall       = hospitals.filter { h ->
                h.mobile == null || verdictFor(h)?.let { !it.sent && !it.unconfirmed } == true
            }
        )

        val body = SosMessageBuilder.contactMessage(userName, timestamp, location, notice)
        val results = smsSender.send(recipients, body)
        val hospitalResults = hospitalSend?.await().orEmpty()
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
            notes          = (results.filterNot { it.sent }.map { "${it.recipient.name}: ${it.error}" } +
                hospitalAudit(lookup, hospitals, hospitalResults)).joinToString("; ")
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
                locationAgeMinutes = location?.ageMinutes,
                hospitalLines      = hospitalLines(lookup, hospitals, hospitalResults, location != null)
            )
        }
    }

    /**
     * The nearest hospitals for this SOS. Waits a few seconds for the fix the
     * countdown requested, then falls back to the last known position (same
     * rules as the message), so the lookup has time to finish before the
     * countdown does. Null when there is no position to search from.
     */
    private suspend fun lookUpHospitals(): HospitalLookup? {
        if (!locationHelper.hasAnyPermission()) return null
        val fix = withTimeoutOrNull(LOOKUP_FIX_WAIT_MS) { locationJob?.await() }
        val (lat, lon) = if (fix != null) fix.lat to fix.lon else {
            val cached = prefsRepo.cachedLocation.first() ?: return null
            val atMs = cached.atMs ?: return null
            if (triggerStartMs - atMs !in 0..HOSPITAL_LOCATION_MAX_AGE_MS) return null
            cached.lat to cached.lon
        }
        return hospitalLocator.nearest(lat, lon)
    }

    private fun remainingMs(deadline: Long) = (deadline - System.currentTimeMillis()).coerceAtLeast(1L)

    private fun sendVerdict(h: Hospital, results: List<SmsSendResult>): String {
        val r = results.firstOrNull { it.recipient.phoneNumber == h.mobile }
        return when {
            r == null      -> AppText.text(R.string.copy_baa006375565, "not texted")
            r.sent         -> "texted"
            r.unconfirmed  -> AppText.text(R.string.copy_b9266d248ed8, "text not confirmed yet")
            else           -> AppText.text(R.string.copy_7a6734ba8e11, "text failed")
        }
    }

    /** For the result screen: what happened with each hospital, or why there were none. */
    private fun hospitalLines(
        lookup: HospitalLookup?,
        hospitals: List<Hospital>,
        results: List<SmsSendResult>,
        hadLocation: Boolean
    ): List<String> = when {
        lookup == null && !hadLocation -> listOf(AppText.text(R.string.copy_26c266e702fe, "Nearby hospitals could not be looked up without your location."))
        lookup == null -> listOf(AppText.text(R.string.copy_3d5f2db8fdf4, "The nearby-hospital lookup did not finish in time."))
        lookup.status == HospitalLookup.Status.FAILED -> listOf(AppText.text(R.string.copy_8a42d69572a6, "The nearby-hospital lookup failed (map service unreachable)."))
        lookup.status == HospitalLookup.Status.NONE_NEARBY -> listOf(AppText.text(R.string.copy_6fc6072148f4, "No hospital is mapped within 15 km of you."))
        lookup.status == HospitalLookup.Status.NONE_WITH_NUMBER ->
            listOf(AppText.text(R.string.copy_6ad7384ae9d1, "%1\$s hospital(s) are mapped near you, but none lists a phone number.", lookup.found))
        else -> hospitals.map { h ->
            val km = "%.1f".format(Locale.ROOT, h.distanceKm)
            if (h.mobile != null) AppText.text(R.string.copy_fc4c6f22a123, "%1\$s (%2\$s km): %3\$s", h.name, km, sendVerdict(h, results))
            else AppText.text(R.string.copy_c2ec085b7316, "%1\$s (%2\$s km): no mobile number listed, given to your contacts to call", h.name, km)
        }
    }

    /** For sos_events.notes: the hospital attempts, in the same terms. */
    private fun hospitalAudit(lookup: HospitalLookup?, hospitals: List<Hospital>, results: List<SmsSendResult>): List<String> =
        when (lookup?.status) {
            null -> listOf(AppText.text(R.string.copy_a9a63765feea, "hospitals: no lookup result"))
            HospitalLookup.Status.FOUND -> hospitals.map { h ->
                AppText.text(R.string.copy_78c8d8d798bc, "hospital %1\$s ", h.name) + (h.mobile?.let { "$it: ${sendVerdict(h, results)}" } ?: AppText.text(R.string.copy_e6298f17c288, "%1\$s: listed to call", h.callNumber))
            }
            else -> listOf(AppText.text(R.string.copy_5994325791ec, "hospitals: %1\$s (%2\$s mapped)", lookup.status.name.lowercase(), lookup.found))
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
        const val HOSPITAL_LOCATION_MAX_AGE_MS = 15 * 60_000L
        const val MAX_LAST_KNOWN_AGE_MS = 24 * 3_600_000L
        /** How long the contacts may wait for the hospital lookup and its sends after the countdown. */
        const val HOSPITAL_WAIT_MS = 3_000L
        /** How long the lookup waits for a fresh fix before searching from the last known position. */
        const val LOOKUP_FIX_WAIT_MS = 6_000L
    }
}
