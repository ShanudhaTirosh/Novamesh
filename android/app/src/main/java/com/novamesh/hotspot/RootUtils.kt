package com.novamesh.hotspot

import android.util.Log

/**
 * RootUtils — Automatic root detection for NovaMesh
 *
 * Detects root access once at startup and caches the result for the app lifetime.
 * Root-dependent features (iptables, tc bandwidth control, DNS redirect) are
 * automatically enabled when root is available and silently skipped when not.
 *
 * Detection strategy:
 *  1. Check for 'su' binary in common system paths
 *  2. Attempt 'su -c id' and verify UID=0 in the output
 *  3. Cache result — avoids repeated su prompts
 */
object RootUtils {

    private const val TAG = "NovaMesh:RootUtils"

    @Volatile private var checked = false
    @Volatile private var _isRooted = false

    /**
     * True if the device has root access and su responded with uid=0.
     * First call performs the detection (may take ~300ms). All subsequent
     * calls return instantly from cache.
     */
    val isRooted: Boolean
        get() {
            if (!checked) {
                synchronized(this) {
                    if (!checked) {
                        _isRooted = detect()
                        checked   = true
                        Log.i(TAG, if (_isRooted)
                            "✓ Root DETECTED — advanced features enabled (iptables, tc, DNS redirect)"
                        else
                            "✗ Root NOT available — running in safe mode (LocalOnlyHotspot + ARP only)"
                        )
                    }
                }
            }
            return _isRooted
        }

    /** Human-readable status string for the dashboard */
    val statusLabel: String get() = if (isRooted) "Rooted" else "No Root"

    /** Force re-detection on next access (call if root state may have changed) */
    fun reset() {
        checked = false
    }

    // ────────────────────────────────────────────
    //  Detection Logic
    // ────────────────────────────────────────────

    private fun detect(): Boolean {
        // Step 1: su binary must exist
        val suPaths = listOf(
            "/system/bin/su",
            "/system/xbin/su",
            "/sbin/su",
            "/system/su",
            "/system/xbin/sudo",
            "/data/local/xbin/su",
            "/data/local/bin/su",
            "/data/local/su"
        )
        val suExists = suPaths.any { java.io.File(it).exists() }
        if (!suExists) {
            Log.d(TAG, "su binary not found in any standard path")
            return false
        }
        Log.d(TAG, "su binary found — attempting privilege check")

        // Step 2: Execute 'su -c id' and verify uid=0
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
            val output  = process.inputStream.bufferedReader().readText().trim()
            val exit    = process.waitFor()
            Log.d(TAG, "su test → exit=$exit  output='$output'")
            exit == 0 && output.contains("uid=0")
        } catch (e: Exception) {
            Log.d(TAG, "su execution threw: ${e.message}")
            false
        }
    }
}
