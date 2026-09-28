package com.bipss.app.data.repository

import android.content.Context
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.HeightRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.health.connect.client.units.Length
import androidx.health.connect.client.units.Mass
import java.time.Instant
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

/**
 * Health Connect integration, deliberately scoped to interoperable fields
 * only — height and weight, written from the profile. Raw wearable readings (heart rate, SpO2,
 * skin temp, baselines) are NEVER written here; they stay in the app's
 * private Room database (see SensorRepository), because Health Connect is
 * an OS-level shared store — any app the user separately grants read access
 * to could see records written here. Room is the only way to keep that data
 * genuinely restricted to this app.
 */
class HealthConnectRepository(private val context: Context) {

    companion object {
        private const val TAG = "HealthConnectRepo"

        /** Exactly what the app uses - it writes height and weight, nothing else. */
        val PERMISSIONS: Set<String> = setOf(
            HealthPermission.getWritePermission(HeightRecord::class),
            HealthPermission.getWritePermission(WeightRecord::class),
        )
    }

    /** True if the Health Connect provider is installed and ready on this device. */
    fun isAvailable(): Boolean =
        HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE

    /** The Activity Result contract the UI layer launches to request [PERMISSIONS]. */
    fun permissionRequestContract() = PermissionController.createRequestPermissionResultContract()

    suspend fun hasAllPermissions(): Boolean {
        if (!isAvailable()) return false
        return try {
            val granted = HealthConnectClient.getOrCreate(context).permissionController.getGrantedPermissions()
            granted.containsAll(PERMISSIONS)
        } catch (e: Exception) {
            Log.w(TAG, "Permission check failed: ${e.message}")
            false
        }
    }

    /** Writes height/weight. No-ops silently if Health Connect isn't available or permission was denied. */
    suspend fun writeProfile(heightCm: Float, weightKg: Float) {
        if (!isAvailable()) return
        try {
            val client = HealthConnectClient.getOrCreate(context)
            val now = ZonedDateTime.now()
            client.insertRecords(
                listOf(
                    HeightRecord(
                        height = Length.meters(heightCm / 100.0),
                        time = now.toInstant(),
                        zoneOffset = now.offset
                    ),
                    WeightRecord(
                        weight = Mass.kilograms(weightKg.toDouble()),
                        time = now.toInstant(),
                        zoneOffset = now.offset
                    )
                )
            )
        } catch (e: Exception) {
            Log.w(TAG, "Health Connect profile write skipped: ${e.message}")
        }
    }

}
