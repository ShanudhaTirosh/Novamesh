package com.novamesh.hotspot

import android.app.Application
import android.util.Log
import com.google.firebase.FirebaseApp
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

class NovaMeshApp : Application() {

    companion object { private const val TAG = "NovaMesh:App" }

    @OptIn(DelicateCoroutinesApi::class)
    override fun onCreate() {
        super.onCreate()

        // 1. Firebase init — wrapped so missing google-services.json won't crash
        try {
            FirebaseApp.initializeApp(this)
            Log.i(TAG, "Firebase initialized")
        } catch (e: Exception) {
            Log.w(TAG, "Firebase init failed — app runs without Firebase: ${e.message}")
        }

        // 2. Root detection on background thread — caches result for the whole app.
        // Do NOT call RootUtils.isRooted on the main thread; su can block for seconds.
        GlobalScope.launch(Dispatchers.IO) {
            val rooted = RootUtils.isRooted
            Log.i(TAG, "NovaMesh v2.1.0 started — Root=$rooted (${RootUtils.statusLabel})")
        }
    }
}
