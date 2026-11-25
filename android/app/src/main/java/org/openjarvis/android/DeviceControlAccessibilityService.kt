package org.openjarvis.android

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

/**
 * User-enabled accessibility bridge for explicit, foreground-initiated device commands.
 * It deliberately does not inspect window content, capture gestures, or run voice recording.
 */
class DeviceControlAccessibilityService : AccessibilityService() {
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    companion object {
        @Volatile private var instance: DeviceControlAccessibilityService? = null

        fun perform(action: Int): Boolean =
            instance?.performGlobalAction(action) ?: false

        fun isEnabled(): Boolean = instance != null
    }
}
