package au.net.kal.teslasync.update

import android.util.Log
import au.net.kal.teslasync.BuildConfig
import au.net.kal.teslasync.data.TessieParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Checks for a newer release on GitHub Releases. The CI attaches teslasync-app.json (the
 * version metadata) and teslasync.apk to each Release; the "latest/download" URLs always
 * point at the newest one and are publicly fetchable for a PUBLIC repo (no token needed).
 *
 * Compares by versionCode (a monotonic int = github.run_number), which is more robust than
 * a string compare of the CalVer name.
 */
object UpdateChecker {
    private const val TAG = "UpdateChecker"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private fun assetUrl(asset: String) =
        "https://github.com/${BuildConfig.GITHUB_REPO}/releases/latest/download/$asset"

    /** Returns a newer release if one exists, else null. Never throws. */
    suspend fun check(): UpdateInfo? = withContext(Dispatchers.IO) {
        try {
            val body = httpGet(assetUrl(BuildConfig.UPDATE_METADATA_ASSET)) ?: return@withContext null
            val meta = TessieParser.json.decodeFromString(AppMetadata.serializer(), body)
            val serverCode = meta.versionCode ?: return@withContext null
            if (serverCode > BuildConfig.VERSION_CODE) {
                Log.i(TAG, "Update available: ${meta.version} (code $serverCode > ${BuildConfig.VERSION_CODE})")
                UpdateInfo(
                    version = meta.version ?: serverCode.toString(),
                    versionCode = serverCode,
                    apkUrl = assetUrl(BuildConfig.UPDATE_APK_ASSET),
                    sha256 = meta.sha256,
                )
            } else {
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Update check failed: ${e.message}")
            null
        }
    }

    private fun httpGet(url: String): String? {
        val req = Request.Builder().url(url).header("Accept", "application/json").get().build()
        client.newCall(req).execute().use { resp ->
            return if (resp.isSuccessful) resp.body?.string() else null
        }
    }
}
