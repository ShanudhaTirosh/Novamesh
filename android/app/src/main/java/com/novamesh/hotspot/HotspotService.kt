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
import kotlinx.coroutines.*

/**
 * NovaMesh Hotspot Foreground Service
 *
 * FIXES applied:
 *  1. CoroutineExceptionHandler on serviceScope — exceptions in background work
 *     no longer crash the whole app.
 *  2. startHotspot() dispatched to Dispatchers.Main — WifiManager.startLocalOnlyHotspot()
 *     MUST be called from the main thread or it throws IllegalStateException.
 *  3. try-catch wrapped around every potentially-throwing operation.
 *  4. FirebaseManager init wrapped so missing Firebase setup never crashes.
 */
class HotspotService : Service() {

    companion object {
        private const val TAG             = "NovaMesh:HotspotService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID      = "novamesh_hotspot"
        private const val CHANNEL_NAME    = "NovaMesh Hotspot"
        const  val WEB_SERVER_PORT        = 8080

        const val ACTION_START          = "com.novamesh.ACTION_START"
        const val ACTION_STOP           = "com.novamesh.ACTION_STOP"
        const val ACTION_TOGGLE_HOTSPOT = "com.novamesh.ACTION_TOGGLE"

        fun startIntent(context: Context) =
            Intent(context, HotspotService::class.java).setAction(ACTION_START)
        fun stopIntent(context: Context) =
            Intent(context, HotspotService::class.java).setAction(ACTION_STOP)
    }

    // FIX 1: CoroutineExceptionHandler catches uncaught exceptions so the
    // app does NOT crash when a background operation fails.
    private val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
        Log.e(TAG, "Caught unhandled exception in service scope — app protected", throwable)
    }
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO + exceptionHandler)

    private lateinit var hotspotManager : HotspotManager
    private lateinit var configStore    : HotspotConfigStore
    private lateinit var firebaseManager: FirebaseManager

    private var webServer               : LocalWebServer?                              = null
    private var wakeLock                : PowerManager.WakeLock?                      = null
    private var localHotspotReservation : WifiManager.LocalOnlyHotspotReservation?    = null
    private var uptimeThread            : Thread?                                      = null
    private var uptimeSeconds             = 0L

    // ────────────────────────────────────────────
    //  Lifecycle
    // ────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Service creating — root=${RootUtils.isRooted}")

        hotspotManager  = HotspotManager(this)
        configStore     = HotspotConfigStore(this)

        // FIX: Wrap Firebase init — if Realtime DB URL missing or any Firebase
        // service is not set up, this won't crash the service.
        firebaseManager = try {
            FirebaseManager(this)
        } catch (e: Exception) {
            Log.w(TAG, "Firebase init failed — running in offline mode: ${e.message}")
            FirebaseManager(this, offline = true)
        }

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
                // WiFi toggle must happen on main thread
                serviceScope.launch(Dispatchers.Main) { toggleHotspot() }
                return START_STICKY
            }
        }

        // startForeground MUST be called immediately in onStartCommand
        // (Android 12+ requires it within 5 seconds or throws ANR)
        startForeground(NOTIFICATION_ID, buildNotification("NovaMesh starting…"))

        serviceScope.launch { launchServices() }
        return START_STICKY
    }

    override fun onDestroy() {
        Log.i(TAG, "Service destroying")
        serviceScope.cancel()
        stopAllServices()
        releaseWakeLock()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ────────────────────────────────────────────
    //  Core Launch (runs on IO dispatcher)
    // ────────────────────────────────────────────

    private suspend fun launchServices() {
        val config = configStore.getConfig()

        // FIX 2: startHotspot MUST run on the main thread.
        // WifiManager.startLocalOnlyHotspot() throws:
        //   IllegalStateException: "Can't create handler inside thread that has not called Looper.prepare()"
        // when called from a background thread (Dispatchers.IO).
        withContext(Dispatchers.Main) {
            startHotspot(config)
        }

        // These are safe on IO
        startWebServer(config)
        tryFirebaseSync(config)
        startUptimeCounter()
        startDevicePolling()

        val rootLabel = if (RootUtils.isRooted) "ROOT" else "SAFE"
        notifySafe("[$rootLabel] ${config.ssid} · :$WEB_SERVER_PORT")
    }

    // ────────────────────────────────────────────
    //  Hotspot — MUST be called on MAIN thread
    // ────────────────────────────────────────────

    private fun startHotspot(config: HotspotConfig) {
        val success = try {
            hotspotManager.startTetheredHotspot(config.ssid, config.password, config.band)
        } catch (e: Exception) {
            Log.e(TAG, "startTetheredHotspot threw: ${e.message}")
            false
        }

        if (success) {
            Log.i(TAG, "Tethered hotspot started: ${config.ssid}")
            notifySafe("Hotspot ON · ${config.ssid}")
        } else {
            Log.w(TAG, "Tethered hotspot unavailable — trying LocalOnlyHotspot")
            try {
                hotspotManager.startLocalHotspot(object : HotspotManager.LocalHotspotCallback {
                    override fun onStarted(reservation: WifiManager.LocalOnlyHotspotReservation) {
                        localHotspotReservation = reservation
                        val ssid = reservation.wifiConfiguration?.SSID ?: "NovaMesh"
                        Log.i(TAG, "LocalOnlyHotspot started: $ssid")
                        notifySafe("Local Hotspot · $ssid")
                    }
                    override fun onStopped()           { Log.i(TAG, "LocalOnlyHotspot stopped") }
                    override fun onFailed(reason: Int) { Log.w(TAG, "LocalOnlyHotspot failed: reason=$reason") }
                })
            } catch (e: Exception) {
                Log.e(TAG, "startLocalHotspot threw: ${e.message}")
                notifySafe("Hotspot error — see logs")
            }
        }
    }

    // ────────────────────────────────────────────
    //  Web Server
    // ────────────────────────────────────────────

    private fun startWebServer(config: HotspotConfig) {
        try {
            webServer?.stop()
            webServer = LocalWebServer(
                port           = WEB_SERVER_PORT,
                context        = this,
                hotspotManager = hotspotManager,
                configStore    = configStore,
                authToken      = config.adminToken
            )
            webServer?.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
            Log.i(TAG, "Web server started on :$WEB_SERVER_PORT")
        } catch (e: Exception) {
            Log.e(TAG, "Web server start failed: ${e.message}")
        }
    }

    private fun restartWebServer(config: HotspotConfig) {
        try { webServer?.stop() } catch (_: Exception) {}
        startWebServer(config)
    }

    // ────────────────────────────────────────────
    //  Firebase — all wrapped so offline = safe
    // ────────────────────────────────────────────

    private fun tryFirebaseSync(config: HotspotConfig) {
        try {
            firebaseManager.syncConfig(config)
            firebaseManager.startRealtimeSync { newConfig ->
                Log.i(TAG, "Remote config update received")
                configStore.saveConfig(newConfig)
                restartWebServer(newConfig)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Firebase sync skipped (offline?): ${e.message}")
        }
    }

    // ────────────────────────────────────────────
    //  Background Threads
    // ────────────────────────────────────────────

    private fun startUptimeCounter() {
        uptimeThread = Thread {
            while (!Thread.currentThread().isInterrupted) {
                try {
                    Thread.sleep(1_000)
                    uptimeSeconds++
                    configStore.updateConfig { it.uptimeSeconds = uptimeSeconds }
                } catch (e: InterruptedException) { break }
                  catch (e: Exception) { Log.e(TAG, "Uptime error: ${e.message}") }
            }
        }.apply { isDaemon = true; start() }
    }

    private fun startDevicePolling() {
        Thread {
            while (!Thread.currentThread().isInterrupted) {
                try {
                    Thread.sleep(5_000)
                    val clients = hotspotManager.getConnectedClients()
                    try { firebaseManager.updateConnectedDevices(clients) }
                    catch (_: Exception) { /* Firebase offline — skip silently */ }
                    enforceBlocklist(clients)
                } catch (e: InterruptedException) { break }
                  catch (e: Exception) { Log.e(TAG, "Polling error: ${e.message}") }
            }
        }.apply { isDaemon = true; start() }
    }

    private fun enforceBlocklist(clients: List<HotspotManager.ConnectedClient>) {
        if (!RootUtils.isRooted) return
        val blocked = configStore.getBlocklist()
        clients.filter { it.mac in blocked }.forEach { client ->
            Log.w(TAG, "Re-enforcing block: ${client.mac}")
            try { hotspotManager.blockDeviceByMAC(client.mac, true) } catch (_: Exception) {}
        }
    }

    private fun toggleHotspot() {
        // Called on Dispatchers.Main
        try {
            if (hotspotManager.isHotspotEnabled()) {
                hotspotManager.stopTetheredHotspot()
                notifySafe("Hotspot OFF")
            } else {
                val cfg = configStore.getConfig()
                hotspotManager.startTetheredHotspot(cfg.ssid, cfg.password)
                notifySafe("Hotspot ON · ${cfg.ssid}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Toggle hotspot failed: ${e.message}")
        }
    }

    // ────────────────────────────────────────────
    //  Cleanup
    // ────────────────────────────────────────────

    private fun stopAllServices() {
        listOf(
            { uptimeThread?.interrupt() },
            { webServer?.stop() },
            { localHotspotReservation?.close() },
            { hotspotManager.stopTetheredHotspot() },
            { firebaseManager.stopSync() }
        ).forEach { action -> try { action() } catch (_: Exception) {} }
        Log.i(TAG, "All services stopped")
    }

    // ────────────────────────────────────────────
    //  Notification
    // ────────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW)
                .apply { description = "NovaMesh hotspot service"; setShowBadge(false) }
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
        }
    }

    private fun buildNotification(text: String): Notification {
        val openPi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopPi = PendingIntent.getService(
            this, 1, stopIntent(this),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("NovaMesh")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(openPi)
            .addAction(0, "Stop", stopPi)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    /** Notification update safe to call from any thread */
    private fun notifySafe(text: String) {
        try {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIFICATION_ID, buildNotification(text))
        } catch (e: Exception) {
            Log.w(TAG, "Notification update failed: ${e.message}")
        }
    }

    // ────────────────────────────────────────────
    //  WakeLock
    // ────────────────────────────────────────────

    private fun acquireWakeLock() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "NovaMesh::WakeLock")
                .apply { acquire(6 * 60 * 60 * 1000L) }
        } catch (e: Exception) {
            Log.w(TAG, "WakeLock acquire failed: ${e.message}")
        }
    }

    private fun releaseWakeLock() {
        try { wakeLock?.takeIf { it.isHeld }?.release() } catch (_: Exception) {}
    }
}
