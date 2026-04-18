package cn.jarvis.hrbridge.data.prefs

import android.content.Context
import cn.jarvis.hrbridge.util.Logger
import kotlinx.coroutines.flow.first

/**
 * v1.2.7 → v2.0 SharedPreferences 迁移
 *
 * v1 使用 Context.getSharedPreferences("hrbridge", MODE_PRIVATE)，仅 5 个键：
 *   device_name / device_address / server_url / token / log（log 不迁移）
 */
object LegacyMigration {

    private const val LEGACY_PREFS = "hrbridge"

    suspend fun runIfNeeded(ctx: Context, store: SettingsStore) {
        val settings = store.settings.first()
        if (settings.migratedFromV1) return

        val legacy = ctx.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE)
        if (!legacy.contains("device_name") && !legacy.contains("server_url")) {
            // 新装用户，无 v1 数据
            store.markMigrated()
            return
        }

        val deviceName = legacy.getString("device_name", "") ?: ""
        val deviceMac  = legacy.getString("device_address", "") ?: ""
        val serverUrl  = legacy.getString("server_url", "") ?: ""
        val token      = legacy.getString("token", "") ?: ""

        if (deviceName.isNotEmpty() || deviceMac.isNotEmpty()) {
            store.setSelectedDevice(deviceName, deviceMac)
        }
        if (serverUrl.isNotEmpty()) store.setServerUrl(serverUrl)
        if (token.isNotEmpty()) store.setToken(token)

        store.markMigrated()

        // 清空旧 prefs（包括无用的 log 字段）
        legacy.edit().clear().apply()

        Logger.i("Migration",
            "v1→v2 迁移完成: device=$deviceName mac=$deviceMac serverUrl=${serverUrl.take(40)} token=${if (token.isEmpty()) "<empty>" else "<set>"}")
    }
}
