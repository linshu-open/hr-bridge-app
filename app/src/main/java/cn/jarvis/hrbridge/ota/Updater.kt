package cn.jarvis.hrbridge.ota

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import cn.jarvis.hrbridge.BuildConfig
import cn.jarvis.hrbridge.ServiceLocator
import cn.jarvis.hrbridge.data.remote.GithubRelease
import cn.jarvis.hrbridge.data.remote.HttpClients
import cn.jarvis.hrbridge.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File

/**
 * OTA 更新：检查 GitHub Releases 最新版本，必要时下载 APK 并触发安装意图。
 *
 * 安装通过 VIEW intent + FileProvider，让用户在系统"安装未知来源"弹窗中显式确认，
 * 避免 v1.2.7 那种静默安装观感（以及 REQUEST_INSTALL_PACKAGES 滥用）。
 */
class Updater(private val ctx: Context) {

    data class UpdateInfo(
        val currentVersion: String,
        val latestVersion: String,
        val downloadUrl: String?,
        val body: String,
        val isNewer: Boolean,
        val isPrerelease: Boolean
    )

    suspend fun check(): Result<UpdateInfo> =
        ServiceLocator.githubApi.latestRelease().mapCatching { rel ->
            val current = "v" + BuildConfig.VERSION_NAME
            val apkAsset = rel.assets.firstOrNull { it.name.endsWith(".apk") }
            UpdateInfo(
                currentVersion = current,
                latestVersion = rel.tagName,
                downloadUrl = apkAsset?.browserDownloadUrl,
                body = rel.body.orEmpty(),
                isNewer = isNewer(current, rel.tagName),
                isPrerelease = rel.prerelease
            )
        }

    /** 简单字面比较：strip "v" 后比较分隔后的数字段；足够处理 "v1.2.7" vs "v2.0.0" */
    private fun isNewer(current: String, latest: String): Boolean {
        val a = current.trimStart('v', 'V').split('-', limit = 2).first().split('.')
        val b = latest.trimStart('v', 'V').split('-', limit = 2).first().split('.')
        val max = maxOf(a.size, b.size)
        for (i in 0 until max) {
            val ai = a.getOrNull(i)?.toIntOrNull() ?: 0
            val bi = b.getOrNull(i)?.toIntOrNull() ?: 0
            if (bi > ai) return true
            if (bi < ai) return false
        }
        return false
    }

    /** 下载 APK 到缓存目录。返回 File，失败则抛异常。 */
    suspend fun downloadApk(url: String, onProgress: ((Int) -> Unit)? = null): File = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url).build()
        val resp = HttpClients.okHttp.newCall(req).execute()
        try {
            if (!resp.isSuccessful) error("HTTP ${resp.code}")
            val body = resp.body ?: error("empty body")
            val total = body.contentLength()
            val outDir = File(ctx.externalCacheDir, ".")
            outDir.mkdirs()
            val outFile = File(outDir, "update.apk")

            body.byteStream().use { input ->
                outFile.outputStream().use { output ->
                    val buf = ByteArray(16 * 1024)
                    var read = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        output.write(buf, 0, n)
                        read += n
                        if (total > 0) onProgress?.invoke(((read * 100) / total).toInt())
                    }
                }
            }
            Logger.i("Updater", "APK 下载完成 ${outFile.absolutePath}")
            outFile
        } finally {
            resp.close()
        }
    }

    /** 触发安装弹窗；用户可在系统弹窗中确认安装。 */
    fun launchInstaller(apk: File) {
        val uri: Uri = FileProvider.getUriForFile(
            ctx, "${ctx.packageName}.fileprovider", apk
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
        }
        ctx.startActivity(intent)
    }
}
