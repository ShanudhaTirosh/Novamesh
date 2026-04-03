package com.novamesh.hotspot

import android.content.Context
import android.util.Log
import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject

/**
 * NovaMesh Local Web Server
 *
 * Serves the Web UI dashboard and exposes a REST API to connected devices.
 * Built on NanoHTTPD (lightweight, pure-Java HTTP server).
 *
 * Endpoints:
 *   GET  /                   → Serves dashboard.html
 *   GET  /api/status         → Hotspot status JSON
 *   GET  /api/devices        → Connected devices JSON
 *   POST /api/hotspot/enable → Enable hotspot
 *   POST /api/hotspot/disable→ Disable hotspot
 *   POST /api/hotspot/config → Update SSID/password
 *   POST /api/device/block   → Block/unblock device by MAC
 *   POST /api/device/limit   → Limit bandwidth for device
 *   POST /api/dns            → Change DNS settings
 *
 * Add to build.gradle: implementation 'org.nanohttpd:nanohttpd:2.3.1'
 */
class LocalWebServer(
    port: Int = 8080,
    private val context: Context,
    private val hotspotManager: HotspotManager,
    private val configStore: HotspotConfigStore,
    private val authToken: String
) : NanoHTTPD(port) {

    companion object {
        private const val TAG = "NovaMesh:WebServer"
    }

    // ────────────────────────────────────────────
    //  Main Request Router
    // ────────────────────────────────────────────

    override fun serve(session: IHTTPSession): Response {
        val uri    = session.uri
        val method = session.method
        val ip     = session.headers["http-client-ip"] ?: session.remoteIpAddress ?: "unknown"

        Log.d(TAG, "$method $uri from $ip")

        // Security: only allow requests from hotspot clients
        if (!isHotspotClient(ip) && ip != "127.0.0.1" && ip != "::1") {
            return jsonError(403, "Access denied: not a hotspot client")
        }

        return when {
            // ── Static UI ──
            uri == "/" || uri == "/index.html" -> serveFile("dashboard.html", "text/html")
            uri.startsWith("/assets/")         -> serveStaticAsset(uri)

            // ── REST API ──
            uri == "/api/status"               -> handleStatus(session)
            uri == "/api/devices"              -> handleDevices(session)
            uri == "/api/hotspot/enable"       -> requireAuth(session) { handleHotspotEnable(true) }
            uri == "/api/hotspot/disable"      -> requireAuth(session) { handleHotspotEnable(false) }
            uri == "/api/hotspot/config"       -> requireAuth(session) { handleHotspotConfig(session) }
            uri == "/api/device/block"         -> requireAuth(session) { handleDeviceBlock(session) }
            uri == "/api/device/limit"         -> requireAuth(session) { handleDeviceLimit(session) }
            uri == "/api/dns"                  -> requireAuth(session) { handleDNS(session) }

            else -> jsonError(404, "Not found: $uri")
        }
    }

    // ────────────────────────────────────────────
    //  Handlers
    // ────────────────────────────────────────────

    private fun handleStatus(session: IHTTPSession): Response {
        val config  = configStore.getConfig()
        val clients = hotspotManager.getConnectedClients()
        val json = JSONObject().apply {
            put("hotspot_active",   hotspotManager.isHotspotEnabled())
            put("ssid",             config.ssid)
            put("band",             config.band)
            put("max_devices",      config.maxDevices)
            put("connected_count",  clients.size)
            put("gateway_ip",       HotspotManager.HOTSPOT_IP)
            put("dns_primary",      config.dnsPrimary)
            put("dns_secondary",    config.dnsSecondary)
            put("uptime_seconds",   config.uptimeSeconds)
            put("version",          "2.1.0")
        }
        return jsonOk(json)
    }

    private fun handleDevices(session: IHTTPSession): Response {
        val clients = hotspotManager.getConnectedClients()
        val arr = JSONArray()
        clients.forEach { c ->
            arr.put(JSONObject().apply {
                put("ip",         c.ip)
                put("mac",        c.mac)
                put("name",       c.name)
                put("is_blocked", c.isBlocked)
                put("limit_kbps", c.bandwidthLimitKbps)
            })
        }
        return jsonOk(JSONObject().put("devices", arr).put("count", clients.size))
    }

    private fun handleHotspotEnable(enable: Boolean): Response {
        val success = if (enable) {
            val cfg = configStore.getConfig()
            hotspotManager.startTetheredHotspot(cfg.ssid, cfg.password, cfg.band)
        } else {
            hotspotManager.stopTetheredHotspot()
        }
        return jsonOk(JSONObject().put("success", success).put("hotspot_active", enable && success))
    }

    private fun handleHotspotConfig(session: IHTTPSession): Response {
        val body = readBody(session)
        return try {
            val json     = JSONObject(body)
            val ssid     = json.optString("ssid").takeIf { it.isNotBlank() }
            val password = json.optString("password").takeIf { it.isNotBlank() }
            val band     = json.optInt("band", 0)
            val maxDev   = json.optInt("max_devices", 8)

            configStore.updateConfig { cfg ->
                ssid?.let     { cfg.ssid       = it }
                password?.let { cfg.password   = it }
                cfg.band       = band
                cfg.maxDevices = maxDev
            }

            // Restart hotspot with new config
            hotspotManager.stopTetheredHotspot()
            Thread.sleep(500)
            val cfg = configStore.getConfig()
            hotspotManager.startTetheredHotspot(cfg.ssid, cfg.password, cfg.band)

            jsonOk(JSONObject().put("success", true).put("applied", true))
        } catch (e: Exception) {
            jsonError(400, "Invalid config JSON: ${e.message}")
        }
    }

    private fun handleDeviceBlock(session: IHTTPSession): Response {
        val body = readBody(session)
        return try {
            val json    = JSONObject(body)
            val mac     = json.getString("mac")
            val blocked = json.getBoolean("blocked")

            val rootSuccess = hotspotManager.blockDeviceByMAC(mac, blocked)

            // Always store in allowlist/blocklist (works without root for UI tracking)
            configStore.updateBlocklist(mac, blocked)

            JSONObject().apply {
                put("success", true)
                put("mac",     mac)
                put("blocked", blocked)
                put("root_enforced", rootSuccess)
                if (!rootSuccess) put("warning", "Root not available — blocked in config but not enforced at network level")
            }.let { jsonOk(it) }
        } catch (e: Exception) {
            jsonError(400, "Invalid request: ${e.message}")
        }
    }

    private fun handleDeviceLimit(session: IHTTPSession): Response {
        val body = readBody(session)
        return try {
            val json      = JSONObject(body)
            val ip        = json.getString("ip")
            val limitKbps = json.getInt("limit_kbps")

            val rootSuccess = hotspotManager.limitBandwidth(ip, limitKbps)
            configStore.setBandwidthLimit(ip, limitKbps)

            jsonOk(JSONObject().apply {
                put("success", true)
                put("ip",    ip)
                put("limit_kbps",    limitKbps)
                put("root_enforced", rootSuccess)
            })
        } catch (e: Exception) {
            jsonError(400, "Invalid request: ${e.message}")
        }
    }

    private fun handleDNS(session: IHTTPSession): Response {
        val body = readBody(session)
        return try {
            val json      = JSONObject(body)
            val primary   = json.getString("primary")
            val secondary = json.optString("secondary", "8.8.8.8")

            configStore.updateConfig { it.dnsPrimary = primary; it.dnsSecondary = secondary }
            val rootSuccess = hotspotManager.setCustomDNS(primary, secondary)

            jsonOk(JSONObject().apply {
                put("success", true)
                put("primary", primary)
                put("secondary", secondary)
                put("root_enforced", rootSuccess)
                if (!rootSuccess) put("note", "DNS stored in config. Full enforcement requires root or VPN DNS mode.")
            })
        } catch (e: Exception) {
            jsonError(400, "Invalid DNS JSON: ${e.message}")
        }
    }

    // ────────────────────────────────────────────
    //  Auth Middleware
    // ────────────────────────────────────────────

    private fun requireAuth(session: IHTTPSession, handler: () -> Response): Response {
        val token = session.headers["x-auth-token"]
            ?: session.headers["authorization"]?.removePrefix("Bearer ")
        return if (token == authToken) {
            handler()
        } else {
            jsonError(401, "Unauthorized — provide valid X-Auth-Token header")
        }
    }

    // ────────────────────────────────────────────
    //  File Serving
    // ────────────────────────────────────────────

    private fun serveFile(filename: String, mimeType: String): Response {
        return try {
            val stream = context.assets.open("webui/$filename")
            newChunkedResponse(Response.Status.OK, mimeType, stream)
        } catch (e: Exception) {
            Log.e(TAG, "Asset not found: $filename")
            jsonError(404, "File not found: $filename")
        }
    }

    private fun serveStaticAsset(uri: String): Response {
        val filename = uri.removePrefix("/assets/")
        val mime = when {
            filename.endsWith(".css")  -> "text/css"
            filename.endsWith(".js")   -> "application/javascript"
            filename.endsWith(".png")  -> "image/png"
            filename.endsWith(".svg")  -> "image/svg+xml"
            else                       -> "application/octet-stream"
        }
        return serveFile("assets/$filename", mime)
    }

    // ────────────────────────────────────────────
    //  Helpers
    // ────────────────────────────────────────────

    private fun isHotspotClient(ip: String): Boolean {
        return ip.startsWith("192.168.43.") ||
               ip.startsWith("192.168.") ||
               ip == "127.0.0.1"
    }

    private fun readBody(session: IHTTPSession): String {
        val contentLength = session.headers["content-length"]?.toIntOrNull() ?: 0
        val buf = ByteArray(contentLength)
        session.inputStream.read(buf, 0, contentLength)
        return String(buf)
    }

    private fun jsonOk(json: JSONObject): Response =
        newFixedLengthResponse(Response.Status.OK, "application/json", json.toString())

    private fun jsonError(code: Int, message: String): Response {
        val status = when (code) {
            400  -> Response.Status.BAD_REQUEST
            401  -> Response.Status.UNAUTHORIZED
            403  -> Response.Status.FORBIDDEN
            404  -> Response.Status.NOT_FOUND
            else -> Response.Status.INTERNAL_ERROR
        }
        return newFixedLengthResponse(
            status, "application/json",
            JSONObject().put("error", message).put("code", code).toString()
        )
    }
}
