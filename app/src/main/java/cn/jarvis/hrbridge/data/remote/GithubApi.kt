package cn.jarvis.hrbridge.data.remote

import cn.jarvis.hrbridge.BuildConfig
import com.squareup.moshi.Moshi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/** GitHub Releases API 轻量封装：只读取 latest release */
class GithubApi(
    private val client: OkHttpClient = HttpClients.okHttp,
    private val moshi: Moshi = HttpClients.moshi
) {
    private val adapter = moshi.adapter(GithubRelease::class.java).lenient()

    suspend fun latestRelease(
        url: String = BuildConfig.GITHUB_RELEASES_API
    ): Result<GithubRelease> = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder()
                .url(url)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "HRBridge-Android/2.0")
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw ApiException(resp.code, resp.body?.string().orEmpty())
                val body = resp.body?.string().orEmpty()
                adapter.fromJson(body) ?: error("null release body")
            }
        }
    }
}
