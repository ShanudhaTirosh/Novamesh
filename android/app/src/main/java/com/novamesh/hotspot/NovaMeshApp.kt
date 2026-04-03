package com.novamesh.hotspot

import android.app.Application
import android.util.Log
import com.google.firebase.FirebaseApp

/**
 * NovaMeshApp — Application class
 *
 * Initialises Firebase and triggers root detection once,
 * so the result is cached before any Activity or Service starts.
 */
class NovaMeshApp : Application() {

    companion object {
        private const val TAG = "NovaMesh:App"
    }

    override fun onCreate() {
        super.onCreate()

        // 1. Initialise Firebase (required before any Firebase call)
        FirebaseApp.initializeApp(this)

        // 2. Trigger root detection now — cached for entire app lifetime.
        //    All components (HotspotManager, etc.) read RootUtils.isRooted
        //    which returns instantly after this first call.
        val rooted = RootUtils.isRooted
        Log.i(TAG, "NovaMesh v2.1.0 started — Root: $rooted")
    }
}
