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

        var ok = 0
        val client = cn.jarvis.hrbridge.data.remote.HttpClients.okHttp

        for (rec in pending) {
            val url = base.trimEnd('/') + "/" + rec.sensorType
            try {
                val req = Request.Builder()
                    .url(url)
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
