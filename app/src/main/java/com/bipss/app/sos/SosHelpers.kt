package com.bipss.app.sos

import com.bipss.app.localization.AppText
import com.bipss.app.R

import kotlinx.coroutines.channels.awaitClose

import android.annotation.SuppressLint
import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume

/** A position fix with the information needed to describe how good it is. */
data class LocationFix(
    val lat: Double,
    val lon: Double,
    val accuracyM: Float?,
    val timeMs: Long,
    /** Only approximate (coarse) permission is granted, so the fix is deliberately fuzzed by Android. */
    val isApproximate: Boolean
)

class LocationHelper(private val context: Context) {

    private val fusedLocationClient = LocationServices.getFusedLocationProviderClient(context)

    fun hasFinePermission(): Boolean = ContextCompat.checkSelfPermission(
        context, Manifest.permission.ACCESS_FINE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED

    /** Either precise or approximate location is granted. */
    fun hasAnyPermission(): Boolean = hasFinePermission() || ContextCompat.checkSelfPermission(
        context, Manifest.permission.ACCESS_COARSE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED

    /** A fresh fix, or null without permission, on failure, or after [timeoutMs]. */
    @SuppressLint("MissingPermission")
    suspend fun getCurrentFix(timeoutMs: Long = 10_000L): LocationFix? {
        if (!hasAnyPermission()) return null
        val approximate = !hasFinePermission()
        return try {
            withTimeout(timeoutMs) {
                suspendCancellableCoroutine { cont ->
                    val cts = CancellationTokenSource()
                    val priority = if (approximate) Priority.PRIORITY_BALANCED_POWER_ACCURACY else Priority.PRIORITY_HIGH_ACCURACY
                    val request = CurrentLocationRequest.Builder().setPriority(priority).setMaxUpdateAgeMillis(0).build()
                    fusedLocationClient.getCurrentLocation(request, cts.token)
                        .addOnSuccessListener { location: Location? ->
                            if (cont.isActive) cont.resume(location?.let {
                                LocationFix(it.latitude, it.longitude,
                                    if (it.hasAccuracy()) it.accuracy else null, it.time, approximate)
                            })
                        }
                        .addOnFailureListener {
                            if (cont.isActive) cont.resume(null)
                        }
                    cont.invokeOnCancellation { cts.cancel() }
                }
            }
        } catch (e: TimeoutCancellationException) {
            null
        } catch (e: CancellationException) { throw e
        } catch (e: Exception) {
            null
        }
    }

    /**
     * High-accuracy fixes as they arrive, for the setup screen's calibration
     * window: the caller shows each fix's accuracy and stops when it is good
     * enough or time runs out. Nothing without permission.
     */
    @SuppressLint("MissingPermission")
    fun calibrationFixes(intervalMs: Long = 500L): kotlinx.coroutines.flow.Flow<LocationFix> =
        kotlinx.coroutines.flow.callbackFlow {
            if (!hasAnyPermission()) { close(); return@callbackFlow }
            val approximate = !hasFinePermission()
            val request = com.google.android.gms.location.LocationRequest.Builder(
                if (approximate) Priority.PRIORITY_BALANCED_POWER_ACCURACY else Priority.PRIORITY_HIGH_ACCURACY,
                intervalMs
            ).setMinUpdateIntervalMillis(intervalMs).build()
            val callback = object : com.google.android.gms.location.LocationCallback() {
                override fun onLocationResult(result: com.google.android.gms.location.LocationResult) {
                    result.lastLocation?.let {
                        trySend(LocationFix(it.latitude, it.longitude,
                            if (it.hasAccuracy()) it.accuracy else null, it.time, approximate))
                    }
                }
            }
            // A provider that refuses (or a permission revoked a moment ago)
            // just ends calibration with no fixes; it is not an error the
            // caller has to survive.
            try {
                fusedLocationClient.requestLocationUpdates(request, callback, android.os.Looper.getMainLooper())
                    .addOnFailureListener { e -> Log.w("LocationHelper", "Location updates refused: ${e.message}"); close() }
            } catch (e: SecurityException) {
                Log.w("LocationHelper", "Location permission gone before calibration: ${e.message}")
                close()
            }
            awaitClose { fusedLocationClient.removeLocationUpdates(callback) }
        }

    /**
     * Reverse-geocodes the current position into an Indian district and state.
     *
     * Both are needed to filter the national alert feed down to what actually
     * concerns the user - without this a Kolkata wearer sees Madhya Pradesh
     * warnings. Returns nulls rather than a guess when the geocoder is
     * unavailable (it needs network on most devices); the caller then falls
     * back to coordinate distance against the alert centroid, which works
     * offline. Gives up after [GEOCODE_TIMEOUT_MS].
     */
    suspend fun resolveDistrictAndState(
        lat: Double,
        lon: Double
    ): Pair<String?, String?> {
        val address = withTimeoutOrNull(GEOCODE_TIMEOUT_MS) { geocode(lat, lon) } ?: return null to null
        // subAdminArea is the district in Indian addresses; locality is the
        // city, which is the usable fallback for metros where the district
        // and city names coincide.
        val district = address.subAdminArea?.takeIf { it.isNotBlank() }
            ?: address.locality?.takeIf { it.isNotBlank() }
        val state = address.adminArea?.takeIf { it.isNotBlank() }
        return district to state
    }

    /**
     * The first address for a position, or null. From API 33 the lookup is a
     * callback, which a time limit can abandon; before that it is a blocking
     * call that cannot be cut short.
     */
    private suspend fun geocode(lat: Double, lon: Double): Address? = try {
        val geocoder = Geocoder(context, Locale.getDefault())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            suspendCancellableCoroutine { cont ->
                geocoder.getFromLocation(lat, lon, 1, object : Geocoder.GeocodeListener {
                    override fun onGeocode(addresses: MutableList<Address>) {
                        if (cont.isActive) cont.resume(addresses.firstOrNull())
                    }

                    override fun onError(errorMessage: String?) {
                        Log.w("LocationHelper", "Reverse geocode failed: $errorMessage")
                        if (cont.isActive) cont.resume(null)
                    }
                })
            }
        } else withContext(Dispatchers.IO) {
            @Suppress("DEPRECATION")
            geocoder.getFromLocation(lat, lon, 1)?.firstOrNull()
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w("LocationHelper", "Reverse geocode failed: ${e.message}")
        null
    }

    private companion object {
        const val GEOCODE_TIMEOUT_MS = 5_000L
    }
}

/** One SMS recipient. */
data class SmsRecipient(val name: String, val phoneNumber: String)

/**
 * What the radio reported for one recipient. [sent] is only true on RESULT_OK
 * for every part. [unconfirmed] means no verdict arrived in time: the message
 * was handed to the phone's SMS service and may still go out - or may have
 * failed - so it is reported as neither sent nor failed.
 */
data class SmsSendResult(
    val recipient:   SmsRecipient,
    val sent:        Boolean,
    val error:       String? = null,
    val unconfirmed: Boolean = false
)

/** Whether this device can send an SMS at all right now. */
enum class SmsCapability { OK, NO_PERMISSION, NO_TELEPHONY }

/**
 * Sends SMS and reports what actually happened.
 *
 * "Success" used to mean "no exception was thrown", with null sent-intents, so
 * a message the radio rejected still counted as sent - and a zero count was
 * then shown as "Alert sent". Each part now carries a sent-intent and a
 * recipient only counts as sent when the radio returns RESULT_OK for every
 * part. That confirms the message left the phone for the carrier; delivery to
 * the handset is not tracked, since delivery reports can take minutes.
 */
class SmsSender(private val context: Context) {

    fun capability(): SmsCapability = when {
        !context.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY_MESSAGING) &&
            !context.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY) -> SmsCapability.NO_TELEPHONY
        ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS)
            != PackageManager.PERMISSION_GRANTED -> SmsCapability.NO_PERMISSION
        else -> SmsCapability.OK
    }

    /**
     * The SmsManager for the default SMS subscription. `getSystemService` only
     * returns an instance from API 31; below that it is null, which previously
     * made every send throw inside a catch and silently fail on Android 8-11.
     */
    @Suppress("DEPRECATION")
    private fun smsManager(): SmsManager {
        val subId = SmsManager.getDefaultSmsSubscriptionId()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val base = context.getSystemService(SmsManager::class.java)
            if (subId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) base.createForSubscriptionId(subId) else base
        } else {
            if (subId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) SmsManager.getSmsManagerForSubscriptionId(subId)
            else SmsManager.getDefault()
        }
    }

    /**
     * Sends [body] to every recipient and waits (up to [timeoutMs]) for the
     * radio's verdict on each. A recipient with no verdict by then is reported
     * as not sent - never assumed to have gone through.
     */
    @SuppressLint("MissingPermission")
    suspend fun send(recipients: List<SmsRecipient>, body: String, timeoutMs: Long = 20_000L): List<SmsSendResult> {
        if (recipients.isEmpty()) return emptyList()
        if (capability() != SmsCapability.OK) {
            return recipients.map { SmsSendResult(it, sent = false, error = capability().name) }
        }
        val manager = smsManager()
        // divideMessage always: the 160-character limit only holds for GSM-7.
        // A name in Bengali or Devanagari switches the whole message to UCS-2,
        // where one part holds 70 characters.
        val parts = manager.divideMessage(body)
        val action = "com.bipss.app.SMS_SENT." + System.nanoTime()

        val outcomes = HashMap<Int, BooleanArray>()   // recipient index -> per-part OK
        val reported = HashMap<Int, BooleanArray>()   // recipient index -> per-part verdict arrived
        val codes = HashMap<Int, Int>()               // recipient index -> first failing result code
        val done = CompletableDeferred<Unit>()
        var remaining = recipients.size * parts.size
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val r = intent.getIntExtra(EXTRA_RECIPIENT, -1)
                val p = intent.getIntExtra(EXTRA_PART, -1)
                synchronized(outcomes) {
                    if (r >= 0 && p >= 0) {
                        outcomes.getOrPut(r) { BooleanArray(parts.size) }[p] = resultCode == Activity.RESULT_OK
                        reported.getOrPut(r) { BooleanArray(parts.size) }[p] = true
                        if (resultCode != Activity.RESULT_OK) codes.putIfAbsent(r, resultCode)
                    }
                    remaining--
                    if (remaining <= 0) done.complete(Unit)
                }
            }
        }
        ContextCompat.registerReceiver(context, receiver, IntentFilter(action), ContextCompat.RECEIVER_NOT_EXPORTED)
        val errors = HashMap<Int, String>()
        try {
            recipients.forEachIndexed { r, recipient ->
                val sentIntents = ArrayList<PendingIntent>(parts.size)
                parts.indices.forEach { p ->
                    val intent = Intent(action).setPackage(context.packageName)
                        .putExtra(EXTRA_RECIPIENT, r).putExtra(EXTRA_PART, p)
                    sentIntents += PendingIntent.getBroadcast(
                        context, r * 100 + p, intent,
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                    )
                }
                try {
                    if (parts.size > 1) {
                        manager.sendMultipartTextMessage(recipient.phoneNumber, null, parts, sentIntents, null)
                    } else {
                        manager.sendTextMessage(recipient.phoneNumber, null, parts[0], sentIntents[0], null)
                    }
                } catch (e: Exception) {
                    Log.e("SmsSender", "Send to ${recipient.name} failed: ${e.message}")
                    errors[r] = e.message ?: e.javaClass.simpleName
                    synchronized(outcomes) {
                        remaining -= parts.size
                        if (remaining <= 0) done.complete(Unit)
                    }
                }
            }
            withTimeoutOrNull(timeoutMs) { done.await() }
        } finally {
            runCatching { context.unregisterReceiver(receiver) }
        }
        return recipients.mapIndexed { r, recipient ->
            synchronized(outcomes) {
                val allReported = reported[r]?.all { it } == true
                val ok = allReported && outcomes[r]?.all { it } == true
                when {
                    errors[r] != null -> SmsSendResult(recipient, sent = false, error = errors[r])
                    ok                -> SmsSendResult(recipient, sent = true)
                    codes[r] != null  -> SmsSendResult(recipient, sent = false, error = describeResultCode(codes[r]!!))
                    // No verdict before the timeout: neither sent nor failed.
                    else              -> SmsSendResult(recipient, sent = false, unconfirmed = true,
                                                       error = AppText.text(R.string.copy_5fb98e3ebcca, "no confirmation within %1\$s s", timeoutMs / 1000))
                }
            }
        }
    }

    private fun describeResultCode(code: Int): String = when (code) {
        SmsManager.RESULT_ERROR_RADIO_OFF       -> AppText.text(R.string.copy_1f45bc903a43, "mobile radio is off")
        SmsManager.RESULT_ERROR_NO_SERVICE      -> AppText.text(R.string.copy_c653f9e3bf1f, "no mobile service")
        SmsManager.RESULT_ERROR_NULL_PDU        -> AppText.text(R.string.copy_31d0b56e8db2, "message could not be encoded")
        SmsManager.RESULT_ERROR_GENERIC_FAILURE -> AppText.text(R.string.copy_48dc39dd494c, "the network rejected it")
        else                                    -> AppText.text(R.string.copy_dd2f56641be7, "SMS error %1\$s", code)
    }

    private companion object {
        const val EXTRA_RECIPIENT = "recipient"
        const val EXTRA_PART = "part"
    }
}
