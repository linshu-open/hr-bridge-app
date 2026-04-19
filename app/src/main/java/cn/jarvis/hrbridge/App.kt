package cn.jarvis.hrbridge

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.core.content.getSystemService

/**
 * 全局 Application：
 * - 持有轻量 ServiceLocator（手动 DI，避免 Hilt 膨胀）
 * - 创建通知渠道
 * - 触发首次启动的 v1→v2 prefs 迁移（Phase 2 填充）
 */
class App : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannels()
        ServiceLocator.init(this)
    }

    private fun createNotificationChannels() {
        val nm = getSystemService<NotificationManager>() ?: return
        // minSdk=26，通知渠道无需 SDK_INT 判断
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_SERVICE,
                getString(R.string.notif_channel_service),
                NotificationManager.IMPORTANCE_LOW
            ).apply { setShowBadge(false) }
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ALERT,
                getString(R.string.notif_channel_alert),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                enableVibration(true)
                enableLights(true)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_SENSOR_ALERT,
                "传感器告警",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                enableVibration(true)
                enableLights(true)
                description = "跌倒、久坐、围栏等传感器告警"
            }
        )
    }

    companion object {
        const val CHANNEL_SERVICE = "hrbridge_service"
        const val CHANNEL_ALERT = "hrbridge_alert"
        const val CHANNEL_SENSOR_ALERT = "hrbridge_sensor_alert"

        lateinit var instance: App
            private set
    }
}
