package com.novamesh.hotspot

import android.app.Application
import android.util.Log
import com.google.firebase.FirebaseApp
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

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

    @OptIn(DelicateCoroutinesApi::class)
    override fun onCreate() {
        super.onCreate()

        // 1. Initialise Firebase (required before any Firebase call)
        FirebaseApp.initializeApp(this)

        // 2. Root detection runs on a background thread — it calls su which
        //    can block for several seconds on Samsung/Knox devices. Calling
        //    waitFor() on the main thread causes an ANR crash.
        GlobalScope.launch(Dispatchers.IO) {
            val rooted = RootUtils.isRooted
            Log.i(TAG, "NovaMesh v2.1.0 started — Root: $rooted")
        }
    }
}
