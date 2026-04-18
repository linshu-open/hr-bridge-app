package cn.jarvis.hrbridge.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.jarvis.hrbridge.ServiceLocator
import cn.jarvis.hrbridge.data.prefs.AppSettings
import cn.jarvis.hrbridge.data.remote.HrUploadRequest
import cn.jarvis.hrbridge.util.HrStatus
import cn.jarvis.hrbridge.util.Logger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.launch

data class HomeUiState(
    val hrSamples: List<Int> = emptyList(),
    val min: Int? = null,
    val avg: Int? = null,
    val max: Int? = null,
    val pendingCount: Int = 0,
    val settings: AppSettings = AppSettings(),
    val testing: Boolean = false,
    val lastTestMessage: String? = null
) {
    val latestHr: Int? get() = hrSamples.lastOrNull()
    val hasDevice: Boolean get() = settings.selectedDeviceMac.isNotEmpty()
}

class HomeViewModel : ViewModel() {
    private val repo = ServiceLocator.hrRepository
    private val settingsStore = ServiceLocator.settingsStore
    private val jarvisApi = ServiceLocator.jarvisApi

    private val _testing = MutableStateFlow(false)
    private val _testMessage = MutableStateFlow<String?>(null)

    private val recentFlow: Flow<List<Int>> =
        repo.observeRecent().map { list -> list.map { it.hr }.reversed() }

    val state: StateFlow<HomeUiState> = combine(
        recentFlow,
        repo.observePendingCount(),
        settingsStore.settings,
        _testing,
        _testMessage
    ) { samples, pending, settings, testing, msg ->
        HomeUiState(
            hrSamples = samples,
            min = samples.minOrNull(),
            avg = if (samples.isEmpty()) null else samples.average().toInt(),
            max = samples.maxOrNull(),
            pendingCount = pending,
            settings = settings,
            testing = testing,
            lastTestMessage = msg
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, HomeUiState())

    fun testServer() {
        viewModelScope.launch {
            _testing.value = true
            _testMessage.value = null
            val s = state.value.settings
            val body = HrUploadRequest(
                hr = 75, avg = 75, status = HrStatus.TEST.wire, trend = "stable",
                samples = 1, device = "test", ts = System.currentTimeMillis() / 1000,
                token = s.authToken.ifEmpty { null }
            )
            jarvisApi.uploadSingle(s.serverUrl, body).fold(
                onSuccess = {
                    _testMessage.value = "连接正常 · ${it.message ?: "ok"}"
                    Logger.i("HomeVM", "测试连接成功")
                },
                onFailure = {
                    _testMessage.value = "失败: ${it.message?.take(80)}"
                    Logger.w("HomeVM", "测试连接失败: ${it.message}")
                }
            )
            _testing.value = false
        }
    }

    fun clearTestMessage() { _testMessage.value = null }
}
