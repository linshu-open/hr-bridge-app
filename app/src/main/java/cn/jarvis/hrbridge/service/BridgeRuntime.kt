package cn.jarvis.hrbridge.service

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import cn.jarvis.hrbridge.ServiceLocator
import cn.jarvis.hrbridge.util.Logger
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.util.concurrent.TimeUnit

object BridgeRuntime {

    private const val WATCHDOG_WORK_NAME = "jarvis_service_watchdog"

    /**
     * 保证 Watchdog 定时任务被排队。
     */
    fun ensureWatchdogScheduled(context: Context) {
        val workRequest = PeriodicWorkRequestBuilder<ServiceWatchdogWorker>(15, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WATCHDOG_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            workRequest
        )
        Logger.i("BridgeRuntime", "Watchdog periodic work ensured.")
    }

    /**
     * 根据设置中的 bridgeDesiredRunning 状态，决定是否拉起服务。
     * 供 BootReceiver 等各种触发器调用。
     */
    fun ensureScheduledAndMaybeStart(context: Context, reason: String) {
        Logger.i("BridgeRuntime", "ensureScheduledAndMaybeStart triggered. Reason: $reason")

        ensureWatchdogScheduled(context)

        // 我们在协程中读取配置，因为它是 flow
        // runBlocking 在这里是安全的，因为 DataStore 第一次读取很快（在 BroadcastReceiver 里）
        try {
            val desired = runBlocking {
                ServiceLocator.settingsStore.settings.first().bridgeDesiredRunning
            }
            if (desired) {
                Logger.i("BridgeRuntime", "bridgeDesiredRunning is true, starting HeartRateService.")
                HeartRateService.start(context)
            } else {
                Logger.i("BridgeRuntime", "bridgeDesiredRunning is false, ignoring start.")
            }
        } catch (e: Exception) {
            Logger.e("BridgeRuntime", "Failed to check desired state or start service: ${e.message}")
        }
    }
}
