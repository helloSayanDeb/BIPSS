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
import com.bipss.app.BuildConfig
import com.bipss.app.ble.BleConstants.BATTERY_SERVICE
import com.bipss.app.ble.BleConstants.BIPSS_SERVICE_UUID
import com.bipss.app.ble.BleConstants.CCCD_UUID
import com.bipss.app.ble.BleConstants.CHAR_BATTERY_LEVEL
import com.bipss.app.ble.BleConstants.CHAR_STATUS
import com.bipss.app.ble.BleConstants.CHAR_WAVEFORM
import com.bipss.app.ble.protocol.BandPacketParser
import com.bipss.app.ble.protocol.BandProtocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

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

/** Which wire protocol the connected band speaks - see docs/ble-protocol-v2.md. */
enum class BandProtocolVersion {
    /** 24-byte frame only: no motion data, no waveform. */
    V1_LEGACY,
    /** 20-byte status + waveform packets. */
    V2
}

/**
 * One decoded 1 Hz reading, stamped with the time it was measured.
 *
 * [timestamp] comes from the band's uptime clock mapped onto wall time by
 * [BandClock], not from arrival time: if the band ever buffers and replays,
 * arrival-time stamping would pile samples onto one instant.
 */
data class SensorReading(
    val heartRate:      Int?   = null,
    /**
     * RMSSD of pulse intervals (PRV), ms. As the band computed it in a raw
     * reading; in [com.bipss.app.data.SensorRepository.sensorEvents], the
     * phone pipeline's value while the band streams its waveform.
     */
    val prvRmssdMs:     Int?   = null,
    val spO2:           Int?   = null,
    val skinTempC:      Float? = null,
    val ambientTempC:   Float? = null,
    val humidityPct:    Float? = null,
    /** ENS160 air quality on the UBA 1..5 scale - not comparable to a 0..500 AQI. */
    val ensUba:         Int?   = null,
    /** Movement over the last second, mg*s. Null on v1 bands, which have no motion data. */
    val activityCount:  Int?   = null,
    /** The band is streaming its pulse waveform, so heart rate and PRV come from the phone's pipeline. */
    val waveformStreaming: Boolean = false,
    /** Where [heartRate] came from, in the events the app consumes. */
    val pulseSource:    PulseSource = PulseSource.BAND,
    val timestamp:      Long   = System.currentTimeMillis()
)

enum class PulseSource {
    /** The band's own on-device beat detector. */
    BAND,
    /** The phone's signal pipeline on the streamed waveform (motion-cancelled, quality-checked). */
    PIPELINE
}

/**
 * Entry point for the debug band simulator. Packets go through the same
 * decode path as the radio - parser, clock, loss detection - so simulated data
 * exercises exactly the code a real band does.
 *
 * Unlike the radio path, which drops a packet when consumers are behind,
 * these suspend until there is room: a simulator replaying hours of data in
 * minutes must be paced by the app, not outrun it.
 */
interface BandPacketSink {
    suspend fun status(bytes: ByteArray, arrivalMs: Long)
    suspend fun waveform(bytes: ByteArray, arrivalMs: Long)
}

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
     * PPG + accelerometer, 12.5 chunks a second from a v2 band. A consumer that
     * falls far enough behind loses chunks here; it must check
     * [WaveformChunk.firstSampleIndex] continuity itself, not only
     * [WaveformChunk.gapBefore].
     */
    private val _waveform = MutableSharedFlow<WaveformChunk>(replay = 0, extraBufferCapacity = 512)
    val waveform: SharedFlow<WaveformChunk> = _waveform.asSharedFlow()

    private val _protocol = MutableStateFlow<BandProtocolVersion?>(null)
    /** Null while no band is connected. */
    val protocolVersion: StateFlow<BandProtocolVersion?> = _protocol.asStateFlow()

    private val _legacyFramesTruncated = MutableStateFlow(false)
    /**
     * A v1 band whose 24-byte frames arrive cut to 20 bytes because the MTU
     * stayed at its default: nothing it sends can be decoded.
     */
    val legacyFramesTruncated: StateFlow<Boolean> = _legacyFramesTruncated.asStateFlow()

    private val _isSimulated = MutableStateFlow(false)
    /** True while the debug simulator, not a radio, is the source of band data. */
    val isSimulated: StateFlow<Boolean> = _isSimulated.asStateFlow()

    /**
     * Band battery, kept outside [SensorReading]: it is device telemetry, and
     * letting it into raw_readings would put it into the baseline maths.
     */
    private val _batteryPercent = MutableStateFlow<Int?>(null)
    val batteryPercent: StateFlow<Int?> = _batteryPercent.asStateFlow()

    private var gatt: BluetoothGatt? = null
    private var pairedMac: String? = null
    private var reconnectAttempts = 0
    private var reconnectJob: Job? = null
    private var mtuFallbackJob: Job? = null
    @Volatile private var userInitiatedDisconnect = false

    // Per-connection state, reset by resetLinkState().
    private val linkLock = Any()
    private val clock = BandClock()
    private val assembler = WaveformAssembler(clock)
    private val opQueue = GattOpQueue(scope)
    private val subscribed = AtomicBoolean(false)
    private var mtuAnswered = false
    private var cacheRefreshTried = false
    @Volatile private var cacheRefreshInFlight = false
    private var staleCacheReported = false
    private var consecutiveV2Headers = 0

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
        if (_isSimulated.value) return
        stopScan()
        reconnectJob?.cancel()
        userInitiatedDisconnect = false
        pairedMac = address
        val device = bluetoothAdapter?.getRemoteDevice(address) ?: return
        _connectionState.value = BleConnectionState.Connecting

        // A second connectGatt without closing the first leaks a client
        // interface; Android has a small fixed number of them, and once they
        // are exhausted no connection works until Bluetooth is restarted.
        closeGatt()

        // Bonding gives link-layer encryption for health data in transit.
        if (device.bondState == BluetoothDevice.BOND_NONE) {
            runCatching { device.createBond() }
        }
        gatt = device.connectGatt(context, autoConnect, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    fun disconnect() {
        userInitiatedDisconnect = true
        reconnectJob?.cancel()
        gatt?.disconnect()
        closeGatt()
        if (_isSimulated.value) detachSimulatedBand()
        _connectionState.value = BleConnectionState.Disconnected
    }

    private fun closeGatt() {
        mtuFallbackJob?.cancel()
        opQueue.clear()
        gatt?.close()
        gatt = null
        resetLinkState()
    }

    private fun resetLinkState() {
        synchronized(linkLock) {
            // The band may have rebooted while away, so nothing about its
            // clocks can be carried across a reconnect.
            clock.reset()
            assembler.reset()
        }
        subscribed.set(false)
        _legacyFramesTruncated.value = false
        mtuAnswered = false
        cacheRefreshTried = false
        cacheRefreshInFlight = false
        staleCacheReported = false
        consecutiveV2Headers = 0
        _protocol.value = null
    }

    // -- GATT callbacks --------------------------------------------------------

    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (g !== gatt) {
                // A late callback for a handle we have already replaced.
                if (newState == BluetoothProfile.STATE_DISCONNECTED) g.close()
                return
            }
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.i(TAG, "Connected - discovering services")
                    reconnectAttempts = 0
                    // Fast intervals for discovery and subscription only; see subscribeAll.
                    g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                    g.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.w(TAG, "Disconnected (status=$status)")
                    closeGatt()
                    _connectionState.value = BleConnectionState.Disconnected
                    scheduleReconnect()
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            cacheRefreshInFlight = false
            if (status != BluetoothGatt.GATT_SUCCESS) {
                _connectionState.value = BleConnectionState.Error("Service discovery failed")
                return
            }
            val service = g.getService(BIPSS_SERVICE_UUID)
            if (service == null) {
                if (refreshServiceCache(g)) return
                _connectionState.value = BleConnectionState.Error(
                    "This device doesn't offer the BIPSS band service. Unpair it in Android's Bluetooth settings and pair again."
                )
                return
            }
            _protocol.value = if (service.getCharacteristic(CHAR_WAVEFORM) != null) {
                BandProtocolVersion.V2
            } else {
                BandProtocolVersion.V1_LEGACY
            }
            Log.i(TAG, "Band protocol: ${_protocol.value}")
            _connectionState.value = BleConnectionState.Connected

            if (mtuAnswered) {
                // Rediscovery after a cache refresh: the MTU is already settled.
                subscribeAll(g)
            } else {
                g.requestMtu(BleConstants.PREFERRED_MTU)
                mtuFallbackJob?.cancel()
                mtuFallbackJob = scope.launch {
                    delay(BleConstants.MTU_ANSWER_TIMEOUT_MS)
                    Log.w(TAG, "No MTU answer - subscribing at the default MTU")
                    subscribeAll(g)
                }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            // Any status: v2 packets fit the default MTU, so a refused
            // negotiation is logged and the subscription goes ahead regardless.
            Log.i(TAG, "MTU now $mtu (status=$status)")
            mtuAnswered = true
            mtuFallbackJob?.cancel()
            // Android 14+ exchanges the MTU on its own as soon as the link is
            // up, which can land before discovery: there is nothing to
            // subscribe to yet, and onServicesDiscovered will do it.
            if (_protocol.value == null) return
            subscribeAll(g)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(TAG, "Subscribe to ${descriptor.characteristic.uuid} failed: $status")
            }
            opQueue.complete()
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(
            g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray
        ) = handleCharacteristicData(g, characteristic.uuid, value)

        @Deprecated("Used for API < 33")
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < 33) {
                @Suppress("DEPRECATION")
                characteristic.value?.let { handleCharacteristicData(g, characteristic.uuid, it.copyOf()) }
            }
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicRead(
            g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray, status: Int
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) handleCharacteristicData(g, characteristic.uuid, value)
            opQueue.complete()
        }

        @Deprecated("Used for API < 33")
        override fun onCharacteristicRead(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (Build.VERSION.SDK_INT < 33) {
                @Suppress("DEPRECATION")
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    characteristic.value?.let { handleCharacteristicData(g, characteristic.uuid, it.copyOf()) }
                }
                opQueue.complete()
            }
        }
    }

    /** Queues every subscription, then relaxes the connection interval. Runs once per connection. */
    private fun subscribeAll(g: BluetoothGatt) {
        if (!subscribed.compareAndSet(false, true)) return
        enqueueNotification(g, BIPSS_SERVICE_UUID, CHAR_STATUS)
        if (_protocol.value == BandProtocolVersion.V2) enqueueNotification(g, BIPSS_SERVICE_UUID, CHAR_WAVEFORM)
        // Battery lives in its own standard service, which the band may not have.
        enqueueNotification(g, BATTERY_SERVICE, CHAR_BATTERY_LEVEL)
        g.getService(BATTERY_SERVICE)?.getCharacteristic(CHAR_BATTERY_LEVEL)?.let { char ->
            opQueue.enqueue(GattOpQueue.Op("read battery") { g.readCharacteristic(char) })
        }
        // 12.5 packets a second of 20 bytes needs nothing like HIGH priority;
        // BALANCED roughly halves the radio's duty cycle on both ends.
        opQueue.enqueue(GattOpQueue.Op("balanced priority", awaitsCallback = false) {
            g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_BALANCED)
        })
    }

    private fun enqueueNotification(g: BluetoothGatt, serviceUuid: UUID, charUuid: UUID) {
        val char = g.getService(serviceUuid)?.getCharacteristic(charUuid) ?: run {
            Log.w(TAG, "Characteristic $charUuid not found"); return
        }
        opQueue.enqueue(GattOpQueue.Op("subscribe $charUuid") {
            if (!g.setCharacteristicNotification(char, true)) return@Op false
            val descriptor = char.getDescriptor(CCCD_UUID) ?: return@Op false
            if (Build.VERSION.SDK_INT >= 33) {
                g.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) ==
                    BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION")
                g.writeDescriptor(descriptor)
            }
        })
    }

    /**
     * Android caches a bonded device's GATT table. After the band is reflashed
     * from v1 to v2 the cache still shows the old table, so the new waveform
     * characteristic "does not exist". BluetoothGatt.refresh() is hidden API,
     * reached by reflection; it is tried once per connection, then the user is
     * asked to re-pair, which always clears the cache.
     */
    private fun refreshServiceCache(g: BluetoothGatt): Boolean {
        if (cacheRefreshTried) return false
        cacheRefreshTried = true
        val refreshed = runCatching {
            g.javaClass.getMethod("refresh").invoke(g) as? Boolean ?: false
        }.getOrDefault(false)
        Log.i(TAG, "GATT cache refresh: $refreshed")
        if (!refreshed) return false
        opQueue.clear()
        subscribed.set(false)
        cacheRefreshInFlight = true
        _protocol.value = null
        scope.launch {
            delay(600)  // the stack needs a moment before rediscovery sees the new table
            g.discoverServices()
        }
        return true
    }

    /** Exponential backoff, capped. Skipped when the user disconnected deliberately. */
    private fun scheduleReconnect() {
        if (userInitiatedDisconnect || _isSimulated.value) return
        val mac = pairedMac ?: return
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            val delayMs = minOf(1000L shl reconnectAttempts.coerceAtMost(6), BleConstants.MAX_RECONNECT_DELAY_MS)
            reconnectAttempts++
            _connectionState.value = BleConnectionState.Reconnecting(reconnectAttempts)
            delay(delayMs)
            if (!userInitiatedDisconnect) connectToDevice(mac, autoConnect = true)
        }
    }

    // -- Packets ---------------------------------------------------------------

    private fun handleCharacteristicData(g: BluetoothGatt?, uuid: UUID, value: ByteArray) {
        val arrival = System.currentTimeMillis()
        when (uuid) {
            CHAR_BATTERY_LEVEL -> if (value.isNotEmpty()) {
                // The standard battery service has no "unknown" value, but 0xFF
                // is what a band without a readable battery pin sends here too.
                _batteryPercent.value = (value[0].toInt() and 0xFF).takeIf { it in 0..100 }
            }
            CHAR_WAVEFORM -> decodeWaveform(value)?.let { chunk ->
                if (!_waveform.tryEmit(chunk)) Log.w(TAG, "Waveform consumer behind - chunk dropped")
            }
            CHAR_STATUS -> when {
                _protocol.value == BandProtocolVersion.V2 -> decodeStatus(value, arrival)?.let { reading ->
                    if (!_sensorReadings.tryEmit(reading)) Log.w(TAG, "Reading consumer behind - reading dropped")
                }
                // Mid-refresh the table is in flux; the next discovery decides.
                cacheRefreshInFlight -> Unit
                looksLikeV2Status(value) -> {
                    // A v1 frame cut to 20 bytes starts with its sequence
                    // number, which is 0x21 once every 256 frames. A reflashed
                    // v2 band sends the header on every packet.
                    if (++consecutiveV2Headers >= V2_HEADERS_BEFORE_STALE) onStaleCacheSuspected(g)
                }
                else -> {
                    consecutiveV2Headers = 0
                    handleLegacyFrame(value, arrival)
                }
            }
        }
    }

    private companion object {
        const val V2_HEADERS_BEFORE_STALE = 3
    }

    private fun looksLikeV2Status(value: ByteArray) =
        value.size == BandProtocol.PACKET_SIZE && (value[0].toInt() and 0xFF) == BandProtocol.HEADER_STATUS

    /** A v2 packet on a link whose GATT table says v1: the cached table is stale. */
    private fun onStaleCacheSuspected(g: BluetoothGatt?) {
        if (g == null || staleCacheReported) return
        if (refreshServiceCache(g)) return
        staleCacheReported = true
        Log.w(TAG, "Band sends v2 packets but the cached GATT table is v1")
        _connectionState.value = BleConnectionState.Error(
            "Your band's firmware was updated. Unpair it in Android's Bluetooth settings, then pair it again in BIPSS."
        )
    }

    private fun decodeStatus(bytes: ByteArray, arrivalMs: Long): SensorReading? {
        val p = BandPacketParser.parseStatus(bytes) ?: run {
            Log.w(TAG, "Unparseable status packet of ${bytes.size} bytes"); return null
        }
        val wall = synchronized(linkLock) {
            clock.onStatus(p.bandUptimeMs, p.sampleIndex, arrivalMs) ?: run {
                // Uptime ran backwards: the band rebooted. The clock has
                // reset; this packet starts the new mapping.
                assembler.reset()
                clock.onStatus(p.bandUptimeMs, p.sampleIndex, arrivalMs)
            }
        } ?: return null
        p.batteryPct?.let { _batteryPercent.value = it }
        return SensorReading(
            heartRate      = p.hr,
            prvRmssdMs     = p.prvRmssdMs,
            spO2           = p.spo2,
            skinTempC      = p.skinTempC,
            ambientTempC   = p.ambientTempC,
            humidityPct    = p.humidityPct,
            ensUba         = p.ensUba,
            activityCount  = p.activityCount,
            waveformStreaming = p.waveformStreaming,
            timestamp      = wall
        )
    }

    private fun decodeWaveform(bytes: ByteArray): WaveformChunk? {
        val p = BandPacketParser.parseWaveform(bytes) ?: return null
        return synchronized(linkLock) { assembler.accept(p) }
    }

    private val sink = object : BandPacketSink {
        // Once detached (e.g. "Forget" or "Delete my data"), a simulator that
        // is still running is ignored rather than decoded and stored.
        override suspend fun status(bytes: ByteArray, arrivalMs: Long) {
            if (!_isSimulated.value) return
            decodeStatus(bytes, arrivalMs)?.let { _sensorReadings.emit(it) }
        }
        override suspend fun waveform(bytes: ByteArray, arrivalMs: Long) {
            if (!_isSimulated.value) return
            decodeWaveform(bytes)?.let { _waveform.emit(it) }
        }
    }

    private fun handleLegacyFrame(value: ByteArray, arrivalMs: Long) {
        val frame = BandPacketParser.parseLegacy(value) ?: run {
            Log.w(TAG, "Unparseable v1 frame of ${value.size} bytes")
            if (value.size < BandProtocol.LEGACY_FRAME_SIZE) _legacyFramesTruncated.value = true
            return
        }
        _legacyFramesTruncated.value = false
        val wall = synchronized(linkLock) {
            clock.onUptimeOnly(frame.bandUptimeMs, arrivalMs) ?: clock.onUptimeOnly(frame.bandUptimeMs, arrivalMs)
        } ?: return
        frame.batteryPct?.let { _batteryPercent.value = it }
        _sensorReadings.tryEmit(
            SensorReading(
                heartRate      = frame.hr,
                prvRmssdMs     = frame.prvRmssdMs,
                spO2           = frame.spo2,
                skinTempC      = frame.skinTempC,
                ambientTempC   = frame.ambientTempC,
                humidityPct    = frame.humidityPct,
                ensUba         = frame.ensUba,
                activityCount  = null,
                timestamp      = wall
            )
        )
    }

    // -- Debug simulator hook ----------------------------------------------------

    /**
     * Presents the debug simulator as a connected v2 band and returns the sink
     * it feeds. Returns null in release builds, where the simulator does not
     * exist. Any real connection is closed first so the two can never interleave.
     */
    fun attachSimulatedBand(): BandPacketSink? {
        if (!BuildConfig.DEBUG) return null
        userInitiatedDisconnect = true
        reconnectJob?.cancel()
        gatt?.disconnect()
        closeGatt()
        _isSimulated.value = true
        _protocol.value = BandProtocolVersion.V2
        _batteryPercent.value = null
        _connectionState.value = BleConnectionState.Connected
        return sink
    }

    fun detachSimulatedBand() {
        if (!_isSimulated.value) return
        _isSimulated.value = false
        resetLinkState()
        _batteryPercent.value = null
        _connectionState.value = BleConnectionState.Disconnected
    }
}
