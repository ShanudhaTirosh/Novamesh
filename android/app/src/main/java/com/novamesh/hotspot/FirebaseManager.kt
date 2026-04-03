package com.novamesh.hotspot

import android.content.Context
import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.database.*
import com.google.firebase.firestore.FirebaseFirestore

/**
 * NovaMesh Firebase Manager
 *
 * Handles:
 *  - Firebase Authentication (email/password)
 *  - Firestore: storing hotspot config, device profiles, access rules
 *  - Realtime Database: live device list, bandwidth stats
 *
 * Firestore Schema:
 * ┌─ users/{uid}/
 * │   ├─ config/          ← hotspot settings
 * │   ├─ devices/         ← known device profiles
 * │   └─ rules/           ← ACL rules, parental controls
 * └─ realtime/{uid}/
 *     ├─ devices/         ← live connected device list
 *     └─ stats/           ← bandwidth counters
 */
class FirebaseManager(private val context: Context) {

    companion object {
        private const val TAG = "NovaMesh:Firebase"
    }

    private val auth       = FirebaseAuth.getInstance()
    private val firestore  = FirebaseFirestore.getInstance()
    private val realtimeDB = FirebaseDatabase.getInstance().reference
    private var configListener: ValueEventListener? = null
    private var currentUser: FirebaseUser? = auth.currentUser

    // ────────────────────────────────────────────
    //  Authentication
    // ────────────────────────────────────────────

    fun signIn(
        email: String,
        password: String,
        onSuccess: (FirebaseUser) -> Unit,
        onError: (Exception) -> Unit
    ) {
        auth.signInWithEmailAndPassword(email, password)
            .addOnSuccessListener { result ->
                currentUser = result.user
                Log.i(TAG, "Signed in: ${result.user?.email}")
                result.user?.let { onSuccess(it) }
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Sign-in failed: ${e.message}")
                onError(e)
            }
    }

    fun signOut() {
        auth.signOut()
        currentUser = null
    }

    fun isSignedIn() = auth.currentUser != null

    fun getCurrentUser() = auth.currentUser

    fun generateSessionToken(): String {
        // Returns Firebase ID token for use as X-Auth-Token in web UI
        var token = ""
        auth.currentUser?.getIdToken(false)
            ?.addOnSuccessListener { result -> token = result.token ?: "" }
        return token
    }

    // ────────────────────────────────────────────
    //  Firestore: Config Sync
    // ────────────────────────────────────────────

    /**
     * Push local config to Firestore
     */
    fun syncConfig(config: HotspotConfig) {
        val uid = currentUser?.uid ?: return
        val data = mapOf(
            "ssid"          to config.ssid,
            "band"          to config.band,
            "max_devices"   to config.maxDevices,
            "dns_primary"   to config.dnsPrimary,
            "dns_secondary" to config.dnsSecondary,
            "guest_enabled" to config.guestEnabled,
            "guest_ssid"    to config.guestSSID,
            "acl_mode"      to config.aclMode,
            "updated_at"    to com.google.firebase.Timestamp.now()
        )
        firestore.collection("users")
            .document(uid)
            .collection("config")
            .document("hotspot")
            .set(data)
            .addOnSuccessListener { Log.i(TAG, "Config synced to Firestore") }
            .addOnFailureListener { Log.e(TAG, "Config sync failed: ${it.message}") }
    }

    /**
     * Load config from Firestore (called on first launch if no local config)
     */
    fun loadRemoteConfig(onLoaded: (HotspotConfig) -> Unit) {
        val uid = currentUser?.uid ?: return
        firestore.collection("users")
            .document(uid)
            .collection("config")
            .document("hotspot")
            .get()
            .addOnSuccessListener { doc ->
                if (doc.exists()) {
                    val config = HotspotConfig(
                        ssid         = doc.getString("ssid") ?: "NovaMesh",
                        band         = doc.getLong("band")?.toInt() ?: 0,
                        maxDevices   = doc.getLong("max_devices")?.toInt() ?: 8,
                        dnsPrimary   = doc.getString("dns_primary") ?: "8.8.8.8",
                        dnsSecondary = doc.getString("dns_secondary") ?: "8.8.4.4",
                        guestEnabled = doc.getBoolean("guest_enabled") ?: false,
                        guestSSID    = doc.getString("guest_ssid") ?: "NovaMesh-Guest",
                        aclMode      = doc.getString("acl_mode") ?: "open"
                    )
                    Log.i(TAG, "Remote config loaded")
                    onLoaded(config)
                }
            }
            .addOnFailureListener { Log.e(TAG, "Failed to load remote config: ${it.message}") }
    }

    // ────────────────────────────────────────────
    //  Realtime Database: Live Device List
    // ────────────────────────────────────────────

    /**
     * Push current connected devices to Realtime DB (for multi-device admin view)
     */
    fun updateConnectedDevices(clients: List<HotspotManager.ConnectedClient>) {
        val uid = currentUser?.uid ?: return
        val data = clients.associate { c ->
            c.mac.replace(":", "_") to mapOf(
                "ip"         to c.ip,
                "mac"        to c.mac,
                "name"       to c.name,
                "is_blocked" to c.isBlocked,
                "limit_kbps" to c.bandwidthLimitKbps,
                "last_seen"  to System.currentTimeMillis()
            )
        }
        realtimeDB.child("realtime/$uid/devices").setValue(data)
            .addOnFailureListener { Log.e(TAG, "RT device update failed: ${it.message}") }
    }

    /**
     * Listen for remote config changes (e.g., admin changes settings from another device)
     */
    fun startRealtimeSync(onConfigUpdate: (HotspotConfig) -> Unit) {
        val uid = currentUser?.uid ?: return
        val ref = realtimeDB.child("realtime/$uid/remote_config")

        configListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (!snapshot.exists()) return
                try {
                    val config = HotspotConfig(
                        ssid         = snapshot.child("ssid").getValue(String::class.java) ?: return,
                        band         = snapshot.child("band").getValue(Int::class.java) ?: 0,
                        maxDevices   = snapshot.child("max_devices").getValue(Int::class.java) ?: 8,
                        dnsPrimary   = snapshot.child("dns_primary").getValue(String::class.java) ?: "8.8.8.8",
                        dnsSecondary = snapshot.child("dns_secondary").getValue(String::class.java) ?: "8.8.4.4"
                    )
                    Log.i(TAG, "Remote config update received")
                    onConfigUpdate(config)
                } catch (e: Exception) {
                    Log.e(TAG, "Config parse error: ${e.message}")
                }
            }
            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "RT sync cancelled: ${error.message}")
            }
        }
        ref.addValueEventListener(configListener!!)
    }

    fun stopSync() {
        val uid = currentUser?.uid ?: return
        configListener?.let {
            realtimeDB.child("realtime/$uid/remote_config").removeEventListener(it)
        }
    }

    // ────────────────────────────────────────────
    //  Firestore: Device Profiles & Rules
    // ────────────────────────────────────────────

    fun saveDeviceProfile(mac: String, name: String, isBlocked: Boolean) {
        val uid = currentUser?.uid ?: return
        firestore.collection("users/$uid/devices")
            .document(mac.replace(":", "_"))
            .set(mapOf("mac" to mac, "name" to name, "is_blocked" to isBlocked))
    }

    fun saveParentalRule(mac: String, blockFrom: String, blockUntil: String) {
        val uid = currentUser?.uid ?: return
        firestore.collection("users/$uid/rules")
            .document("parental_${mac.replace(":", "_")}")
            .set(mapOf("mac" to mac, "block_from" to blockFrom, "block_until" to blockUntil, "enabled" to true))
    }

    // ────────────────────────────────────────────
    //  Stats Logging
    // ────────────────────────────────────────────

    fun logBandwidthStat(downloadMbps: Double, uploadMbps: Double) {
        val uid = currentUser?.uid ?: return
        val entry = mapOf(
            "timestamp"     to System.currentTimeMillis(),
            "download_mbps" to downloadMbps,
            "upload_mbps"   to uploadMbps
        )
        realtimeDB.child("realtime/$uid/stats").push().setValue(entry)
    }
}
