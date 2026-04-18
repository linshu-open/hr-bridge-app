package cn.jarvis.hrbridge.data.remote

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/** 单条上传请求（与 v1.2.7 兼容） */
@JsonClass(generateAdapter = true)
data class HrUploadRequest(
    @Json(name = "hr")      val hr: Int,
    @Json(name = "avg")     val avg: Int? = null,
    @Json(name = "status")  val status: String,
    @Json(name = "trend")   val trend: String = "stable",
    @Json(name = "samples") val samples: Int = 1,
    @Json(name = "device")  val device: String,
    @Json(name = "ts")      val ts: Long,
    @Json(name = "token")   val token: String? = null
)

/** 批量上传请求（v2 新增） */
@JsonClass(generateAdapter = true)
data class HrBatchRequest(
    val device: String,
    val token: String? = null,
    val count: Int,
    val samples: List<HrBatchSample>
)

@JsonClass(generateAdapter = true)
data class HrBatchSample(
    val hr: Int,
    val status: String,
    val trend: String = "stable",
    val ts: Long
)

/** 服务端通用响应（字段宽松） */
@JsonClass(generateAdapter = true)
data class JarvisResponse(
    val success: Boolean = false,
    val message: String? = null,
    val data: Map<String, Any>? = null,
    val accepted: Int? = null,
    val rejected: Int? = null,
    val action: String? = null
)

/** GitHub Releases API 所需字段（只解析必要的） */
@JsonClass(generateAdapter = true)
data class GithubRelease(
    @Json(name = "tag_name")     val tagName: String,
    @Json(name = "name")         val name: String? = null,
    @Json(name = "body")         val body: String? = null,
    @Json(name = "prerelease")   val prerelease: Boolean = false,
    @Json(name = "assets")       val assets: List<GithubAsset> = emptyList()
)

@JsonClass(generateAdapter = true)
data class GithubAsset(
    @Json(name = "name")                 val name: String,
    @Json(name = "browser_download_url") val browserDownloadUrl: String,
    @Json(name = "size")                 val size: Long
)
