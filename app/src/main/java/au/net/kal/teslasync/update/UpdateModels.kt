package au.net.kal.teslasync.update

import kotlinx.serialization.Serializable

/**
 * Mirrors the teslasync-app.json the CI publishes alongside the APK in each GitHub Release
 * (see .github/workflows/build-android.yml). All fields optional so a malformed/partial
 * file degrades gracefully rather than crashing the check.
 */
@Serializable
data class AppMetadata(
    val version: String? = null,
    val versionCode: Int? = null,
    val sha256: String? = null,
    val sizeBytes: Long? = null,
    val builtAt: String? = null,
    val commit: String? = null,
)

/** A confirmed-newer release the user can install. */
data class UpdateInfo(
    val version: String,
    val versionCode: Int,
    val apkUrl: String,
    val sha256: String?,
)
