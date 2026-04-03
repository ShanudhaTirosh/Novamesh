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
 * FIX: Added `offline` constructor parameter.
 * When offline=true, all Firebase calls are no-ops — the service and activity
 * can safely create FirebaseManager even if Firebase services are not configured.
 * This prevents crashes when:
 *  - Realtime Database URL is missing from google-services.json
 *  - Firebase Auth user doesn't exist yet
 *  - No internet connection
 */
class FirebaseManager(private val context: Context, private val offline: Boolean = false) {

    companion object { private const val TAG = "NovaMesh:Firebase" }

    // All instances are nullable — if init throws, we stay in offline mode
    private val auth      : FirebaseAuth?       = if (offline) null else tryGet { FirebaseAuth.getInstance() }
    private val firestore : FirebaseFirestore?  = if (offline) null else tryGet { FirebaseFirestore.getInstance() }
    private val realtimeDB: DatabaseReference? = if (offline) null else tryGet { FirebaseDatabase.getInstance().reference }

    private var configListener: ValueEventListener? = null
    private var currentUser: FirebaseUser? = auth?.currentUser

    init {
        if (offline) Log.i(TAG, "FirebaseManager running in offline/fallback mode")
        else Log.i(TAG, "FirebaseManager initialized (auth=${auth != null}, db=${realtimeDB != null})")
    }

    private fun <T> tryGet(block: () -> T): T? = try { block() }
    catch (e: Exception) {
        Log.w(TAG, "Firebase component init failed: ${e.message}")
        null
    }

    // ── Auth ───────────────────────────────────────────────────────────────

    fun signIn(email: String, password: String,
               onSuccess: (FirebaseUser) -> Unit,
               onError: (Exception) -> Unit) {
        val a = auth
        if (a == null) {
            onError(IllegalStateException("Firebase Auth not available"))
            return
        }
        a.signInWithEmailAndPassword(email, password)
            .addOnSuccessListener { r ->
                currentUser = r.user
                r.user?.let { onSuccess(it) }
                    ?: onError(IllegalStateException("Auth returned null user"))
            }
            .addOnFailureListener { onError(it) }
    }

    fun signOut()    { try { auth?.signOut(); currentUser = null } catch (_: Exception) {} }
    fun isSignedIn() = auth?.currentUser != null
    fun currentUser()= auth?.currentUser
    private fun uid()= auth?.currentUser?.uid

    // ── Firestore Config ───────────────────────────────────────────────────

    fun syncConfig(config: HotspotConfig) {
        val uid = uid() ?: return
        val fs  = firestore ?: return
        val data = mapOf(
            "ssid"          to config.ssid,
            "band"          to config.band,
            "max_devices"   to config.maxDevices,
            "dns_primary"   to config.dnsPrimary,
            "dns_secondary" to config.dnsSecondary,
            "guest_enabled" to config.guestEnabled,
            "guest_ssid"    to config.guestSSID,
            "acl_mode"      to config.aclMode
        )
        fs.collection("users/$uid/config").document("hotspot")
            .set(data)
            .addOnSuccessListener { Log.i(TAG, "Config synced") }
            .addOnFailureListener { Log.w(TAG, "Config sync failed: ${it.message}") }
    }

    fun loadRemoteConfig(onLoaded: (HotspotConfig) -> Unit) {
        val uid = uid() ?: return
        val fs  = firestore ?: return
        fs.collection("users/$uid/config").document("hotspot").get()
            .addOnSuccessListener { doc ->
                if (!doc.exists()) return@addOnSuccessListener
                try {
                    onLoaded(HotspotConfig(
                        ssid         = doc.getString("ssid")           ?: "NovaMesh",
                        band         = doc.getLong("band")?.toInt()    ?: 0,
                        maxDevices   = doc.getLong("max_devices")?.toInt() ?: 8,
                        dnsPrimary   = doc.getString("dns_primary")    ?: "8.8.8.8",
                        dnsSecondary = doc.getString("dns_secondary")  ?: "8.8.4.4",
                        guestEnabled = doc.getBoolean("guest_enabled") ?: false,
                        guestSSID    = doc.getString("guest_ssid")     ?: "NovaMesh-Guest",
                        aclMode      = doc.getString("acl_mode")       ?: "open"
                    ))
                } catch (e: Exception) { Log.e(TAG, "Config parse error: ${e.message}") }
            }
            .addOnFailureListener { Log.w(TAG, "Load remote config failed: ${it.message}") }
    }

    // ── Realtime Database ──────────────────────────────────────────────────

    fun updateConnectedDevices(clients: List<HotspotManager.ConnectedClient>) {
        val uid = uid() ?: return
        val db  = realtimeDB ?: return
        val data = clients.associate { c ->
            c.mac.replace(":", "_") to mapOf(
                "ip" to c.ip, "mac" to c.mac, "name" to c.name,
                "is_blocked" to c.isBlocked, "limit_kbps" to c.bandwidthLimitKbps,
                "last_seen" to System.currentTimeMillis()
            )
        }
        try {
            db.child("realtime/$uid/devices").setValue(data)
                .addOnFailureListener { Log.w(TAG, "RT device update failed: ${it.message}") }
        } catch (e: Exception) { Log.w(TAG, "updateConnectedDevices failed: ${e.message}") }
    }

    fun startRealtimeSync(onConfigUpdate: (HotspotConfig) -> Unit) {
        val uid = uid() ?: return
        val db  = realtimeDB ?: return
        configListener = object : ValueEventListener {
            override fun onDataChange(snap: DataSnapshot) {
                if (!snap.exists()) return
                try {
                    onConfigUpdate(HotspotConfig(
                        ssid       = snap.child("ssid").getValue(String::class.java) ?: return,
                        band       = snap.child("band").getValue(Int::class.java) ?: 0,
                        maxDevices = snap.child("max_devices").getValue(Int::class.java) ?: 8,
                        dnsPrimary = snap.child("dns_primary").getValue(String::class.java) ?: "8.8.8.8"
                    ))
                } catch (e: Exception) { Log.e(TAG, "RT config parse error: ${e.message}") }
            }
            override fun onCancelled(e: DatabaseError) = Log.w(TAG, "RT sync cancelled: ${e.message}")
        }
        try {
            db.child("realtime/$uid/remote_config").addValueEventListener(configListener!!)
        } catch (e: Exception) { Log.w(TAG, "startRealtimeSync failed: ${e.message}") }
    }

    fun stopSync() {
        val uid = uid() ?: return
        val db  = realtimeDB ?: return
        configListener?.let {
            try { db.child("realtime/$uid/remote_config").removeEventListener(it) }
            catch (_: Exception) {}
        }
    }

    fun logBandwidthStat(down: Double, up: Double) {
        val uid = uid() ?: return
        val db  = realtimeDB ?: return
        try {
            db.child("realtime/$uid/stats").push().setValue(
                mapOf("ts" to System.currentTimeMillis(), "down_mbps" to down, "up_mbps" to up)
            )
        } catch (_: Exception) {}
    }
}
