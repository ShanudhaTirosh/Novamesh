package com.novamesh.hotspot

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import java.util.UUID

/**
 * HotspotConfig — Data model for all hotspot settings
 */
data class HotspotConfig(
    var ssid:          String  = "NovaMesh-5G",
    var password:      String  = "NovaSecure2024",
    var band:          Int     = 0,          // 0=auto, 2=2.4GHz, 5=5GHz
    var maxDevices:    Int     = 8,
    var dnsPrimary:    String  = "8.8.8.8",
    var dnsSecondary:  String  = "8.8.4.4",
    var guestEnabled:  Boolean = false,
    var guestSSID:     String  = "NovaMesh-Guest",
    var guestPassword: String  = "GuestPass2024",
    var guestLimitKbps:Int     = 10240,      // 10 Mbps
    var aclMode:       String  = "open",     // open | allowlist | blocklist
    var adminToken:    String  = UUID.randomUUID().toString(),
    var uptimeSeconds: Long    = 0L
)

/**
 * HotspotConfigStore — SharedPreferences-backed local config persistence
 *
 * Thread-safe config reads and updates with optional callback support.
 */
class HotspotConfigStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("novamesh_config", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_SSID           = "ssid"
        private const val KEY_PASSWORD       = "password"
        private const val KEY_BAND           = "band"
        private const val KEY_MAX_DEVICES    = "max_devices"
        private const val KEY_DNS_PRIMARY    = "dns_primary"
        private const val KEY_DNS_SECONDARY  = "dns_secondary"
        private const val KEY_GUEST_ENABLED  = "guest_enabled"
        private const val KEY_GUEST_SSID     = "guest_ssid"
        private const val KEY_GUEST_PASS     = "guest_password"
        private const val KEY_GUEST_LIMIT    = "guest_limit_kbps"
        private const val KEY_ACL_MODE       = "acl_mode"
        private const val KEY_ADMIN_TOKEN    = "admin_token"
        private const val KEY_UPTIME         = "uptime_seconds"
        private const val KEY_BLOCKLIST      = "blocklist"
        private const val KEY_ALLOWLIST      = "allowlist"
        private const val KEY_BW_LIMITS      = "bw_limits"
    }

    fun getConfig(): HotspotConfig {
        return HotspotConfig(
            ssid          = prefs.getString(KEY_SSID,          "NovaMesh-5G")!!,
            password      = prefs.getString(KEY_PASSWORD,      "NovaSecure2024")!!,
            band          = prefs.getInt   (KEY_BAND,          0),
            maxDevices    = prefs.getInt   (KEY_MAX_DEVICES,   8),
            dnsPrimary    = prefs.getString(KEY_DNS_PRIMARY,   "8.8.8.8")!!,
            dnsSecondary  = prefs.getString(KEY_DNS_SECONDARY, "8.8.4.4")!!,
            guestEnabled  = prefs.getBoolean(KEY_GUEST_ENABLED, false),
            guestSSID     = prefs.getString(KEY_GUEST_SSID,   "NovaMesh-Guest")!!,
            guestPassword = prefs.getString(KEY_GUEST_PASS,   "GuestPass2024")!!,
            guestLimitKbps= prefs.getInt   (KEY_GUEST_LIMIT,  10240),
            aclMode       = prefs.getString(KEY_ACL_MODE,     "open")!!,
            adminToken    = prefs.getString(KEY_ADMIN_TOKEN,  generateToken())!!,
            uptimeSeconds = prefs.getLong  (KEY_UPTIME,       0L)
        )
    }

    fun saveConfig(config: HotspotConfig) {
        prefs.edit().apply {
            putString (KEY_SSID,          config.ssid)
            putString (KEY_PASSWORD,      config.password)
            putInt    (KEY_BAND,          config.band)
            putInt    (KEY_MAX_DEVICES,   config.maxDevices)
            putString (KEY_DNS_PRIMARY,   config.dnsPrimary)
            putString (KEY_DNS_SECONDARY, config.dnsSecondary)
            putBoolean(KEY_GUEST_ENABLED, config.guestEnabled)
            putString (KEY_GUEST_SSID,    config.guestSSID)
            putString (KEY_GUEST_PASS,    config.guestPassword)
            putInt    (KEY_GUEST_LIMIT,   config.guestLimitKbps)
            putString (KEY_ACL_MODE,      config.aclMode)
            putString (KEY_ADMIN_TOKEN,   config.adminToken)
            putLong   (KEY_UPTIME,        config.uptimeSeconds)
        }.apply()
    }

    /**
     * Atomic config update — thread-safe read-modify-write
     */
    @Synchronized
    fun updateConfig(block: (HotspotConfig) -> Unit) {
        val cfg = getConfig()
        block(cfg)
        saveConfig(cfg)
    }

    // ──────────────── Blocklist ────────────────

    fun getBlocklist(): Set<String> {
        return prefs.getStringSet(KEY_BLOCKLIST, emptySet()) ?: emptySet()
    }

    fun updateBlocklist(mac: String, blocked: Boolean) {
        val current = getBlocklist().toMutableSet()
        if (blocked) current.add(mac) else current.remove(mac)
        prefs.edit().putStringSet(KEY_BLOCKLIST, current).apply()
    }

    // ──────────────── Allowlist ────────────────

    fun getAllowlist(): Set<String> {
        return prefs.getStringSet(KEY_ALLOWLIST, emptySet()) ?: emptySet()
    }

    fun updateAllowlist(mac: String, allowed: Boolean) {
        val current = getAllowlist().toMutableSet()
        if (allowed) current.add(mac) else current.remove(mac)
        prefs.edit().putStringSet(KEY_ALLOWLIST, current).apply()
    }

    // ──────────────── Bandwidth Limits ────────────────

    fun getBandwidthLimit(ip: String): Int {
        val raw = prefs.getString(KEY_BW_LIMITS, "{}") ?: "{}"
        return try {
            val obj = org.json.JSONObject(raw)
            obj.optInt(ip.replace(".", "_"), 0)
        } catch (e: Exception) { 0 }
    }

    fun setBandwidthLimit(ip: String, limitKbps: Int) {
        val raw = prefs.getString(KEY_BW_LIMITS, "{}") ?: "{}"
        val obj = try { org.json.JSONObject(raw) } catch (e: Exception) { org.json.JSONObject() }
        obj.put(ip.replace(".", "_"), limitKbps)
        prefs.edit().putString(KEY_BW_LIMITS, obj.toString()).apply()
    }

    fun clearBandwidthLimit(ip: String) = setBandwidthLimit(ip, 0)

    // ──────────────── Helpers ────────────────

    private fun generateToken(): String {
        val token = UUID.randomUUID().toString()
        prefs.edit().putString(KEY_ADMIN_TOKEN, token).apply()
        return token
    }

    fun resetToDefaults() {
        prefs.edit().clear().apply()
    }
}
