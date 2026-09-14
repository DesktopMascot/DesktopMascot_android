package com.ekinao.desktopmascot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat

/** Starts the mascot automatically after the phone finishes booting. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return

        // The overlay cannot be created until the user has granted the
        // "draw over other apps" permission. If it has not been granted yet,
        // simply wait until the user opens the app and grants it.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            !Settings.canDrawOverlays(context)) {
            return
        }

        val serviceIntent = Intent(context, MascotService::class.java)
        try {
            ContextCompat.startForegroundService(context, serviceIntent)
        } catch (_: Exception) {
            // Do not crash the boot receiver if the system refuses a
            // background foreground-service start.
        }
    }
}
