package com.novamesh.hotspot

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
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

class MainActivity : AppCompatActivity() {

    private lateinit var configStore:     HotspotConfigStore
    private lateinit var firebaseManager: FirebaseManager

    private lateinit var tvStatus:        TextView
    private lateinit var tvSSID:          TextView
    private lateinit var tvDashboardUrl:  TextView
    private lateinit var tvRootStatus:    TextView
    private lateinit var btnOpenDashboard: Button
    private lateinit var btnStop:         Button

    // ── Permission launchers ──────────────────────────────────────────────

    // Step 1: POST_NOTIFICATIONS (Android 13+)
    private val notificationPermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            // Granted or denied — proceed to location check regardless
            requestLocationPermissionIfNeeded()
        }

    // Step 2: ACCESS_FINE_LOCATION — required by WifiManager.startLocalOnlyHotspot()
    private val locationPermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            val fineGranted = results[Manifest.permission.ACCESS_FINE_LOCATION] == true
            if (!fineGranted) {
                tvStatus.text = "Location permission denied — hotspot unavailable"
                Toast.makeText(
                    this,
                    "Location permission is required for the hotspot API",
                    Toast.LENGTH_LONG
                ).show()
                // Still start Firebase auth so the rest of the app works
            }
            beginAuthFlow()
        }

    // ── Lifecycle ─────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus         = findViewById(R.id.tvStatus)
        tvSSID           = findViewById(R.id.tvSSID)
        tvDashboardUrl   = findViewById(R.id.tvDashboardUrl)
        tvRootStatus     = findViewById(R.id.tvRootStatus)
        btnOpenDashboard = findViewById(R.id.btnOpenDashboard)
        btnStop          = findViewById(R.id.btnStop)

        configStore     = HotspotConfigStore(this)
        firebaseManager = FirebaseManager(this)

        tvRootStatus.text = "Root: ${RootUtils.statusLabel}"

        btnOpenDashboard.setOnClickListener { openDashboard() }
        btnStop.setOnClickListener {
            startService(HotspotService.stopIntent(this))
            tvStatus.text = "Service stopped"
        }

        // Start permission chain: notifications → location → auth → service
        requestNotificationPermissionIfNeeded()
    }

    // ── Permission chain ──────────────────────────────────────────────────

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                notificationPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                return
            }
        }
        requestLocationPermissionIfNeeded()
    }

    private fun requestLocationPermissionIfNeeded() {
        val fine   = Manifest.permission.ACCESS_FINE_LOCATION
        val coarse = Manifest.permission.ACCESS_COARSE_LOCATION

        val fineGranted = ContextCompat.checkSelfPermission(this, fine) ==
                PackageManager.PERMISSION_GRANTED

        if (!fineGranted) {
            // Must request both fine + coarse together on Android 12+
            locationPermLauncher.launch(arrayOf(fine, coarse))
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
        tvStatus.text = "Signing in…"
        firebaseManager.signIn(
            email    = "admin@novamesh.local",
            password = "nova2024",
            onSuccess = { user ->
                Toast.makeText(this, "Signed in: ${user.email}", Toast.LENGTH_SHORT).show()
                onSignedIn()
            },
            onError = { _ ->
                Toast.makeText(this, "Firebase offline — local mode", Toast.LENGTH_SHORT).show()
                tvStatus.text = "Local mode (no Firebase)"
                startHotspotService()
            }
        )
    }

    private fun onSignedIn() {
        startHotspotService()
        lifecycleScope.launch(Dispatchers.IO) {
            val config = configStore.getConfig()
            withContext(Dispatchers.Main) { showDashboardInfo(config) }
        }
    }

    // ── Service control ───────────────────────────────────────────────────

    private fun startHotspotService() {
        val intent = HotspotService.startIntent(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    // ── UI ────────────────────────────────────────────────────────────────

    private fun showDashboardInfo(config: HotspotConfig) {
        tvStatus.text       = "Service running"
        tvSSID.text         = "SSID: ${config.ssid}"
        tvDashboardUrl.text = "Dashboard: http://${HotspotManager.HOTSPOT_IP}:8080"
    }

    fun openDashboard() {
        val url = "http://${HotspotManager.HOTSPOT_IP}:8080"
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }

    override fun onDestroy() {
        super.onDestroy()
    }
}
