package com.aiagents.data.automation

import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 悬浮窗控制器: 让 AI 工具和用户都能操作悬浮窗的位置与最小化状态。
 * 每个悬浮窗前台服务 (聊天/浏览器) 在显示时注册自己的 [Window], 销毁时注销。
 * 工具在后台协程执行, 因此所有窗口级操作都派发到主线程。
 */
object FloatingWindowController {

    const val BROWSER = "browser"
    const val CHAT = "chat"

    /** 可注册的悬浮窗句柄。 */
    class Window(val id: String) {
        private val mainHandler = Handler(Looper.getMainLooper())

        private fun isMain() = Looper.myLooper() == Looper.getMainLooper()

        /** 最小化状态, UI 与 AI 共享 */
        var isMinimized by mutableStateOf(false)
            private set

        /** 可见性(截图时自动隐藏, 截完恢复) */
        var visible by mutableStateOf(true)
            private set

        // 当前位置 (上一次已知值)
        var x by mutableIntStateOf(0)
            private set
        var y by mutableIntStateOf(0)
            private set

        var screenWidth by mutableIntStateOf(1080)
        var screenHeight by mutableIntStateOf(2400)

        /** 当前窗口尺寸 (px)。0 表示由内容自动决定 (WRAP_CONTENT, 如最小化药丸)。 */
        var width by mutableIntStateOf(0)
            private set
        var height by mutableIntStateOf(0)
            private set

        private var onApplyMoveTo: ((x: Int, y: Int) -> Pair<Int, Int>)? = null
        private var onApplyMoveBy: ((dx: Int, dy: Int) -> Unit)? = null
        private var onApplyResize: ((width: Int, height: Int) -> Unit)? = null
        private var onApplyMinimizedChanged: (() -> Unit)? = null
        private var onApplyVisibilityChanged: ((visible: Boolean) -> Unit)? = null

        fun bind(
            onApplyMoveTo: ((x: Int, y: Int) -> Pair<Int, Int>)? = this.onApplyMoveTo,
            onApplyMoveBy: ((dx: Int, dy: Int) -> Unit)? = this.onApplyMoveBy,
            onApplyResize: ((width: Int, height: Int) -> Unit)? = this.onApplyResize,
            onApplyMinimizedChanged: (() -> Unit)? = this.onApplyMinimizedChanged,
            onApplyVisibilityChanged: ((visible: Boolean) -> Unit)? = this.onApplyVisibilityChanged,
        ) {
            this.onApplyMoveTo = onApplyMoveTo
            this.onApplyMoveBy = onApplyMoveBy
            this.onApplyResize = onApplyResize
            this.onApplyMinimizedChanged = onApplyMinimizedChanged
            this.onApplyVisibilityChanged = onApplyVisibilityChanged
        }

        /** 设置窗口宽高 (px)。主线程直接应用, 保证拖拽流畅。 */
        fun resizeTo(width: Int, height: Int) {
            if (isMain()) onApplyResize?.invoke(width, height)
            else mainHandler.post { onApplyResize?.invoke(width, height) }
        }

        /** 由 Service 记录最终应用的窗口尺寸。 */
        internal fun updateSize(width: Int, height: Int) {
            this.width = width
            this.height = height
        }

        fun setMinimizedState(minimized: Boolean) {
            if (isMinimized == minimized) return
            isMinimized = minimized
            if (isMain()) onApplyMinimizedChanged?.invoke()
            else mainHandler.post { onApplyMinimizedChanged?.invoke() }
        }

        /** 设置可见性(截图时自动隐藏, 截完恢复) */
        fun applyVisibility(visible: Boolean) {
            if (this.visible == visible) return
            this.visible = visible
            if (isMain()) onApplyVisibilityChanged?.invoke(visible)
            else mainHandler.post { onApplyVisibilityChanged?.invoke(visible) }
        }

        /** 移动到绝对位置(自动钳制到屏幕内)。主线程直接同步, 其它线程派发。 */
        fun moveTo(x: Int, y: Int) {
            if (isMain()) {
                val result = onApplyMoveTo?.invoke(x, y) ?: Pair(x, y)
                this.x = result.first
                this.y = result.second
            } else {
                mainHandler.post {
                    val result = onApplyMoveTo?.invoke(x, y) ?: Pair(x, y)
                    this.x = result.first
                    this.y = result.second
                }
            }
        }

        /** 相对移动。 */
        fun moveBy(dx: Int, dy: Int) {
            if (isMain()) onApplyMoveBy?.invoke(dx, dy)
            else mainHandler.post { onApplyMoveBy?.invoke(dx, dy) }
        }

        /** 由 Service 在应用完窗口布局后, 同步最新位置 (property setter 私有, 仅此类可直接改)。 */
        internal fun updatePosition(x: Int, y: Int) {
            this.x = x
            this.y = y
        }
    }

    private val windows = HashMap<String, Window>()

    fun register(window: Window) {
        synchronized(windows) { windows[window.id] = window }
    }

    fun unregister(id: String) {
        synchronized(windows) { windows.remove(id) }
    }

    fun get(id: String): Window? = synchronized(windows) { windows[id] }

    fun list(): List<Window> = synchronized(windows) { windows.values.toList() }
}