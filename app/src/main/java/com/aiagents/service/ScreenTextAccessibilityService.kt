package com.aiagents.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.aiagents.data.automation.dispatchGesture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import com.aiagents.data.automation.AutoAccessibilityController
import com.aiagents.data.automation.buildHierarchyJson
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class ScreenTextAccessibilityService : AccessibilityService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var lastCaptureAt = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        AutoAccessibilityController.service = this
        ScreenTextRepository.markConnected(true)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == this.packageName) return
        if (isIgnoredPackage(pkg)) return

        val now = System.currentTimeMillis()
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            capture(pkg, event)
        } else if (event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            if (now - lastCaptureAt >= 800) {
                lastCaptureAt = now
                capture(pkg, event)
            }
        }
    }

    private fun capture(pkg: String, event: AccessibilityEvent) {
        scope.launch {
            val root = findAppWindowRoot(pkg) ?: rootInActiveWindow ?: return@launch
            try {
                val texts = collectTexts(root, limit = 150)
                if (texts.isNotEmpty()) {
                    ScreenTextRepository.update(pkg, texts)
                }
            } finally {
                root.recycle()
            }
        }
    }

    private fun findAppWindowRoot(targetPkg: String): AccessibilityNodeInfo? {
        for (window in windows) {
            val root = window.root ?: continue
            val pkg = root.packageName?.toString() ?: continue
            if (pkg == targetPkg) {
                return root
            }
            root.recycle()
        }
        return null
    }

    private fun isIgnoredPackage(pkg: String): Boolean {
        return pkg in IGNORED_PACKAGES ||
            pkg.contains("inputmethod") ||
            pkg.contains("keyboard") ||
            pkg.contains("ime")
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        if (AutoAccessibilityController.service === this) {
            AutoAccessibilityController.service = null
        }
        ScreenTextRepository.markConnected(false)
        return super.onUnbind(intent)
    }

    private fun collectTexts(node: AccessibilityNodeInfo, limit: Int): List<String> {
        val result = mutableListOf<String>()
        fun walk(n: AccessibilityNodeInfo, depth: Int) {
            if (result.size >= limit || depth > 40) return
            n.text?.toString()?.trim()?.takeIf { it.isNotBlank() }?.let { result.add(it) }
            n.contentDescription?.toString()?.trim()?.takeIf { it.isNotBlank() }?.let { result.add(it) }
            val children = n.childCount
            for (i in 0 until children) {
                if (result.size >= limit) return
                val child = n.getChild(i) ?: continue
                walk(child, depth + 1)
                child.recycle()
            }
        }
        walk(node, 0)
        return result.distinct()
    }

    // ---- 自动化动作(由 AutoAccessibilityController 在主线程调用) ----

    suspend fun click(x: Int, y: Int): Boolean = dispatchGesture {
        addStroke(GestureDescription.StrokeDescription(pathOf(x, y, x, y), 0L, 60L))
    }

    suspend fun longClick(x: Int, y: Int): Boolean = dispatchGesture {
        addStroke(GestureDescription.StrokeDescription(pathOf(x, y, x, y), 0L, 600L))
    }

    suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long): Boolean = dispatchGesture {
        addStroke(GestureDescription.StrokeDescription(pathOf(x1, y1, x2, y2), 0L, durationMs.coerceIn(100, 2000)))
    }

    suspend fun inputText(text: String): Boolean {
        val focused = rootInActiveWindow?.let { findFocusedOrEditable(it) }
            ?: return false
        val bundle = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, bundle)
    }

    suspend fun pressBack(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)

    suspend fun pressHome(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)

    suspend fun pressRecents(): Boolean = performGlobalAction(GLOBAL_ACTION_RECENTS)

    suspend fun pressEnter(): Boolean {
        val focused = rootInActiveWindow?.let { findFocusedOrEditable(it) }
            ?: return false
        return focused.performAction(AccessibilityNodeInfo.ACTION_CLICK) ||
            focused.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION)
    }

    suspend fun scroll(direction: String): Boolean {
        when (direction) {
            "up", "forward" -> {
                val scrollable = rootInActiveWindow?.let { findScrollable(it) }
                if (scrollable != null) {
                    return scrollable.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
                }
            }
            "down", "backward" -> {
                val scrollable = rootInActiveWindow?.let { findScrollable(it) }
                if (scrollable != null) {
                    return scrollable.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                }
            }
            else -> Unit
        }
        // left/right/top/bottom 等方向用整屏滑动手势兜底
        val dm = resources.displayMetrics
        val w = dm.widthPixels
        val h = dm.heightPixels
        return when (direction) {
            "left" -> swipe(w * 3 / 4, h / 2, w / 4, h / 2, 350)
            "right" -> swipe(w / 4, h / 2, w * 3 / 4, h / 2, 350)
            "top" -> swipe(w / 2, h / 4, w / 2, h * 3 / 4, 350)
            "bottom" -> swipe(w / 2, h * 3 / 4, w / 2, h / 4, 350)
            else -> false
        }
    }

    fun hierarchyJson(maxDepth: Int = 20, maxNodes: Int = 300): String =
        buildHierarchyJson(rootInActiveWindow, maxDepth, maxNodes)

    suspend fun clickNodeByText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val node = findNodeByText(root, text) ?: return false
        val bounds = android.graphics.Rect().also { node.getBoundsInScreen(it) }
        if (bounds.isEmpty) return false
        return click(bounds.centerX(), bounds.centerY())
    }

    suspend fun clickNodeById(id: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val nodes = root.findAccessibilityNodeInfosByViewId(id)
            ?: return false
        if (nodes.isEmpty()) return false
        val node = nodes.firstOrNull() ?: return false
        val bounds = android.graphics.Rect().also { node.getBoundsInScreen(it) }
        if (bounds.isEmpty) return false
        return click(bounds.centerX(), bounds.centerY())
    }

    fun windowInfoJson(): String {
        val display = resources.displayMetrics
        val pkg = rootInActiveWindow?.packageName?.toString() ?: ""
        return buildJsonObject {
            put("package", pkg)
            put("screenWidth", display.widthPixels)
            put("screenHeight", display.heightPixels)
            put("densityDpi", display.densityDpi)
        }.toString()
    }

    private fun findFocusedOrEditable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isFocused || node.isEditable) return node
        val children = node.childCount
        for (i in 0 until children) {
            val child = node.getChild(i) ?: continue
            val found = findFocusedOrEditable(child)
            if (found != null) return found
            child.recycle()
        }
        return null
    }

    private fun findScrollable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isScrollable) return node
        val children = node.childCount
        for (i in 0 until children) {
            val child = node.getChild(i) ?: continue
            val found = findScrollable(child)
            if (found != null) return found
            child.recycle()
        }
        return null
    }

    private fun findNodeByText(node: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? {
        val query = text.trim().lowercase()
        val direct = node.text?.toString()?.trim()?.lowercase()
        val desc = node.contentDescription?.toString()?.trim()?.lowercase()
        if ((direct != null && direct.contains(query)) || (desc != null && desc.contains(query))) {
            if (node.isClickable) return node
        }
        val children = node.childCount
        for (i in 0 until children) {
            val child = node.getChild(i) ?: continue
            val found = findNodeByText(child, query)
            if (found != null) return found
            child.recycle()
        }
        return null
    }

    companion object {
        private val IGNORED_PACKAGES = setOf(
            "com.android.systemui",
            "com.android.launcher",
            "com.android.settings",
            "com.android.inputmethod.latin",
        )
    }

    private fun pathOf(x1: Int, y1: Int, x2: Int, y2: Int): Path =
        Path().apply {
            moveTo(x1.toFloat(), y1.toFloat())
            lineTo(x2.toFloat(), y2.toFloat())
        }
}

object ScreenTextRepository {
    private var _connected: Boolean = false

    fun isConnected(): Boolean = _connected

    fun markConnected(v: Boolean) {
        _connected = v
    }

    private data class Snapshot(
        val packageName: String,
        val texts: List<String>,
        val timestamp: Long,
    )

    private val history = ArrayDeque<Snapshot>()

    fun update(packageName: String?, texts: List<String>) {
        history.addFirst(Snapshot(packageName.orEmpty(), texts, System.currentTimeMillis()))
        if (history.size > 20) history.removeLast()
    }

    fun latestText(limit: Int = 1): List<ScreenTextSnapshot> {
        return history.take(limit).map {
            ScreenTextSnapshot(
                packageName = it.packageName,
                texts = it.texts,
                timestamp = it.timestamp,
            )
        }
    }
}

data class ScreenTextSnapshot(
    val packageName: String,
    val texts: List<String>,
    val timestamp: Long,
)
