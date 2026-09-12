package com.bipss.app.demo

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Parses the demo replay JSON from assets and holds all six arrays in memory.
 *
 * Row counts are verified against _meta.streams.<name>.rows at parse time —
 * a mismatch throws so demo mode cannot start with corrupted/truncated data.
 */
class DemoReplayDataStore(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    var mpuRawAccelGyro:      List<MpuRawAccelGyroRow>      = emptyList(); private set
    var mpuActivityDetection: List<MpuActivityDetectionRow>  = emptyList(); private set
    var maxRawPpg:            List<MaxRawPpgRow>             = emptyList(); private set
    var combinedSerial:       List<CombinedSerialRow>        = emptyList(); private set
    var combinedBleFrames:    List<CombinedBleFrameRow>      = emptyList(); private set
    var combinedBattery:      List<CombinedBatteryRow>       = emptyList(); private set

    var isLoaded = false; private set

    fun loadFromAssets() {
        if (isLoaded) return
        val raw = context.assets.open("demo_replay_package.json").bufferedReader().readText()
        val root = json.parseToJsonElement(raw).jsonObject

        val meta = root["_meta"]!!.jsonObject
        val streams = meta["streams"]!!.jsonObject

        mpuRawAccelGyro = parseAndValidate(
            root, streams, "mpu6050_raw_accel_gyro"
        )
        mpuActivityDetection = parseAndValidate(
            root, streams, "mpu6050_activity_detection"
        )
        maxRawPpg = parseAndValidate(
            root, streams, "max30102_raw_ppg"
        )
        combinedSerial = parseAndValidate(
            root, streams, "combined_mpu_max_tmp_serial"
        )
        combinedBleFrames = parseAndValidate(
            root, streams, "combined_mpu_max_tmp_ble_frames"
        )
        combinedBattery = parseAndValidate(
            root, streams, "combined_mpu_max_tmp_battery"
        )

        isLoaded = true
    }

    private inline fun <reified T> parseAndValidate(
        root: JsonObject, streams: JsonObject, key: String
    ): List<T> {
        val expectedRows = streams[key]!!.jsonObject["rows"]!!.jsonPrimitive.int
        val array = root[key]!!.jsonArray
        val parsed = array.map { json.decodeFromJsonElement<T>(it) }
        check(parsed.size == expectedRows) {
            "Demo data '$key': expected $expectedRows rows, got ${parsed.size}"
        }
        return parsed
    }
}

// ── Data classes matching JSON exactly ───────────────────────────────────────

@Serializable
data class MpuRawAccelGyroRow(
    val sample: Int,
    val accel_x_g: Double,
    val accel_y_g: Double,
    val accel_z_g: Double,
    val gyro_x_dps: Double,
    val gyro_y_dps: Double,
    val gyro_z_dps: Double
)

@Serializable
data class MpuActivityDetectionRow(
    val sample: Int,
    val steps: Int,
    val orientation: String,
    val sleep_state: String,
    val fall_state: String,
    val accel_magnitude_g: Double,
    val movement_score: Double,
    val day_baseline: Double,
    val die_temp_c: Double
)

@Serializable
data class MaxRawPpgRow(
    val sample: Int,
    val red: Int,
    val ir: Int,
    val contact: String
)

@Serializable
data class CombinedSerialRow(
    val sample: Int,
    val steps: Int,
    val orientation: String,
    val sleep_state: String,
    val fall_state: String,
    val hr_bpm: Int,
    val hrv_rmssd_ms: Int,
    val spo2_pct: Int,
    val contact: String,
    val skin_temp_c: Double
)

@Serializable
data class CombinedBleFrameRow(
    val seq: Int,
    val hr_bpm: Int? = null,
    val spo2_pct: Int? = null,
    val skin_temp_c: Double? = null,
    val battery_pct: Int? = null,
    val quality_hex: String,
    val ble_connected: Boolean
)

@Serializable
data class CombinedBatteryRow(
    val sample: Int,
    val steps: Int,
    val orientation: String,
    val sleep_state: String,
    val fall_state: String,
    val hr_bpm: Int,
    val hrv_rmssd_ms: Int,
    val spo2_pct: Int,
    val contact: String,
    val hr_acquire_count: Int? = null,
    val skin_temp_f: Double,
    val battery_pct: Int? = null,
    val battery_v: Double? = null
)
