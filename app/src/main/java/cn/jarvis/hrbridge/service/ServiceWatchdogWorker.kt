package cn.jarvis.hrbridge.service

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import cn.jarvis.hrbridge.ServiceLocator
import cn.jarvis.hrbridge.util.Logger
import kotlinx.coroutines.flow.first

/**
 * 守护进程 Worker：负责检查前台服务是否在运行，并在期望运行时拉起。
 * 同时执行后备的批量上传，以防前台服务虽然存活但因某种原因未成功上传。
 */
class ServiceWatchdogWorker(
    private val ctx: Context,
    params: WorkerParameters
) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result = WakeLockScope.withPartialWakeLock(ctx, "Watchdog", 15_000L) {
        Logger.i("Watchdog", "Watchdog worker executing...")

        val settings = ServiceLocator.settingsStore.settings.first()
        val desired = settings.bridgeDesiredRunning

        if (desired) {
            // Check if HeartRateService is running. We could use a complex activity manager check,
            // but simply calling start() on the foreground service is generally safe.
            Logger.i("Watchdog", "Bridge desired running is true, ensuring service is started.")
            try {
                HeartRateService.start(ctx)
            } catch (e: Exception) {
                Logger.e("Watchdog", "Failed to start HeartRateService: ${e.message}")
            }
        } else {
            Logger.i("Watchdog", "Bridge desired running is false, skipping restart.")
        }

        // 顺便执行后备清理/上传，无论是否在前台运行
        try {
            val repo = ServiceLocator.sensorRepository
            repo.runMaintenance()
            val uploaded = repo.flushPending(100)
            Logger.i("Watchdog", "Maintenance and fallback flush complete. Uploaded: $uploaded")
        } catch (e: Exception) {
            Logger.e("Watchdog", "Maintenance/flush failed: ${e.message}")
        }

        return@withPartialWakeLock Result.success()
    }
}
