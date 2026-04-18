package cn.jarvis.hrbridge.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.jarvis.hrbridge.ServiceLocator
import cn.jarvis.hrbridge.ble.BleConstants
import cn.jarvis.hrbridge.ble.BleScanner
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

data class ScanUiState(
    val devices: List<BleScanner.Discovery> = emptyList(),
    val scanning: Boolean = false,
    val error: String? = null
)

class ScanViewModel : ViewModel() {
    private val scanner = ServiceLocator.bleScanner
    private val settingsStore = ServiceLocator.settingsStore

    private val _state = MutableStateFlow(ScanUiState())
    val state: StateFlow<ScanUiState> = _state.asStateFlow()

    private var scanJob: Job? = null

    fun startScan() {
        if (_state.value.scanning) return
        if (!scanner.isReady()) {
            _state.value = _state.value.copy(error = "蓝牙未开启或不支持 BLE")
            return
        }
        _state.value = ScanUiState(scanning = true)

        scanJob = viewModelScope.launch {
            // 自动超时
            val timeout = viewModelScope.launch {
                delay(BleConstants.SCAN_TIMEOUT_MS)
                stopScan()
            }
            runCatching {
                scanner.scan().collect { d ->
                    if (d.name.isNullOrBlank()) return@collect
                    val current = _state.value.devices
                    if (current.none { it.mac == d.mac }) {
                        _state.value = _state.value.copy(devices = current + d)
                    } else {
                        // 更新 RSSI
                        _state.value = _state.value.copy(
                            devices = current.map { if (it.mac == d.mac) d else it }
                        )
                    }
                }
            }.onFailure { e ->
                _state.value = _state.value.copy(error = e.message, scanning = false)
            }
            timeout.cancel()
        }
    }

    fun stopScan() {
        scanJob?.cancel()
        scanJob = null
        _state.value = _state.value.copy(scanning = false)
    }

    fun select(d: BleScanner.Discovery, onDone: () -> Unit) {
        viewModelScope.launch {
            settingsStore.setSelectedDevice(d.name.orEmpty(), d.mac)
            onDone()
        }
    }

    override fun onCleared() {
        stopScan()
        super.onCleared()
    }
}
