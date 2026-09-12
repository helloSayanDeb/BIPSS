package com.bipss.app.ui.onboarding

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bipss.app.ble.BleConnectionState
import com.bipss.app.ble.BleForegroundService
import com.bipss.app.ble.BleGattManager
import com.bipss.app.data.UserPreferencesRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
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

    @SuppressLint("MissingPermission")
    fun startScan() {
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
        bleGattManager.stopScan()
    }
}
