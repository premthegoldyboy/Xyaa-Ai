package org.openjarvis.android

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityEvent

/**
 * User-enabled Android interaction bridge.
 *
 * Screen inspection and gestures are enabled only after the user explicitly
 * turns this service on in Android Accessibility settings.
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

    private fun clickText(query: String): Boolean {
        if (query.isBlank()) return false
        val root = rootInActiveWindow ?: return false
        val sensitiveAction = Regex("(?i)\\b(send|submit|post|publish|buy|purchase|pay|transfer|delete|remove|erase|call|dial|confirm|place order|accept|allow|grant|revoke|disable account)\\b")
        return try {
            val matches = root.findAccessibilityNodeInfosByText(query)
            try {
                val target = matches.firstOrNull {
                    it.isVisibleToUser && (it.isClickable || it.parent?.isClickable == true)
                } ?: return false
                // Check the actual control label and its clickable parent, not just the model's search text.
                var node: AccessibilityNodeInfo? = AccessibilityNodeInfo.obtain(target)
                var blocked = false
                try {
                    repeat(4) {
                        val current = node ?: return@repeat
                        val label = listOf(current.text, current.contentDescription)
                            .filterNotNull().joinToString(" ").trim()
                        if (sensitiveAction.containsMatchIn(label)) blocked = true
                        val parent = current.parent
                        current.recycle()
                        node = parent
                    }
                } finally {
                    node?.recycle()
                }
                if (blocked) return false
                val clickable = if (target.isClickable) target else target.parent
                try {
                    clickable?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
                } finally {
                    if (clickable !== target) clickable?.recycle()
                }
            } finally {
                matches.forEach { it.recycle() }
            }
        } finally {
            root.recycle()
        }
    }

    private fun setText(query: String, value: String): Boolean {
        val root = rootInActiveWindow ?: return false
        return try {
            val matches = if (query.isBlank()) emptyList() else root.findAccessibilityNodeInfosByText(query)
            val target = matches.firstOrNull { it.isVisibleToUser && it.isEditable && !it.isPassword }?.let { AccessibilityNodeInfo.obtain(it) }
                ?: findEditable(root)
            try {
                // Never write into password/credential fields through the generic text tool.
                if (target?.isPassword == true) return false
                target?.performAction(
                    AccessibilityNodeInfo.ACTION_SET_TEXT,
                    Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value) }
                ) == true
            } finally {
                target?.recycle()
                matches.forEach { it.recycle() }
            }
        } finally {
            root.recycle()
        }
    }

    private fun findEditable(node: AccessibilityNodeInfo, depth: Int = 0): AccessibilityNodeInfo? {
        if (depth > 30) return null
        if (node.isVisibleToUser && node.isEditable) return AccessibilityNodeInfo.obtain(node)
        for (index in 0 until minOf(node.childCount, 80)) {
            val child = node.getChild(index) ?: continue
            val found = findEditable(child, depth + 1)
            child.recycle()
            if (found != null) return found
        }
        return null
    }

    private fun scroll(forward: Boolean): Boolean {
        val root = rootInActiveWindow ?: return false
        return try {
            val target = findScrollable(root)
            try {
                (target ?: root).performAction(
                    if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                    else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                )
            } finally {
                target?.recycle()
            }
        } finally {
            root.recycle()
        }
    }

    private fun findScrollable(node: AccessibilityNodeInfo, depth: Int = 0): AccessibilityNodeInfo? {
        if (depth > 30) return null
        if (node.isVisibleToUser && node.isScrollable) return AccessibilityNodeInfo.obtain(node)
        for (index in 0 until minOf(node.childCount, 80)) {
            val child = node.getChild(index) ?: continue
            val found = findScrollable(child, depth + 1)
            child.recycle()
            if (found != null) return found
        }
        return null
    }

    private fun screenSummary(): String {
        val root = rootInActiveWindow ?: return "No accessible app window is active."
        return try {
            val lines = mutableListOf<String>()
            fun visit(node: AccessibilityNodeInfo, depth: Int) {
                if (depth > 18 || lines.size >= 120) return
                if (node.isVisibleToUser) {
                    val label = (node.text ?: node.contentDescription)?.toString()?.trim().orEmpty()
                    if (label.isNotBlank()) lines += label.take(240)
                }
                for (index in 0 until minOf(node.childCount, 80)) {
                    val child = node.getChild(index) ?: continue
                    visit(child, depth + 1)
                    child.recycle()
                    if (lines.size >= 120) break
                }
            }
            visit(root, 0)
            lines.distinct().joinToString("\n").ifBlank { "No readable text found on the current screen." }
        } finally {
            root.recycle()
        }
    }

    private fun swipe(direction: String): Boolean {
        val metrics = resources.displayMetrics
        val width = metrics.widthPixels.toFloat()
        val height = metrics.heightPixels.toFloat()
        val centerX = width / 2f
        val centerY = height / 2f
        val path = Path()
        when (direction.lowercase()) {
            "up" -> { path.moveTo(centerX, height * 0.78f); path.lineTo(centerX, height * 0.25f) }
            "down" -> { path.moveTo(centerX, height * 0.25f); path.lineTo(centerX, height * 0.78f) }
            "left" -> { path.moveTo(width * 0.82f, centerY); path.lineTo(width * 0.18f, centerY) }
            "right" -> { path.moveTo(width * 0.18f, centerY); path.lineTo(width * 0.82f, centerY) }
            else -> return false
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 350))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    private fun listApps(): String {
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = packageManager.queryIntentActivities(launcher, 0)
            .map { info ->
                val label = info.loadLabel(packageManager).toString()
                "$label | ${info.activityInfo.packageName}"
            }
            .distinct()
            .sortedBy { it.lowercase() }
            .take(100)
        return apps.joinToString("\n").ifBlank { "No launchable apps were found." }
    }

    private fun openApp(identifier: String): Boolean {
        if (identifier.isBlank()) return false
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val match = packageManager.queryIntentActivities(launcher, 0).firstOrNull { info ->
            info.activityInfo.packageName.equals(identifier, ignoreCase = true) ||
                info.loadLabel(packageManager).toString().equals(identifier, ignoreCase = true)
        } ?: return false
        val launch = packageManager.getLaunchIntentForPackage(match.activityInfo.packageName) ?: return false
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(launch)
        return true
    }

    private fun execute(action: String, value: String): String = when (action.lowercase()) {
        "back" -> result(performGlobalAction(GLOBAL_ACTION_BACK), "Back")
        "home" -> result(performGlobalAction(GLOBAL_ACTION_HOME), "Home")
        "notifications" -> result(performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS), "Notifications")
        "quick_settings" -> result(performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS), "Quick settings")
        "click_text" -> result(clickText(value), "Tap text: $value")
        "set_text" -> {
            val parts = value.split("|", limit = 2)
            if (parts.size != 2) "Use label|text format."
            else result(setText(parts[0], parts[1]), "Enter text in: ${parts[0]}")
        }
        "scroll_up" -> result(scroll(false), "Scroll up")
        "scroll_down" -> result(scroll(true), "Scroll down")
        "swipe" -> result(swipe(value), "Swipe $value")
        "read_screen" -> screenSummary()
        "list_apps" -> listApps()
        "open_app" -> result(openApp(value), "Open app: $value")
        else -> "Unknown action: $action"
    }

    private fun result(ok: Boolean, label: String) =
        if (ok) "$label completed." else "$label could not be completed. Check the target app and permissions."

    companion object {
        @Volatile private var instance: DeviceControlAccessibilityService? = null

        fun isEnabled(): Boolean = instance != null

        fun execute(action: String, value: String = ""): String =
            instance?.execute(action, value) ?: "Enable OpenJarvis Accessibility access first."
    }
}
