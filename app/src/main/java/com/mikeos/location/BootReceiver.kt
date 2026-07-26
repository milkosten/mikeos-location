package com.mikeos.location

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Starts the always-on location provider on boot. On the MikeOS ROM this app is a priv-app
 * with location granted by default, so the foreground service can come straight up and begin
 * feeding the daemon before any app is opened.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED -> {
                Log.i("MikeLocation", "boot → starting LocationProviderService")
                runCatching { LocationProviderService.start(context) }
            }
        }
    }
}
