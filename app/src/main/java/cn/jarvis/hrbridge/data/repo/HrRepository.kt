package cn.jarvis.hrbridge.data.repo

import cn.jarvis.hrbridge.data.local.HrDao
import cn.jarvis.hrbridge.data.local.HrRecordEntity
import cn.jarvis.hrbridge.data.local.HrStatsRow
import cn.jarvis.hrbridge.data.prefs.SettingsStore
import cn.jarvis.hrbridge.data.remote.ApiException
import cn.jarvis.hrbridge.data.remote.HrBatchRequest
import cn.jarvis.hrbridge.data.remote.HrBatchSample
import cn.jarvis.hrbridge.data.remote.HrUploadRequest
import cn.jarvis.hrbridge.data.remote.JarvisApi
import cn.jarvis.hrbridge.util.HrStatus
import cn.jarvis.hrbridge.util.HrTrend
import cn.jarvis.hrbridge.util.Logger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

/**
 * 心率采集与上传的主仓库。
 *
 * 写入策略：
 *   1. 每条心率直接入库（pending），保证断网零丢失
 *   2. critical 心率立即走单条上传（不等批量）
 *   3. 非 critical 累积到 N 条或 T 秒由 UploadWorker 批量上送
 */
class HrRepository(
    private val hrDao: HrDao,
    private val api: JarvisApi,
    private val settings: SettingsStore
) {

    /** 滚动窗口内最近心率（默认 30 分钟，UI 订阅） */
    fun observeRecent(windowSec: Long = 30 * 60): Flow<List<HrRecordEntity>> =
        hrDao.observeRecent(sinceSec = System.currentTimeMillis() / 1000 - windowSec)

    fun observePendingCount(): Flow<Int> = hrDao.observePendingCount()

    suspend fun statsSince(windowSec: Long = 30 * 60): HrStatsRow? =
        hrDao.statsSince(System.currentTimeMillis() / 1000 - windowSec)

    /**
     * 记录一条心率到本地缓存。
     *
     * 返回 true 表示是紧急心率，调用方需立即触发单条上传。
     */
    suspend fun ingest(hr: Int, device: String, recentHrForTrend: List<Int> = emptyList()): Pair<Long, HrStatus> {
        val s = settings.settings.first()
        val status = s.thresholds.classify(hr)
        val trend = HrTrend.infer(recentHrForTrend)
        val id = hrDao.insert(
            HrRecordEntity(
                hr = hr,
                status = status.wire,
                trend = trend.wire,
                device = device,
                timestamp = System.currentTimeMillis() / 1000,
                uploadState = HrRecordEntity.STATE_PENDING
            )
        )
        return id to status
    }

    /** 立即单条上传（用于紧急心率或测试连接） */
    suspend fun uploadImmediate(recordId: Long): Result<Unit> {
        val s = settings.settings.first()
        val rec = hrDao.fetchByStates(
            listOf(HrRecordEntity.STATE_PENDING, HrRecordEntity.STATE_FAILED),
            limit = 500
        ).firstOrNull { it.id == recordId } ?: return Result.failure(IllegalStateException("record not found"))

        hrDao.updateState(listOf(recordId), HrRecordEntity.STATE_UPLOADING)
        val body = HrUploadRequest(
            hr = rec.hr,
            avg = rec.hr,
            status = rec.status,
            trend = rec.trend,
            samples = 1,
            device = rec.device.ifEmpty { s.selectedDeviceName },
            ts = rec.timestamp,
            token = s.authToken.ifEmpty { null }
        )
        return api.uploadSingle(s.serverUrl, body).fold(
            onSuccess = {
                hrDao.markUploaded(listOf(recordId), uploadedAt = System.currentTimeMillis() / 1000)
                Result.success(Unit)
            },
            onFailure = { e ->
                hrDao.updateState(listOf(recordId), HrRecordEntity.STATE_FAILED, e.message)
                Result.failure(e)
            }
        )
    }

    /** 批量上传待发条目。返回本次上传的条数；0 表示无事可做。 */
    suspend fun flushBatch(maxBatch: Int = 50): Int {
        val s = settings.settings.first()
        val pending = hrDao.fetchByStates(
            listOf(HrRecordEntity.STATE_PENDING, HrRecordEntity.STATE_FAILED),
            limit = maxBatch
        )
        if (pending.isEmpty()) return 0

        val ids = pending.map { it.id }
        hrDao.updateState(ids, HrRecordEntity.STATE_UPLOADING)

        val body = HrBatchRequest(
            device = s.selectedDeviceName.ifEmpty { "unknown" },
            token = s.authToken.ifEmpty { null },
            count = pending.size,
            samples = pending.map {
                HrBatchSample(hr = it.hr, status = it.status, trend = it.trend, ts = it.timestamp)
            }
        )

        val result = api.uploadBatch(s.batchUrl, body)
        return result.fold(
            onSuccess = {
                hrDao.markUploaded(ids, uploadedAt = System.currentTimeMillis() / 1000)
                Logger.i("HrRepo", "batch 上传成功: ${pending.size} 条")
                pending.size
            },
            onFailure = { e ->
                // 404 → 服务端可能未实现 /batch，降级为逐条
                if (e is ApiException && e.code == 404) {
                    Logger.w("HrRepo", "/batch 端点不存在，降级逐条上传")
                    hrDao.updateState(ids, HrRecordEntity.STATE_PENDING)   // 恢复为 pending
                    fallbackToSingles(pending, s.serverUrl, s.authToken)
                } else {
                    hrDao.updateState(ids, HrRecordEntity.STATE_FAILED, e.message)
                    Logger.w("HrRepo", "batch 上传失败: ${e.message}")
                    0
                }
            }
        )
    }

    private suspend fun fallbackToSingles(records: List<HrRecordEntity>, url: String, token: String): Int {
        var ok = 0
        for (r in records) {
            hrDao.updateState(listOf(r.id), HrRecordEntity.STATE_UPLOADING)
            val body = HrUploadRequest(
                hr = r.hr, avg = r.hr, status = r.status, trend = r.trend,
                samples = 1, device = r.device, ts = r.timestamp,
                token = token.ifEmpty { null }
            )
            api.uploadSingle(url, body).fold(
                onSuccess = {
                    hrDao.markUploaded(listOf(r.id), uploadedAt = System.currentTimeMillis() / 1000)
                    ok++
                },
                onFailure = { e -> hrDao.updateState(listOf(r.id), HrRecordEntity.STATE_FAILED, e.message) }
            )
        }
        return ok
    }

    /** 清理维护：过期、清理已上传历史（避免数据库无限增长） */
    suspend fun runMaintenance() {
        val now = System.currentTimeMillis() / 1000
        hrDao.expireOldRecords(cutoffSec = now - 24 * 3600)
        // 保留 7 天已上传数据
        hrDao.purgeUploaded(cutoffSec = now - 7 * 24 * 3600)
    }
}
