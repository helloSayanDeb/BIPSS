package com.bipss.app.demo

import android.util.Log
import com.bipss.app.ble.SensorReading
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

private const val TAG = "DemoReplayEngine"

/**
 * Replays the six captured bench-test streams at their real sample rates,
 * emitting [SensorReading] objects into the same pipeline the app uses for
 * live BLE data. Each stream loops independently and indefinitely.
 *
 * The three 1 Hz combined streams are the ones that feed the main UI:
 * - **ble_frames** (26 rows): primary source — real HR, SpO2, skin temp, battery, quality bitmask
 * - **serial** (48 rows): secondary — the richest session with the HR acquisition arc
 * - **battery** (24 rows): includes battery_pct and skin_temp_f
 *
 * AHT21 and ENS160 have no valid data. ambTempC, humidityPct, aqiRaw stay null.
 * MQ135 is absent from the hardware and this code.
 */
class DemoReplayEngine(
    private val dataStore: DemoReplayDataStore
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _sensorReadings = MutableSharedFlow<SensorReading>(replay = 0, extraBufferCapacity = 64)
    val sensorReadings: SharedFlow<SensorReading> = _sensorReadings.asSharedFlow()

    private val _batteryPercent = MutableStateFlow<Int?>(null)
    val batteryPercent: StateFlow<Int?> = _batteryPercent.asStateFlow()

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    /**
     * Starts all six playback loops. Safe to call repeatedly — a second call
     * is a no-op while already running.
     */
    fun start() {
        if (_isRunning.value) return
        if (!dataStore.isLoaded) dataStore.loadFromAssets()
        _isRunning.value = true
        Log.i(TAG, "Demo replay started")

        // ── 1 Hz combined streams (primary UI data) ──────────────────────────

        // BLE frames: the primary source. Real quality bitmask, real HR/SpO2.
        scope.launch {
            val rows = dataStore.combinedBleFrames
            if (rows.isEmpty()) return@launch
            var idx = 0
            while (isActive && _isRunning.value) {
                val row = rows[idx]
                // Null in JSON means sensor not reporting (finger off).
                // 0 in hr/spo2 also means not-yet-acquired for the serial/battery streams,
                // but the ble_frames stream uses null directly.
                _sensorReadings.emit(
                    SensorReading(
                        heartRate    = row.hr_bpm?.takeIf { it > 0 },
                        hrvRmssd     = null, // not in this stream
                        spO2         = row.spo2_pct?.takeIf { it > 0 },
                        skinTempC    = row.skin_temp_c?.toFloat(),
                        ambientTempC = null, // AHT21 not available
                        humidityPct  = null, // AHT21 not available
                        aqiRaw       = null, // ENS160 not available
                        accelMagnitude = null,
                        timestamp    = System.currentTimeMillis()
                    )
                )
                row.battery_pct?.let { _batteryPercent.value = it }
                idx = (idx + 1) % rows.size
                delay(1000L)
            }
        }

        // Serial: richest session — shows the HR acquisition arc.
        // Runs independently, does NOT merge with ble_frames by index.
        scope.launch {
            val rows = dataStore.combinedSerial
            if (rows.isEmpty()) return@launch
            var idx = 0
            // Offset so serial and BLE don't emit at the exact same instant.
            delay(500L)
            while (isActive && _isRunning.value) {
                val row = rows[idx]
                _sensorReadings.emit(
                    SensorReading(
                        heartRate    = row.hr_bpm.takeIf { it > 0 },
                        hrvRmssd     = row.hrv_rmssd_ms.takeIf { it > 0 },
                        spO2         = row.spo2_pct.takeIf { it > 0 },
                        skinTempC    = row.skin_temp_c.toFloat(),
                        ambientTempC = null,
                        humidityPct  = null,
                        aqiRaw       = null,
                        accelMagnitude = null,
                        timestamp    = System.currentTimeMillis()
                    )
                )
                idx = (idx + 1) % rows.size
                delay(1000L)
            }
        }

        // Battery: includes battery % and skin temp in Fahrenheit.
        scope.launch {
            val rows = dataStore.combinedBattery
            if (rows.isEmpty()) return@launch
            var idx = 0
            while (isActive && _isRunning.value) {
                val row = rows[idx]
                row.battery_pct?.let { _batteryPercent.value = it }
                // Convert F → C for skinTempC
                val skinC = ((row.skin_temp_f - 32.0) * 5.0 / 9.0).toFloat()
                _sensorReadings.emit(
                    SensorReading(
                        heartRate    = row.hr_bpm.takeIf { it > 0 },
                        hrvRmssd     = row.hrv_rmssd_ms.takeIf { it > 0 },
                        spO2         = row.spo2_pct.takeIf { it > 0 },
                        skinTempC    = skinC,
                        ambientTempC = null,
                        humidityPct  = null,
                        aqiRaw       = null,
                        accelMagnitude = null,
                        timestamp    = System.currentTimeMillis()
                    )
                )
                idx = (idx + 1) % rows.size
                delay(1000L)
            }
        }

        // ── High-rate streams (raw waveform data, no SensorReading emission) ─
        // These feed into their own display if one exists. Currently no raw
        // waveform view exists in the app, but the data loops for future use.

        // MPU6050 raw accel/gyro at 5 Hz
        scope.launch {
            val rows = dataStore.mpuRawAccelGyro
            if (rows.isEmpty()) return@launch
            var idx = 0
            while (isActive && _isRunning.value) {
                // The accel magnitude feeds into the SensorReading for the
                // existing accelMagnitude field.
                val row = rows[idx]
                val mag = kotlin.math.sqrt(
                    row.accel_x_g * row.accel_x_g +
                    row.accel_y_g * row.accel_y_g +
                    row.accel_z_g * row.accel_z_g
                ).toFloat()
                _sensorReadings.emit(
                    SensorReading(
                        accelMagnitude = mag,
                        timestamp = System.currentTimeMillis()
                    )
                )
                idx = (idx + 1) % rows.size
                delay(200L)  // 5 Hz
            }
        }

        // Activity detection at ~1 Hz (irregular, but we treat as 1 Hz)
        scope.launch {
            val rows = dataStore.mpuActivityDetection
            if (rows.isEmpty()) return@launch
            var idx = 0
            while (isActive && _isRunning.value) {
                // Activity data — accel magnitude goes into pipeline
                val row = rows[idx]
                _sensorReadings.emit(
                    SensorReading(
                        accelMagnitude = row.accel_magnitude_g.toFloat(),
                        timestamp = System.currentTimeMillis()
                    )
                )
                idx = (idx + 1) % rows.size
                delay(1000L)
            }
        }

        // MAX30102 raw PPG at 50 Hz — pure waveform data, no SensorReading
        // mapping (red/IR are not fields in SensorReading). Loops for future
        // raw PPG display.
        scope.launch {
            val rows = dataStore.maxRawPpg
            if (rows.isEmpty()) return@launch
            var idx = 0
            while (isActive && _isRunning.value) {
                // No emission — no raw PPG view in the app yet.
                // Just advance the cursor to prove the loop runs.
                idx = (idx + 1) % rows.size
                delay(20L)  // 50 Hz
            }
        }
    }

    /** Stops all playback loops. */
    fun stop() {
        if (!_isRunning.value) return
        _isRunning.value = false
        scope.coroutineContext.cancelChildren()
        _batteryPercent.value = null
        Log.i(TAG, "Demo replay stopped")
    }
}
