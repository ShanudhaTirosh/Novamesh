package com.novamesh.hotspot

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * NovaMesh MainActivity
 *
 * FIXES applied:
 *  1. startHotspotService() wrapped in try-catch — catches
 *     ForegroundServiceStartNotAllowedException on Android 12+ when called
 *     during the brief window after a permission dialog closes.
 *  2. Service start is guarded by a serviceStarted flag — prevents double-start
 *     if onResume fires after a re-create.
 *  3. Service start is posted via Handler(mainLooper) with a tiny delay so the
 *     window fully returns to foreground before startForegroundService() is called.
 *  4. Firebase sign-in failure is handled gracefully; app continues in local mode.
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "NovaMesh:MainActivity"
    }

    private lateinit var configStore    : HotspotConfigStore
    private lateinit var firebaseManager: FirebaseManager

    private lateinit var tvStatus       : TextView
    private lateinit var tvSSID         : TextView
    private lateinit var tvDashboardUrl : TextView
    private lateinit var tvRootStatus   : TextView
    private lateinit var btnOpenDashboard: Button
    private lateinit var btnStop        : Button

    // Guard: only start the service once per app session
    private var serviceStarted = false

    // ── Permission launchers ──────────────────────────────────────────────

    private val notificationPermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            // Proceed regardless of notification permission result
            requestLocationPermissionIfNeeded()
        }

    private val locationPermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            val fineGranted = results[Manifest.permission.ACCESS_FINE_LOCATION] == true
            if (!fineGranted) {
                Log.w(TAG, "Location permission denied — hotspot API limited")
                tvStatus.text = "Location denied — hotspot may be limited"
                Toast.makeText(this, "Location permission needed for hotspot", Toast.LENGTH_LONG).show()
            }
            // Always proceed — app works in degraded mode without location
            beginAuthFlow()
        }

    // ── Lifecycle ─────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus          = findViewById(R.id.tvStatus)
        tvSSID            = findViewById(R.id.tvSSID)
        tvDashboardUrl    = findViewById(R.id.tvDashboardUrl)
        tvRootStatus      = findViewById(R.id.tvRootStatus)
        btnOpenDashboard  = findViewById(R.id.btnOpenDashboard)
        btnStop           = findViewById(R.id.btnStop)

        configStore      = HotspotConfigStore(this)
        firebaseManager  = try { FirebaseManager(this) }
                           catch (e: Exception) { FirebaseManager(this, offline = true) }

        // Show root status (cached from NovaMeshApp startup — no blocking call here)
        tvRootStatus.text = "Root: ${RootUtils.statusLabel}"

        btnOpenDashboard.setOnClickListener { openDashboard() }
        btnStop.setOnClickListener {
            try { startService(HotspotService.stopIntent(this)) } catch (_: Exception) {}
            tvStatus.text = "Service stopped"
        }

        requestNotificationPermissionIfNeeded()
    }

    // ── Permission chain ──────────────────────────────────────────────────

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            notificationPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        requestLocationPermissionIfNeeded()
    }

    private fun requestLocationPermissionIfNeeded() {
        val fine = Manifest.permission.ACCESS_FINE_LOCATION
        if (ContextCompat.checkSelfPermission(this, fine) != PackageManager.PERMISSION_GRANTED) {
            locationPermLauncher.launch(arrayOf(fine, Manifest.permission.ACCESS_COARSE_LOCATION))
            return
        }
        beginAuthFlow()
    }

    // ── Auth + service start ──────────────────────────────────────────────

    private fun beginAuthFlow() {
        if (firebaseManager.isSignedIn()) {
            onSignedIn()
        } else {
            startSignIn()
        }
    }

    private fun startSignIn() {
        tvStatus.text = "Connecting…"
        firebaseManager.signIn(
            email    = "admin@novamesh.local",
            password = "nova2024",
            onSuccess = { user ->
                Log.i(TAG, "Firebase signed in: ${user.email}")
                onSignedIn()
            },
            onError = { e ->
                // Expected on first run — no user created yet. App works fully in local mode.
                Log.w(TAG, "Firebase sign-in skipped (${e.message}) — local mode")
                runOnUiThread {
                    tvStatus.text = "Local mode (Firebase optional)"
                }
                scheduleServiceStart()
            }
        )
    }

    private fun onSignedIn() {
        lifecycleScope.launch(Dispatchers.IO) {
            val config = configStore.getConfig()
            withContext(Dispatchers.Main) {
                tvSSID.text         = "SSID: ${config.ssid}"
                tvDashboardUrl.text = "Dashboard: http://${HotspotManager.HOTSPOT_IP}:${HotspotService.WEB_SERVER_PORT}"
                tvStatus.text       = "Service starting…"
            }
        }
        scheduleServiceStart()
    }

    // ── FIX 3: Delayed service start ─────────────────────────────────────
    // Post with a short delay so the activity window is FULLY in the foreground
    // before startForegroundService() is called.
    // This prevents ForegroundServiceStartNotAllowedException on Android 12+
    // which fires when the system still considers the app "transitioning from background".
    private fun scheduleServiceStart() {
        if (serviceStarted) return
        Handler(Looper.getMainLooper()).postDelayed({
            startHotspotService()
        }, 300L)
    }

    private fun startHotspotService() {
        if (serviceStarted) return
        serviceStarted = true

        val intent = HotspotService.startIntent(this)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            tvStatus.text = "Service running"
            Log.i(TAG, "HotspotService started")
        } catch (e: Exception) {
            // ForegroundServiceStartNotAllowedException (Android 12+) or SecurityException
            Log.w(TAG, "startForegroundService failed (${e.javaClass.simpleName}): ${e.message}")
            tvStatus.text = "Service start failed — tap again"
            serviceStarted = false   // allow retry
            // Fallback: try plain startService
            try {
                startService(intent)
                serviceStarted = true
                tvStatus.text = "Service running (compat mode)"
            } catch (e2: Exception) {
                Log.e(TAG, "startService also failed: ${e2.message}")
                tvStatus.text = "Could not start service — check permissions"
            }
        }
    }

    // ── UI ────────────────────────────────────────────────────────────────

    private fun openDashboard() {
        val url = "http://${HotspotManager.HOTSPOT_IP}:${HotspotService.WEB_SERVER_PORT}"
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            Toast.makeText(this, "No browser found", Toast.LENGTH_SHORT).show()
        }
    }
}
