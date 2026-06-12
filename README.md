# TeslaSync

Sends the destination you enter on your **Tesla**'s screen to **Waze** on your Android
phone, so you can navigate with Waze's police/camera/hazard alerts instead of Tesla's
built‑in navigation.

You set the destination once (on the car), and within ~15–30s your phone opens Waze routed
to it. Tesla is the input; Waze does the guiding.

---

## How it works

```
Tesla screen (set destination)
      │  Tessie reads the Tesla Fleet API for you
      ▼
Tessie REST  GET https://api.tessie.com/{vin}/state   ← polled every ~15s while armed
      ▼
TeslaSync app  (foreground service)
   • reads drive_state.active_route_destination / latitude / longitude
   • fires only when the destination CHANGES
   • opens  https://waze.com/ul?ll=LAT,LON&navigate=yes
      ▼
Waze opens to the route → you tap GO
```

There is **no server and no Firebase** — the app polls Tessie directly while armed. The only
external dependency is a Tessie subscription.

## Two things to know up front

1. **One tap in Waze.** Since Waze Android 5.3.0.2 (Jan 2025), Waze opens on the route‑preview
   screen and you tap **GO**; it does not auto‑start navigation. Not hands‑free.
2. **~15–30s lag.** It polls, so there's a short delay. Fine because you set the destination
   before pulling out.

---

## Prerequisites

- A **Tessie** account (https://tessie.com) with your Tesla linked, and a **Tessie API token**
  (Tessie → Settings → API). Tessie costs ~US$6.99/vehicle/month; its polling is free/unlimited
  and never wakes the car.
- **Waze** installed on the phone.
- **Android Studio** (Koala/2024.1+ recommended) to build, and an Android phone (Android 8.0 /
  API 26 minimum). **Pair your phone with the car's Bluetooth first** (normal Android
  Settings pairing) — the app picks the car from your already‑paired devices.

## Step 0 — prove the Tesla side first (5 minutes, do this before building)

In the car, set a navigation destination, then run (PowerShell):

```powershell
curl -H "Authorization: Bearer <TESSIE_TOKEN>" `
  "https://api.tessie.com/<VIN>/state?use_cache=true"
```

In the JSON, under `drive_state`, confirm `active_route_destination`,
`active_route_latitude`, `active_route_longitude` are populated. If they are, you're green‑lit.
If the JSON envelope differs from what the app expects, adjust the DTOs in
`app/src/main/java/au/net/kal/teslasync/data/TessieModels.kt` to match.

---

## Build & run

> ⚠️ **OneDrive:** this project lives under OneDrive. Android/Gradle generate large, churning
> `build/`, `.gradle/`, and `.idea/` folders. **Exclude them from OneDrive sync** (right‑click →
> *Free up space* / *Always keep off this device*) or you'll hit file‑lock errors and slow
> builds. They're already git‑ignored.

1. Open the `TeslaSync` folder in **Android Studio**. Let it **Gradle sync** — this downloads
   Gradle 8.7 + the Android SDK pieces and generates the Gradle wrapper jar automatically.
   (No JDK/Gradle needs to be pre‑installed; Android Studio bundles them.)
2. Plug in your phone (USB debugging on) and press **Run ▶**.
3. Run the unit tests any time: **Run → app tests**, or `./gradlew testDebugUnitTest`.

## First‑run setup (in the app)

1. **Tessie token** — paste it, tap *Save token*, then *Load vehicles*.
2. **Vehicle** — pick your car from the list (or type the VIN).
3. **Arming**
   - *Auto‑arm on car Bluetooth* (default on) — tap **Choose car Bluetooth (paired
     devices)** and pick your Tesla from the list of devices already paired with the phone.
     The app then arms/disarms automatically when the phone connects to / disconnects from
     the car.
   - *Fire if a destination is already set at arm time* (default on).
4. **Permissions** — grant notifications when prompted; tap *Allow display over other apps*
   (so Waze opens with a single GO tap instead of a notification tap), and *Disable battery
   optimisation* (so the OS doesn't kill the watcher mid‑drive).
5. **Start watching now** — the manual arm; the foreground notification confirms it's running.

---

## Verifying it works

- **Unit tests** (logic): `./gradlew testDebugUnitTest` — covers the fire/dedup state machine,
  the Waze URL builder (lat/lon order), and Tessie JSON parsing.
- **End‑to‑end:** arm the app, set a destination in the Tesla, and within ~30s the phone opens
  Waze at the right place. Eyeball the pin — a reversed lat/lon lands in the ocean.
- **Auto‑arm:** connect the phone to the car's Bluetooth → the watcher starts; disconnect → it
  stops. (Verify on your phone; OEM battery managers can interfere — see Troubleshooting.)

## Troubleshooting

| Symptom | Likely cause / fix |
|---|---|
| Nothing happens when I set a destination | Car must be awake & a route actually set. Check the foreground notification text; check the token/VIN. |
| Waze shows the wrong location | lat/lon reversed — check `active_route_*` values from Step 0. |
| Auto‑arm never fires | The car must be paired with the phone AND chosen via *Choose car Bluetooth*, with battery optimisation disabled (Android 12+ blocks background starts otherwise — you'd see a "tap to start watching" notification instead). Manual *Start watching* always works. |
| Watcher stops overnight / mid‑drive | Disable battery optimisation for TeslaSync (Samsung/Xiaomi/etc. are aggressive). |
| Waze opens but doesn't auto‑navigate | Expected — tap **GO** (Waze removed auto‑start in Jan 2025). |

## Releases, CI & in-app updates

This mirrors the PeptideTrack pipeline, adapted for a native app with no server.

- **CI** (`.github/workflows/build-android.yml`): on every push to `main` that touches the app,
  or via manual *Run workflow*, GitHub Actions builds a **signed** release APK, versions it
  `YYYY.MM.DD.<run_number>` (versionCode = run number), and publishes it to a **GitHub Release**
  along with `teslasync-app.json` (version metadata).
- **In-app updates**: on launch, the app fetches
  `https://github.com/pcxnet/TeslaSync/releases/latest/download/teslasync-app.json`, compares
  `versionCode` to the running build, and shows an *Update* banner if newer. Tapping **Update**
  downloads the APK, verifies its SHA-256, and launches the system installer. Same signing key →
  clean in-place upgrade (Android prompts once for "install unknown apps").
- **Public repo required** so those `releases/latest/download/...` URLs are fetchable without a
  token. No secrets live in the repo — the signing key is in Actions secrets, the Tessie token is
  entered at runtime.

### One-time signing setup (do after installing Android Studio)

The CI build **fails until the signing secrets exist**. Generate the keystore and push the
secrets:

```powershell
pwsh scripts/setup-ci-secrets.ps1
```

It finds `keytool` (from Android Studio's bundled JDK), creates `keystore.jks` +
`keystore.properties` locally (git-ignored), and sets four Actions secrets:
`ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`,
`ANDROID_KEY_PASSWORD`.

> ⚠️ **Back up `keystore.jks` + `keystore.properties`.** Lose them and you can never ship another
> update — Android only accepts an upgrade signed with the same key.

Then trigger the first build:

```powershell
gh workflow run build-android.yml --repo pcxnet/TeslaSync
```

## Versioning

Calendar versioning `YYYY.MM.DD.<build>` — injected by CI (`ANDROID_VERSION_NAME`/`_CODE` env →
`app/build.gradle.kts`), shown at the bottom of the app screen. Local builds show `0.0.0-dev`.

## Privacy

Your Tessie token is stored encrypted on‑device (EncryptedSharedPreferences). The app talks
only to Tessie (`api.tessie.com`) and opens Waze locally. No backend, no analytics.
