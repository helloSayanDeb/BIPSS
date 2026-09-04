package com.bipss.app.sos

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
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
