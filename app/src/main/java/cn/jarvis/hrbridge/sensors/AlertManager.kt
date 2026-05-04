package cn.jarvis.hrbridge.sensors

import android.app.NotificationManager
import android.content.Context
import androidx.core.content.getSystemService
import cn.jarvis.hrbridge.data.prefs.AppSettings
import cn.jarvis.hrbridge.service.NotifyHelper
import cn.jarvis.hrbridge.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 多传感器告警管理器。
 *
 * - **跌倒检测 P0**：accelerometer 的 fall_detected=true → 立即通知
 * - **久坐提醒 P2**：accelerometer 的 still_duration_min ≥ sedentaryAlertMin → 通知（每 30min 最多 1 次）
 * - **地理围栏离开 P1**：location 的 activity 从 home/office 变为 other → 通知
 *
 * 由 HeartRateService 启动，接收 SensorHub emit 出来的 JSON 数据进行规则判定。
 */
class AlertManager(private val ctx: Context) {

    private var scope: CoroutineScope? = null
    private var lastFallAlertMs: Long = 0L
    private var lastSedentaryAlertMs: Long = 0L
    private var lastGeofenceActivity: String = ""

    /** 启动告警监听 */
    fun start(scope: CoroutineScope) {
        this.scope = scope
        Logger.i("AlertMgr", "started")
    }

    /** 停止 */
    fun stop() {
        scope = null
        Logger.i("AlertMgr", "stopped")
    }

    /**
     * 接收传感器 emit 的 JSON 数据，按类型判定告警。
     * 由 SensorRepository.ingest() 调用（ingest 后回调）。
     */
    fun onSensorData(type: String, json: String) {
        val s = scope ?: return
        when (type) {
            SensorType.ACCELEROMETER -> checkAccelerometerAlerts(json, s)
            SensorType.LOCATION     -> checkGeofenceAlert(json, s)
        }
    }

    // ---- 跌倒检测 P0 ----

    private fun checkAccelerometerAlerts(json: String, scope: CoroutineScope) {
        val fallDetected = json.contains("\"fall_detected\":true")
        val stillMin = extractInt(json, "still_duration_min") ?: 0

        if (fallDetected) {
            val now = System.currentTimeMillis()
            if (now - lastFallAlertMs > 60_000L) {  // 1min 内不重复
                lastFallAlertMs = now
                scope.launch { showAlert("跌倒检测", "检测到疑似跌倒，请确认安全", P0) }
            }
        }

        // 久坐提醒
        scope.launch {
            val settings = cn.jarvis.hrbridge.ServiceLocator.settingsStore.settings.first()
            if (stillMin >= settings.sedentaryAlertMin && settings.alertEnabled) {
                val now = System.currentTimeMillis()
                if (now - lastSedentaryAlertMs > 30 * 60_000L) {  // 30min 内不重复
                    lastSedentaryAlertMs = now
                    showAlert("久坐提醒", "已静坐 ${stillMin} 分钟，建议活动", P2)
                }
            }
        }
    }

    // ---- 地理围栏离开 P1 ----

    private fun checkGeofenceAlert(json: String, scope: CoroutineScope) {
        val activity = extractString(json, "activity") ?: return
        if (lastGeofenceActivity.isEmpty()) {
            lastGeofenceActivity = activity
            return
        }
        val prevActivity = lastGeofenceActivity
        val wasHomeOrOffice = prevActivity == "home" || prevActivity == "office"
        val nowOther = activity == "other"
        lastGeofenceActivity = activity

        if (wasHomeOrOffice && nowOther) {
            val placeLabel = if (prevActivity == "home") "家" else "公司"
            scope.launch {
                val settings = cn.jarvis.hrbridge.ServiceLocator.settingsStore.settings.first()
                if (settings.alertEnabled) {
                    showAlert("离开围栏", "已离开${placeLabel}区域", P1)
                }
            }
        }
    }

    // ---- 通知 ----

    private suspend fun showAlert(title: String, message: String, priority: Int) {
        val settings = cn.jarvis.hrbridge.ServiceLocator.settingsStore.settings.first()
        if (!settings.alertEnabled) return
        if (isInQuietHours(settings)) return

        val nm = ctx.getSystemService<NotificationManager>() ?: return
        val notifId = NotifyHelper.NOTIF_ID_ALERT + priority
        val notif = NotifyHelper.sensorAlertNotification(ctx, title, message)
        nm.notify(notifId, notif)
        Logger.i("AlertMgr", "alert: [$priority] $title — $message")
    }

    private fun isInQuietHours(s: AppSettings): Boolean {
        val hour = java.time.LocalTime.now().hour
        val start = s.alertQuietStart
        val end = s.alertQuietEnd
        return if (start <= end) hour in start until end
               else hour >= start || hour < end
    }

    // ---- JSON 简易提取（避免引入 JSON 库开销） ----

    private fun extractInt(json: String, key: String): Int? {
        val pattern = "\"$key\":"
        val idx = json.indexOf(pattern)
        if (idx < 0) return null
        val rest = json.substring(idx + pattern.length)
        val num = rest.takeWhile { it.isDigit() || it == '-' }
        return num.toIntOrNull()
    }

    private fun extractString(json: String, key: String): String? {
        val pattern = "\"$key\":\""
        val idx = json.indexOf(pattern)
        if (idx < 0) return null
        val rest = json.substring(idx + pattern.length)
        val end = rest.indexOf('"')
        return if (end >= 0) rest.substring(0, end) else null
    }

    companion object {
        const val P0 = 0  // 紧急
        const val P1 = 1  // 中等
        const val P2 = 2  // 低
    }
}
