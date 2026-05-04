package cn.jarvis.hrbridge.sensors

import cn.jarvis.hrbridge.data.local.SensorDao
import cn.jarvis.hrbridge.data.local.SensorRecordEntity
import cn.jarvis.hrbridge.data.prefs.SettingsStore
import cn.jarvis.hrbridge.data.remote.JarvisApi
import cn.jarvis.hrbridge.data.remote.McpClient
import cn.jarvis.hrbridge.util.Logger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

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
    private val alertManager: AlertManager? = null,
    private val mcpClient: McpClient? = null
) {
    private val json = Json { ignoreUnknownKeys = true }

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
        // MCP 上报（并行/异步，不阻塞主链路）
        if (mcpClient != null && mcpClient.connected) {
            try {
                flushMcp(pending)
            } catch (e: Exception) {
                Logger.w("SensorRepo", "MCP flush failed: ${e.message}")
            }
        }

        return ok
    }

    /**
     * 通过 MCP 上报传感器数据。
     * 解析 JSON 中的关键字段并调用对应 tool。
     */
    private suspend fun flushMcp(records: List<SensorRecordEntity>) {
        for (rec in records) {
            try {
                val data = json.parseToJsonElement(rec.dataJson).jsonObject
                when (rec.sensorType) {
                    "location" -> {
                        val lat = data["latitude"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: continue
                        val lng = data["longitude"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: continue
                        val acc = data["accuracy"]?.jsonPrimitive?.content?.toFloatOrNull() ?: 0f
                        mcpClient?.uploadLocation(lat, lng, acc, timestamp = rec.timestamp * 1000L)
                    }
                    "heart_rate" -> {
                        val bpm = data["bpm"]?.jsonPrimitive?.content?.toIntOrNull() ?: continue
                        mcpClient?.callTool("pai.sensor.heart_rate", mapOf(
                            "bpm" to JsonPrimitive(bpm),
                            "timestamp" to JsonPrimitive(rec.timestamp * 1000L)
                        ))
                    }
                    "step_counter" -> {
                        val steps = data["steps"]?.jsonPrimitive?.content?.toIntOrNull() ?: continue
                        mcpClient?.callTool("pai.sensor.steps", mapOf(
                            "steps" to JsonPrimitive(steps),
                            "timestamp" to JsonPrimitive(rec.timestamp * 1000L)
                        ))
                    }
                    else -> {
                        // 通用传感器上传
                        val value = data["value"]?.jsonPrimitive?.content?.toFloatOrNull()
                            ?: data["value"]?.jsonPrimitive?.content?.toIntOrNull()?.toFloat()
                            ?: continue
                        val unit = data["unit"]?.jsonPrimitive?.content ?: ""
                        mcpClient?.uploadSensorData(rec.sensorType, value, unit, timestamp = rec.timestamp * 1000L)
                    }
                }
                Logger.d("SensorRepo", "MCP uploaded: ${rec.sensorType}")
            } catch (e: Exception) {
                Logger.w("SensorRepo", "MCP upload ${rec.sensorType} failed: ${e.message}")
            }
        }
    }

    /** 维护：过期清理 + 已上传历史清理 */
    suspend fun runMaintenance(retainDays: Int = 7, expireHours: Int = 24) {
        val now = System.currentTimeMillis() / 1000
        dao.purgeExpired(cutoff = now - expireHours * 3600L)
        dao.purgeUploaded(cutoff = now - retainDays * 24 * 3600L)
    }
}
