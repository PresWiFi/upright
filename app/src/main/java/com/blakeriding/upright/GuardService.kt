package com.blakeriding.upright

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent

/**
 * During alarm hours (and while a wake-up is in progress), backs out of any system screen that could
 * uninstall, force-stop or switch off Upright. While the alarm is ringing, it also brings the ringing
 * screen back if you leave it.
 */
class GuardService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private var lastToastAt = 0L
    private val recheck = Runnable { if (active()) guard() }
    private val bringBack = Runnable {
        if (Store.state.value.session?.phase == Phase.RINGING) {
            startActivity(Intent(this, RingActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    override fun onServiceConnected() {
        Store.init(this)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun onInterrupt() {}

    private fun active() = guardActive(Store.state.value, nowZ())

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName) {
            handler.removeCallbacks(bringBack)
            return
        }
        if (!active()) return
        if (isSensitive(pkg)) guard()
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            Store.state.value.session?.phase == Phase.RINGING && ALLOWED_WHILE_RINGING.none { it in pkg }
        ) {
            handler.removeCallbacks(bringBack)
            handler.postDelayed(bringBack, BRING_BACK_MS)
        }
    }

    /** Backs out of settings/installer screens that mention Upright (app info, uninstall, admin, accessibility). */
    private fun guard() {
        val label = getString(R.string.app_name)
        // Check every window, not just the active one: a confirm dialog doesn't name the app, the page under it does.
        val tampering = windows.any { window ->
            val root = window.root ?: return@any false
            isSensitive(root.packageName?.toString()) && root.findAccessibilityNodeInfosByText(label).isNotEmpty()
        }
        if (!tampering) return
        performGlobalAction(GLOBAL_ACTION_BACK)
        val now = SystemClock.elapsedRealtime()
        if (now - lastToastAt > TOAST_INTERVAL_MS) {
            lastToastAt = now
            toast(getString(R.string.protected_toast))
        }
        // Keep backing out until the screen is gone, even if no further event arrives.
        handler.removeCallbacks(recheck)
        handler.postDelayed(recheck, RECHECK_MS)
    }

    private fun isSensitive(pkg: String?) = pkg != null && SENSITIVE.any { it in pkg }

    private companion object {
        const val RECHECK_MS = 300L
        const val TOAST_INTERVAL_MS = 3000L
        const val BRING_BACK_MS = 1500L

        // Settings apps, installers and OEM phone-manager apps (OnePlus/Oppo use oplus/coloros names).
        val SENSITIVE = listOf(
            "settings", "packageinstaller", "permissioncontroller", "securitycenter", "safecenter",
            "com.oplus.battery", "com.coloros", "com.android.vending",
        )

        // Never pull you away from these while ringing: the barcode scanner, the system UI and anything phone/emergency.
        val ALLOWED_WHILE_RINGING = listOf(
            "com.google.android.gms", "systemui", "dialer", "telecom", "incallui", "emergency", "com.android.phone",
        )
    }
}
