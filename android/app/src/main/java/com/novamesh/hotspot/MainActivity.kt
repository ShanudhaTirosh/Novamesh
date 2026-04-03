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

/**
 * NovaMesh MainActivity
 *
 * Entry point of the app. Handles:
 *  1. POST_NOTIFICATIONS permission request (Android 13+)
 *  2. Firebase sign-in
 *  3. Starting the HotspotService (foreground service)
 *  4. Showing dashboard URL, SSID, root status and token
 *  5. Bind UI buttons to service actions
 *
 * The primary UI is the web dashboard at http://192.168.43.1:8080.
 * This activity is a minimal launcher/status panel.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var configStore:     HotspotConfigStore
    private lateinit var firebaseManager: FirebaseManager

    // UI references
    private lateinit var tvStatus:       TextView
    private lateinit var tvSSID:         TextView
    private lateinit var tvDashboardUrl: TextView
    private lateinit var tvRootStatus:   TextView
    private lateinit var btnOpenDashboard: Button
    private lateinit var btnStop:        Button

    // Android 13+ notification permission launcher
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            // Whether granted or denied, proceed — foreground service will still
            // run, the notification just won't be visible if denied.
            beginAuthFlow()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Bind views
        tvStatus         = findViewById(R.id.tvStatus)
        tvSSID           = findViewById(R.id.tvSSID)
        tvDashboardUrl   = findViewById(R.id.tvDashboardUrl)
        tvRootStatus     = findViewById(R.id.tvRootStatus)
        btnOpenDashboard = findViewById(R.id.btnOpenDashboard)
        btnStop          = findViewById(R.id.btnStop)

        configStore     = HotspotConfigStore(this)
        firebaseManager = FirebaseManager(this)

        // Show root status (may still be "checking" if background detection
        // hasn't finished yet — that's fine, it's cosmetic only)
        tvRootStatus.text = "Root: ${RootUtils.statusLabel}"

        // Button listeners
        btnOpenDashboard.setOnClickListener { openDashboard() }
        btnStop.setOnClickListener {
            startService(HotspotService.stopIntent(this))
            tvStatus.text = "Service stopped"
        }

        // On Android 13+ we must ask for POST_NOTIFICATIONS at runtime before
        // starting a foreground service — otherwise the notification is silently
        // dropped and on some Samsung builds the service start may be blocked.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                return  // beginAuthFlow() called by the launcher callback above
            }
        }
        beginAuthFlow()
    }

    // ────────────────────────────────────────────
    //  Auth Flow
    // ────────────────────────────────────────────

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

    // ────────────────────────────────────────────
    //  Service Control
    // ────────────────────────────────────────────

    private fun startHotspotService() {
        val intent = HotspotService.startIntent(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    // ────────────────────────────────────────────
    //  UI Update
    // ────────────────────────────────────────────

    private fun showDashboardInfo(config: HotspotConfig) {
        val dashboardUrl = "http://${HotspotManager.HOTSPOT_IP}:8080"
        tvStatus.text       = "Service running"
        tvSSID.text         = "SSID: ${config.ssid}"
        tvDashboardUrl.text = "Dashboard: $dashboardUrl"
    }

    /** Opens the dashboard in the device's browser */
    fun openDashboard() {
        val url = "http://${HotspotManager.HOTSPOT_IP}:8080"
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }

    override fun onDestroy() {
        super.onDestroy()
        // HotspotService continues running after activity is destroyed
    }
}
