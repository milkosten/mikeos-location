package com.mikeos.location

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * GPS-incident debug logger ("MikeLocationDebug").
 *
 * Every interesting event in the provider chain (GNSS callback, daemon push, lifecycle,
 * permission state, 60s heartbeat) goes through here so a road test can be reconstructed
 * after the fact:
 *  - android.util.Log with tag [TAG] (logcat),
 *  - a rolling in-memory buffer of the last [MAX_BUFFER] lines ([snapshot] — shown in the
 *    status UI),
 *  - an append-only file the user can pull WITHOUT root:
 *    getExternalFilesDir(null)/location-debug.log
 *    (adb pull /sdcard/Android/data/com.mikeos.location/files/location-debug.log)
 *    capped at ~1 MB: on oversize it is truncated down to the in-memory tail.
 *
 * Never throws — logging must not be able to take the provider down.
 */
object DebugLog {

    const val TAG = "MikeLocationDebug"
    private const val MAX_BUFFER = 200
    private const val MAX_FILE_BYTES = 1_048_576L // ~1 MB

    private val buffer = ArrayDeque<String>(MAX_BUFFER)
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    @Volatile private var logFile: File? = null

    /** Idempotent; safe to call from every entry point (service, receiver, activity, worker). */
    fun init(context: Context) {
        if (logFile != null) return
        runCatching {
            val dir = context.applicationContext.getExternalFilesDir(null) ?: return
            logFile = File(dir, "location-debug.log")
        }
    }

    @Synchronized
    fun log(line: String) {
        val stamped = "${fmt.format(Date())} $line"
        Log.i(TAG, line)
        if (buffer.size >= MAX_BUFFER) buffer.pollFirst()
        buffer.addLast(stamped)
        writeToFile(stamped)
    }

    fun w(line: String) = log("WARN $line")

    /** Last N lines, newest last (for the status screen). */
    @Synchronized
    fun snapshot(n: Int = MAX_BUFFER): List<String> = buffer.toList().takeLast(n)

    private fun writeToFile(stamped: String) {
        val f = logFile ?: return
        runCatching {
            if (f.exists() && f.length() > MAX_FILE_BYTES) {
                // Simple truncate-on-oversize: keep only the in-memory tail.
                f.writeText(buffer.joinToString("\n") + "\n")
            }
            f.appendText(stamped + "\n")
        }
    }
}
