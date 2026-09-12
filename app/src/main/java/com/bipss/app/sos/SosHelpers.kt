package com.bipss.app.sos

import android.annotation.SuppressLint
import android.content.Context
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
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume

class LocationHelper(private val context: Context) {

    private val fusedLocationClient = LocationServices.getFusedLocationProviderClient(context)

    @SuppressLint("MissingPermission")
    suspend fun getCurrentLocation(): Pair<Double, Double>? {
        val hasPermission = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasPermission) return null

        return try {
            withTimeout(10_000L) {
                suspendCancellableCoroutine { cont ->
                    val cts = CancellationTokenSource()
                    fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cts.token)
                        .addOnSuccessListener { location: Location? ->
                            if (cont.isActive) cont.resume(location?.let { it.latitude to it.longitude })
                        }
                        .addOnFailureListener {
                            if (cont.isActive) cont.resume(null)
                        }
                    cont.invokeOnCancellation { cts.cancel() }
                }
            }
        } catch (e: TimeoutCancellationException) {
            null
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Reverse-geocodes the current position into an Indian district and state.
     *
     * Both are needed to filter the national alert feed down to what actually
     * concerns the user - without this a Kolkata wearer sees Madhya Pradesh
     * warnings. Returns nulls rather than a guess when the geocoder is
     * unavailable (it needs network on most devices); the caller then falls
     * back to coordinate distance against the alert centroid, which works
     * offline.
     */
    suspend fun getDistrictAndState(): Pair<String?, String?> = withContext(Dispatchers.IO) {
        val loc = getCurrentLocation() ?: return@withContext null to null
        resolveDistrictAndState(loc.first, loc.second)
    }

    suspend fun resolveDistrictAndState(
        lat: Double,
        lon: Double
    ): Pair<String?, String?> = withContext(Dispatchers.IO) {
        try {
            val geocoder = Geocoder(context, Locale("en", "IN"))
            @Suppress("DEPRECATION")
            val address = geocoder.getFromLocation(lat, lon, 1)?.firstOrNull()
                ?: return@withContext null to null
            // subAdminArea is the district in Indian addresses; locality is the
            // city, which is the usable fallback for metros where the district
            // and city names coincide.
            val district = address.subAdminArea?.takeIf { it.isNotBlank() }
                ?: address.locality?.takeIf { it.isNotBlank() }
            val state = address.adminArea?.takeIf { it.isNotBlank() }
            district to state
        } catch (e: Exception) {
            android.util.Log.w("LocationHelper", "Reverse geocode failed: ${e.message}")
            null to null
        }
    }
}

class SmsSender(private val context: Context) {

    @SuppressLint("MissingPermission")
    fun sendSosMessages(
        contacts:  List<com.bipss.app.data.db.entity.EmergencyContactEntity>,
        userName:  String,
        lat:       Double?,
        lng:       Double?,
        timestamp: String
    ): Int {
        val locationPart = if (lat != null && lng != null)
            "https://maps.google.com/?q=${"%.6f".format(lat)},${"%.6f".format(lng)}"
        else "location unavailable"

        val message = "$userName may have had a fall or medical event at $timestamp. " +
                      "Last known location: $locationPart. Automated alert from BIPSS."

        val smsManager = context.getSystemService(android.telephony.SmsManager::class.java)
        var successCount = 0

        contacts.forEach { contact ->
            try {
                if (message.length > 160) {
                    val parts = smsManager.divideMessage(message)
                    smsManager.sendMultipartTextMessage(contact.phoneNumber, null, parts, null, null)
                } else {
                    smsManager.sendTextMessage(contact.phoneNumber, null, message, null, null)
                }
                successCount++
            } catch (e: Exception) {
                android.util.Log.e("SmsSender", "Failed to send SOS to ${contact.phoneNumber}: ${e.message}")
            }
        }
        return successCount
    }
}
