package cn.jarvis.hrbridge.sensors

import cn.jarvis.hrbridge.data.local.SensorDao
import cn.jarvis.hrbridge.data.local.SensorRecordEntity
import cn.jarvis.hrbridge.data.prefs.SettingsStore
import cn.jarvis.hrbridge.data.remote.JarvisApi
import cn.jarvis.hrbridge.util.Logger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

/**
 * 通用传感器写入 + 批量上传仓库。
 *
 * 入库：[ingest]，由 Collector 调用。
 * 出库：[flushPending]，由 [cn.jarvis.hrbridge.service.UploadWorker] 周期调度。
 *
 * 心率专用链路仍走 [cn.jarvis.hrbridge.data.repo.HrRepository]，本 Repo 只负责通用端点。
 */
class SensorRepository(
    private val dao: SensorDao,
    private val api: JarvisApi,
    private val settings: SettingsStore,
    private val alertManager: AlertManager? = null
) {

    fun observePendingCount(): Flow<Int> = dao.observePendingCount()

    /** 入库一条待上传样本；返回本地 id。同时触发告警规则判定。 */
    suspend fun ingest(type: String, json: String): Long {
        val id = dao.insert(
            SensorRecordEntity(
                sensorType = type,
                dataJson = json,
                timestamp = System.currentTimeMillis() / 1000
            )
        )
        alertManager?.onSensorData(type, json)
        return id
    }

    /**
     * 顺序 flush 一批待上传数据：
     *  - 失败的单条按 retryCount++ 暂缓，由下次 worker 重试（WorkManager 外层也会退避）
     *  - 成功的标记 uploaded
     *
     * 返回上传成功条数。
     */
    suspend fun flushPending(maxBatch: Int = 80): Int {
        val s = settings.settings.first()
        val base = s.sensorBaseUrl
        if (base.isBlank()) return 0

        val pending = dao.fetchPending(maxBatch)
        if (pending.isEmpty()) return 0

        var ok = 0
        for (rec in pending) {
            // 具体每个 sensor 是否启用由 Collector 入口决定；这里不重复过滤
            val res = api.postSensor(base, rec.sensorType, rec.dataJson)
            res.fold(
                onSuccess = {
                    dao.markUploaded(listOf(rec.id), System.currentTimeMillis() / 1000)
                    ok++
                },
                onFailure = { e ->
                    dao.markFailed(listOf(rec.id), e.message?.take(200))
                    Logger.w("SensorRepo", "upload ${rec.sensorType} failed: ${e.message}")
                }
            )
        }
        return ok
    }

    /** 维护：过期清理 + 已上传历史清理 */
    suspend fun runMaintenance(retainDays: Int = 7, expireHours: Int = 24) {
        val now = System.currentTimeMillis() / 1000
        dao.purgeExpired(cutoff = now - expireHours * 3600L)
        dao.purgeUploaded(cutoff = now - retainDays * 24 * 3600L)
    }
}
