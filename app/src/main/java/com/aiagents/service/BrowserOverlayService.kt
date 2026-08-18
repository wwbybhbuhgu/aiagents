package com.aiagents.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.PixelFormat
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
import com.aiagents.ui.components.browser.BrowserOverlayContent
import com.aiagents.ui.components.browser.BrowserOverlayDragState
import com.aiagents.ui.components.ui.ProvideSafeUriHandler
import com.aiagents.ui.context.LocalSettings
import com.aiagents.ui.hooks.rememberCurrentColorMode
import com.aiagents.ui.theme.AIAgentsTheme
import com.aiagents.ui.theme.ColorMode
import com.aiagents.ui.theme.ThemeState
import org.koin.java.KoinJavaComponent

/**
 * AI 内置浏览器悬浮窗前台服务。
 * 把 [com.aiagents.data.automation.BrowserSession] 的 WebView 挂到一个小型可拖动悬浮窗上,
 * 可显示在其他应用之上且不遮住主界面, 供 browser_* 工具进行 AI 浏览器自动化。
 */
class BrowserOverlayService : Service(), LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner,
    ActivityResultRegistryOwner {

    companion object {
        const val ACTION_START = "com.aiagents.action.BROWSER_OVERLAY_START"
        const val ACTION_STOP = "com.aiagents.action.BROWSER_OVERLAY_STOP"
        const val NOTIFICATION_ID = 2004
        const val CHANNEL_ID = "browser_overlay"
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
            val intent = contract.createIntent(this@BrowserOverlayService, input)
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
            Intent(this, BrowserOverlayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.browser_overlay_notification_title))
            .setContentText(getString(R.string.browser_overlay_notification_desc))
            .setSmallIcon(R.drawable.small_icon)
            .setOngoing(true)
            .addAction(0, getString(R.string.browser_overlay_stop), stopIntent)
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, getString(R.string.browser_overlay_notification_title),
            NotificationManager.IMPORTANCE_LOW,
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun showOverlay() {        if (overlayView != null) return
        val wm = getSystemService(WindowManager::class.java) ?: return
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
            x = 24
            y = 160
        }
        overlayParams = params

        val bounds = currentWindowBounds()
        val win = FloatingWindowController.Window(FloatingWindowController.BROWSER)
        win.screenWidth = bounds.width()
        win.screenHeight = bounds.height()
        win.updatePosition(params.x, params.y)
        win.bind(
            onApplyMoveTo = { x, y ->
                val viewWidth = overlayView?.width ?: 0
                val viewHeight = overlayView?.height ?: 0
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
                val viewWidth = overlayView?.width ?: 0
                val viewHeight = overlayView?.height ?: 0
                val maxX = (bounds.width() - viewWidth).coerceAtLeast(0)
                val maxY = (bounds.height() - viewHeight).coerceAtLeast(0)
                params.x = (params.x + dx).coerceIn(0, maxX)
                params.y = (params.y + dy).coerceIn(0, maxY)
                win.updatePosition(params.x, params.y)
                try { wm.updateViewLayout(overlayView, params) } catch (_: Exception) {}
            },
            onApplyMinimizedChanged = { /* 无需额外窗口级处理 */ },
        )
        FloatingWindowController.register(win)

        val dragState = BrowserOverlayDragState(
            onDrag = { dx, dy -> win.moveBy(dx, dy) },
        )

        val view = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@BrowserOverlayService)
            setViewTreeViewModelStoreOwner(this@BrowserOverlayService)
            setViewTreeSavedStateRegistryOwner(this@BrowserOverlayService)
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
                    ProvideSafeUriHandler {
                        CompositionLocalProvider(
                            LocalActivityResultRegistryOwner provides this@BrowserOverlayService,
                            LocalSettings provides settings,
                        ) {
                            BrowserOverlayContent(
                                context = this@BrowserOverlayService,
                                dragState = dragState,
                                onClose = { stopSelf() },
                            )
                        }
                    }
                }
            }
        }
        wm.addView(view, params)
        overlayView = view
    }

    private fun currentWindowBounds(): android.graphics.Rect {
        val wm = windowManager ?: getSystemService(WindowManager::class.java)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            wm?.currentWindowMetrics?.bounds ?: android.graphics.Rect(0, 0, 1080, 2400)
        } else {
            @Suppress("DEPRECATION")
            val size = android.graphics.Point().also { wm?.defaultDisplay?.getRealSize(it) }
            android.graphics.Rect(0, 0, size.x, size.y)
        }
    }

    override fun onDestroy() {
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        FloatingWindowController.unregister(FloatingWindowController.BROWSER)
        overlayView?.let { runCatching { windowManager?.removeView(it) } }
        overlayView = null
        super.onDestroy()
    }
}
