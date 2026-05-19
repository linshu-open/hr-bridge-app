package cn.jarvis.hrbridge.service

import cn.jarvis.hrbridge.ServiceLocator
import cn.jarvis.hrbridge.data.local.HrDatabase
import cn.jarvis.hrbridge.util.Logger
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Synchronous batch uploader for sensor database records.
 * Invoked by SensorFlushReceiver during AlarmManager wake locks, ensuring 100% bypass of coroutines.
 */
object SyncUploader {
    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    fun flushSync(db: HrDatabase, maxBatch: Int = 100): Int {
        val s = ServiceLocator.settingsStore.cache
        val base = s.sensorBaseUrl
        if (base.isBlank()) {
            Logger.w("SyncUploader", "Sensor base URL is empty, skipping flush.")
            return 0
        }

        val dao = db.sensorDao()
        val pending = dao.fetchPendingSync(maxBatch)
        if (pending.isEmpty()) return 0

        val client = cn.jarvis.hrbridge.data.remote.HttpClients.okHttp
        val ids = pending.map { it.id }

        // 1. 尝试高效的批量上传
        val url = base.trimEnd('/') + "/upload"
        val sb = StringBuilder()
        sb.append("{\"batch\":true,\"device_id\":\"")
        sb.append(s.selectedDeviceName.ifEmpty { "unknown" })
        sb.append("\",\"events\":[")
        for (i in pending.indices) {
            val rec = pending[i]
            sb.append("{\"sensor_type\":\"").append(rec.sensorType).append("\",")
            sb.append("\"timestamp\":").append(rec.timestamp).append(",")
            sb.append("\"values\":").append(rec.dataJson).append("}")
            if (i < pending.size - 1) sb.append(",")
        }
        sb.append("]}")
        val jsonPayload = sb.toString()

        try {
            val req = Request.Builder()
                .url(url)
                .post(jsonPayload.toRequestBody(jsonMedia))
                .header("User-Agent", "HRBridge-Android/2.0-SyncBatch")
                .build()

            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    dao.markUploadedSync(ids, System.currentTimeMillis() / 1000)
                    Logger.i("SyncUploader", "Batch upload success: ${pending.size} records")
                    return pending.size
                } else {
                    Logger.w("SyncUploader", "Batch upload failed with HTTP ${resp.code}, falling back to legacy sequential upload")
                }
            }
        } catch (e: Exception) {
            Logger.w("SyncUploader", "Batch upload exception: ${e.message}, falling back to legacy sequential upload")
        }

        // 2. 降级回退：传统的逐条循环上传逻辑
        var ok = 0
        for (rec in pending) {
            val recUrl = base.trimEnd('/') + "/" + rec.sensorType
            try {
                val req = Request.Builder()
                    .url(recUrl)
                    .post(rec.dataJson.toRequestBody(jsonMedia))
                    .header("User-Agent", "HRBridge-Android/2.0-Sync")
                    .build()
                
                client.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        dao.markUploadedSync(listOf(rec.id), System.currentTimeMillis() / 1000)
                        ok++
                    } else {
                        val text = resp.body?.string().orEmpty().take(200)
                        dao.markFailedSync(listOf(rec.id), "HTTP ${resp.code}: $text")
                        Logger.w("SyncUploader", "Upload failed for type ${rec.sensorType}, code=${resp.code}")
                    }
                }
            } catch (e: Exception) {
                dao.markFailedSync(listOf(rec.id), e.message?.take(200))
                Logger.w("SyncUploader", "Upload exception for type ${rec.sensorType}: ${e.message}")
            }
        }
        return ok
    }
}
