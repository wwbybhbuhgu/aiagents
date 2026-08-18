package com.aiagents.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
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
import com.aiagents.ALARM_NOTIFICATION_CHANNEL_ID
import com.aiagents.AlarmActivity
import com.aiagents.R
import com.aiagents.ui.components.ui.ProvideSafeUriHandler
import com.aiagents.ui.theme.AIAgentsTheme
import com.aiagents.utils.NotificationUtil
import org.koin.java.KoinJavaComponent
import kotlin.math.roundToInt

/**
 * 严格闹钟服务: 触发时立即
 * 1. 用 MediaPlayer 循环播放系统闹钟铃声(STREAM_ALARM, DND 也响)
 * 2. 通过悬浮窗(TYPE_APPLICATION_OVERLAY)在任意应用上方弹出闹钟界面(Compose + AIAgentsTheme,
 *    自动适配深色/浅色), 无需点击通知
 * 3. 前台通知带全屏意图作为息屏/锁屏时的兜底入口
 */
class AlarmOverlayService : Service(), LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry
    private val store = ViewModelStore()
    override val viewModelStore: ViewModelStore get() = store
    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    override val savedStateRegistry: SavedStateRegistry get() = savedStateRegistryController.savedStateRegistry

    private var mediaPlayer: MediaPlayer? = null
    private var overlayView: View? = null
    private var windowManager: WindowManager? = null
    private var overlayParams: WindowManager.LayoutParams? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopAlarm()
            return START_NOT_STICKY
        }

        val reminderId = intent?.getStringExtra(ReminderScheduler.EXTRA_REMINDER_ID)
        if (reminderId == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        val title = intent.getStringExtra(ReminderScheduler.EXTRA_TITLE).orEmpty()
        val message = intent.getStringExtra(ReminderScheduler.EXTRA_MESSAGE).orEmpty()
        val snoozeMinutes = intent.getLongExtra(EXTRA_SNOOZE_MINUTES, 5L)
        val snoozeLabel = intent.getStringExtra(EXTRA_SNOOZE_LABEL)
        val dismissLabel = intent.getStringExtra(EXTRA_DISMISS_LABEL)

        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
        ServiceCompat.startForeground(
            this,
            reminderId.hashCode(),
            buildNotification(this, reminderId, title, message, dismissLabel),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0,
        )
        startAlarmSound()
        showOverlay(reminderId, title, message, snoozeMinutes, snoozeLabel, dismissLabel)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        stopAlarmSound()
        removeOverlay()
        super.onDestroy()
    }

    private fun showOverlay(
        reminderId: String,
        title: String,
        message: String,
        snoozeMinutes: Long,
        snoozeLabel: String?,
        dismissLabel: String?,
    ) {
        if (overlayView != null) return
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        windowManager = wm

        val width = (resources.displayMetrics.widthPixels - dp(32f)).roundToInt()
        val params = WindowManager.LayoutParams(
            width,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(160f).roundToInt()
        }
        overlayParams = params

        val view = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@AlarmOverlayService)
            setViewTreeViewModelStoreOwner(this@AlarmOverlayService)
            setViewTreeSavedStateRegistryOwner(this@AlarmOverlayService)
            setContent {
                AIAgentsTheme {
                    ProvideSafeUriHandler {
                        AlarmOverlayContent(
                            title = title,
                            message = message,
                            showSnooze = snoozeMinutes > 0,
                            snoozeLabel = snoozeLabel,
                            dismissLabel = dismissLabel,
                            snoozeMinutes = snoozeMinutes,
                            onDismiss = { onDismissed(reminderId) },
                            onSnooze = { onSnoozed(reminderId, title, message, snoozeMinutes) },
                        )
                    }
                }
            }
        }

        try {
            wm.addView(view, params)
            overlayView = view
        } catch (_: SecurityException) {
            // 悬浮窗权限未授予: 直接拉起全屏闹钟界面兜底
            overlayView = null
            startActivity(
                Intent(this, AlarmActivity::class.java).apply {
                    putExtra(ReminderScheduler.EXTRA_REMINDER_ID, reminderId)
                    putExtra(ReminderScheduler.EXTRA_TITLE, title)
                    putExtra(ReminderScheduler.EXTRA_MESSAGE, message)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                }
            )
        }
    }

    private fun onDismissed(reminderId: String) {
        stopAlarmSound()
        removeOverlay()
        NotificationUtil.cancel(this, reminderId.hashCode())
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun onSnoozed(reminderId: String, title: String, message: String, snoozeMinutes: Long) {
        val scheduler = KoinJavaComponent.get<ReminderScheduler>(ReminderScheduler::class.java)
        scheduler.scheduleSnooze(reminderId, title, message, snoozeMinutes)
        onDismissed(reminderId)
    }

    private fun stopAlarm() {
        stopAlarmSound()
        removeOverlay()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun removeOverlay() {
        overlayView?.let { runCatching { windowManager?.removeView(it) } }
        overlayView = null
    }

    private fun startAlarmSound() {
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            ?: return
        runCatching {
            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                setDataSource(this@AlarmOverlayService, uri)
                isLooping = true
                setVolume(1f, 1f)
                prepare()
                start()
            }
        }
    }

    private fun stopAlarmSound() {
        runCatching { mediaPlayer?.let { if (it.isPlaying) it.stop() } }
        mediaPlayer?.release()
        mediaPlayer = null
    }

    private fun buildNotification(
        context: Context,
        reminderId: String,
        title: String,
        message: String,
        dismissLabel: String?,
    ): Notification {
        val openIntent = Intent(context, AlarmActivity::class.java).apply {
            putExtra(ReminderScheduler.EXTRA_REMINDER_ID, reminderId)
            putExtra(ReminderScheduler.EXTRA_TITLE, title)
            putExtra(ReminderScheduler.EXTRA_MESSAGE, message)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val openPendingIntent = PendingIntent.getActivity(
            context,
            reminderId.hashCode(),
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = Intent(context, AlarmOverlayService::class.java).apply {
            action = ACTION_STOP
            putExtra(ReminderScheduler.EXTRA_REMINDER_ID, reminderId)
        }
        val stopPendingIntent = PendingIntent.getService(
            context,
            reminderId.hashCode() + 1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(context, ALARM_NOTIFICATION_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(message)
            .setSmallIcon(R.drawable.small_icon)
            .setOngoing(true)
            .setAutoCancel(false)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setFullScreenIntent(openPendingIntent, true)
            .setContentIntent(openPendingIntent)
            .addAction(
                0,
                dismissLabel?.takeIf { it.isNotBlank() } ?: context.getString(R.string.alarm_dismiss),
                stopPendingIntent,
            )
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .build()
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    companion object {
        const val ACTION_STOP = "com.aiagents.action.STOP_ALARM"
        const val EXTRA_SNOOZE_MINUTES = "snoozeMinutes"
        const val EXTRA_SNOOZE_LABEL = "snoozeLabel"
        const val EXTRA_DISMISS_LABEL = "dismissLabel"

        fun buildStartIntent(
            context: Context,
            reminderId: String,
            title: String,
            message: String,
            snoozeMinutes: Long,
            snoozeLabel: String?,
            dismissLabel: String?,
        ): Intent = Intent(context, AlarmOverlayService::class.java).apply {
            putExtra(ReminderScheduler.EXTRA_REMINDER_ID, reminderId)
            putExtra(ReminderScheduler.EXTRA_TITLE, title)
            putExtra(ReminderScheduler.EXTRA_MESSAGE, message)
            putExtra(EXTRA_SNOOZE_MINUTES, snoozeMinutes)
            putExtra(EXTRA_SNOOZE_LABEL, snoozeLabel)
            putExtra(EXTRA_DISMISS_LABEL, dismissLabel)
        }
    }
}

@Composable
private fun AlarmOverlayContent(
    title: String,
    message: String,
    showSnooze: Boolean,
    snoozeLabel: String?,
    dismissLabel: String?,
    snoozeMinutes: Long,
    onDismiss: () -> Unit,
    onSnooze: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(12.dp, RoundedCornerShape(16.dp))
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(20.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        if (message.isNotBlank()) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(modifier = Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (showSnooze) {
                OutlinedButton(
                    onClick = onSnooze,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(
                        snoozeLabel?.takeIf { it.isNotBlank() }
                            ?: stringResource(R.string.alarm_snooze, snoozeMinutes)
                    )
                }
            }
            Button(
                onClick = onDismiss,
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    dismissLabel?.takeIf { it.isNotBlank() }
                        ?: stringResource(R.string.alarm_dismiss)
                )
            }
        }
    }
}
