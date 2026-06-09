# TeslaSync — design / plan

> Original design doc from the brainstorming session, kept with the project for
> reference. Implementation has since added a CI pipeline (GitHub Actions →
> signed APK → GitHub Release) and an in-app self-updater — see `../CLAUDE.md`
> ("CI & self-update") and `../README.md` ("Releases, CI & in-app updates").

---

# Tesla → Waze Bridge (Android App)

## Context

**Goal:** When Chris enters a destination on his Tesla's screen, an Android app
detects it within ~15–30s and opens Waze pre-loaded with that destination, so he
can navigate using Waze's police/camera/hazard alerts instead of Tesla's built‑in
nav. Tesla becomes the *input* method; Waze does the actual guiding.

**Why this shape (decided during brainstorming):**
- The painful 80% of this problem is Tesla's OAuth / partner registration / public‑key
  hosting / virtual‑key pairing / token refresh / wake management. **Tessie** (~US$6.99/
  vehicle/month) absorbs all of it behind a simple REST API, with **free, unlimited
  polling** that never wakes or drains the car. That removes the only hard part.
- Because the phone side is a **custom app** (not an automation tool), the app can poll
  Tessie *directly* from a foreground service that only runs while driving — so there is
  **no server, no Firebase, no cPanel**. The whole product is one Android app + the Tessie
  subscription.
- Arming is **both** Bluetooth‑auto (start polling when the phone connects to the car's
  Bluetooth, stop on disconnect) **and** a manual toggle as a guaranteed‑reliable fallback.

**Two constraints that are true regardless of implementation (set expectations):**
1. **One tap in Waze.** Since Waze Android 5.3.0.2 (Jan 2025), `navigate=yes` no longer
   auto‑starts; Waze opens on the route‑preview screen and the user taps **GO**. Not
   hands‑free. (Re‑verify on the actual phone — Waze changes this.)
2. **~15–30s lag.** REST polling, not instant push. Fine because the destination is set
   before pulling out.

---

## Step 0 — De‑risk before writing any app code (5 minutes)

Before building, prove the Tesla side works for *this specific car/firmware*. In the car,
set a navigation destination, then from any machine:

```powershell
curl -H "Authorization: Bearer <TESSIE_TOKEN>" `
  "https://api.tessie.com/<VIN>/state?use_cache=true"
```

Confirm the response JSON contains, under `drive_state`:
`active_route_destination` (string), `active_route_latitude`, `active_route_longitude`
(numbers). If these populate, the whole project is green‑lit.

**Prerequisites:** a Tessie account with the Tesla linked, and a Tessie API token
(Tessie → Settings → API). Waze installed on the phone.

---

## Architecture

```
Tesla screen (set destination)
      │  (Tessie reads Tesla Fleet API for you)
      ▼
Tessie REST  GET https://api.tessie.com/{vin}/state
      ▲  polled every ~15s by the app, ONLY while armed
      │
┌─────┴───────────────────────── Android app ─────────────────────────┐
│  DestinationWatcherService (foreground)                              │
│    • poll Tessie → read drive_state.active_route_*                   │
│    • dedup: fire only when destination CHANGES to a new value        │
│    • launch Waze:  https://waze.com/ul?ll=LAT,LON&navigate=yes       │
│  Arming:  CompanionDeviceManager (car Bluetooth) + manual toggle     │
│  Token:   EncryptedSharedPreferences                                 │
└──────────────────────────────────────────────────────────────────────┘
      ▼
Waze opens to the route → user taps GO
```

No backend. The app is the entire system.

---

## Tech stack

- **Kotlin** + Jetpack Compose. `minSdk 26`, `targetSdk 35`.
- **OkHttp** + **Coroutines** for the Tessie poll loop (a `while` loop with `delay(15_000)`
  inside the foreground service — `WorkManager`'s 15‑min minimum is far too slow).
- **EncryptedSharedPreferences** (androidx.security.crypto) for the Tessie token + VIN.
- **CompanionDeviceManager** for Bluetooth‑triggered arming (the modern API for
  "wake my app when this paired device connects", with background‑launch privileges).

---

## Components

1. **Onboarding / Settings screen** — Tessie token (encrypted), VIN picker (fetched from
   Tessie), car Bluetooth picker, arming toggles, permission prompts, app version.
2. **`TessieClient`** — `GET /{vin}/state?use_cache=true`; parse `drive_state.active_route_*`;
   never crash the loop (log + continue on empty/asleep/HTTP/non‑JSON).
3. **`DestinationWatcherService`** (foreground) — coroutine poll loop + dedup state machine
   (`lastFiredKey` = rounded lat,lon; fire on change; clear on empty; `prime` to suppress
   firing an already‑set destination on arm).
4. **`WazeLauncher`** — `https://waze.com/ul?ll=$lat,$lon&navigate=yes` (lat,lon order!),
   package `com.waze`; direct launch if SYSTEM_ALERT_WINDOW granted, else heads‑up
   notification the user taps.
5. **Arming** — manual toggle + CompanionDeviceManager presence (auto‑arm on car BT).
6. **Permissions** — INTERNET, FOREGROUND_SERVICE(+DATA_SYNC/CONNECTED_DEVICE),
   BLUETOOTH_CONNECT, POST_NOTIFICATIONS, SYSTEM_ALERT_WINDOW, companion run/start perms.

---

## Confirmed facts (don't re‑derive)

- **Tessie:** `GET /{vin}/state` → `drive_state.active_route_destination` / `_latitude` /
  `_longitude`. Polling free & unlimited; `use_cache=true` <15s; never wakes the car.
- **Waze:** `https://waze.com/ul?ll=LAT,LON&navigate=yes`, package `com.waze`; `ll` is
  latitude,longitude; `?q=address` does NOT auto‑route. `navigate=yes` no longer auto‑starts
  since Waze Android 5.3.0.2 — user taps GO.
- **No Tesla→phone webhook** — the app must poll (hence the foreground service).
- Tesla gives lat/lon directly — no geocoding.

---

## Verification

**Unit (pure functions):** dedup state machine; Waze URL builder (lat,lon order); Tessie
JSON parsing (populated / null / error bodies).

**End‑to‑end (real device + car):** token + VIN → arm → set destination in Tesla → within
~30s Waze opens at the correct pin; BT connect arms / disconnect disarms; changing the
destination re‑fires, leaving it doesn't; confirm the "GO" tap behaviour on the live Waze.

**Tessie de‑risk (Step 0)** first — highest‑value check.

---

## Project location

- Standalone Android Studio project at **`C:\Users\chris\OneDrive\AI\TeslaSync`** (separate
  from the Obsidian wiki vault).
- **OneDrive caveat:** exclude `build/`, `.gradle/`, `.idea/` from OneDrive sync (they're
  git‑ignored) or builds hit file‑lock errors.

## Upgrade path if needed

- If the OS proves too aggressive at killing the foreground service, fall back to a
  server+FCM design (Node bridge on cPanel polling Tessie + Firebase push). Not expected to
  be necessary for a drive‑duration foreground service.
