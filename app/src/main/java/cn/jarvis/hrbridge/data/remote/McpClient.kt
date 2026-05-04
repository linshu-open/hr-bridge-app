package cn.jarvis.hrbridge.data.remote

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * MCP HTTP/SSE 客户端
 * 连接云端 JARVIS PAI Server (114.132.201.207:3001)
 */
class McpClient(
    private val serverUrl: String = "http://114.132.201.207:3001",
    private val authToken: String = "jarvis_mcp_token_2025"
) {
    private val httpClient = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .connectTimeout(30, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }
    private var endpoint: String? = null
    private var eventSource: EventSource? = null
    private var isConnected = false

    companion object {
        private const val TAG = "McpClient"
    }

    /**
     * 建立 SSE 连接并获取 endpoint
     */
    suspend fun connect(): Boolean = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$serverUrl/sse")
                .header("Authorization", "Bearer $authToken")
                .header("Accept", "text/event-stream")
                .build()

            val result = suspendCancellableCoroutine<Boolean> { continuation ->
                val listener = object : EventSourceListener() {
                    override fun onOpen(eventSource: EventSource, response: Response) {
                        Log.d(TAG, "SSE connection opened")
                    }

                    override fun onEvent(
                        eventSource: EventSource,
                        id: String?,
                        type: String?,
                        data: String
                    ) {
                        Log.d(TAG, "SSE event: type=$type data=$data")
                        when (type) {
                            "endpoint" -> {
                                endpoint = data.trim('"')
                                isConnected = true
                                continuation.resume(true)
                            }
                        }
                    }

                    override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                        Log.e(TAG, "SSE connection failed", t)
                        if (continuation.isActive) {
                            continuation.resumeWithException(t ?: Exception("SSE connection failed"))
                        }
                    }
                }

                eventSource = EventSources.createFactory(httpClient).newEventSource(request, listener)

                continuation.invokeOnCancellation {
                    eventSource?.cancel()
                }
            }
            result
        } catch (e: Exception) {
            Log.e(TAG, "Failed to connect", e)
            false
        }
    }

    /**
     * 调用 MCP 工具
     */
    suspend fun callTool(
        name: String,
        arguments: Map<String, JsonElement> = emptyMap()
    ): JsonElement = withContext(Dispatchers.IO) {
        val ep = endpoint ?: throw IllegalStateException("Not connected. Call connect() first.")

        val payload = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", System.currentTimeMillis())
            put("method", "tools/call")
            putJsonObject("params") {
                put("name", name)
                putJsonObject("arguments") {
                    arguments.forEach { (k, v) -> put(k, v) }
                }
            }
        }

        val requestBody = payload.toString().toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url("$serverUrl$ep")
            .header("Authorization", "Bearer $authToken")
            .post(requestBody)
            .build()

        httpClient.newCall(request).execute().use { response ->
            val body = response.body?.string() ?: "{}"
            Log.d(TAG, "Tool $name response: $body")
            json.parseToJsonElement(body)
        }
    }

    /**
     * 上传传感器数据
     */
    suspend fun uploadSensorData(
        sensorType: String,
        value: Float,
        unit: String,
        timestamp: Long = System.currentTimeMillis()
    ): JsonElement {
        return callTool("pai.sensor.upload", mapOf(
            "sensor_type" to JsonPrimitive(sensorType),
            "value" to JsonPrimitive(value),
            "unit" to JsonPrimitive(unit),
            "timestamp" to JsonPrimitive(timestamp)
        ))
    }

    /**
     * 上传位置数据
     */
    suspend fun uploadLocation(
        latitude: Double,
        longitude: Double,
        accuracy: Float,
        source: String = "gps",
        timestamp: Long = System.currentTimeMillis()
    ): JsonElement {
        return callTool("pai.sensor.location", mapOf(
            "latitude" to JsonPrimitive(latitude),
            "longitude" to JsonPrimitive(longitude),
            "accuracy" to JsonPrimitive(accuracy),
            "source" to JsonPrimitive(source),
            "timestamp" to JsonPrimitive(timestamp)
        ))
    }

    /**
     * 上报场景状态
     */
    suspend fun reportScene(
        scene: String,
        confidence: Float,
        factors: List<String> = emptyList()
    ): JsonElement {
        return callTool("pai.scene.report", mapOf(
            "scene" to JsonPrimitive(scene),
            "confidence" to JsonPrimitive(confidence),
            "factors" to JsonArray(factors.map { JsonPrimitive(it) }),
            "timestamp" to JsonPrimitive(System.currentTimeMillis())
        ))
    }

    /**
     * 获取世界状态
     */
    suspend fun getWorldState(): JsonElement {
        return callTool("pai.world.get", emptyMap())
    }

    /**
     * 获取主人画像
     */
    suspend fun getPersona(): JsonElement {
        return callTool("pai.persona.get", emptyMap())
    }

    fun disconnect() {
        eventSource?.cancel()
        eventSource = null
        endpoint = null
        isConnected = false
    }

    val connected: Boolean get() = isConnected
}
