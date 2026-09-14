package com.ekinao.desktopmascot

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.IntentFilter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel

class MascotService : Service() {
    private lateinit var overlay: MascotOverlay
    // Fail closed until the accessibility service identifies an allowed foreground window.
    private var appHidden = true
    private var batteryHidden = false
    private var batteryReceiverRegistered = false
    private var batteryPercent = -1
    private var batteryCharging = false
    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != Intent.ACTION_BATTERY_CHANGED) return
            val level = intent.getIntExtra("level", -1)
            val scale = intent.getIntExtra("scale", -1)
            val status = intent.getIntExtra("status", -1)
            if (level < 0 || scale <= 0) return

            batteryPercent = (level * 100) / scale
            batteryCharging = status == android.os.BatteryManager.BATTERY_STATUS_CHARGING ||
                status == android.os.BatteryManager.BATTERY_STATUS_FULL
            reevaluateBatteryHidden()
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()

        serviceScope.launch {
            currentSettings = SettingsRepository(this@MascotService).settings.first()
            overlay = MascotOverlay(this@MascotService)
            overlay.show()
            registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            batteryReceiverRegistered = true

            serviceScope.launch {
                SettingsRepository(this@MascotService).settings.collectLatest { settings ->
                    currentSettings = settings
                    if (::overlay.isInitialized) overlay.updateSettings(settings)
                    reevaluateBatteryHidden()
                }
            }

            startAsForegroundService()
        }
    }

    private fun startAsForegroundService() {
        val notification = createNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun createNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pending = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_myplaces)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setOngoing(true)
            .setContentIntent(pending)
            .build()
    }

    private fun applyHiddenState() {
        if (!::overlay.isInitialized) return
        if (appHidden || batteryHidden) {
            overlay.temporarilyHide()
        } else {
            overlay.restoreAfterTemporaryHide()
        }
    }

    private fun hideForSelectedApp() {
        appHidden = true
        applyHiddenState()
    }

    private fun showAfterSelectedApp() {
        appHidden = false
        applyHiddenState()
    }

    private fun reevaluateBatteryHidden() {
        val settings = currentSettings ?: return
        val shouldHide = batteryPercent >= 0 &&
            settings.hideWhenLowBattery &&
            !batteryCharging &&
            batteryPercent <= settings.batteryThreshold
        setBatteryHidden(shouldHide)
    }

    private fun setBatteryHidden(hidden: Boolean) {
        if (batteryHidden == hidden) return
        batteryHidden = hidden
        applyHiddenState()
    }

    override fun onDestroy() {
        if (batteryReceiverRegistered) {
            try { unregisterReceiver(batteryReceiver) } catch (_: Exception) {}
            batteryReceiverRegistered = false
        }
        if (::overlay.isInitialized) overlay.destroy()
        serviceScope.cancel()
        currentSettings = null
        MascotServiceHolder.running = false
        instance = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val CHANNEL_ID = "mascot_running"
        const val NOTIFICATION_ID = 1001
        @Volatile
        var currentSettings: MascotSettings? = null

        @Volatile
        private var instance: MascotService? = null

        fun setAppHidden(hidden: Boolean) {
            instance?.let { service ->
                if (!service::overlay.isInitialized) return
                if (hidden) service.hideForSelectedApp() else service.showAfterSelectedApp()
            }
        }
    }
}
