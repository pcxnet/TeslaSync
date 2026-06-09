package au.net.kal.teslasync.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Downloads a release APK to app-private cache and hands it to the Android package installer
 * via a FileProvider URI — the same self-update flow PeptideTrack uses, done natively.
 *
 * Because every CI build is signed with the same key (see build.gradle.kts + the signing
 * secrets), this is a clean in-place upgrade with no uninstall. On Android O+ the user must
 * grant "install unknown apps" once; we send them to that Settings page and report back.
 */
object ApkInstaller {
    private const val TAG = "ApkInstaller"

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    sealed interface Result {
        data object Launched : Result
        data object PermissionRequired : Result
        data class Failed(val error: String) : Result
    }

    suspend fun downloadAndInstall(context: Context, apkUrl: String, expectedSha256: String?): Result {
        val appContext = context.applicationContext
        return try {
            val apk = withContext(Dispatchers.IO) { download(appContext, apkUrl) }
            if (expectedSha256 != null) {
                val actual = withContext(Dispatchers.IO) { sha256(apk) }
                if (!actual.equals(expectedSha256, ignoreCase = true)) {
                    Log.w(TAG, "Checksum mismatch: expected $expectedSha256 got $actual")
                    return Result.Failed("Download checksum mismatch")
                }
            }
            launchInstaller(appContext, apk)
        } catch (e: Exception) {
            Log.w(TAG, "Install failed: ${e.message}", e)
            Result.Failed(e.message ?: e.toString())
        }
    }

    private fun download(context: Context, url: String): File {
        val dir = File(context.cacheDir, "apk").apply { mkdirs() }
        val out = File(dir, "teslasync.apk")
        val req = Request.Builder().url(url).get().build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code} downloading APK")
            val body = resp.body ?: throw IOException("Empty APK response")
            out.outputStream().use { fos -> body.byteStream().copyTo(fos) }
        }
        return out
    }

    private fun launchInstaller(context: Context, apk: File): Result {
        // Android O+ requires per-app "install unknown apps" consent (minSdk is 26 = O).
        if (!context.packageManager.canRequestPackageInstalls()) {
            val intent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}"),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            return Result.PermissionRequired
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        return Result.Launched
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(8192)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
