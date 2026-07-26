# mikeos-location — CLAUDE.md

## What this is

**`com.mikeos.location`** is the MikeOS **system GNSS provider**: a headless, always-on foreground
Service that OWNS the phone's GNSS request and continuously feeds fresh fixes to the on-device daemon
(`POST https://127.0.0.1:7743/api/location`). Because the daemon is the single location authority
(`GET /api/location`), every reader app gets a fresh shared fix **regardless of which app is
foregrounded** — replacing the old fragile behaviour where location only worked while **MikeGuide** was
open/foregrounded.

It is **NOT a MikeAgent** — no heartbeat, no hive, no Agent Inspector, no `DaemonBrain`. It is a plain
system service. The only UI is a tiny status screen (`MainActivity`) that reads `/api/location` and
shows the last fix, plus requests the runtime location permission on a normal install.

Design source: `mikeos-architecture/docs/DAEMON-AS-SYSTEM.md` **Part B**.

## Architecture

- **`LocationProviderService`** — persistent foreground Service (`foregroundServiceType=location`).
  - **Primary path: platform `LocationManager`** (`GPS_PROVIDER` + `FUSED` + `NETWORK`). This works
    **standalone on the de-Googled MikeOS ROM where Google Play Services is ABSENT.**
  - **Optional: `FusedLocationProviderClient`** (Play Services) — registered too *if* Play Services is
    present (`GoogleApiAvailability` check, all wrapped in try/catch). The daemon keeps the freshest
    fix, so having both sources is harmless. On the ROM this path is simply skipped.
  - **Adaptive cadence:** ~2 s while screen-on, backed off to ~30 s while screen-off (still well under
    the daemon's 3-min STALE threshold). Driven by `ACTION_SCREEN_ON/OFF`.
  - On every fix it POSTs `{lat, lon, accuracy, altitude, speed, bearing, satellites, source:"mikelocation"}`
    to the daemon via `DaemonLocationClient`. **Mock/test-provider fixes are dropped** (`isFromMockProvider`).
- **`DaemonLocationClient`** — loopback push to `POST /api/location`. Uses `com.mikeos.core.net.LoopbackHttp`
  (trusts ONLY the daemon's self-signed `127.0.0.1` cert). Best-effort: a failed POST (daemon momentarily
  down) is swallowed and retried on the next fix. **Never-trust-200:** it confirms `applied:true` in the
  response before counting a push as success. `POST /api/location` is an auth-exempt loopback endpoint,
  but the bearer is sent anyway to future-proof.
- **`BootReceiver`** — starts the service on `BOOT_COMPLETED` / `LOCKED_BOOT_COMPLETED`.
- **`MainActivity`** — tiny status screen; also requests the runtime location permission and starts the
  service on a normal (non-ROM) install.

## Packaging: it is meant to be a ROM priv-app

Per DAEMON-AS-SYSTEM.md Part B, in the MikeOS ROM this ships as a **privileged system app**
(`/system/priv-app/MikeLocation` or `/product/priv-app`) with `ACCESS_FINE_LOCATION` +
`ACCESS_COARSE_LOCATION` + `ACCESS_BACKGROUND_LOCATION` + `FOREGROUND_SERVICE(_LOCATION)` **granted by
default** (via `privapp-permissions-mikeos.xml` + a baked default-permissions grant) — **no runtime
prompt**, and as a priv-app the service is non-killable by the user. On a normal sideload it requests the
runtime location permission in `MainActivity` (and you can `pm grant` it via adb).

## Build / install / verify

```bash
./gradlew assembleDebug --no-daemon --max-workers=2
adb -s R58N4101P2V install -r app/build/outputs/apk/debug/app-debug.apk
adb -s R58N4101P2V shell pm grant com.mikeos.location android.permission.ACCESS_FINE_LOCATION
adb -s R58N4101P2V shell pm grant com.mikeos.location android.permission.ACCESS_COARSE_LOCATION
adb -s R58N4101P2V shell am start -n com.mikeos.location/.MainActivity
# verify the daemon serves a FRESH fix driven by this provider:
adb -s R58N4101P2V shell 'curl -sk -m10 https://127.0.0.1:7743/api/location'   # expect stale:false, source:"mikelocation"
```

## FOLLOW-UP (required): strip MikeGuide's provider role

MikeGuide (`mikeos-guide`, the old `mikeguide:gps` provider) must be **demoted to a plain reader** of
`GET /api/location`. Delete its GPS/location-push code (its `POST /api/location` push path) so this app
is the *single* designated provider. Nothing else in MikeGuide changes — it keeps reading the shared fix
like every other app. This also resolves the "on-device GPS provider periodically stops feeding / gps-cloud
goes stale" standing issue, which was a symptom of location depending on MikeGuide being foregrounded.
