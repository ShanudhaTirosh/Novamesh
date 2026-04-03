package com.novamesh.hotspot

import android.content.Context
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import java.lang.reflect.Method

/**
 * NovaMesh HotspotManager
 *
 * Handles enabling/disabling hotspot, reading connected clients,
 * and configuring SSID/password.
 *
 * Root detection is automatic via [RootUtils]. Root-only operations
 * (iptables, tc) are silently skipped when root is unavailable.
 *
 * ─────────────────────────────────────────
 *  ROOT vs NON-ROOT CAPABILITY TABLE
 * ─────────────────────────────────────────
 *  Feature                     No Root    Root
 *  ─────────────────────────────────────────
 *  Enable/disable hotspot       ✓ (API 26+) ✓
 *  Set SSID / Password          ✓ (<API29)  ✓
 *  Read connected clients       ✓ (arp)     ✓
 *  Per-device bandwidth limit   ✗           ✓ (tc/iptables)
 *  Block device (MAC)           ✗           ✓ (iptables)
 *  Custom DHCP range            ✗           ✓
 *  DNS override (system-wide)   VPN-trick   ✓ (iptables NAT)
 * ─────────────────────────────────────────
 */
class HotspotManager(private val context: Context) {

    companion object {
        private const val TAG = "NovaMesh:HotspotManager"
        const val HOTSPOT_IP  = "192.168.43.1"
    }

    private val wifiManager: WifiManager =
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

    private val connectivityManager: ConnectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    // ────────────────────────────────────────────
    //  1.  Hotspot ON/OFF  (API 26+)
    // ────────────────────────────────────────────

    /**
     * Starts the hotspot using LocalOnlyHotspot (no Internet sharing, API 26+).
     * For Internet-sharing tethering, root or a system app is required (API 30+).
     */
    fun startLocalHotspot(callback: LocalHotspotCallback) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            wifiManager.startLocalOnlyHotspot(object : WifiManager.LocalOnlyHotspotCallback() {
                override fun onStarted(reservation: WifiManager.LocalOnlyHotspotReservation) {
                    super.onStarted(reservation)
                    Log.i(TAG, "LocalOnlyHotspot started. SSID=${reservation.wifiConfiguration?.SSID}")
                    callback.onStarted(reservation)
                }
                override fun onStopped() {
                    super.onStopped()
                    Log.i(TAG, "LocalOnlyHotspot stopped")
                    callback.onStopped()
                }
                override fun onFailed(reason: Int) {
                    super.onFailed(reason)
                    Log.e(TAG, "LocalOnlyHotspot failed: reason=$reason")
                    callback.onFailed(reason)
                }
            }, null)
        } else {
            callback.onFailed(-1) // Not supported below API 26
        }
    }

    /**
     * Enable full tethering hotspot using reflection (API < 29) or root (API 29+).
     * On API 29+ without root, this will fail gracefully; call [startLocalHotspot] as fallback.
     */
    @Suppress("DEPRECATION")
    fun startTetheredHotspot(ssid: String, password: String, band: Int = 0): Boolean {
        return if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            // Pre-Android 10: reflection works without root
            try {
                val config = android.net.wifi.WifiConfiguration().apply {
                    SSID = ssid
                    preSharedKey = password
                    allowedKeyManagement.set(android.net.wifi.WifiConfiguration.KeyMgmt.WPA_PSK)
                }
                val method: Method = wifiManager.javaClass.getMethod(
                    "setWifiApEnabled",
                    android.net.wifi.WifiConfiguration::class.java,
                    Boolean::class.java
                )
                method.invoke(wifiManager, config, true) as Boolean
            } catch (e: Exception) {
                Log.e(TAG, "Failed to enable hotspot via reflection: ${e.message}")
                false
            }
        } else {
            // Android 10+: requires root
            if (RootUtils.isRooted) {
                Log.i(TAG, "API 29+ — using root fallback for tethering")
                enableHotspotRootFallback(ssid, password, true)
            } else {
                Log.w(TAG, "API 29+ tethering requires root — no root available, use LocalOnlyHotspot")
                false
            }
        }
    }

    fun stopTetheredHotspot(): Boolean {
        return if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            try {
                val method: Method = wifiManager.javaClass.getMethod(
                    "setWifiApEnabled",
                    android.net.wifi.WifiConfiguration::class.java,
                    Boolean::class.java
                )
                method.invoke(wifiManager, null, false) as Boolean
            } catch (e: Exception) {
                Log.e(TAG, "Failed to stop hotspot: ${e.message}")
                false
            }
        } else {
            if (RootUtils.isRooted) enableHotspotRootFallback(enabled = false) else false
        }
    }

    // ────────────────────────────────────────────
    //  2.  Hotspot State Query
    // ────────────────────────────────────────────

    fun isHotspotEnabled(): Boolean {
        return try {
            val method: Method = wifiManager.javaClass.getMethod("isWifiApEnabled")
            method.invoke(wifiManager) as Boolean
        } catch (e: Exception) { false }
    }

    @Suppress("DEPRECATION")
    fun getHotspotConfig(): android.net.wifi.WifiConfiguration? {
        return try {
            val method: Method = wifiManager.javaClass.getMethod("getWifiApConfiguration")
            method.invoke(wifiManager) as? android.net.wifi.WifiConfiguration
        } catch (e: Exception) { null }
    }

    // ────────────────────────────────────────────
    //  3.  Connected Clients (ARP table — no root)
    // ────────────────────────────────────────────

    fun getConnectedClients(): List<ConnectedClient> {
        val clients = mutableListOf<ConnectedClient>()
        try {
            val proc   = Runtime.getRuntime().exec("cat /proc/net/arp")
            val reader = proc.inputStream.bufferedReader()
            reader.forEachLine { line ->
                if (line.isBlank() || line.startsWith("IP")) return@forEachLine
                val parts = line.trim().split("\\s+".toRegex())
                if (parts.size >= 4) {
                    val ip  = parts[0]
                    val mac = parts[3]
                    if (mac != "00:00:00:00:00:00" && ip.startsWith("192.168.")) {
                        clients.add(ConnectedClient(
                            ip   = ip,
                            mac  = mac.uppercase(),
                            name = resolveHostname(ip) ?: "Unknown Device"
                        ))
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "ARP table read failed: ${e.message}")
        }
        return clients
    }

    private fun resolveHostname(ip: String): String? {
        return try {
            java.net.InetAddress.getByName(ip).canonicalHostName.takeIf { it != ip }
        } catch (e: Exception) { null }
    }

    // ────────────────────────────────────────────
    //  4.  Root Operations — auto-gated by RootUtils
    // ────────────────────────────────────────────

    /**
     * Block a device by MAC address using iptables.
     * Returns false immediately (with log) when root is unavailable.
     */
    fun blockDeviceByMAC(mac: String, block: Boolean): Boolean {
        if (!RootUtils.isRooted) {
            Log.w(TAG, "blockDeviceByMAC: root not available — stored in config only (not enforced at network level)")
            return false
        }
        val action = if (block) "-A" else "-D"
        return executeRootCommand("iptables $action FORWARD -m mac --mac-source $mac -j DROP")
    }

    /**
     * Limit bandwidth for a device using tc (ROOT REQUIRED).
     * Returns false immediately when root is unavailable.
     * @param ip       Device IP address
     * @param limitKbps Speed limit in kbps (e.g., 5120 = 5 Mbps)
     */
    fun limitBandwidth(ip: String, limitKbps: Int): Boolean {
        if (!RootUtils.isRooted) {
            Log.w(TAG, "limitBandwidth: root not available — stored in config only")
            return false
        }
        val cmds = listOf(
            "tc qdisc add dev wlan0 root handle 1: htb default 10",
            "tc class add dev wlan0 parent 1: classid 1:1 htb rate ${limitKbps}kbit",
            "iptables -t mangle -A POSTROUTING -d $ip -j MARK --set-mark 1"
        )
        return cmds.all { executeRootCommand(it) }
    }

    /**
     * Redirect all DNS traffic via iptables NAT (ROOT REQUIRED).
     * Without root, DNS is stored in config and applied as best-effort.
     */
    fun setCustomDNS(primaryDNS: String, secondaryDNS: String): Boolean {
        if (!RootUtils.isRooted) {
            Log.w(TAG, "setCustomDNS: root not available — DNS stored in config; use VPN mode for non-root DNS override")
            return false
        }
        val cmds = listOf(
            "iptables -t nat -F PREROUTING",
            "iptables -t nat -A PREROUTING -p udp --dport 53 -j DNAT --to-destination $primaryDNS:53",
            "iptables -t nat -A PREROUTING -p tcp --dport 53 -j DNAT --to-destination $primaryDNS:53"
        )
        return cmds.all { executeRootCommand(it) }
    }

    /**
     * Enable IP forwarding for Internet sharing (ROOT REQUIRED).
     */
    fun enableIPForwarding(): Boolean {
        if (!RootUtils.isRooted) {
            Log.w(TAG, "enableIPForwarding: root not available")
            return false
        }
        return executeRootCommand("echo 1 > /proc/sys/net/ipv4/ip_forward") &&
               executeRootCommand("iptables -t nat -A POSTROUTING -o rmnet0 -j MASQUERADE")
    }

    // ────────────────────────────────────────────
    //  Internal helpers
    // ────────────────────────────────────────────

    /**
     * Execute a shell command via su. Always pre-guarded by RootUtils.isRooted.
     */
    private fun executeRootCommand(cmd: String): Boolean {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
            val exit    = process.waitFor()
            if (exit != 0) Log.w(TAG, "Root command exited $exit: $cmd")
            exit == 0
        } catch (e: Exception) {
            Log.e(TAG, "Root exec failed: ${e.message}")
            false
        }
    }

    private fun enableHotspotRootFallback(
        ssid: String    = "NovaMesh",
        password: String = "",
        enabled: Boolean = true
    ): Boolean {
        return if (enabled) {
            executeRootCommand("svc wifi disable") &&
            executeRootCommand("settings put global tether_dun_required 0") &&
            executeRootCommand("svc tethering start")
        } else {
            executeRootCommand("svc tethering stop")
        }
    }

    // ────────────────────────────────────────────
    //  Data Classes
    // ────────────────────────────────────────────

    data class ConnectedClient(
        val ip:                  String,
        val mac:                 String,
        val name:                String,
        val isBlocked:           Boolean = false,
        val bandwidthLimitKbps:  Int     = 0
    )

    interface LocalHotspotCallback {
        fun onStarted(reservation: WifiManager.LocalOnlyHotspotReservation)
        fun onStopped()
        fun onFailed(reason: Int)
    }
}
