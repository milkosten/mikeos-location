package com.mikeos.location

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * The MikeOS system location provider.
 *
 * A persistent foreground Service that OWNS the phone's GNSS request and continuously feeds
 * fresh fixes to the on-device daemon (POST /api/location). Every reader app then gets a
 * fresh shared fix from GET /api/location no matter which app is foregrounded — replacing the
 * old fragile "location only works while MikeGuide is open" behaviour.
 *
 * Primary path: the platform [LocationManager] (GPS_PROVIDER + FUSED/NETWORK). This works
 * STANDALONE on the de-Googled MikeOS ROM where Google Play Services may be absent. If Play
 * Services IS present we additionally register a fused request (best-effort, reflection-safe);
 * the freshest fix wins on the daemon side, so having both is harmless.
 *
 * Adaptive cadence for battery: fast updates (~2s) while the screen is on / moving, backed
 * off (~30s, still under the daemon's 3-min STALE threshold) while the screen is off.
 */
class LocationProviderService : Service() {

    companion object {
        private const val TAG = "MikeLocation"
        private const val CHANNEL_ID = "mikeos_location"
        private const val NOTIF_ID = 7743

        // Active (screen-on) cadence — keep the daemon well under its 3-min STALE threshold.
        private const val ACTIVE_INTERVAL_MS = 2_000L
        private const val ACTIVE_MIN_MS = 1_500L
        // Backed-off (screen-off) cadence — still fresh enough (< 3 min) but battery-friendly.
        private const val IDLE_INTERVAL_MS = 30_000L
        private const val IDLE_MIN_MS = 20_000L

        fun start(ctx: Context) {
            val i = Intent(ctx, LocationProviderService::class.java)
            ContextCompat.startForegroundService(ctx, i)
        }
    }

    private lateinit var locationManager: LocationManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Last fix we pushed, surfaced on the status screen + notification.
    @Volatile private var lastFix: Location? = null
    @Volatile private var lastPushOk: Boolean = false
    @Volatile private var lastPushAt: Long = 0L

    // Fused (Play Services) client, if available. Held as Any? so the class loads even when
    // play-services-location is unusable at runtime.
    private var fused: com.google.android.gms.location.FusedLocationProviderClient? = null
    private var fusedCallback: com.google.android.gms.location.LocationCallback? = null

    private var screenOn = true
    private var registeredCadence: Long = -1L

    // Screen on/off drives the adaptive cadence.
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> setScreen(true)
                Intent.ACTION_SCREEN_OFF -> setScreen(false)
            }
        }
    }

    private val platformListener = object : LocationListener {
        override fun onLocationChanged(location: Location) = handleFix(location)
        override fun onProviderEnabled(provider: String) {
            DebugLog.log("provider '$provider' ENABLED")
        }
        override fun onProviderDisabled(provider: String) {
            DebugLog.w("provider '$provider' DISABLED (location toggle off? airplane?)")
        }
        @Deprecated("deprecated in API 29")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    }

    private var heartbeatJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        DebugLog.init(this)
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        createChannel()
        // CRASH-PROOF FGS PROMOTION: an FGS of type `location` throws SecurityException if
        // neither location permission is granted at startForeground() time (the old fleet
        // bug). Promote as `location` only when the permission is already held; otherwise
        // come up as `specialUse` — the type is upgraded to `location` on the first
        // onStartCommand after the grant (the watchdog re-kicks us every 15 min).
        promoteToForeground("Starting…")
        DebugLog.log(
            "service onCreate — perm fine=${has(Manifest.permission.ACCESS_FINE_LOCATION)} " +
                "coarse=${has(Manifest.permission.ACCESS_COARSE_LOCATION)} " +
                "bg=${has(Manifest.permission.ACCESS_BACKGROUND_LOCATION)} " +
                "providers=${runCatching { locationManager.allProviders }.getOrNull()} " +
                "sdk=${Build.VERSION.SDK_INT}",
        )

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        runCatching { registerReceiver(screenReceiver, filter) }

        startHeartbeat()
        ProviderWatchdogWorker.schedule(this)

        if (!hasLocationPermission()) {
            Log.w(TAG, "no location permission yet — service up, waiting for grant")
            DebugLog.w("NO location permission — GNSS updates NOT registered; waiting for grant (degraded, not crashed)")
            updateNotification("Waiting for location permission")
            return
        }

        // Seed with the platform's last-known fix immediately so the daemon has something
        // fresh-ish before the first live update arrives.
        pushLastKnown()
        startPlatformUpdates(ACTIVE_INTERVAL_MS, ACTIVE_MIN_MS)
        startFusedIfAvailable(ACTIVE_INTERVAL_MS)
        registeredCadence = ACTIVE_INTERVAL_MS
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        DebugLog.log(
            "onStartCommand flags=$flags startId=$startId perm=${hasLocationPermission()} " +
                "registeredCadence=$registeredCadence",
        )
        // If we came up before the permission was granted, try to (re)start updates now
        // (and upgrade the FGS type from specialUse to location).
        if (hasLocationPermission() && registeredCadence < 0) {
            DebugLog.log("permission now granted — registering GNSS updates (late grant path)")
            promoteToForeground("Starting…")
            pushLastKnown()
            startPlatformUpdates(ACTIVE_INTERVAL_MS, ACTIVE_MIN_MS)
            startFusedIfAvailable(ACTIVE_INTERVAL_MS)
            registeredCadence = ACTIVE_INTERVAL_MS
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        DebugLog.w("onTaskRemoved — task swiped away (service should survive, START_STICKY)")
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        DebugLog.w("service onDestroy — provider going DOWN (lastFix=${fixAgeStr()} lastPush=${DaemonLocationClient.lastResultDesc})")
        runCatching { locationManager.removeUpdates(platformListener) }
        runCatching { unregisterReceiver(screenReceiver) }
        stopFused()
        scope.cancel()
        super.onDestroy()
    }

    /**
     * startForeground with a type that cannot throw: `location` needs a granted location
     * permission (SecurityException otherwise, Android 14+), so fall back to `specialUse`
     * until the grant lands. Any residual failure is logged, never fatal.
     */
    private fun promoteToForeground(text: String) {
        val wantLocation = hasLocationPermission()
        val type = if (wantLocation) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        else ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        try {
            ServiceCompat.startForeground(this, NOTIF_ID, buildNotification(text), type)
            DebugLog.log("startForeground ok, type=${if (wantLocation) "location" else "specialUse (no perm yet)"}")
        } catch (e: Exception) {
            DebugLog.w("startForeground type=$type FAILED ${e.javaClass.simpleName}: ${e.message} — retrying as specialUse")
            runCatching {
                ServiceCompat.startForeground(
                    this, NOTIF_ID, buildNotification(text),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                )
            }.onFailure { DebugLog.w("startForeground specialUse ALSO failed: ${it.message}") }
        }
    }

    // --- 60s debug heartbeat -----------------------------------------------------------

    private fun startHeartbeat() {
        if (heartbeatJob != null) return
        heartbeatJob = scope.launch {
            while (true) {
                kotlinx.coroutines.delay(60_000L)
                runCatching {
                    DebugLog.log(
                        "alive — lastFix=${fixAgeStr()} lastPushResult=${DaemonLocationClient.lastResultDesc} " +
                            "lastPush=${pushAgeStr()} pushes=${DaemonLocationClient.pushOkCount}ok/" +
                            "${DaemonLocationClient.pushFailCount}fail " +
                            "consecFail=${DaemonLocationClient.consecutiveFailures} " +
                            "cadence=${if (registeredCadence < 0) "NONE" else "${registeredCadence}ms"} " +
                            "screen=${if (screenOn) "on" else "off"} perm=${hasLocationPermission()}",
                    )
                }
            }
        }
    }

    private fun fixAgeStr(): String {
        val f = lastFix ?: return "NEVER"
        return "${(System.currentTimeMillis() - f.time) / 1000}s ago"
    }

    private fun pushAgeStr(): String =
        if (lastPushAt == 0L) "never" else "${(System.currentTimeMillis() - lastPushAt) / 1000}s ago"

    private fun has(perm: String): Boolean =
        ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED

    // --- Cadence -----------------------------------------------------------------------

    private fun setScreen(on: Boolean) {
        if (screenOn == on) return
        screenOn = on
        val interval = if (on) ACTIVE_INTERVAL_MS else IDLE_INTERVAL_MS
        val minTime = if (on) ACTIVE_MIN_MS else IDLE_MIN_MS
        if (interval == registeredCadence) return
        if (!hasLocationPermission()) return
        Log.d(TAG, "cadence → ${if (on) "active(2s)" else "idle(30s)"}")
        DebugLog.log("screen=${if (on) "ON" else "OFF"} → cadence ${if (on) "active(2s)" else "idle(30s)"}")
        startPlatformUpdates(interval, minTime)
        startFusedIfAvailable(interval)
        registeredCadence = interval
    }

    // --- Platform LocationManager (primary, works without Play Services) ---------------

    private fun startPlatformUpdates(intervalMs: Long, minTimeMs: Long) {
        if (!hasLocationPermission()) return
        runCatching { locationManager.removeUpdates(platformListener) }

        // Register on every provider that exists on this device. GPS gives real satellite
        // fixes; FUSED/NETWORK give fast coarse fixes (useful indoors). The daemon keeps the
        // freshest, so registering on all of them only helps.
        val providers = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(LocationManager.FUSED_PROVIDER)
            add(LocationManager.GPS_PROVIDER)
            add(LocationManager.NETWORK_PROVIDER)
        }.distinct()

        var any = false
        for (p in providers) {
            try {
                if (!locationManager.allProviders.contains(p)) continue
                locationManager.requestLocationUpdates(
                    p, minTimeMs, 0f, platformListener, Looper.getMainLooper(),
                )
                any = true
                Log.d(TAG, "requestLocationUpdates on '$p' every ${minTimeMs}ms")
                DebugLog.log("registered GNSS updates on '$p' every ${minTimeMs}ms")
            } catch (e: SecurityException) {
                Log.w(TAG, "no permission for provider '$p': ${e.message}")
                DebugLog.w("SecurityException registering '$p': ${e.message}")
            } catch (e: Exception) {
                Log.w(TAG, "provider '$p' unavailable: ${e.message}")
                DebugLog.w("provider '$p' unavailable: ${e.message}")
            }
        }
        if (!any) {
            Log.w(TAG, "no location providers available")
            DebugLog.w("NO location providers registered — the daemon will never get a fix from us")
        }
    }

    private fun pushLastKnown() {
        if (!hasLocationPermission()) return
        try {
            val providers = listOf(
                LocationManager.GPS_PROVIDER,
                LocationManager.NETWORK_PROVIDER,
                LocationManager.PASSIVE_PROVIDER,
            )
            var best: Location? = null
            for (p in providers) {
                if (!locationManager.allProviders.contains(p)) continue
                val loc = runCatching { locationManager.getLastKnownLocation(p) }.getOrNull() ?: continue
                if (best == null || loc.time > best!!.time) best = loc
            }
            best?.let { handleFix(it) }
        } catch (e: SecurityException) {
            Log.w(TAG, "getLastKnownLocation denied: ${e.message}")
        }
    }

    // --- Fused (Play Services) — OPTIONAL, best-effort ---------------------------------

    private fun startFusedIfAvailable(intervalMs: Long) {
        try {
            // If Play Services isn't installed/usable this whole block throws and we simply
            // continue on the platform LocationManager path.
            if (!isPlayServicesAvailable()) return
            if (fused == null) {
                fused = com.google.android.gms.location.LocationServices
                    .getFusedLocationProviderClient(this)
            }
            val client = fused ?: return
            stopFusedCallback()

            val request = com.google.android.gms.location.LocationRequest.Builder(
                com.google.android.gms.location.Priority.PRIORITY_HIGH_ACCURACY,
                intervalMs,
            ).setMinUpdateIntervalMillis(intervalMs).build()

            val cb = object : com.google.android.gms.location.LocationCallback() {
                override fun onLocationResult(result: com.google.android.gms.location.LocationResult) {
                    result.lastLocation?.let { handleFix(it) }
                }
            }
            fusedCallback = cb
            if (hasLocationPermission()) {
                client.requestLocationUpdates(request, cb, Looper.getMainLooper())
                Log.d(TAG, "fused (Play Services) updates registered @ ${intervalMs}ms")
            }
        } catch (e: Throwable) {
            // No Play Services on this de-Googled ROM — expected. Platform path carries us.
            Log.d(TAG, "fused unavailable, using platform LocationManager: ${e.message}")
        }
    }

    private fun isPlayServicesAvailable(): Boolean = try {
        val gaa = com.google.android.gms.common.GoogleApiAvailability.getInstance()
        gaa.isGooglePlayServicesAvailable(this) ==
            com.google.android.gms.common.ConnectionResult.SUCCESS
    } catch (e: Throwable) {
        false
    }

    private fun stopFusedCallback() {
        try {
            val c = fusedCallback ?: return
            fused?.removeLocationUpdates(c)
        } catch (_: Throwable) {}
        fusedCallback = null
    }

    private fun stopFused() = stopFusedCallback()

    // --- Fix handling: push to daemon --------------------------------------------------

    private fun handleFix(location: Location) {
        // Ignore mock/test-provider fixes — the daemon rejects them too, but don't even push.
        if (location.isFromMockProvider) {
            Log.d(TAG, "ignoring mock fix")
            DebugLog.log("GNSS fix from '${location.provider}' IGNORED (mock provider)")
            return
        }
        DebugLog.log(
            "GNSS fix provider=%s lat=%.5f lon=%.5f acc=%s speed=%s sat=%s age=%ds".format(
                location.provider,
                location.latitude, location.longitude,
                if (location.hasAccuracy()) "±%.0fm".format(location.accuracy) else "-",
                if (location.hasSpeed()) "%.1fm/s".format(location.speed) else "-",
                location.extras?.getInt("satellites")?.takeIf { it > 0 }?.toString() ?: "-",
                (System.currentTimeMillis() - location.time) / 1000,
            ),
        )
        lastFix = location
        scope.launch {
            val ok = DaemonLocationClient.push(
                lat = location.latitude,
                lon = location.longitude,
                accuracy = if (location.hasAccuracy()) location.accuracy else null,
                altitude = if (location.hasAltitude()) location.altitude else null,
                speed = if (location.hasSpeed()) location.speed else null,
                bearing = if (location.hasBearing()) location.bearing else null,
                satellites = location.extras?.getInt("satellites")?.takeIf { it > 0 },
                source = "mikelocation",
            )
            lastPushOk = ok
            lastPushAt = System.currentTimeMillis()
            updateNotification(
                if (ok) "Feeding daemon · ${fmt(location)}"
                else "Fix ${fmt(location)} · daemon unreachable, retrying",
            )
        }
    }

    private fun fmt(l: Location): String =
        "%.5f, %.5f (±%.0fm)".format(l.latitude, l.longitude, if (l.hasAccuracy()) l.accuracy else 0f)

    // --- Notification ------------------------------------------------------------------

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        val ch = NotificationChannel(
            CHANNEL_ID, "MikeOS Location", NotificationManager.IMPORTANCE_LOW,
        ).apply { description = "System GNSS provider feeding the MikeOS daemon" }
        nm.createNotificationChannel(ch)
    }

    private fun buildNotification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("MikeOS Location active")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    private fun updateNotification(text: String) {
        runCatching {
            getSystemService(NotificationManager::class.java)
                .notify(NOTIF_ID, buildNotification(text))
        }
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
}
