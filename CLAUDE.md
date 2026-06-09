# TeslaSync — project notes for future sessions

Android app that mirrors the Tesla's active navigation destination into Waze. Standalone
Android Studio project (Kotlin + Compose). No backend.

## What it does (one line)

While armed, a foreground service polls **Tessie** for `drive_state.active_route_destination`
and opens **Waze** (`https://waze.com/ul?ll=LAT,LON&navigate=yes`) whenever the destination
changes.

## Architecture / file map

```
data/        TessieModels.kt   @Serializable DTOs + TessieParser (pure, unit-tested)
             TessieClient.kt   OkHttp GET /{vin}/state and /vehicles  (single point of control)
             SecureStore.kt    EncryptedSharedPreferences (Tessie token)
             SettingsRepository.kt  typed settings (token/vin/bt/flags/poll interval)
service/     DestinationStateMachine.kt  pure fire/dedup logic (unit-tested)
             WazeUrl.kt        pure deep-link builder (unit-tested)
             WazeLauncher.kt   Intent vs heads-up-notification launch tiers
             DestinationWatcherService.kt  LifecycleService + coroutine poll loop
bluetooth/   CarCompanionManager.kt  CompanionDeviceManager associate + observe presence
             CarPresenceService.kt   CompanionDeviceService -> auto arm/disarm
update/      UpdateChecker.kt   poll GitHub Releases for a newer versionCode
             ApkInstaller.kt    download + SHA-256 verify + FileProvider install
             UpdateModels.kt    AppMetadata (teslasync-app.json) + UpdateInfo
ui/          MainViewModel.kt  AndroidViewModel, persists every change
             MainScreen.kt     Compose config screen + permission/picker launchers
             Theme.kt
TeslaSyncApp.kt  Application — creates notification channels
MainActivity.kt
```

## Non-obvious decisions & gotchas

- **No server by design.** Because the phone side is a custom app, it polls Tessie directly
  from a foreground service that runs only while armed. (An automation-tool design like
  MacroDroid would have needed a server to poll + push.) Tessie polling is free/unlimited.
- **Two hard constraints, true regardless of code:**
  1. Waze no longer auto-starts (`navigate=yes`) since Waze Android 5.3.0.2 (Jan 2025) — the
     user taps GO. Don't claim hands-free.
  2. Polling = ~15–30s lag. Real-time would need Tesla Fleet Telemetry (a server + mTLS), which
     we deliberately avoided.
- **lat/lon ordering trap.** Waze `ll=` is LATITUDE,LONGITUDE. Reversed → routes to the ocean,
  no error. `WazeUrlTest` guards the order. Tesla hands us lat/lon directly (no geocoding).
- **Locale trap (handled).** `WazeUrl.build` uses Kotlin string templates (Double.toString,
  always '.'), NOT String.format — so comma-decimal locales don't corrupt the URL.
- **Android background-activity-launch.** A foreground service can't start an Activity (Waze)
  from the background on Android 10+ unless we hold SYSTEM_ALERT_WINDOW ("Display over other
  apps"). `WazeLauncher` launches directly if that's granted, else posts a heads-up
  notification the user taps. The "one tap GO" vs "tap notification then GO" UX depends on this.
- **Bluetooth auto-arm = CompanionDeviceManager, not a BroadcastReceiver.** A manifest
  `ACTION_ACL_CONNECTED` receiver does NOT get background-launch privilege on Android 12+.
  CDM `associate()` + `startObservingDevicePresence()` + a `CompanionDeviceService` is the
  sanctioned path. `CarPresenceService` overrides only the **String** `onDeviceAppeared/
  onDeviceDisappeared` (the framework's API-33 `AssociationInfo` default delegates to them),
  which avoids referencing the API-33 `AssociationInfo` type on older devices. Auto-arm needs
  **API 31+**; manual arm is the always-works fallback (that's why the design has both).
  **Gotcha:** the manifest MUST declare `<uses-feature android:name="android.software.companion_device_setup" android:required="false"/>` or `associate()` throws *"must declare uses-feature … to use this API"* (crashed v3–v5; fixed by adding it).
- **`active_route_*` only exists when a route is set**, and the car must be awake (Tessie's
  cached read avoids waking it). The poll loop treats null/empty/asleep as "no destination" and
  never crashes — it logs and continues.
- **Tessie envelope is assumed, not confirmed.** `TessieModels.kt` parses top-level
  `drive_state`. VERIFY against a real `/state` body (Step 0 curl) and adjust DTOs if Tessie
  wraps it differently. `/vehicles` parsing is best-effort with a manual-VIN fallback in the UI.
- **Foreground service type = dataSync.** Declared `dataSync|connectedDevice` in the manifest;
  started with `FOREGROUND_SERVICE_TYPE_DATA_SYNC`. Android 15 caps dataSync at ~6h/day, reset
  when the service stops — fine for drive-length sessions (we stop on BT disconnect / manual).

## Version pins (single source: gradle/libs.versions.toml)

AGP 8.6.0 · Gradle 8.7 · Kotlin 2.0.20 (+ compose plugin) · compileSdk/targetSdk 35 ·
minSdk 26 · Compose BOM 2024.09.02 · OkHttp 4.12.0 · kotlinx-serialization 1.7.1 ·
coroutines 1.8.1 · security-crypto 1.1.0-alpha06. JDK 17.

## CI & self-update (mirrors PeptideTrack, adapted to native + GitHub Releases)

- `update/UpdateChecker.kt` fetches `teslasync-app.json` from
  `https://github.com/pcxnet/TeslaSync/releases/latest/download/...` and compares its
  `versionCode` to `BuildConfig.VERSION_CODE`. `update/ApkInstaller.kt` downloads the release
  APK to cache, verifies SHA-256, and installs it via a `FileProvider` + `ACTION_VIEW` intent
  (Android O+ "install unknown apps" consent handled). Repo slug + asset names are
  `buildConfigField`s in `app/build.gradle.kts`.
- `.github/workflows/build-android.yml`: JDK 17 + `gradle/actions/setup-gradle` (gradle-version
  8.7, so no committed wrapper jar needed) → signed `assembleRelease` → GitHub Release with the
  APK + metadata JSON. Version = `YYYY.MM.DD.<run_number>`, versionCode = run_number, passed via
  `ANDROID_VERSION_NAME`/`ANDROID_VERSION_CODE` env.
- **Signing**: `app/build.gradle.kts` reads creds from env (CI) or `keystore.properties` (local).
  CI passes them as env (NOT a properties file) to dodge Java `Properties.load()` mangling
  backslashes/`\u####`. `scripts/setup-ci-secrets.ps1` generates `keystore.jks` and sets the four
  Actions secrets — uses keytool if a JDK is present, else OpenSSL (PKCS12), so it works on
  Windows-on-ARM with no JDK/Android Studio (the live keystore was made this way). **The keystore must be backed up** — losing it blocks all future updates.
- **Repo is PUBLIC** so `releases/latest/download` is tokenless. No secrets in the repo.
- Why GitHub Releases not cPanel (PeptideTrack's path): PeptideTrack's APK is a web-shell that
  needs its server anyway; TeslaSync is fully native and has none, so Releases is the no-server
  distribution channel.

## Testing

- JVM unit tests (`app/src/test`): state machine, Waze URL, Tessie parsing. `./gradlew
  testDebugUnitTest`.
- No instrumented tests yet — the service/BT/launch paths are verified on-device (see README).

## Likely next features (don't pre-build)

- If the OS kills the foreground service too aggressively, fall back to a cloud bridge
  (Node on cPanel) polling Tessie + FCM push.
- Optional: confirm-before-launch toggle; per-destination Waze options (avoid tolls, etc.);
  show ETA from `active_route_minutes_to_arrival`.
