import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// ── Signing ────────────────────────────────────────────────────────────────
// Credentials come from one of two sources, in priority order:
//   1. Environment variables (CI — set by .github/workflows/build-android.yml).
//      Passing them straight to Gradle avoids round-tripping through Java's
//      Properties.load(), which silently rewrites backslashes / \u#### escapes
//      in passwords and yields a bogus "keystore password was incorrect".
//   2. keystore.properties at the repo root (local — written by
//      scripts/setup-ci-secrets.ps1 or Android Studio's signing wizard).
// With neither present, assembleRelease produces an UNSIGNED apk that won't
// install — the intended failure mode, not a silent fallback.
val envStorePw = System.getenv("ANDROID_KEYSTORE_PASSWORD")
val envKeyAlias = System.getenv("ANDROID_KEY_ALIAS")
val envKeyPw = System.getenv("ANDROID_KEY_PASSWORD")
val hasEnvCreds = !envStorePw.isNullOrBlank() && !envKeyAlias.isNullOrBlank() && !envKeyPw.isNullOrBlank()

val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (!hasEnvCreds && keystorePropsFile.exists()) {
        FileInputStream(keystorePropsFile).use { load(it) }
    }
}
val hasKeystore = hasEnvCreds || keystorePropsFile.exists()
val resolvedStoreFile = rootProject.file((keystoreProps["storeFile"] as String?) ?: "keystore.jks")
val resolvedStorePassword = envStorePw ?: keystoreProps["storePassword"] as String?
val resolvedKeyAlias = envKeyAlias ?: keystoreProps["keyAlias"] as String?
val resolvedKeyPassword = envKeyPw ?: keystoreProps["keyPassword"] as String?

android {
    namespace = "au.net.kal.teslasync"
    compileSdk = 35

    defaultConfig {
        applicationId = "au.net.kal.teslasync"
        minSdk = 26
        targetSdk = 35

        // versionName / versionCode are injected by the GitHub Actions workflow:
        //   versionName = YYYY.MM.DD.RUN_NUMBER   (e.g. "2026.06.10.42")
        //   versionCode = github.run_number       (monotonic int — "newer wins")
        // The fallbacks are only used for local builds (Android Studio). A local
        // versionCode of 1 keeps a stray local build from out-ranking a CI APK at
        // install time.
        versionCode = System.getenv("ANDROID_VERSION_CODE")?.toIntOrNull() ?: 1
        versionName = System.getenv("ANDROID_VERSION_NAME") ?: "0.0.0-dev"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Where the in-app updater looks for new releases (GitHub Releases).
        buildConfigField("String", "GITHUB_REPO", "\"pcxnet/TeslaSync\"")
        buildConfigField("String", "UPDATE_METADATA_ASSET", "\"teslasync-app.json\"")
        buildConfigField("String", "UPDATE_APK_ASSET", "\"teslasync.apk\"")
    }

    signingConfigs {
        create("release") {
            if (hasKeystore) {
                storeFile = resolvedStoreFile
                storePassword = resolvedStorePassword
                keyAlias = resolvedKeyAlias
                keyPassword = resolvedKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (hasKeystore) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    debugImplementation(libs.androidx.ui.tooling)

    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.security.crypto)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
