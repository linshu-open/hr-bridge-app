package cn.jarvis.hrbridge.service

import android.content.Context
import cn.jarvis.hrbridge.ServiceLocator
import cn.jarvis.hrbridge.util.Logger
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/**
 * 运行时生命周期管理器。
 *
 * v2 升级：
 * - 用 AlarmManager 精确定时替代 WorkManager 的 15min 周期（#15）
 * - 启动双进程 KeepAliveService 作为主进程锚点（#14/#23）
 * - WatchdogAlarmReceiver 的 1min 心跳 + 双进程互保 = 最大化后台存活率
 */
object BridgeRuntime {

    /**
     * 调度 AlarmManager 1 分钟心跳看门狗（替代 WorkManager WatchdogWorker）。
     * 幂等——重复调用只更新闹钟时间。
     */
    fun ensureWatchdogScheduled(context: Context) {
        WatchdogAlarmReceiver.schedule(context)
        Logger.i("BridgeRuntime", "AlarmManager watchdog scheduled (1min heartbeat)")
    }

    /**
     * 闹钟触发后的回调：检查并拉起服务。
     * 由 WatchdogAlarmReceiver.onReceive() 调用。
     */
    fun onAlarmTriggered(context: Context) {
        try {
            val desired = runBlocking {
                ServiceLocator.settingsStore.settings.first().bridgeDesiredRunning
            }
            if (!desired) {
                Logger.d("BridgeRuntime", "alarm triggered but bridgeDesiredRunning=false, skip")
                return
            }
        } catch (e: Exception) {
            Logger.w("BridgeRuntime", "cannot read settings on alarm: ${e.message}, starting anyway")
        }

        // double insurance: ensure both services are running
        try {
            HeartRateService.start(context)
            KeepAliveService.start(context)
        } catch (e: Exception) {
            Logger.e("BridgeRuntime", "onAlarmTriggered start failed: ${e.message}")
        }
    }

    /**
     * 根据设置中的 bridgeDesiredRunning 状态，决定是否拉起服务。
     * 供 BootReceiver 等各种触发器调用。
     */
    fun ensureScheduledAndMaybeStart(context: Context, reason: String) {
        Logger.i("BridgeRuntime", "ensureScheduledAndMaybeStart triggered. Reason: $reason")

        // 启动 AlarmManager 心跳
        ensureWatchdogScheduled(context)

        try {
            val desired = runBlocking {
                ServiceLocator.settingsStore.settings.first().bridgeDesiredRunning
            }
            if (desired) {
                Logger.i("BridgeRuntime", "bridgeDesiredRunning is true, starting services.")
                HeartRateService.start(context)
                KeepAliveService.start(context)
            } else {
                Logger.i("BridgeRuntime", "bridgeDesiredRunning is false, ignoring start.")
            }
        } catch (e: Exception) {
            Logger.e("BridgeRuntime", "Failed to check desired state or start service: ${e.message}")
        }
    }
}
