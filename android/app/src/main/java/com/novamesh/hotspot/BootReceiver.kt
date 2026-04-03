package com.novamesh.hotspot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * BootReceiver — Auto-starts HotspotService after device reboot.
 *
 * Declared in AndroidManifest.xml with RECEIVE_BOOT_COMPLETED permission.
 * Handles both standard BOOT_COMPLETED and MIUI/OnePlus QUICKBOOT_POWERON.
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "NovaMesh:BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON" -> {
                Log.i(TAG, "Boot completed — auto-starting HotspotService")
                val serviceIntent = HotspotService.startIntent(context)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
            }
        }
    }
}
