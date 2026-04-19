package cn.jarvis.hrbridge.service

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import cn.jarvis.hrbridge.App
import cn.jarvis.hrbridge.MainActivity
import cn.jarvis.hrbridge.R

/** 封装心率服务用的通知构建 */
object NotifyHelper {

    const val NOTIF_ID_SERVICE = 1001
    const val NOTIF_ID_ALERT   = 1002

    fun serviceNotification(
        ctx: Context,
        contentText: String,
        hr: Int? = null
    ): Notification {
        val pending = PendingIntent.getActivity(
            ctx, 0,
            Intent(ctx, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(ctx, App.CHANNEL_SERVICE)
            .setContentTitle(ctx.getString(R.string.notif_service_title))
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_menu_info_details)   // TODO: Phase 4 替换为矢量图标
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOnlyAlertOnce(true)
            .setContentIntent(pending)
            .apply { if (hr != null) setSubText("$hr bpm") }
            .build()
    }

    fun alertNotification(ctx: Context, title: String, message: String): Notification {
        val pending = PendingIntent.getActivity(
            ctx, 1,
            Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(ctx, App.CHANNEL_ALERT)
            .setContentTitle(title)
            .setContentText(message)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()
    }

    fun sensorAlertNotification(ctx: Context, title: String, message: String): Notification {
        val pending = PendingIntent.getActivity(
            ctx, 2,
            Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(ctx, App.CHANNEL_SENSOR_ALERT)
            .setContentTitle(title)
            .setContentText(message)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()
    }
}
