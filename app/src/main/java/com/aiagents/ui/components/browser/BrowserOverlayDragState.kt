package com.aiagents.ui.components.browser

/** 悬浮窗拖动状态。 */
class BrowserOverlayDragState(
    val onDrag: (dx: Int, dy: Int) -> Unit,
) {
    fun dragBy(dx: Int, dy: Int) = onDrag(dx, dy)
}
