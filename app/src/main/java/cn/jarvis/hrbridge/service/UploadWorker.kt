package cn.jarvis.hrbridge.service

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import cn.jarvis.hrbridge.ServiceLocator
import cn.jarvis.hrbridge.util.Logger
import java.util.concurrent.TimeUnit

/**
 * 周期性批量上传未发送的心率数据。
 *
 * 调度策略：
 * - 每 15 分钟（最小间隔）检查一次，有网络才运行
 * - 单次最多拉 50 条，由 Repository 做批量端点调用
 * - 失败由 WorkManager 内置重试机制（指数退避）
 */
class UploadWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val hrRepo = ServiceLocator.hrRepository
        val sensorRepo = ServiceLocator.sensorRepository
        return try {
            val hrN = hrRepo.flushBatch(maxBatch = 50)
            val sensorN = sensorRepo.flushPending(maxBatch = 80)
            Logger.i("UploadWorker", "上传 HR=$hrN sensor=$sensorN")
            hrRepo.runMaintenance()
            sensorRepo.runMaintenance()
            Result.success()
        } catch (e: Exception) {
            Logger.w("UploadWorker", "上传出错: ${e.message}")
            if (runAttemptCount < 5) Result.retry() else Result.failure()
        }
    }

    companion object {
        private const val UNIQUE_NAME = "hr_upload_periodic"

        /** 注册周期任务（幂等，多次调用安全） */
        fun schedule(ctx: Context) {
            val req = PeriodicWorkRequestBuilder<UploadWorker>(15, TimeUnit.MINUTES)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .setBackoffCriteria(
                    androidx.work.BackoffPolicy.EXPONENTIAL,
                    30, TimeUnit.SECONDS
                )
                .build()

            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
                UNIQUE_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                req
            )
        }

        fun cancel(ctx: Context) {
            WorkManager.getInstance(ctx).cancelUniqueWork(UNIQUE_NAME)
        }
    }
}
