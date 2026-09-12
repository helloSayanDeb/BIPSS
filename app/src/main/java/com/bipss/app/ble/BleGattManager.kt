package com.bipss.app.ble

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import com.bipss.app.ble.BleConstants.BATTERY_SERVICE
import com.bipss.app.ble.BleConstants.BIPSS_SERVICE_UUID
import com.bipss.app.ble.BleConstants.CCCD_UUID
import com.bipss.app.ble.BleConstants.CHAR_BATTERY_LEVEL
import com.bipss.app.ble.BleConstants.CHAR_SENSOR_FRAME
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.UUID

private const val TAG = "BleGattManager"

sealed class BleConnectionState {
    object Disconnected : BleConnectionState()
    object Scanning     : BleConnectionState()
    data class DeviceFound(val name: String, val address: String, val rssi: Int) : BleConnectionState()
    object Connecting   : BleConnectionState()
    object Connected    : BleConnectionState()
    data class Reconnecting(val attempt: Int) : BleConnectionState()
    data class Error(val message: String) : BleConnectionState()
}

/**
 * One decoded reading, stamped with the time it was actually measured.
 *
 * [timestamp] comes from the band's uptime clock plus a per-connection offset,
 * not from arrival time. If the band ever buffers and replays, arrival-time
 * stamping would place thousands of samples at the same instant, which
 * corrupts every baseline and makes every graph wrong.
 */
data class SensorReading(
    val heartRate:   Int?   = null,
    val hrvRmssd:    Int?   = null,
    val spO2:        Int?   = null,
    val skinTempC:   Float? = null,
    val ambientTempC: Float? = null,
    val humidityPct: Float? = null,
    val aqiRaw:      Int?   = null,
    val accelMagnitude: Float? = null,
    val timestamp:   Long   = System.currentTimeMillis()
)

@SuppressLint("MissingPermission")
class BleGattManager(private val context: Context) {

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter = bluetoothManager.adapter
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _connectionState = MutableStateFlow<BleConnectionState>(BleConnectionState.Disconnected)
    val connectionState: StateFlow<BleConnectionState> = _connectionState.asStateFlow()

    private val _sensorReadings = MutableSharedFlow<SensorReading>(replay = 0, extraBufferCapacity = 64)
    val sensorReadings: SharedFlow<SensorReading> = _sensorReadings.asSharedFlow()

    /**
     * Band battery, kept deliberately outside [SensorReading].
     *
     * Battery is device telemetry, not a health metric. Letting it into
     * raw_readings would put it into the baseline maths alongside heart rate.
     */
    private val _batteryPercent = MutableStateFlow<Int?>(null)
    val batteryPercent: StateFlow<Int?> = _batteryPercent.asStateFlow()

    private var gatt: BluetoothGatt? = null
    private var pairedMac: String? = null
    private var reconnectAttempts = 0
    private var userInitiatedDisconnect = false

    /**
     * wallClockMs - bandUptimeMs, established from the first frame of each
     * connection. Cleared on disconnect because the band may have rebooted,
     * which resets its uptime and invalidates the old offset.
     */
    private var clockOffsetMs: Long? = null

    // -- Scanning --------------------------------------------------------------

    fun startScan() {
        val scanner = bluetoothAdapter?.bluetoothLeScanner ?: run {
            _connectionState.value = BleConnectionState.Error("Bluetooth not available")
            return
        }
        _connectionState.value = BleConnectionState.Scanning

        val filters = listOf(
            ScanFilter.Builder().setServiceUuid(ParcelUuid(BIPSS_SERVICE_UUID)).build()
        )
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        scanner.startScan(filters, settings, scanCallback)
    }

    fun stopScan() {
        runCatching { bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback) }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            _connectionState.value = BleConnectionState.DeviceFound(
                name    = device.name ?: "BIPSS Band",
                address = device.address,
                rssi    = result.rssi
            )
        }
        override fun onScanFailed(errorCode: Int) {
            _connectionState.value = BleConnectionState.Error("Scan failed: $errorCode")
        }
    }

    // -- Connection ------------------------------------------------------------

    fun connectToDevice(address: String, autoConnect: Boolean = true) {
        stopScan()
        userInitiatedDisconnect = false
        pairedMac = address
        val device = bluetoothAdapter?.getRemoteDevice(address) ?: return
        _connectionState.value = BleConnectionState.Connecting

        // Bonding gives us link-layer encryption for health data in transit.
        if (device.bondState == BluetoothDevice.BOND_NONE) {
            runCatching { device.createBond() }
        }
        gatt = device.connectGatt(context, autoConnect, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    fun disconnect() {
        userInitiatedDisconnect = true
        gatt?.disconnect()
        gatt?.close()
        gatt = null
        clockOffsetMs = null
        _connectionState.value = BleConnectionState.Disconnected
    }

    // -- GATT callbacks --------------------------------------------------------

    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.i(TAG, "Connected - discovering services")
                    reconnectAttempts = 0
                    gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                    gatt.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.w(TAG, "Disconnected (status=$status)")
                    // The band may reboot while away, so the uptime-to-wallclock
                    // offset cannot be carried across a reconnect.
                    clockOffsetMs = null
                    gatt.close()
                    this@BleGattManager.gatt = null
                    _connectionState.value = BleConnectionState.Disconnected
                    scheduleReconnect()
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                _connectionState.value = BleConnectionState.Error("Service discovery failed")
                return
            }
            _connectionState.value = BleConnectionState.Connected
            // Request a larger MTU FIRST. Notification subscription is deferred
            // to onMtuChanged so the 24-byte sensor frame is never truncated by
            // the default 23-byte ATT_MTU.
            gatt.requestMtu(BleConstants.PREFERRED_MTU)
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            Log.i(TAG, "MTU now $mtu (status=$status)")
            // Now that the transport can carry the full frame, subscribe.
            enableNotification(gatt, BIPSS_SERVICE_UUID, CHAR_SENSOR_FRAME)
            // Battery lives in its own standard service, so the service UUID has
            // to be a parameter - hardcoding the vendor service silently skipped it.
            enableNotification(gatt, BATTERY_SERVICE, CHAR_BATTERY_LEVEL)
            readCharacteristic(gatt, BATTERY_SERVICE, CHAR_BATTERY_LEVEL)
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray
        ) = handleCharacteristicData(characteristic.uuid, value)

        @Deprecated("Used for API < 33")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic
        ) {
            if (Build.VERSION.SDK_INT < 33) {
                @Suppress("DEPRECATION")
                characteristic.value?.let { handleCharacteristicData(characteristic.uuid, it) }
            }
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicRead(
            gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic,
            value: ByteArray, status: Int
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) handleCharacteristicData(characteristic.uuid, value)
        }

        @Deprecated("Used for API < 33")
        override fun onCharacteristicRead(
            gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int
        ) {
            if (Build.VERSION.SDK_INT < 33 && status == BluetoothGatt.GATT_SUCCESS) {
                @Suppress("DEPRECATION")
                characteristic.value?.let { handleCharacteristicData(characteristic.uuid, it) }
            }
        }
    }

    /** Exponential backoff, capped. Skipped when the user disconnected deliberately. */
    private fun scheduleReconnect() {
        if (userInitiatedDisconnect) return
        val mac = pairedMac ?: return
        scope.launch {
            val delayMs = minOf(1000L shl reconnectAttempts.coerceAtMost(6), BleConstants.MAX_RECONNECT_DELAY_MS)
            reconnectAttempts++
            _connectionState.value = BleConnectionState.Reconnecting(reconnectAttempts)
            delay(delayMs)
            if (!userInitiatedDisconnect) connectToDevice(mac, autoConnect = true)
        }
    }

    private fun enableNotification(gatt: BluetoothGatt, serviceUuid: UUID, charUuid: UUID) {
        val service = gatt.getService(serviceUuid) ?: run {
            Log.w(TAG, "Service $serviceUuid not found"); return
        }
        val char = service.getCharacteristic(charUuid) ?: run {
            Log.w(TAG, "Characteristic $charUuid not found"); return
        }
        gatt.setCharacteristicNotification(char, true)
        val descriptor = char.getDescriptor(CCCD_UUID) ?: return
        if (Build.VERSION.SDK_INT >= 33) {
            gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
        } else {
            @Suppress("DEPRECATION")
            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            @Suppress("DEPRECATION")
            gatt.writeDescriptor(descriptor)
        }
    }

    private fun readCharacteristic(gatt: BluetoothGatt, serviceUuid: UUID, charUuid: UUID) {
        gatt.getService(serviceUuid)?.getCharacteristic(charUuid)?.let { gatt.readCharacteristic(it) }
    }

    private fun handleCharacteristicData(uuid: UUID, value: ByteArray) {
        if (uuid == CHAR_BATTERY_LEVEL) {
            if (value.isNotEmpty()) {
                _batteryPercent.value = (value[0].toInt() and 0xFF).takeIf { it in 0..100 }
            }
            return
        }
        if (uuid != CHAR_SENSOR_FRAME) return

        val frame = SensorFrameParser.parse(value) ?: run {
            Log.w(TAG, "Unparseable frame of ${value.size} bytes")
            return
        }

        if (clockOffsetMs == null) {
            clockOffsetMs = System.currentTimeMillis() - frame.bandUptimeMs
            Log.i(TAG, "Clock offset established: $clockOffsetMs")
        }

        frame.batteryPct?.let { _batteryPercent.value = it }

        _sensorReadings.tryEmit(
            SensorReading(
                heartRate    = frame.hr,
                hrvRmssd     = frame.hrvRmssd,
                spO2         = frame.spo2,
                skinTempC    = frame.skinTempC,
                ambientTempC = frame.ambTempC,
                humidityPct  = frame.humidityPct,
                aqiRaw       = frame.aqiRaw,
                accelMagnitude = null,
                timestamp    = trueTimestamp(frame)
            )
        )
    }

    /**
     * Wall-clock time of the sample, reconstructed from the band's own clock.
     *
     * Falls back to arrival time only before the offset has been established,
     * which is the very first frame of a connection.
     */
    fun trueTimestamp(frame: SensorFrame): Long =
        clockOffsetMs?.let { frame.bandUptimeMs + it } ?: System.currentTimeMillis()
}
