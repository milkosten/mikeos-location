package com.mikeos.location

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Starts the always-on location provider on boot. On the MikeOS ROM this app is a priv-app
 * with location granted by default, so the foreground service can come straight up and begin
 * feeding the daemon before any app is opened.
 *
 * Two-layer boot start (cannot crash):
 *  1. Direct FGS start. Android 15 allows a `location`-typed FGS from BOOT_COMPLETED (only
 *     dataSync, camera, mediaPlayback, mediaProjection, phoneCall and microphone are barred),
 *     and the service itself now
 *     promotes as `specialUse` when the location permission isn't granted yet, so the old
 *     "location FGS before grant → SecurityException" fleet bug cannot recur. Any residual
 *     denial is caught + logged.
 *  2. [ProviderWatchdogWorker] (15-min periodic, persisted by WorkManager across reboots)
 *     re-kicks the service even if the direct start was denied or the process is later killed.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED -> {
                DebugLog.init(context)
                Log.i("MikeLocation", "boot → starting LocationProviderService")
                val r = runCatching { LocationProviderService.start(context) }
                DebugLog.log(
                    "BOOT (${intent.action}) → direct FGS start " +
                        if (r.isSuccess) "ok"
                        else "DENIED ${r.exceptionOrNull()?.javaClass?.simpleName}: ${r.exceptionOrNull()?.message} (watchdog will retry)",
                )
                ProviderWatchdogWorker.schedule(context)
            }
        }
    }
}
