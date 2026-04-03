package com.novamesh.hotspot

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
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
    private lateinit var tvPassword     : TextView
    private lateinit var tvDashboardUrl : TextView
    private lateinit var tvRootStatus   : TextView
    private lateinit var btnOpenDashboard: Button
    private lateinit var btnStop        : Button
    private lateinit var btnStart       : Button

    // Guard: only start the service once per app session
    private var serviceStarted = false

    // Actual gateway IP received from the service broadcast
    private var currentGatewayIp = HotspotManager.HOTSPOT_IP

    // ── Status broadcast receiver ─────────────────────────────────────────

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != HotspotService.ACTION_STATUS) return
            val status = intent.getStringExtra(HotspotService.EXTRA_STATUS) ?: return
            when (status) {
                "running" -> {
                    val ssid    = intent.getStringExtra(HotspotService.EXTRA_SSID)        ?: ""
                    val pass    = intent.getStringExtra(HotspotService.EXTRA_PASSWORD)    ?: ""
                    val ip      = intent.getStringExtra(HotspotService.EXTRA_GATEWAY_IP)  ?: HotspotManager.HOTSPOT_IP
                    val mode    = intent.getStringExtra(HotspotService.EXTRA_HOTSPOT_MODE) ?: ""
                    currentGatewayIp = ip
                    val modeLabel = when (mode) {
                        "tethered" -> "Tethered"
                        "local"    -> "Local-Only (no internet share)"
                        else       -> "Active"
                    }
                    tvStatus.text       = "✓ Running — $modeLabel"
                    if (ssid.isNotEmpty()) tvSSID.text = "SSID: $ssid"
                    if (pass.isNotEmpty()) tvPassword.text = "Password: $pass"
                    tvDashboardUrl.text =
                        "This device : http://127.0.0.1:${HotspotService.WEB_SERVER_PORT}\n" +
                        "Clients     : http://$ip:${HotspotService.WEB_SERVER_PORT}"
                    btnStop.visibility  = View.VISIBLE
                    btnStart.visibility = View.GONE
                }
                "stopped" -> {
                    tvStatus.text       = "Service stopped — tap Start to restart"
                    serviceStarted      = false
                    btnStop.visibility  = View.GONE
                    btnStart.visibility = View.VISIBLE
                }
            }
        }
    }

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
        tvPassword        = findViewById(R.id.tvPassword)
        tvDashboardUrl    = findViewById(R.id.tvDashboardUrl)
        tvRootStatus      = findViewById(R.id.tvRootStatus)
        btnOpenDashboard  = findViewById(R.id.btnOpenDashboard)
        btnStop           = findViewById(R.id.btnStop)
        btnStart          = findViewById(R.id.btnStart)

        configStore      = HotspotConfigStore(this)
        firebaseManager  = try { FirebaseManager(this) }
                           catch (e: Exception) { FirebaseManager(this, offline = true) }

        // Show root status (cached from NovaMeshApp startup — no blocking call here)
        tvRootStatus.text = "Root: ${RootUtils.statusLabel}"

        btnOpenDashboard.setOnClickListener { openDashboard() }

        btnStop.setOnClickListener {
            try { startService(HotspotService.stopIntent(this)) } catch (_: Exception) {}
            tvStatus.text       = "Stopping…"
            serviceStarted      = false
            btnStop.visibility  = View.GONE
            btnStart.visibility = View.VISIBLE
        }

        btnStart.setOnClickListener {
            tvStatus.text       = "Service starting…"
            btnStart.visibility = View.GONE
            btnStop.visibility  = View.VISIBLE
            scheduleServiceStart()
        }

        requestNotificationPermissionIfNeeded()
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter(HotspotService.ACTION_STATUS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(statusReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(statusReceiver, filter)
        }
    }

    override fun onPause() {
        super.onPause()
        try { unregisterReceiver(statusReceiver) } catch (_: Exception) {}
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
                tvPassword.text     = "Password: ${config.password}"
                tvDashboardUrl.text = "Dashboard: http://127.0.0.1:${HotspotService.WEB_SERVER_PORT}"
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
        // Open on 127.0.0.1 — works from this device regardless of hotspot mode.
        // Connected clients should use the gateway IP shown in tvDashboardUrl.
        val url = "http://127.0.0.1:${HotspotService.WEB_SERVER_PORT}"
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            Toast.makeText(this, "No browser found", Toast.LENGTH_SHORT).show()
        }
    }
}
