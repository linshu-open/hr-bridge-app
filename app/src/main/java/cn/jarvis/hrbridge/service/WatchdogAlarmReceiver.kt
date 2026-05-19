package cn.jarvis.hrbridge.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.os.SystemClock
import cn.jarvis.hrbridge.ServiceLocator
import cn.jarvis.hrbridge.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * AlarmManager 精确定时唤醒接收器 —— 替代 WorkManager 的 15 分钟周期。
 *
 * 优势：
 * - setExactAndAllowWhileIdle: Idle/Doze 模式仍可唤醒（每 15 分钟至少一次维护窗口）
 * - 1 分钟间隔，远短于 WorkManager 最小 15 分钟
 * - 不依赖 WorkManager 内部 of 延迟调度队列
 *
 * 触发后立即重新调度下一次闹钟，保持 1 分钟的心跳周期。
 */
class WatchdogAlarmReceiver : BroadcastReceiver() {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onReceive(context: Context, intent: Intent?) {
        Logger.d("WatchdogAlarm", "alarm triggered, checking service health and flushing pending data")

        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val wl = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WatchdogAlarm::FlushWl")
        wl?.acquire(15_000L) // 同步获取 WakeLock，保持 CPU 唤醒 15 秒以保证网络 POST 请求和协程启动

        val pendingResult = goAsync()
        scope.launch {
            try {
                // 1. 检查并自拉起服务
                BridgeRuntime.onAlarmTriggered(context)

                // 2. 物理级熄屏断网对抗：直接在此处触发同步网络上传 (确保在 WakeLock 持有期间执行)
                val db = cn.jarvis.hrbridge.data.local.HrDatabase.get(context)
                val sensorN = SyncUploader.flushSync(db)
                val hrN = ServiceLocator.hrRepository.flushBatch(maxBatch = 50)
                Logger.i("WatchdogAlarm", "Async background flush completed: HR=$hrN sensor=$sensorN")
            } catch (e: Throwable) {
                Logger.e("WatchdogAlarm", "Async background flush failed: ${e.message}")
            } finally {
                wl?.let { if (it.isHeld) it.release() }
                pendingResult.finish()
            }
        }

        // 重新调度下一次闹钟（自保持链）
        schedule(context)
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
                try {
                    // 降级到普通精确闹钟
                    am.setExact(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        SystemClock.elapsedRealtime() + HEARTBEAT_MS,
                        pending
                    )
                } catch (ex: SecurityException) {
                    Logger.w("WatchdogAlarm", "setExact also denied: ${ex.message}, fallback to inexact allow while idle")
                    // 终极安全降级：非精确待机唤醒闹钟，100% 不会抛 SecurityException 崩溃
                    am.setAndAllowWhileIdle(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        SystemClock.elapsedRealtime() + HEARTBEAT_MS,
                        pending
                    )
                }
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
