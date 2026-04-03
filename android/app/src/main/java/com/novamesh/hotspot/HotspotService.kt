package com.novamesh.hotspot

import android.app.*
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import fi.iki.elonen.NanoHTTPD

/**
 * NovaMesh Hotspot Foreground Service
 *
 * Keeps the hotspot running and the local web server alive even when the
 * app is in the background. Uses a WakeLock to prevent CPU sleep.
 *
 * Root features (iptables block, tc bandwidth, DNS redirect) are enabled
 * automatically when [RootUtils.isRooted] is true; otherwise the service
 * operates in safe no-root mode.
 *
 * Lifecycle:
 *   startService(intent) → onCreate → onStartCommand → runs
 *   stopService(intent)  → onDestroy → cleanup
 */
class HotspotService : Service() {

    private val serviceScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO
    )

    companion object {
        private const val TAG             = "NovaMesh:HotspotService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID      = "novamesh_hotspot"
        private const val CHANNEL_NAME    = "NovaMesh Hotspot"
        private const val WEB_SERVER_PORT = 8080

        const val ACTION_START          = "com.novamesh.ACTION_START"
        const val ACTION_STOP           = "com.novamesh.ACTION_STOP"
        const val ACTION_TOGGLE_HOTSPOT = "com.novamesh.ACTION_TOGGLE"

        fun startIntent(context: Context) = Intent(context, HotspotService::class.java).setAction(ACTION_START)
        fun stopIntent (context: Context) = Intent(context, HotspotService::class.java).setAction(ACTION_STOP)
    }

    private lateinit var hotspotManager: HotspotManager
    private lateinit var configStore:    HotspotConfigStore
    private lateinit var firebaseManager: FirebaseManager
    private var webServer: LocalWebServer?                             = null
    private var wakeLock:  PowerManager.WakeLock?                     = null
    private var localHotspotReservation: WifiManager.LocalOnlyHotspotReservation? = null
    private var uptimeThread: Thread? = null
    private var uptimeSeconds = 0L

    // ────────────────────────────────────────────
    //  Lifecycle
    // ────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Service creating — root=${RootUtils.isRooted}")

        hotspotManager  = HotspotManager(this)
        configStore     = HotspotConfigStore(this)
        firebaseManager = FirebaseManager(this)

        createNotificationChannel()
        acquireWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_TOGGLE_HOTSPOT -> {
                toggleHotspot()
                return START_STICKY
            }
        }

        // Default: start everything
        startForeground(NOTIFICATION_ID, buildNotification("Starting NovaMesh…"))
        // launchServices() does disk I/O, network, and system calls — must NOT
        // run on the main thread (onStartCommand is called on main thread).
        serviceScope.launch { launchServices() }
        return START_STICKY
    }

    override fun onDestroy() {
        Log.i(TAG, "Service destroying — cleaning up")
        serviceScope.cancel()
        stopAllServices()
        releaseWakeLock()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ────────────────────────────────────────────
    //  Core Launch
    // ────────────────────────────────────────────

    private fun launchServices() {
        val config = configStore.getConfig()

        // 1. Start hotspot (root-aware)
        startHotspot(config)

        // 2. Start local web server
        startWebServer(config)

        // 3. Sync with Firebase
        firebaseManager.syncConfig(config)
        firebaseManager.startRealtimeSync(
            onConfigUpdate = { newConfig ->
                Log.i(TAG, "Firebase config update received")
                configStore.saveConfig(newConfig)
                restartWebServer(newConfig)
            }
        )

        // 4. Uptime counter + periodic device polling
        startUptimeCounter()
        startDevicePolling()

        val rootLabel = RootUtils.statusLabel
        updateNotification("NovaMesh active · ${config.ssid} · :$WEB_SERVER_PORT · $rootLabel")
    }

    private fun startHotspot(config: HotspotConfig) {
        val success = hotspotManager.startTetheredHotspot(config.ssid, config.password, config.band)
        if (success) {
            Log.i(TAG, "Tethered hotspot started: ${config.ssid}")
            updateNotification("Hotspot ON · ${config.ssid}")
        } else {
            Log.w(TAG, "Tethered hotspot failed — falling back to LocalOnlyHotspot")
            hotspotManager.startLocalHotspot(object : HotspotManager.LocalHotspotCallback {
                override fun onStarted(reservation: WifiManager.LocalOnlyHotspotReservation) {
                    localHotspotReservation = reservation
                    val ssid = reservation.wifiConfiguration?.SSID ?: "NovaMesh"
                    Log.i(TAG, "LocalOnlyHotspot started: $ssid")
                    updateNotification("Local Hotspot · $ssid · (no Internet sharing)")
                }
                override fun onStopped() = Log.i(TAG, "LocalOnlyHotspot stopped")
                override fun onFailed(reason: Int) = Log.e(TAG, "Hotspot failed: $reason")
            })
        }
    }

    private fun startWebServer(config: HotspotConfig) {
        try {
            webServer = LocalWebServer(
                port           = WEB_SERVER_PORT,
                context        = this,
                hotspotManager = hotspotManager,
                configStore    = configStore,
                authToken      = config.adminToken
            )
            webServer?.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
            Log.i(TAG, "Web server started on port $WEB_SERVER_PORT")
        } catch (e: Exception) {
            Log.e(TAG, "Web server failed to start: ${e.message}")
        }
    }

    private fun restartWebServer(config: HotspotConfig) {
        webServer?.stop()
        startWebServer(config)
    }

    // ────────────────────────────────────────────
    //  Uptime Counter + Device Polling
    // ────────────────────────────────────────────

    private fun startUptimeCounter() {
        uptimeThread = Thread {
            while (!Thread.currentThread().isInterrupted) {
                try {
                    Thread.sleep(1000)
                    uptimeSeconds++
                    configStore.updateConfig { it.uptimeSeconds = uptimeSeconds }
                } catch (e: InterruptedException) { break }
            }
        }.also { it.start() }
    }

    private fun startDevicePolling() {
        Thread {
            while (!Thread.currentThread().isInterrupted) {
                try {
                    Thread.sleep(5000)
                    val clients = hotspotManager.getConnectedClients()
                    firebaseManager.updateConnectedDevices(clients)
                    enforceBlocklist(clients)
                } catch (e: InterruptedException) { break }
                catch (e: Exception) { Log.e(TAG, "Device polling error: ${e.message}") }
            }
        }.also { it.isDaemon = true; it.start() }
    }

    private fun enforceBlocklist(clients: List<HotspotManager.ConnectedClient>) {
        val blocked = configStore.getBlocklist()
        clients.filter { it.mac in blocked }.forEach { client ->
            Log.w(TAG, "Blocked device detected — enforcing: ${client.mac} (${client.ip})")
            hotspotManager.blockDeviceByMAC(client.mac, true)
        }
    }

    private fun toggleHotspot() {
        if (hotspotManager.isHotspotEnabled()) {
            hotspotManager.stopTetheredHotspot()
            updateNotification("Hotspot OFF")
        } else {
            val cfg = configStore.getConfig()
            hotspotManager.startTetheredHotspot(cfg.ssid, cfg.password)
            updateNotification("Hotspot ON · ${cfg.ssid}")
        }
    }

    // ────────────────────────────────────────────
    //  Cleanup
    // ────────────────────────────────────────────

    private fun stopAllServices() {
        uptimeThread?.interrupt()
        webServer?.stop()
        localHotspotReservation?.close()
        hotspotManager.stopTetheredHotspot()
        firebaseManager.stopSync()
        Log.i(TAG, "All services stopped")
    }

    // ────────────────────────────────────────────
    //  Notification
    // ────────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "NovaMesh hotspot service notification"
                setShowBadge(false)
            }
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this, 1,
            stopIntent(this),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("NovaMesh")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(openIntent)
            .addAction(android.R.drawable.ic_delete, "Stop", stopIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(text))
    }

    // ────────────────────────────────────────────
    //  Wake Lock
    // ────────────────────────────────────────────

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "NovaMesh::HotspotWakeLock"
        ).apply { acquire(6 * 60 * 60 * 1000L) }
        Log.d(TAG, "WakeLock acquired")
    }

    private fun releaseWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.release()
        Log.d(TAG, "WakeLock released")
    }
}
