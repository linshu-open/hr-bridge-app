package cn.jarvis.hrbridge.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.jarvis.hrbridge.ServiceLocator
import cn.jarvis.hrbridge.data.prefs.AppSettings
import cn.jarvis.hrbridge.ota.Updater
import cn.jarvis.hrbridge.util.HrThresholds
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.launch

data class SettingsUiState(
    val settings: AppSettings = AppSettings(),
    val updateInfo: Updater.UpdateInfo? = null,
    val checkingUpdate: Boolean = false,
    val downloadingProgress: Int? = null,
    val updateMessage: String? = null
)

class SettingsViewModel : ViewModel() {
    private val store = ServiceLocator.settingsStore
    private val updater = Updater(ServiceLocator.applicationContext)

    private val _checkingUpdate = MutableStateFlow(false)
    private val _updateInfo = MutableStateFlow<Updater.UpdateInfo?>(null)
    private val _updateMessage = MutableStateFlow<String?>(null)
    private val _progress = MutableStateFlow<Int?>(null)

    val state: StateFlow<SettingsUiState> = combine(
        store.settings, _checkingUpdate, _updateInfo, _updateMessage, _progress
    ) { settings, checking, info, msg, prog ->
        SettingsUiState(
            settings = settings,
            updateInfo = info,
            checkingUpdate = checking,
            downloadingProgress = prog,
            updateMessage = msg
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, SettingsUiState())

    fun setServerUrl(url: String)          = viewModelScope.launch { store.setServerUrl(url.trim()) }
    fun setBatchUrl(url: String)           = viewModelScope.launch { store.setBatchUrl(url.trim()) }
    fun setToken(t: String)                = viewModelScope.launch { store.setToken(t.trim()) }
    fun setAutoReconnect(on: Boolean)      = viewModelScope.launch { store.setAutoReconnect(on) }
    fun setAlertEnabled(on: Boolean)       = viewModelScope.launch { store.setAlertEnabled(on) }
    fun setDynamicColor(on: Boolean)       = viewModelScope.launch { store.setDynamicColor(on) }
    fun setDarkTheme(mode: Int)            = viewModelScope.launch { store.setDarkTheme(mode) }
    fun setUploadInterval(sec: Int)        = viewModelScope.launch { store.setUploadInterval(sec) }
    fun setQuietHours(start: Int, end: Int)= viewModelScope.launch { store.setQuietHours(start, end) }
    fun setThresholds(t: HrThresholds)     = viewModelScope.launch { store.setThresholds(t) }

    fun checkUpdate() {
        viewModelScope.launch {
            _checkingUpdate.value = true
            _updateMessage.value = null
            updater.check().fold(
                onSuccess = { info ->
                    _updateInfo.value = info
                    _updateMessage.value = if (info.isNewer) {
                        "有新版本 ${info.latestVersion}"
                    } else "已是最新 ${info.currentVersion}"
                },
                onFailure = { _updateMessage.value = "检查失败: ${it.message}" }
            )
            _checkingUpdate.value = false
        }
    }

    fun downloadAndInstall() {
        val info = _updateInfo.value ?: return
        val url = info.downloadUrl ?: return
        viewModelScope.launch {
            _progress.value = 0
            runCatching {
                val apk = updater.downloadApk(url) { _progress.value = it }
                _progress.value = null
                updater.launchInstaller(apk)
            }.onFailure {
                _progress.value = null
                _updateMessage.value = "下载失败: ${it.message}"
            }
        }
    }
}
