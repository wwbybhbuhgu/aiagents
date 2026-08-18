package com.aiagents.data.automation

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.view.accessibility.AccessibilityNodeInfo
import com.aiagents.service.ScreenTextAccessibilityService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.add
import kotlinx.serialization.json.put
import kotlin.coroutines.resume

/**
 * 无障碍自动化入口: 持有无障碍服务实例, 给 AI 工具提供"在当前屏幕执行点击/输入/手势"的能力。
 *
 * 无障碍服务是 Android 自动化能力的载体, 也是本功能的主力通道:
 * - 读取当前窗口层级(hierarchy)
 * - 模拟点击/长按/滑动/滚动/输入文本/返回键等
 * - 无需 root/adb
 */
object AutoAccessibilityController {

    @Volatile
    var service: ScreenTextAccessibilityService? = null

    fun isConnected(): Boolean = service != null

    // 节流: 防止 AI 疯狂连续触发动作(如连续按返回)造成失控循环。
    private const val NAV_COOLDOWN_MS = 1500L
    private const val ACTION_COOLDOWN_MS = 500L
    private val navThrottle = java.util.concurrent.atomic.AtomicLong(0L)
    private val actionThrottle = java.util.concurrent.atomic.AtomicLong(0L)

    private fun throttle(gate: java.util.concurrent.atomic.AtomicLong, cooldownMs: Long): Boolean {
        val now = System.currentTimeMillis()
        val prev = gate.get()
        if (now - prev < cooldownMs) return false
        return gate.compareAndSet(prev, now)
    }

    /** 在服务的主线程上执行操作(无障碍 API 要求主线程) */
    suspend fun <T> onMain(block: suspend (ScreenTextAccessibilityService) -> T): T? {
        val svc = service ?: return null
        return withContext(Dispatchers.Main) { block(svc) }
    }

    suspend fun click(x: Int, y: Int): Boolean {
        if (!throttle(actionThrottle, ACTION_COOLDOWN_MS)) return false
        return onMain { it.click(x, y) } ?: false
    }

    suspend fun longClick(x: Int, y: Int): Boolean {
        if (!throttle(actionThrottle, ACTION_COOLDOWN_MS)) return false
        return onMain { it.longClick(x, y) } ?: false
    }

    suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long): Boolean {
        if (!throttle(actionThrottle, ACTION_COOLDOWN_MS)) return false
        return onMain { it.swipe(x1, y1, x2, y2, durationMs) } ?: false
    }

    suspend fun inputText(text: String): Boolean {
        if (!throttle(actionThrottle, ACTION_COOLDOWN_MS)) return false
        return onMain { it.inputText(text) } ?: false
    }

    suspend fun pressBack(): Boolean {
        if (!throttle(navThrottle, NAV_COOLDOWN_MS)) return false
        return onMain { it.pressBack() } ?: false
    }

    suspend fun pressHome(): Boolean {
        if (!throttle(navThrottle, NAV_COOLDOWN_MS)) return false
        return onMain { it.pressHome() } ?: false
    }

    suspend fun pressRecents(): Boolean {
        if (!throttle(navThrottle, NAV_COOLDOWN_MS)) return false
        return onMain { it.pressRecents() } ?: false
    }

    suspend fun pressEnter(): Boolean {
        if (!throttle(actionThrottle, ACTION_COOLDOWN_MS)) return false
        return onMain { it.pressEnter() } ?: false
    }

    suspend fun scroll(direction: String): Boolean {
        if (!throttle(actionThrottle, ACTION_COOLDOWN_MS)) return false
        return onMain { it.scroll(direction) } ?: false
    }

    suspend fun hierarchyJson(maxDepth: Int = 20, maxNodes: Int = 300): String? =
        onMain { it.hierarchyJson(maxDepth, maxNodes) }

    suspend fun clickNodeByText(text: String): Boolean {
        if (!throttle(actionThrottle, ACTION_COOLDOWN_MS)) return false
        return onMain { it.clickNodeByText(text) } ?: false
    }

    suspend fun clickNodeById(id: String): Boolean {
        if (!throttle(actionThrottle, ACTION_COOLDOWN_MS)) return false
        return onMain { it.clickNodeById(id) } ?: false
    }

    suspend fun getWindowInfo(): String? =
        onMain { it.windowInfoJson() }
}

/** 构造并派发一个无障碍手势, 等待完成回调返回结果 */
internal suspend fun dispatchGesture(
    build: GestureDescription.Builder.() -> Unit,
): Boolean {
    val svc = AutoAccessibilityController.service ?: return false
    val gesture = GestureDescription.Builder().apply(build).build()
    return suspendCancellableCoroutine { cont ->
        val callback = object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription) {
                if (cont.isActive) cont.resume(true)
            }

            override fun onCancelled(gestureDescription: GestureDescription) {
                if (cont.isActive) cont.resume(false)
            }
        }
        val accepted = svc.dispatchGesture(gesture, callback, null)
        if (!accepted && cont.isActive) {
            cont.resume(false)
        }
    }
}

/** 无障碍节点层级 JSON 构建器(供服务复用) */
internal fun buildHierarchyJson(
    root: AccessibilityNodeInfo?,
    maxDepth: Int,
    maxNodes: Int,
): String {
    if (root == null) return """{"error":"no_active_window"}"""
    val result = kotlinx.serialization.json.buildJsonArray {
        var count = 0
        fun nodeToJson(node: AccessibilityNodeInfo, depth: Int) {
            if (count >= maxNodes || depth > maxDepth) return
            val bounds = android.graphics.Rect()
            node.getBoundsInScreen(bounds)
            if (bounds.isEmpty) return
            val clickable = node.isClickable || node.isLongClickable
            val text = node.text?.toString().orEmpty().trim()
            val desc = node.contentDescription?.toString().orEmpty().trim()
            val hasContent = text.isNotEmpty() || desc.isNotEmpty() || clickable || node.isEditable || node.isScrollable
            if (hasContent) {
                count++
                add(kotlinx.serialization.json.buildJsonObject {
                    put("depth", depth)
                    put("type", node.className?.toString()?.substringAfterLast('.') ?: "node")
                    if (node.viewIdResourceName?.isNotBlank() == true) {
                        put("id", node.viewIdResourceName.substringAfterLast('/'))
                        put("fullId", node.viewIdResourceName)
                    }
                    if (text.isNotEmpty()) put("text", text)
                    if (desc.isNotEmpty()) put("desc", desc)
                    put("clickable", node.isClickable)
                    put("longClickable", node.isLongClickable)
                    put("scrollable", node.isScrollable)
                    put("editable", node.isEditable)
                    put("checked", node.isChecked)
                    put("focused", node.isFocused)
                    put("enabled", node.isEnabled)
                    put("selected", node.isSelected)
                    put("x", bounds.centerX())
                    put("y", bounds.centerY())
                    put("w", bounds.width())
                    put("h", bounds.height())
                    put("left", bounds.left)
                    put("top", bounds.top)
                    put("right", bounds.right)
                    put("bottom", bounds.bottom)
                    val children = node.childCount
                    if (children > 0 && depth < maxDepth) {
                        put("children", kotlinx.serialization.json.buildJsonArray {
                            for (i in 0 until children) {
                                if (count >= maxNodes) break
                                val child = node.getChild(i) ?: continue
                                nodeToJson(child, depth + 1)
                                child.recycle()
                            }
                        })
                    }
                })
            } else {
                val children = node.childCount
                for (i in 0 until children) {
                    if (count >= maxNodes) break
                    val child = node.getChild(i) ?: continue
                    nodeToJson(child, depth + 1)
                    child.recycle()
                }
            }
        }
        nodeToJson(root, 0)
    }
    return result.toString()
}
