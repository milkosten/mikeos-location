package com.mikeos.location

import android.util.Log
import com.mikeos.core.net.loopbackTrustingClientPublic
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Posts GNSS fixes to the on-device daemon at POST https://127.0.0.1:7743/api/location.
 *
 * This is the single-location-authority push path: the daemon accepts a real fix here
 * and serves it from GET /api/location to every reader app, regardless of which app is
 * foregrounded. POST /api/location is an auth-exempt loopback endpoint on the daemon, but
 * we include the bearer anyway (harmless, and future-proofs if the route is tightened).
 *
 * Best-effort: a failed POST (daemon momentarily down) is swallowed; the next fix retries.
 * The daemon reads: { lat, lon, accuracy, speed, altitude, bearing, satellites, source }.
 */
object DaemonLocationClient {

    private const val TAG = "MikeLocation"
    private const val BASE = "https://127.0.0.1:7743"
    private const val URL = "$BASE/api/location"
    private const val CONTEXT_URL = "$BASE/api/location/context"
    private const val BEARER = "7bdc23451b18b5801036f992b66a872670975d19"

    /** Debug telemetry for the 60s heartbeat line + the status screen. */
    @Volatile var lastResultDesc: String = "never pushed"
        private set
    @Volatile var pushOkCount: Long = 0L
        private set
    @Volatile var pushFailCount: Long = 0L
        private set
    @Volatile var consecutiveFailures: Long = 0L
        private set

    private val JSON = "application/json; charset=utf-8".toMediaType()

    // Loopback-trusting client (self-signed 127.0.0.1 cert), with tight timeouts so a
    // stuck daemon never wedges the location loop.
    private val client: OkHttpClient by lazy {
        loopbackTrustingClientPublic(BASE).newBuilder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .writeTimeout(8, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Push one fix. Returns true if the daemon acknowledged applying it (`applied:true`).
     * Never throws — logs and returns false on any error.
     */
    fun push(
        lat: Double,
        lon: Double,
        accuracy: Float?,
        altitude: Double?,
        speed: Float?,
        bearing: Float?,
        satellites: Int?,
        source: String = "mikelocation",
    ): Boolean {
        val attempt = "push #%d lat=%.5f lon=%.5f acc=%s sat=%s src=%s".format(
            pushOkCount + pushFailCount + 1, lat, lon,
            accuracy?.let { "%.0fm".format(it) } ?: "-", satellites?.toString() ?: "-", source,
        )
        return try {
            val body = JSONObject().apply {
                put("lat", lat)
                put("lon", lon)
                if (accuracy != null && !accuracy.isNaN()) put("accuracy", accuracy.toDouble())
                if (altitude != null && !altitude.isNaN()) put("altitude", altitude)
                if (speed != null && !speed.isNaN()) put("speed", speed.toDouble())
                if (bearing != null && !bearing.isNaN()) put("bearing", bearing.toDouble())
                if (satellites != null) put("satellites", satellites)
                put("source", source)
            }.toString()

            val req = Request.Builder()
                .url(URL)
                .addHeader("Authorization", "Bearer $BEARER")
                .post(body.toRequestBody(JSON))
                .build()

            client.newCall(req).execute().use { resp ->
                val txt = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    Log.w(TAG, "push HTTP ${resp.code}: $txt")
                    // The boot-gate/unpaired 503 lands here — capture code AND body verbatim.
                    recordFailure("HTTP ${resp.code} body=${txt.take(300)}", attempt)
                    return false
                }
                // Never-trust-200: confirm the daemon actually applied the fix.
                val applied = runCatching { JSONObject(txt).optBoolean("applied", false) }.getOrDefault(false)
                if (!applied) {
                    Log.w(TAG, "daemon did not apply fix: $txt")
                    recordFailure("200-but-not-applied body=${txt.take(300)}", attempt)
                } else {
                    recordSuccess(attempt)
                }
                applied
            }
        } catch (e: Exception) {
            // Daemon down / not reachable yet — fine, retry on the next fix.
            Log.d(TAG, "push failed (will retry next fix): ${e.message}")
            recordFailure("EXC ${e.javaClass.simpleName}: ${e.message}", attempt)
            false
        }
    }

    /**
     * Push visible cell towers to the daemon (POST /api/location/context {cells}). The daemon
     * MERGES these with its context and crowd-sources them on a fresh GNSS fix — the same path
     * MikeWIFI used, but now driven by this always-on provider so it survives Doze. Cell IDs
     * are only readable by an app with ACCESS_FINE_LOCATION (this one); the Node daemon can't
     * read them itself. Best-effort, never throws.
     */
    fun pushCells(cells: List<String>) {
        if (cells.isEmpty()) return
        try {
            val arr = org.json.JSONArray()
            cells.take(6).forEach { arr.put(it) }
            val body = JSONObject().put("cells", arr).toString()
            val req = Request.Builder()
                .url(CONTEXT_URL)
                .addHeader("Authorization", "Bearer $BEARER")
                .post(body.toRequestBody(JSON))
                .build()
            client.newCall(req).execute().use { resp ->
                DebugLog.log("cells push (${cells.size}) → HTTP ${resp.code}")
            }
        } catch (e: Exception) {
            DebugLog.w("cells push failed: ${e.message}")
        }
    }

    private fun recordSuccess(attempt: String) {
        pushOkCount++
        consecutiveFailures = 0
        lastResultDesc = "OK (applied)"
        DebugLog.log("$attempt → OK applied (ok=$pushOkCount fail=$pushFailCount)")
    }

    private fun recordFailure(desc: String, attempt: String) {
        pushFailCount++
        consecutiveFailures++
        lastResultDesc = desc
        DebugLog.w("$attempt → FAIL $desc (consecutive=$consecutiveFailures ok=$pushOkCount fail=$pushFailCount)")
    }
}
