package com.ossm.remote.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ossm.remote.ble.BleManager
import com.ossm.remote.model.BleConnectionState
import com.ossm.remote.model.BleDevice
import com.ossm.remote.model.MachineState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class BleViewModel @Inject constructor(
    private val bleManager: BleManager
) : ViewModel() {

    val connectionState: StateFlow<BleConnectionState> = bleManager.connectionState
        .stateIn(viewModelScope, SharingStarted.Eagerly, BleConnectionState.Disconnected)

    val scannedDevices: StateFlow<List<BleDevice>> = bleManager.scannedDevices
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val machineState: StateFlow<MachineState> = bleManager.machineState
        .stateIn(viewModelScope, SharingStarted.Eagerly, MachineState())

    fun startScan() = bleManager.startScan()
    fun stopScan() = bleManager.stopScan()
    fun connect(address: String) = bleManager.connect(address)
    fun disconnect() = bleManager.disconnect()
    fun emergencyStop() = bleManager.emergencyStop()
    fun home(targetPage: String = "strokeEngine", patternId: Int = 0) =
        bleManager.home(targetPage, patternId)
    fun isBluetoothEnabled() = bleManager.isBluetoothEnabled()

    override fun onCleared() {
        super.onCleared()
        bleManager.stopScan()
    }
}
