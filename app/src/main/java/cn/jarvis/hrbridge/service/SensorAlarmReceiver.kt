package cn.jarvis.hrbridge.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import cn.jarvis.hrbridge.ServiceLocator
import cn.jarvis.hrbridge.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Doze 模式下的周期性传感器数据采集保活。
 *
 * 当 APP 进入后台且 CPU 被 Doze 暂停时，Collector 内的协程 timer 也会停止。
 * 用 AlarmManager#setExactAndAllowWhileIdle 在 Doze 模式下也能准时唤醒，
 * 唤醒后触发一次全量传感器数据入 DB + HTTP 上传。
 *
 * 调度策略：
 *   - 正常前台：不做额外干预，Collector 协程 timer 自行采集
 *   - 后台/Doze：由 AlarmManager 每 5 分钟唤醒一次
 */
class SensorAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_SENSOR_TICK) return
        val pending = goAsync()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val sensorRepo = ServiceLocator.sensorRepository

                // 1. 把各 Collector 内存中的最新数据写入 DB
                //    Collector 在 SensorEventListener 回调中更新了 lastValue，
                //    但 timer 可能在后台停了，这里手动触发一次 flush
                runCatching {
                    val hub = ServiceLocator.sensorHub
                    hub.syncAll()  // 强制各 Collector 立刻 emit 当前 buffer
                }.onFailure { Logger.w("SensorAlarm", "syncAll failed: ${it.message}") }

                // 2. 立即上传（有网络的话）
                val n = sensorRepo.flushPending(maxBatch = 80)
                sensorRepo.runMaintenance()
                Logger.i("SensorAlarm", "tick: uploaded $n records")
            } catch (e: Exception) {
                Logger.w("SensorAlarm", "tick failed: ${e.message}")
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_SENSOR_TICK = "cn.jarvis.hrbridge.SENSOR_TICK"
        private const val ALARM_REQUEST_CODE = 20001

        /** 注册定期闹钟（NORMAL 模式 5 分钟，POWER_SAVER 15 分钟） */
        fun schedule(context: Context) {
            // 先读当前模式决定间隔
            val scope = CoroutineScope(Dispatchers.Default)
            scope.launch {
                val s = ServiceLocator.settingsStore.settings.first()
                val intervalMs = when (s.uploadMode.toString()) {
                    "power_saver" -> 15 * 60_000L
                    "realtime"    -> 1 * 60_000L
                    else          -> 5 * 60_000L
                }

                val alarmMgr = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return@launch
                val intent = Intent(context, SensorAlarmReceiver::class.java).apply {
                    action = ACTION_SENSOR_TICK
                }
                val pending = PendingIntent.getBroadcast(
                    context, ALARM_REQUEST_CODE, intent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )

                alarmMgr.setExactAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    SystemClock.elapsedRealtime() + intervalMs,
                    pending
                )
            }
        }

        fun cancel(context: Context) {
            val alarmMgr = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            val intent = Intent(context, SensorAlarmReceiver::class.java).apply {
                action = ACTION_SENSOR_TICK
            }
            val pending = PendingIntent.getBroadcast(
                context, ALARM_REQUEST_CODE, intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE
            ) ?: return
            alarmMgr.cancel(pending)
        }
    }
}
