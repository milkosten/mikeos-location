package com.mikeos.location

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Watchdog for the always-on provider (GPS-dead-in-car incident hardening).
 *
 * Every ~15 min it (re)starts [LocationProviderService]. This covers the failure modes where
 * the service silently stops feeding and nothing restarts it:
 *  - the process was killed and START_STICKY didn't bring it back (OEM/battery kill),
 *  - the boot-time direct FGS start failed (e.g. FGS restrictions on a non-ROM install),
 *  - the location permission was granted AFTER the service came up (onStartCommand re-checks
 *    and registers GNSS updates on the next watchdog kick).
 *
 * If the service is already running the extra onStartCommand is a harmless no-op — so this
 * worker cannot crash the app and cannot double-register listeners. A background FGS-start
 * denial (ForegroundServiceStartNotAllowedException on a sideloaded install) is caught and
 * logged; on the MikeOS ROM the priv-app exemption lets it through.
 */
class ProviderWatchdogWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {

    override fun doWork(): Result {
        DebugLog.init(applicationContext)
        val r = runCatching { LocationProviderService.start(applicationContext) }
        DebugLog.log(
            "watchdog: start service → " +
                if (r.isSuccess) "ok" else "DENIED ${r.exceptionOrNull()?.javaClass?.simpleName}: ${r.exceptionOrNull()?.message}",
        )
        return Result.success()
    }

    companion object {
        private const val UNIQUE = "mikeos-location-watchdog"

        /** Idempotent; call from boot, app open, and service start. Persists across reboots. */
        fun schedule(context: Context) {
            runCatching {
                val req = PeriodicWorkRequestBuilder<ProviderWatchdogWorker>(15, TimeUnit.MINUTES)
                    .build()
                WorkManager.getInstance(context.applicationContext)
                    .enqueueUniquePeriodicWork(UNIQUE, ExistingPeriodicWorkPolicy.KEEP, req)
            }
        }
    }
}
