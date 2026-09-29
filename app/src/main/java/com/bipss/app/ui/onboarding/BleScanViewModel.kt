package com.bipss.app.ui.onboarding

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bipss.app.ble.BleConnectionState
import com.bipss.app.ble.BleForegroundService
import com.bipss.app.ble.BleGattManager
import com.bipss.app.data.UserPreferencesRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class BleScanViewModel(
    private val bleGattManager: BleGattManager,
    private val repository:     UserPreferencesRepository,
    private val context:        Context
) : ViewModel() {

    val connectionState: StateFlow<BleConnectionState> =
        bleGattManager.connectionState.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            BleConnectionState.Disconnected
        )

    private val adapter: BluetoothAdapter? =
        context.getSystemService(BluetoothManager::class.java)?.adapter

    /** False on a phone with no Bluetooth at all - nothing to turn on, only skip. */
    val hasBluetooth: Boolean = adapter != null

    private val _bluetoothOn = MutableStateFlow(adapter?.isEnabled == true)
    /**
     * Whether Bluetooth is switched on, kept current by the system broadcast so
     * the scan can start the moment the user turns it on.
     */
    val bluetoothOn: StateFlow<Boolean> = _bluetoothOn.asStateFlow()

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            val s = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
            _bluetoothOn.value = s == BluetoothAdapter.STATE_ON
        }
    }

    init {
        ContextCompat.registerReceiver(
            context, stateReceiver,
            IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    /** Backstop for a missed broadcast: re-reads the adapter (called on resume). */
    fun refreshBluetoothState() { _bluetoothOn.value = adapter?.isEnabled == true }

    @SuppressLint("MissingPermission")
    fun startScan() {
        if (adapter?.isEnabled != true) return
        bleGattManager.startScan()
    }

    fun stopScan() {
        bleGattManager.stopScan()
    }

    fun connectToDevice(address: String) {
        // The service has to be running before the link is established, so the
        // connection survives the app going to the background - which is what
        // happens every night during sleep tracking.
        BleForegroundService.start(context)
        bleGattManager.connectToDevice(address)
    }

    fun savePairedDevice(mac: String, name: String) {
        viewModelScope.launch {
            repository.savePairedBand(mac = mac, name = name)
        }
    }

    override fun onCleared() {
        super.onCleared()
        runCatching { context.unregisterReceiver(stateReceiver) }
        bleGattManager.stopScan()
    }
}
