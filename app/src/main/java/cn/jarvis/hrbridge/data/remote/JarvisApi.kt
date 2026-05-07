package cn.jarvis.hrbridge.data.remote

import cn.jarvis.hrbridge.util.Logger
import com.squareup.moshi.Moshi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * JARVIS 服务端 HTTP 接口封装。
 * 不做 Retrofit，因为只有 2-3 个端点，用 OkHttp 直接发更省依赖。
 */
class JarvisApi(
    private val client: OkHttpClient = HttpClients.okHttp,
    private val moshi: Moshi = HttpClients.moshi
) {
    private val jsonMedia = "application/json; charset=utf-8".toMediaType()
    private val uploadAdapter = moshi.adapter(HrUploadRequest::class.java)
    private val batchAdapter  = moshi.adapter(HrBatchRequest::class.java)
    private val respAdapter   = moshi.adapter(JarvisResponse::class.java).lenient()

    /** 单条上传。返回 [Result] 以便调用方决定重试/标记上传成功。 */
    suspend fun uploadSingle(url: String, body: HrUploadRequest): Result<JarvisResponse> =
        post(url, uploadAdapter.toJson(body))

    /** 批量上传，count 字段自动填充。 */
    suspend fun uploadBatch(url: String, body: HrBatchRequest): Result<JarvisResponse> =
        post(url, batchAdapter.toJson(body.copy(count = body.samples.size)))

    /**
     * 通用传感器上传端点
     * 新API: POST /api/sensor/upload
     * Body: {"sensor_type":"xxx","device_id":"android-phone","values":{...}}
     *
     * @param baseUrl 例如 http://114.132.201.207:3001/api/sensor（无尾斜杠）
     * @param type    sensor_type（step_count/location/accelerometer/...）
     * @param json    已经序列化好的 JSON body（原始数据）
     */
    suspend fun postSensor(baseUrl: String, type: String, json: String): Result<JarvisResponse> {
        // 新API格式：包装成 {"sensor_type":"xxx","device_id":"...","values":{...}}
        val wrappedJson = buildString {
            append("{\"sensor_type\":\"")
            append(type)
            append("\",\"device_id\":\"android-phone-001\",\"values\":")
            append(json)
            append("}")
        }
        val url = baseUrl.trimEnd('/') + "/upload"
        return post(url, wrappedJson)
    }

    private suspend fun post(url: String, json: String): Result<JarvisResponse> = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder()
                .url(url)
                .post(json.toRequestBody(jsonMedia))
                .header("User-Agent", "HRBridge-Android/2.0")
                .build()
            client.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    throw ApiException(resp.code, text)
                }
                val parsed = runCatching { respAdapter.fromJson(text) }.getOrNull()
                parsed ?: JarvisResponse(success = true, message = "ok(no-json)")
            }
        }.onFailure { Logger.w("JarvisApi", "POST $url failed: ${it.message}") }
    }
}

class ApiException(val code: Int, val rawBody: String) :
    RuntimeException("HTTP $code: ${rawBody.take(200)}")
