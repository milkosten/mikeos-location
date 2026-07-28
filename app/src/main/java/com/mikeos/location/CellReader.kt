package com.mikeos.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.CellIdentityNr
import android.telephony.CellInfo
import android.telephony.CellInfoGsm
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import android.telephony.CellInfoWcdma
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * PERCEIVE (CELL) — moved OUT of the foreground MikeWIFI app INTO this always-on system
 * provider so cell positioning no longer depends on a user app that Android dozes.
 *
 * The Node daemon (root, non-app) CANNOT read raw cell IDs — the framework redacts mCi/mTac
 * to hashes for any non-ACCESS_FINE_LOCATION caller. This service DOES hold the permission and
 * runs persistently (foreground service + daemon watchdog + boot receiver), so it is the right
 * headless place to read cells and feed them to the daemon (POST /api/location/context).
 *
 * Each visible tower becomes a "cell key" `mcc-mnc-lac-cid` (e.g. "208-10-1234-56789"),
 * strongest-first, capped. Best-effort: returns an empty list on any error / permission gap /
 * no cellular, and NEVER throws.
 */
class CellReader(private val context: Context) {

    private val tm: TelephonyManager? =
        context.applicationContext.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Visible cell towers as `mcc-mnc-lac-cid` keys, strongest signal first, capped at [cap].
     * Empty on any error / permission gap / no cellular. MUST be called off the main thread.
     */
    @Suppress("DEPRECATION")
    fun cellKeys(cap: Int = 6): List<String> {
        val telephony = tm ?: return emptyList()
        if (!hasLocationPermission()) return emptyList()
        return try {
            // getAllCellInfo() returns an EMPTY cache on Pixel/Tensor until the modem is
            // actively polled; requestCellInfoUpdate() forces a fresh query (fall back to the
            // cached list on timeout). Same fix as MikeWIFI's sensor.
            val fresh = requestFreshCellInfo(telephony)
            val infos: List<CellInfo> = if (!fresh.isNullOrEmpty()) fresh
                else telephony.allCellInfo.orEmpty()
            infos
                .mapNotNull { info -> keyAndLevel(info) }
                .sortedByDescending { it.second }
                .map { it.first }
                .distinct()
                .take(cap)
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun requestFreshCellInfo(telephony: TelephonyManager): List<CellInfo>? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        if (!hasLocationPermission()) return null
        val exec = Executors.newSingleThreadExecutor()
        return try {
            val latch = CountDownLatch(1)
            val holder = AtomicReference<List<CellInfo>?>(null)
            telephony.requestCellInfoUpdate(exec, object : TelephonyManager.CellInfoCallback() {
                override fun onCellInfo(activeCellInfo: MutableList<CellInfo>) {
                    holder.set(activeCellInfo); latch.countDown()
                }
                override fun onError(errorCode: Int, detail: Throwable?) { latch.countDown() }
            })
            latch.await(3, TimeUnit.SECONDS)
            holder.get()
        } catch (e: Exception) {
            null
        } finally {
            exec.shutdownNow()
        }
    }

    /** One cell → (key, signal-dBm) or null when its identity is incomplete. */
    private fun keyAndLevel(info: CellInfo): Pair<String, Int>? = try {
        when (info) {
            is CellInfoLte -> {
                val id = info.cellIdentity
                key(id.mccString, id.mncString, id.tac, id.ci)?.let { it to info.cellSignalStrength.dbm }
            }
            is CellInfoGsm -> {
                val id = info.cellIdentity
                key(id.mccString, id.mncString, id.lac, id.cid)?.let { it to info.cellSignalStrength.dbm }
            }
            is CellInfoWcdma -> {
                val id = info.cellIdentity
                key(id.mccString, id.mncString, id.lac, id.cid)?.let { it to info.cellSignalStrength.dbm }
            }
            else -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && info is CellInfoNr) {
                    val id = info.cellIdentity
                    if (id is CellIdentityNr) {
                        // NR (5G): TAC stands in for LAC, NCI (long) for CID.
                        key(id.mccString, id.mncString, id.tac, id.nci)?.let {
                            it to info.cellSignalStrength.dbm
                        }
                    } else null
                } else null
            }
        }
    } catch (e: Exception) {
        null
    }

    /** Build `mcc-mnc-lac-cid`, or null if any field is unavailable/invalid. */
    private fun key(mcc: String?, mnc: String?, lac: Long, cid: Long): String? {
        if (mcc.isNullOrBlank() || mnc.isNullOrBlank()) return null
        if (lac == Long.MAX_VALUE || lac < 0) return null
        if (cid == Long.MAX_VALUE || cid < 0) return null
        return "$mcc-$mnc-$lac-$cid"
    }

    private fun key(mcc: String?, mnc: String?, lac: Int, cid: Int): String? {
        if (lac == Int.MAX_VALUE || cid == Int.MAX_VALUE) return null
        return key(mcc, mnc, lac.toLong(), cid.toLong())
    }
    private fun key(mcc: String?, mnc: String?, lac: Int, cid: Long): String? {
        if (lac == Int.MAX_VALUE) return null
        return key(mcc, mnc, lac.toLong(), cid)
    }
}
