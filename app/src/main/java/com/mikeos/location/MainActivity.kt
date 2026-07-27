package com.mikeos.location

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.mikeos.core.net.loopbackTrustingClientPublic
import com.mikeos.location.ui.theme.MikeLocationTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * The tiny status face of MikeOS Location. There is no real UI — this activity exists to:
 *  1. request the runtime location permission on a normal (non-ROM) install, and
 *  2. start the always-on [LocationProviderService], and
 *  3. show a live status read of the daemon's GET /api/location so you can confirm the
 *     provider is actually feeding fresh fixes.
 */
class MainActivity : ComponentActivity() {

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { LocationProviderService.start(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        DebugLog.init(this)
        DebugLog.log("MainActivity opened")
        ProviderWatchdogWorker.schedule(this)
        ensurePermissionsThenStart()

        setContent {
            MikeLocationTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { pad ->
                    StatusScreen(pad)
                }
            }
        }
    }

    private fun ensurePermissionsThenStart() {
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
        if (fine == PackageManager.PERMISSION_GRANTED) {
            LocationProviderService.start(this)
            return
        }
        val perms = buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }.toTypedArray()
        permLauncher.launch(perms)
        // Start now too; the service tolerates a missing grant and picks up on the next start.
        LocationProviderService.start(this)
    }
}

private val statusClient: OkHttpClient by lazy {
    loopbackTrustingClientPublic("https://127.0.0.1:7743").newBuilder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .build()
}

@Composable
private fun StatusScreen(pad: PaddingValues) {
    var status by remember { mutableStateOf("Reading daemon…") }
    var debugTail by remember { mutableStateOf(listOf<String>()) }

    LaunchedEffect(Unit) {
        while (true) {
            status = fetchDaemonStatus()
            debugTail = DebugLog.snapshot(8)
            delay(3000)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(pad)
            .padding(28.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            "MikeOS Location",
            fontSize = 26.sp,
            color = MaterialTheme.colorScheme.secondary,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "The system GNSS provider — feeding the daemon so every app gets a fresh fix.",
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
        Text(
            status,
            fontSize = 15.sp,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Start,
        )
        Spacer(Modifier.height(24.dp))
        Text(
            "DEBUG (MikeLocationDebug · files/location-debug.log)",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.secondary,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            if (debugTail.isEmpty()) "no events yet" else debugTail.joinToString("\n"),
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private suspend fun fetchDaemonStatus(): String = withContext(Dispatchers.IO) {
    try {
        val req = Request.Builder().url("https://127.0.0.1:7743/api/location").get().build()
        statusClient.newCall(req).execute().use { resp ->
            val txt = resp.body?.string().orEmpty()
            val j = JSONObject(txt)
            val lat = if (j.isNull("lat")) null else j.optDouble("lat")
            val lon = if (j.isNull("lon")) null else j.optDouble("lon")
            if (lat == null || lon == null) return@withContext "No fix yet — waiting for GNSS lock."
            val acc = if (j.isNull("accuracy")) null else j.optDouble("accuracy")
            val stale = j.optBoolean("stale", true)
            val ageMs = if (j.isNull("fixAgeMs")) null else j.optLong("fixAgeMs")
            val source = if (j.isNull("source")) "?" else j.optString("source")
            val ageStr = ageMs?.let { "${it / 1000}s ago" } ?: "?"
            buildString {
                append(if (stale) "STALE" else "FRESH")
                append(" · last fix $ageStr\n\n")
                append("lat/lon: %.5f, %.5f\n".format(lat, lon))
                if (acc != null) append("accuracy: ±%.0fm\n".format(acc))
                append("source: $source")
            }
        }
    } catch (e: Exception) {
        "Daemon unreachable: ${e.message}"
    }
}
