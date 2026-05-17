package cn.jarvis.hrbridge.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import cn.jarvis.hrbridge.MainActivity
import cn.jarvis.hrbridge.util.Logger

/**
 * 双进程守护前台服务 —— 运行在独立进程，作为主进程的锚点。
 *
 * 设计理由：
 * - android:process=":keepalive" 使其运行在独立进程
 * - 主进程 HeartRateService 被 kill 时，KeepAliveService 仍存活
 * - onTrimMemory/onDestroy 中主动拉起主进程服务
 * - 轻量通知常驻，耗电极低
 *
 * 组合效果：
 * - AlarmManager 1min 心跳 + 双进程互保 = Android 后台存活率最大化
 */
class KeepAliveService : Service() {

    override fun onCreate() {
        super.onCreate()
        promoteToForeground()
        Logger.i("KeepAlive", "created in process: ${android.os.Process.myPid()}")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 确保主进程服务在跑
        runCatching {
            HeartRateService.start(this)
        }.onFailure {
            Logger.w("KeepAlive", "start HeartRateService failed: ${it.message}")
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        Logger.w("KeepAlive", "onDestroy — restarting main service")
        // 被杀前最后一搏：拉起主进程服务
        runCatching { HeartRateService.start(this) }
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Logger.w("KeepAlive", "task removed — requesting restart")
        // 调度闹钟保底
        WatchdogAlarmReceiver.schedule(this)
        runCatching { HeartRateService.start(this) }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_RUNNING_CRITICAL) {
            Logger.w("KeepAlive", "trim memory critical — ensuring main service alive")
            runCatching { HeartRateService.start(this) }
        }
    }

    private fun promoteToForeground() {
        val channelId = "keepalive_channel"
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "后台运行保障",
                NotificationManager.IMPORTANCE_MIN // 最低优先，不弹出
            ).apply {
                description = "保持数据采集服务连续运行"
                setShowBadge(false)
            }
            nm.createNotificationChannel(channel)
        }

        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("数据采集运行中")
            .setContentText("Jarvis 传感器桥接")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setContentIntent(pendingIntent)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIF_ID,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    companion object {
        private const val NOTIF_ID = 2002

        fun start(context: Context) {
            val intent = Intent(context, KeepAliveService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, KeepAliveService::class.java))
        }
    }
}
