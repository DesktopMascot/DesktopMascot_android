package com.ekinao.desktopmascot

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent

/**
 * Enforces the visibility rule:
 *   - show on the Android Home/Launcher
 *   - show while Desktop Mascot itself is the foreground app
 *   - hide for every other app/window (including the keyboard)
 */
class MascotAccessibilityService : AccessibilityService() {
    private var lastShouldShow: Boolean? = null
    private val handler = Handler(Looper.getMainLooper())
    private val homePackages = mutableSetOf<String>()

    private fun refreshHomePackages() {
        homePackages.clear()
        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            addCategory(Intent.CATEGORY_DEFAULT)
        }
        packageManager.queryIntentActivities(intent, 0).forEach { info ->
            info.activityInfo?.packageName?.let { homePackages.add(it) }
        }

        // Always allow the mascot app itself.
        homePackages.add(packageName)
    }

    private fun updateVisibility(foregroundPackage: String?) {
        if (foregroundPackage.isNullOrBlank()) return

        val shouldShow = homePackages.contains(foregroundPackage)

        if (shouldShow == lastShouldShow) return
        lastShouldShow = shouldShow

        // Battery hiding remains an independent condition inside MascotService.
        MascotService.setAppHidden(!shouldShow)
    }

    private fun checkActiveWindow() {
        val pkg = rootInActiveWindow?.packageName?.toString()
        if (!pkg.isNullOrBlank()) {
            updateVisibility(pkg)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        refreshHomePackages()

        // Give Android a moment to expose the active application/window after
        // the accessibility service connects, then check it more than once.
        checkActiveWindow()
        handler.postDelayed({ checkActiveWindow() }, 300)
        handler.postDelayed({ checkActiveWindow() }, 1000)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> {
                val eventPackage = event.packageName?.toString()
                if (!eventPackage.isNullOrBlank()) {
                    updateVisibility(eventPackage)
                } else {
                    checkActiveWindow()
                }
            }
        }
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        // If the visibility-control service is disabled/stopped, fail closed:
        // do not leave the overlay visible over arbitrary apps.
        MascotService.setAppHidden(true)
        super.onDestroy()
    }
}
