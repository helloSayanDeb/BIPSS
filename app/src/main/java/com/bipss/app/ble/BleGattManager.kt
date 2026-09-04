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
import com.bipss.app.ble.BleConstants.BIPSS_SERVICE_UUID
import com.bipss.app.ble.BleConstants.CCCD_UUID
import com.bipss.app.ble.BleConstants.CHAR_ACCELEROMETER
import com.bipss.app.ble.BleConstants.CHAR_HEART_RATE
import com.bipss.app.ble.BleConstants.CHAR_SKIN_TEMP
import com.bipss.app.ble.BleConstants.CHAR_SPO2
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

private const val TAG = "BleGattManager"

sealed class BleConnectionState {
    object Disconnected : BleConnectionState()
    object Scanning     : BleConnectionState()
    data class DeviceFound(val name: String, val address: String, val rssi: Int) : BleConnectionState()
    object Connecting   : BleConnectionState()
    object Connected    : BleConnectionState()
    data class Error(val message: String) : BleConnectionState()
}

data class SensorReading(
    val heartRate:  Int?   = null,
    val spO2:       Int?   = null,
    val skinTempC:  Float? = null,
    val accelX:     Float? = null,
    val accelY:     Float? = null,
    val accelZ:     Float? = null,
    val timestamp:  Long   = System.currentTimeMillis()
)

@SuppressLint("MissingPermission")
class BleGattManager(private val context: Context) {

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter  = bluetoothManager.adapter

    private val _connectionState = MutableStateFlow<BleConnectionState>(BleConnectionState.Disconnected)
    val connectionState: StateFlow<BleConnectionState> = _connectionState.asStateFlow()

    // GATT characteristic notification channel — consumed by SensorRepository
    private val _sensorReadings = MutableSharedFlow<SensorReading>(
        replay          = 0,
        extraBufferCapacity = 64
    )
    val sensorReadings: SharedFlow<SensorReading> = _sensorReadings.asSharedFlow()

    private var gatt: BluetoothGatt? = null

    // Serialized GATT operations queue — prevents race conditions
    private val gattMutex = Mutex()
    private val gattResultChannel = Channel<Boolean>(Channel.CONFLATED)

    // ── Scanning ──────────────────────────────────────────────────────────────

    fun startScan() {
        val scanner = bluetoothAdapter?.bluetoothLeScanner ?: run {
            _connectionState.value = BleConnectionState.Error("Bluetooth not available")
            return
        }

        _connectionState.value = BleConnectionState.Scanning

        val filters = listOf(
            ScanFilter.Builder()
                .setServiceUuid(ParcelUuid(BIPSS_SERVICE_UUID))
                .build()
        )
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        scanner.startScan(filters, settings, scanCallback)
    }

    fun stopScan() {
        bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val name   = device.name ?: "BIPSS Band"
            _connectionState.value = BleConnectionState.DeviceFound(
                name    = name,
                address = device.address,
                rssi    = result.rssi
            )
        }
        override fun onScanFailed(errorCode: Int) {
            _connectionState.value = BleConnectionState.Error("Scan failed: $errorCode")
        }
    }

    // ── Connection ────────────────────────────────────────────────────────────

    fun connectToDevice(address: String) {
        stopScan()
        val device = bluetoothAdapter?.getRemoteDevice(address) ?: return
        _connectionState.value = BleConnectionState.Connecting
        gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    fun disconnect() {
        gatt?.disconnect()
        gatt?.close()
        gatt = null
        _connectionState.value = BleConnectionState.Disconnected
    }

    // ── GATT Callback ─────────────────────────────────────────────────────────

    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.i(TAG, "Connected — discovering services")
                    gatt.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    _connectionState.value = BleConnectionState.Disconnected
                    gatt.close()
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                _connectionState.value = BleConnectionState.Error("Service discovery failed")
                return
            }
            _connectionState.value = BleConnectionState.Connected

            // Subscribe to all sensor characteristic notifications
            listOf(CHAR_HEART_RATE, CHAR_SPO2, CHAR_SKIN_TEMP, CHAR_ACCELEROMETER).forEach { uuid ->
                enableNotification(gatt, uuid)
            }
        }

        // API 33+ preferred signature
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(
            gatt:           BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value:          ByteArray
        ) {
            handleCharacteristicData(characteristic.uuid, value)
        }

        // Deprecated but required for API < 33
        @Deprecated("Used for API < 33")
        override fun onCharacteristicChanged(
            gatt:           BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (Build.VERSION.SDK_INT < 33) {
                @Suppress("DEPRECATION")
                handleCharacteristicData(characteristic.uuid, characteristic.value)
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            gattResultChannel.trySend(status == BluetoothGatt.GATT_SUCCESS)
        }
    }

    private fun enableNotification(gatt: BluetoothGatt, charUuid: UUID) {
        val service = gatt.getService(BIPSS_SERVICE_UUID) ?: return
        val char    = service.getCharacteristic(charUuid) ?: return
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

    /**
     * Parse incoming bytes into typed sensor readings.
     *
     * TODO: The exact byte format below is a placeholder — the real encoding
     * depends on firmware and must be confirmed once the BIPSS band hardware is flashed.
     * Current assumption: little-endian int16 per value.
     */
    private fun handleCharacteristicData(uuid: UUID, value: ByteArray) {
        val reading = when (uuid) {
            CHAR_HEART_RATE -> {
                // Standard BLE HR Measurement format: flags byte + uint8 HR value
                val hr = if (value.isNotEmpty()) (value[0].toInt() and 0xFF) else return
                SensorReading(heartRate = hr)
            }
            CHAR_SPO2 -> {
                val spo2 = if (value.isNotEmpty()) (value[0].toInt() and 0xFF) else return
                SensorReading(spO2 = spo2)
            }
            CHAR_SKIN_TEMP -> {
                // TODO: Confirm byte format with firmware (placeholder: int16 / 100.0 = °C)
                if (value.size < 2) return
                val raw = ((value[1].toInt() and 0xFF) shl 8) or (value[0].toInt() and 0xFF)
                SensorReading(skinTempC = raw / 100f)
            }
            CHAR_ACCELEROMETER -> {
                // TODO: Confirm byte format (placeholder: 3x int16, little-endian, mg units)
                if (value.size < 6) return
                fun int16(b0: Byte, b1: Byte) = ((b1.toInt() shl 8) or (b0.toInt() and 0xFF)).toShort().toFloat()
                SensorReading(
                    accelX = int16(value[0], value[1]) / 1000f,
                    accelY = int16(value[2], value[3]) / 1000f,
                    accelZ = int16(value[4], value[5]) / 1000f
                )
            }
            else -> return
        }
        _sensorReadings.tryEmit(reading)
    }
}
