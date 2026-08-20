package com.aiagents.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.WindowManager
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.platform.ComposeView
import androidx.core.app.ActivityOptionsCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.aiagents.R
import com.aiagents.data.automation.FloatingWindowController
import com.aiagents.data.datastore.SettingsStore
import com.aiagents.ui.context.LocalSettings
import com.aiagents.ui.context.LocalToaster
import com.aiagents.ui.components.ui.OverlayState
import com.aiagents.ui.components.ui.ProvideSafeUriHandler
import com.aiagents.ui.components.ui.ScreenReaderOverlayContent
import com.aiagents.ui.hooks.rememberCurrentColorMode
import com.aiagents.ui.theme.AIAgentsTheme
import com.aiagents.ui.theme.ColorMode
import com.aiagents.ui.theme.ThemeState
import com.dokar.sonner.rememberToasterState
import org.koin.java.KoinJavaComponent

class ScreenReaderOverlayService : Service(), LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner,
    ActivityResultRegistryOwner {

    companion object {
        const val ACTION_START = "com.aiagents.action.SCREEN_READER_START"
        const val ACTION_STOP = "com.aiagents.action.SCREEN_READER_STOP"
        const val NOTIFICATION_ID = 2003
        const val CHANNEL_ID = "screen_reader_overlay"
    }

    private var overlayView: ComposeView? = null
    private var windowManager: WindowManager? = null
    private var overlayParams: WindowManager.LayoutParams? = null

    private val lifecycleRegistry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry
    private val store = ViewModelStore()
    override val viewModelStore: ViewModelStore get() = store
    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    override val savedStateRegistry: SavedStateRegistry get() = savedStateRegistryController.savedStateRegistry

    private val resultRegistry = object : ActivityResultRegistry() {
        override fun <I, O> onLaunch(
            requestCode: Int,
            contract: ActivityResultContract<I, O>,
            input: I,
            options: ActivityOptionsCompat?,
        ) {
            val intent = contract.createIntent(this@ScreenReaderOverlayService, input)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try { startActivity(intent) } catch (_: Exception) {}
        }
    }
    override val activityResultRegistry: ActivityResultRegistry get() = resultRegistry

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
                stopSelf()
                return START_NOT_STICKY
            }
        }
        // Android 可能在 stopSelf 之后仍复用同一实例再次调用 onStartCommand
        // (START_STICKY 重启 / 重复 startService), 此时 lifecycle 已到 DESTROYED,
        // 直接置 RESUMED 会抛 IllegalStateException, 需先回到 CREATED 重建。
        if (lifecycleRegistry.currentState == Lifecycle.State.DESTROYED) {
            lifecycleRegistry.currentState = Lifecycle.State.CREATED
        }
        startForegroundCompat()
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
        showOverlay()
        return START_STICKY
    }

    private fun startForegroundCompat() {
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, buildNotification(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0,
        )
    }

    private fun buildNotification(): Notification {
        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, ScreenReaderOverlayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.screen_reader_overlay_notification_title))
            .setContentText(getString(R.string.screen_reader_overlay_notification_desc))
            .setSmallIcon(R.drawable.small_icon)
            .setOngoing(true)
            .addAction(0, getString(R.string.screen_reader_overlay_stop), stopIntent)
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, getString(R.string.screen_reader_overlay_notification_title),
            NotificationManager.IMPORTANCE_LOW,
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun setFocusable(focusable: Boolean) {
        val params = overlayParams ?: return
        val view = overlayView ?: return
        if (focusable) {
            params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        } else {
            params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        }
        try { windowManager?.updateViewLayout(view, params) } catch (_: Exception) {}
    }

    private fun screenWidth(): Int = currentScreenBounds().width()

    private fun screenHeight(): Int = currentScreenBounds().height()

    private fun currentScreenBounds(): Rect {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            wm.currentWindowMetrics.bounds
        } else {
            @Suppress("DEPRECATION")
            val size = android.graphics.Point().also { wm.defaultDisplay.getRealSize(it) }
            Rect(0, 0, size.x, size.y)
        }
    }

    private fun showOverlay() {
        if (overlayView != null) return
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        windowManager = wm

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 20
            y = 200
        }
        overlayParams = params

        val bounds = currentScreenBounds()
        val win = FloatingWindowController.Window(FloatingWindowController.CHAT)
        win.screenWidth = bounds.width()
        win.screenHeight = bounds.height()

        // 展开时默认窗口尺寸 (px): 320x520dp, 钳制到屏幕内
        val density = resources.displayMetrics.density
        var expandedW = (320 * density).toInt().coerceAtMost(bounds.width())
        var expandedH = (520 * density).toInt().coerceAtMost(bounds.height())
        win.updatePosition(params.x, params.y)
        win.updateSize(expandedW, expandedH)

        fun applySize(width: Int, height: Int) {
            params.width = width
            params.height = height
            try { wm.updateViewLayout(overlayView, params) } catch (_: Exception) {}
        }

        params.width = expandedW
        params.height = expandedH

        win.bind(
            onApplyMoveTo = { x, y ->
                val viewWidth = overlayView?.width ?: win.width
                val viewHeight = overlayView?.height ?: win.height
                val maxX = (bounds.width() - viewWidth).coerceAtLeast(0)
                val maxY = (bounds.height() - viewHeight).coerceAtLeast(0)
                val cx = x.coerceIn(0, maxX)
                val cy = y.coerceIn(0, maxY)
                params.x = cx
                params.y = cy
                try { wm.updateViewLayout(overlayView, params) } catch (_: Exception) {}
                Pair(cx, cy)
            },
            onApplyMoveBy = { dx, dy ->
                val viewWidth = overlayView?.width ?: win.width
                val viewHeight = overlayView?.height ?: win.height
                val maxX = (bounds.width() - viewWidth).coerceAtLeast(0)
                val maxY = (bounds.height() - viewHeight).coerceAtLeast(0)
                params.x = (params.x + dx).coerceIn(0, maxX)
                params.y = (params.y + dy).coerceIn(0, maxY)
                win.updatePosition(params.x, params.y)
                try { wm.updateViewLayout(overlayView, params) } catch (_: Exception) {}
            },
            onApplyResize = { width, height ->
                if (!win.isMinimized) {
                    val minW = 220
                    val minH = 160
                    val newW = width.coerceIn(minW, bounds.width())
                    val newH = height.coerceIn(minH, bounds.height())
                    expandedW = newW
                    expandedH = newH
                    win.updateSize(newW, newH)
                    // 保持当前左上角不变, 只允许右下延伸, 避免窗口跑出屏幕
                    params.width = newW
                    params.height = newH
                    try { wm.updateViewLayout(overlayView, params) } catch (_: Exception) {}
                }
            },
            onApplyMinimizedChanged = {
                if (win.isMinimized) {
                    // 最小化: 回归内容包裹 (药丸自身尺寸)
                    applySize(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT)
                } else {
                    // 恢复展开尺寸
                    applySize(expandedW, expandedH)
                }
            },
            onApplyVisibilityChanged = { visible ->
                params.alpha = if (visible) 1f else 0f
                try { wm.updateViewLayout(overlayView, params) } catch (_: Exception) {}
            },
        )
        FloatingWindowController.register(win)

        val state = OverlayState(
            onDrag = { dx, dy -> win.moveBy(dx, dy) },
            onFocusToggle = { focusable -> setFocusable(focusable) },
            onResize = { w, h -> win.resizeTo(w, h) },
        )

        val view = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@ScreenReaderOverlayService)
            setViewTreeViewModelStoreOwner(this@ScreenReaderOverlayService)
            setViewTreeSavedStateRegistryOwner(this@ScreenReaderOverlayService)
            setContent {
                val settingsStore: SettingsStore = remember { KoinJavaComponent.get(SettingsStore::class.java) }
                val settings by settingsStore.settingsFlow.collectAsState()
                // 跟随主界面主题: 尊重显式 colorMode; SYSTEM 时用 ThemeState(主界面解析结果)
                // 或系统配置兜底, 避免 Service ComposeView 里配置过期导致浅色黑字
                val colorModePref = rememberCurrentColorMode()
                val isDark = ThemeState.isDark ||
                    isSystemInDarkTheme() ||
                    (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
                val effectiveColorMode = when (colorModePref) {
                    ColorMode.LIGHT -> ColorMode.LIGHT
                    ColorMode.DARK -> ColorMode.DARK
                    ColorMode.SYSTEM -> if (isDark) ColorMode.DARK else ColorMode.LIGHT
                }
                AIAgentsTheme(colorMode = effectiveColorMode) {
                    val toasterState = rememberToasterState()
                    ProvideSafeUriHandler {
                        CompositionLocalProvider(
                            LocalActivityResultRegistryOwner provides this@ScreenReaderOverlayService,
                            LocalSettings provides settings,
                            LocalToaster provides toasterState,
                        ) {
                            ScreenReaderOverlayContent(
                                context = this@ScreenReaderOverlayService,
                                overlayState = state,
                            )
                        }
                    }
                }
            }
        }
        wm.addView(view, params)
        overlayView = view
    }

    override fun onDestroy() {
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        FloatingWindowController.unregister(FloatingWindowController.CHAT)
        overlayView?.let { runCatching { windowManager?.removeView(it) } }
        overlayView = null
        super.onDestroy()
    }
}
