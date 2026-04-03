package com.novamesh.hotspot

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.lang.reflect.Method

/**
 * NovaMesh HotspotManager
 *
 * FIX: All WiFi API calls are wrapped in try-catch.
 * startLocalHotspot() now passes Handler(mainLooper) explicitly, which is
 * required on some OEM builds even when called from the main thread.
 */
class HotspotManager(private val context: Context) {

    companion object {
        private const val TAG = "NovaMesh:HotspotMgr"
        const val HOTSPOT_IP = "192.168.43.1"
    }

    private val wifiManager: WifiManager =
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

    // ────────────────────────────────────────────
    //  Hotspot ON/OFF
    // ────────────────────────────────────────────

    @Suppress("DEPRECATION")
    fun startTetheredHotspot(ssid: String, password: String, band: Int = 0): Boolean {
        return if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            try {
                val config = android.net.wifi.WifiConfiguration().apply {
                    SSID          = ssid
                    preSharedKey  = password
                    allowedKeyManagement.set(android.net.wifi.WifiConfiguration.KeyMgmt.WPA_PSK)
                }
                val m: Method = wifiManager.javaClass
                    .getMethod("setWifiApEnabled",
                        android.net.wifi.WifiConfiguration::class.java, Boolean::class.java)
                m.invoke(wifiManager, config, true) as? Boolean ?: false
            } catch (e: Exception) {
                Log.e(TAG, "startTetheredHotspot (reflection) failed: ${e.message}")
                false
            }
        } else {
            if (RootUtils.isRooted) {
                try { enableHotspotRootFallback(ssid, password, true) }
                catch (e: Exception) { Log.e(TAG, "Root hotspot failed: ${e.message}"); false }
            } else {
                Log.w(TAG, "API 29+ non-root: tethering not programmable")
                false
            }
        }
    }

    @Suppress("DEPRECATION")
    fun stopTetheredHotspot(): Boolean {
        return try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                val m: Method = wifiManager.javaClass
                    .getMethod("setWifiApEnabled",
                        android.net.wifi.WifiConfiguration::class.java, Boolean::class.java)
                m.invoke(wifiManager, null, false) as? Boolean ?: false
            } else {
                if (RootUtils.isRooted) enableHotspotRootFallback(enabled = false) else false
            }
        } catch (e: Exception) {
            Log.e(TAG, "stopTetheredHotspot failed: ${e.message}")
            false
        }
    }

    /**
     * Starts LocalOnlyHotspot.
     *
     * FIX: Passes Handler(Looper.getMainLooper()) explicitly instead of null.
     * Some OEM builds (Samsung OneUI 5+, Xiaomi MIUI) throw:
     *   "IllegalStateException: WifiManager.startLocalOnlyHotspot requires a looper or handler"
     * even when null is passed. Explicit main looper handler avoids this.
     *
     * MUST be called from the main thread (or at least with the caller holding the
     * main looper — enforced in HotspotService via withContext(Dispatchers.Main)).
     */
    fun startLocalHotspot(callback: LocalHotspotCallback) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                // Explicit main-thread Handler avoids OEM-specific IllegalStateException
                val mainHandler = Handler(Looper.getMainLooper())
                wifiManager.startLocalOnlyHotspot(
                    object : WifiManager.LocalOnlyHotspotCallback() {
                        override fun onStarted(res: WifiManager.LocalOnlyHotspotReservation) {
                            Log.i(TAG, "LocalOnlyHotspot started: ${res.wifiConfiguration?.SSID}")
                            callback.onStarted(res)
                        }
                        override fun onStopped() {
                            Log.i(TAG, "LocalOnlyHotspot stopped")
                            callback.onStopped()
                        }
                        override fun onFailed(reason: Int) {
                            Log.w(TAG, "LocalOnlyHotspot failed: reason=$reason")
                            callback.onFailed(reason)
                        }
                    },
                    mainHandler   // FIX: explicit handler instead of null
                )
            } catch (e: Exception) {
                Log.e(TAG, "startLocalOnlyHotspot threw: ${e.message}")
                callback.onFailed(-99)
            }
        } else {
            callback.onFailed(-1)
        }
    }

    fun isHotspotEnabled(): Boolean {
        return try {
            val m: Method = wifiManager.javaClass.getMethod("isWifiApEnabled")
            m.invoke(wifiManager) as? Boolean ?: false
        } catch (e: Exception) { false }
    }

    // ────────────────────────────────────────────
    //  Connected Clients — ARP table (no root)
    // ────────────────────────────────────────────

    fun getConnectedClients(): List<ConnectedClient> {
        val clients = mutableListOf<ConnectedClient>()
        try {
            Runtime.getRuntime().exec("cat /proc/net/arp")
                .inputStream.bufferedReader()
                .forEachLine { line ->
                    if (line.isBlank() || line.startsWith("IP")) return@forEachLine
                    val p = line.trim().split("\\s+".toRegex())
                    if (p.size >= 4) {
                        val ip  = p[0]
                        val mac = p[3]
                        if (mac != "00:00:00:00:00:00" && ip.startsWith("192.168.")) {
                            clients.add(ConnectedClient(
                                ip   = ip,
                                mac  = mac.uppercase(),
                                name = resolveHostname(ip) ?: "Unknown Device"
                            ))
                        }
                    }
                }
        } catch (e: Exception) { Log.e(TAG, "ARP read failed: ${e.message}") }
        return clients
    }

    private fun resolveHostname(ip: String): String? = try {
        java.net.InetAddress.getByName(ip).canonicalHostName.takeIf { it != ip }
    } catch (_: Exception) { null }

    // ────────────────────────────────────────────
    //  Root Features — auto-gated by RootUtils
    // ────────────────────────────────────────────

    fun blockDeviceByMAC(mac: String, block: Boolean): Boolean {
        if (!RootUtils.isRooted) {
            Log.w(TAG, "blockDeviceByMAC: no root — stored in config only")
            return false
        }
        val action = if (block) "-I" else "-D"
        return execRoot("iptables $action FORWARD -m mac --mac-source $mac -j DROP")
    }

    fun limitBandwidth(ip: String, limitKbps: Int): Boolean {
        if (!RootUtils.isRooted) {
            Log.w(TAG, "limitBandwidth: no root — stored in config only")
            return false
        }
        return listOf(
            "tc qdisc add dev wlan0 root handle 1: htb default 10 2>/dev/null || true",
            "tc class add dev wlan0 parent 1: classid 1:1 htb rate ${limitKbps}kbit",
            "iptables -t mangle -A POSTROUTING -d $ip -j MARK --set-mark 1"
        ).all { execRoot(it) }
    }

    fun setCustomDNS(primary: String, secondary: String): Boolean {
        if (!RootUtils.isRooted) {
            Log.w(TAG, "setCustomDNS: no root — use VPN mode for DNS override")
            return false
        }
        return listOf(
            "iptables -t nat -F PREROUTING",
            "iptables -t nat -A PREROUTING -p udp --dport 53 -j DNAT --to-destination $primary:53",
            "iptables -t nat -A PREROUTING -p tcp --dport 53 -j DNAT --to-destination $primary:53"
        ).all { execRoot(it) }
    }

    fun enableIPForwarding(): Boolean {
        if (!RootUtils.isRooted) return false
        return execRoot("echo 1 > /proc/sys/net/ipv4/ip_forward") &&
               execRoot("iptables -t nat -A POSTROUTING -o rmnet0 -j MASQUERADE")
    }

    private fun execRoot(cmd: String): Boolean {
        return try {
            val p = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
            val exit = p.waitFor()
            if (exit != 0) Log.w(TAG, "Root cmd exit $exit: $cmd")
            exit == 0
        } catch (e: Exception) {
            Log.e(TAG, "Root exec failed: ${e.message}")
            false
        }
    }

    private fun enableHotspotRootFallback(
        ssid: String = "NovaMesh", password: String = "", enabled: Boolean = true
    ): Boolean {
        return if (enabled) {
            execRoot("svc wifi disable") &&
            execRoot("settings put global tether_dun_required 0") &&
            execRoot("svc tethering start")
        } else {
            execRoot("svc tethering stop")
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
