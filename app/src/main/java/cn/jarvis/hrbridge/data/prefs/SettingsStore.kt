package cn.jarvis.hrbridge.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import cn.jarvis.hrbridge.BuildConfig
import cn.jarvis.hrbridge.util.HrThresholds
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

/** 单例 DataStore（绑定到应用上下文） */
private val Context.dataStore by preferencesDataStore(name = "settings")

/** 应用设置模型 */
data class AppSettings(
    val selectedDeviceName: String = "",
    val selectedDeviceMac: String = "",
    val serverUrl: String = BuildConfig.DEFAULT_SERVER_URL,
    val batchUrl: String = BuildConfig.DEFAULT_BATCH_URL,
    val authToken: String = "",

    val thresholds: HrThresholds = HrThresholds.DEFAULT,
    val uploadIntervalSec: Int = 60,
    val alertEnabled: Boolean = true,
    val alertQuietStart: Int = 23,   // 0..23 小时
    val alertQuietEnd: Int = 7,

    val autoReconnect: Boolean = true,
    val dynamicColor: Boolean = true,
    val darkTheme: Int = DARK_SYSTEM,

    val migratedFromV1: Boolean = false
) {
    companion object {
        const val DARK_SYSTEM = 0
        const val DARK_ALWAYS = 1
        const val DARK_NEVER  = 2
    }
}

class SettingsStore(private val ctx: Context) {

    private object Keys {
        val DEVICE_NAME = stringPreferencesKey("selected_device_name")
        val DEVICE_MAC  = stringPreferencesKey("selected_device_address")
        val SERVER_URL  = stringPreferencesKey("server_url")
        val BATCH_URL   = stringPreferencesKey("batch_url")
        val TOKEN       = stringPreferencesKey("auth_token")

        val TH_CRIT_LOW = intPreferencesKey("th_critical_low")
        val TH_LOW      = intPreferencesKey("th_low")
        val TH_NORMAL   = intPreferencesKey("th_normal_max")
        val TH_ELEV     = intPreferencesKey("th_elevated")
        val TH_CRIT_HI  = intPreferencesKey("th_critical_high")

        val UPLOAD_INT   = intPreferencesKey("upload_interval_sec")
        val ALERT_EN     = booleanPreferencesKey("alert_enabled")
        val QUIET_START  = intPreferencesKey("alert_quiet_start")
        val QUIET_END    = intPreferencesKey("alert_quiet_end")

        val AUTO_RECONN  = booleanPreferencesKey("auto_reconnect")
        val DYNAMIC_COLOR= booleanPreferencesKey("dynamic_color")
        val DARK_THEME   = intPreferencesKey("dark_theme")

        val MIGRATED_V1  = booleanPreferencesKey("migrated_from_v1")
    }

    val settings: Flow<AppSettings> = ctx.dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { p ->
            AppSettings(
                selectedDeviceName = p[Keys.DEVICE_NAME] ?: "",
                selectedDeviceMac  = p[Keys.DEVICE_MAC]  ?: "",
                serverUrl          = p[Keys.SERVER_URL]  ?: BuildConfig.DEFAULT_SERVER_URL,
                batchUrl           = p[Keys.BATCH_URL]   ?: BuildConfig.DEFAULT_BATCH_URL,
                authToken          = p[Keys.TOKEN]       ?: "",
                thresholds = HrThresholds(
                    criticalLow  = p[Keys.TH_CRIT_LOW] ?: 50,
                    low          = p[Keys.TH_LOW]      ?: 60,
                    normalMax    = p[Keys.TH_NORMAL]   ?: 100,
                    elevated     = p[Keys.TH_ELEV]     ?: 120,
                    criticalHigh = p[Keys.TH_CRIT_HI]  ?: 140
                ),
                uploadIntervalSec = p[Keys.UPLOAD_INT]   ?: 60,
                alertEnabled      = p[Keys.ALERT_EN]     ?: true,
                alertQuietStart   = p[Keys.QUIET_START]  ?: 23,
                alertQuietEnd     = p[Keys.QUIET_END]    ?: 7,
                autoReconnect     = p[Keys.AUTO_RECONN]  ?: true,
                dynamicColor     = p[Keys.DYNAMIC_COLOR] ?: true,
                darkTheme         = p[Keys.DARK_THEME]   ?: AppSettings.DARK_SYSTEM,
                migratedFromV1    = p[Keys.MIGRATED_V1]  ?: false
            )
        }

    suspend fun setSelectedDevice(name: String, mac: String) = edit {
        it[Keys.DEVICE_NAME] = name
        it[Keys.DEVICE_MAC] = mac
    }

    suspend fun setServerUrl(url: String) = edit { it[Keys.SERVER_URL] = url }
    suspend fun setBatchUrl(url: String)  = edit { it[Keys.BATCH_URL] = url }
    suspend fun setToken(token: String)   = edit { it[Keys.TOKEN] = token }

    suspend fun setThresholds(t: HrThresholds) = edit {
        it[Keys.TH_CRIT_LOW] = t.criticalLow
        it[Keys.TH_LOW]      = t.low
        it[Keys.TH_NORMAL]   = t.normalMax
        it[Keys.TH_ELEV]     = t.elevated
        it[Keys.TH_CRIT_HI]  = t.criticalHigh
    }

    suspend fun setUploadInterval(sec: Int) = edit { it[Keys.UPLOAD_INT] = sec }
    suspend fun setAlertEnabled(enabled: Boolean) = edit { it[Keys.ALERT_EN] = enabled }
    suspend fun setQuietHours(start: Int, end: Int) = edit {
        it[Keys.QUIET_START] = start
        it[Keys.QUIET_END] = end
    }
    suspend fun setAutoReconnect(on: Boolean)  = edit { it[Keys.AUTO_RECONN] = on }
    suspend fun setDynamicColor(on: Boolean)   = edit { it[Keys.DYNAMIC_COLOR] = on }
    suspend fun setDarkTheme(mode: Int)        = edit { it[Keys.DARK_THEME] = mode }
    suspend fun markMigrated()                 = edit { it[Keys.MIGRATED_V1] = true }

    private suspend inline fun edit(crossinline block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        ctx.dataStore.edit { block(it) }
    }
}
