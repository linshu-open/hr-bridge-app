package cn.jarvis.hrbridge.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import cn.jarvis.hrbridge.util.Logger

/**
 * AlarmManager 精确定时唤醒接收器 —— 替代 WorkManager 的 15 分钟周期。
 *
 * 优势：
 * - setExactAndAllowWhileIdle: Idle/Doze 模式仍可唤醒（每 15 分钟至少一次维护窗口）
 * - 1 分钟间隔，远短于 WorkManager 最小 15 分钟
 * - 不依赖 WorkManager 内部的延迟调度队列
 *
 * 触发后立即重新调度下一次闹钟，保持 1 分钟的心跳周期。
 */
class WatchdogAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        Logger.d("WatchdogAlarm", "alarm triggered, checking service health")

        try {
            // 检查并拉起 HeartRateService（如果期望运行）
            BridgeRuntime.onAlarmTriggered(context)
        } catch (e: Exception) {
            Logger.e("WatchdogAlarm", "onReceive failed: ${e.message}")
        } finally {
            // 重新调度下一次闹钟（自保持链）
            schedule(context)
        }
    }

    companion object {
        private const val ALARM_REQUEST_CODE = 9001
        private const val HEARTBEAT_MS = 60_000L // 1 分钟

        /**
         * 调度下一次精确定时唤醒。幂等——多次调用只保留最后一次。
         */
        fun schedule(context: Context) {
            val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: run {
                Logger.w("WatchdogAlarm", "AlarmManager not available")
                return
            }

            val intent = Intent(context, WatchdogAlarmReceiver::class.java)
            val pending = PendingIntent.getBroadcast(
                context,
                ALARM_REQUEST_CODE,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            try {
                am.setExactAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    SystemClock.elapsedRealtime() + HEARTBEAT_MS,
                    pending
                )
                Logger.d("WatchdogAlarm", "next alarm in ${HEARTBEAT_MS}ms")
            } catch (e: SecurityException) {
                Logger.w("WatchdogAlarm", "setExactAndAllowWhileIdle denied: ${e.message}")
                // 降级到普通精确闹钟
                am.setExact(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    SystemClock.elapsedRealtime() + HEARTBEAT_MS,
                    pending
                )
            }
        }

        /**
         * 取消已调度的闹钟
         */
        fun cancel(context: Context) {
            val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            val intent = Intent(context, WatchdogAlarmReceiver::class.java)
            val pending = PendingIntent.getBroadcast(
                context,
                ALARM_REQUEST_CODE,
                intent,
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
            )
            pending?.let { am.cancel(it); it.cancel() }
            Logger.d("WatchdogAlarm", "cancelled")
        }
    }
}
