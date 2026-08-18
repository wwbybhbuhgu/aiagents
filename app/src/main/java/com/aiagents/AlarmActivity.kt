package com.aiagents

import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.aiagents.service.AlarmOverlayService
import com.aiagents.service.ReminderScheduler
import com.aiagents.ui.theme.AIAgentsTheme
import com.aiagents.utils.NotificationUtil
import kotlinx.coroutines.delay
import org.koin.android.ext.android.inject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 严格闹钟全屏界面: 锁屏/息屏也能弹出, 循环播放系统闹钟铃声。
 * 按钮文案和贪睡时长由 AI 在创建提醒时指定(贪睡 0 分钟 = 只能点确认)。
 */
class AlarmActivity : ComponentActivity() {

    private val scheduler: ReminderScheduler by inject()

    private var mediaPlayer: MediaPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val reminderId = intent.getStringExtra(ReminderScheduler.EXTRA_REMINDER_ID)
        if (reminderId == null) {
            finish()
            return
        }
        val title = intent.getStringExtra(ReminderScheduler.EXTRA_TITLE).orEmpty()
        val message = intent.getStringExtra(ReminderScheduler.EXTRA_MESSAGE).orEmpty()
        val repeat = intent.getStringExtra(ReminderScheduler.EXTRA_REPEAT) ?: "none"
        val snoozeMinutes = intent.getLongExtra(AlarmOverlayService.EXTRA_SNOOZE_MINUTES, 5L)
        val snoozeLabel = intent.getStringExtra(AlarmOverlayService.EXTRA_SNOOZE_LABEL)
        val dismissLabel = intent.getStringExtra(AlarmOverlayService.EXTRA_DISMISS_LABEL)

        startAlarmSound()

        setContent {
            AIAgentsTheme {
                AlarmContent(
                    title = title,
                    message = message,
                    showSnooze = snoozeMinutes > 0 && repeat != "daily",
                    snoozeLabel = snoozeLabel,
                    dismissLabel = dismissLabel,
                    snoozeMinutes = snoozeMinutes,
                    onDismiss = { dismiss(reminderId) },
                    onSnooze = { snooze(reminderId, title, message, snoozeMinutes) },
                )
            }
        }
    }

    private fun dismiss(reminderId: String) {
        stopAlarmSound()
        stopRingingService(reminderId)
        NotificationUtil.cancel(this, reminderId.hashCode())
        finish()
    }

    private fun snooze(reminderId: String, title: String, message: String, snoozeMinutes: Long) {
        scheduler.scheduleSnooze(reminderId, title, message, snoozeMinutes)
        dismiss(reminderId)
    }

    private fun stopRingingService(reminderId: String) {
        runCatching {
            startService(
                Intent(this, AlarmOverlayService::class.java).apply {
                    action = AlarmOverlayService.ACTION_STOP
                    putExtra(ReminderScheduler.EXTRA_REMINDER_ID, reminderId)
                }
            )
        }
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
                setDataSource(this@AlarmActivity, uri)
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

    override fun onDestroy() {
        stopAlarmSound()
        super.onDestroy()
    }
}

@Composable
private fun AlarmContent(
    title: String,
    message: String,
    showSnooze: Boolean,
    snoozeLabel: String?,
    dismissLabel: String?,
    snoozeMinutes: Long,
    onDismiss: () -> Unit,
    onSnooze: () -> Unit,
) {
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            now = System.currentTimeMillis()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(R.string.alarm_title),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = formatTime(now),
            style = MaterialTheme.typography.displayLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
        )
        if (message.isNotBlank()) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        Spacer(modifier = Modifier.height(56.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            if (showSnooze) {
                OutlinedButton(
                    onClick = onSnooze,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(snoozeLabel?.takeIf { it.isNotBlank() }
                        ?: stringResource(R.string.alarm_snooze, snoozeMinutes))
                }
            }
            Button(
                onClick = onDismiss,
                modifier = Modifier.weight(1f),
            ) {
                Text(dismissLabel?.takeIf { it.isNotBlank() }
                    ?: stringResource(R.string.alarm_dismiss))
            }
        }
    }
}

private fun formatTime(epochMillis: Long): String {
    return Instant.ofEpochMilli(epochMillis)
        .atZone(ZoneId.systemDefault())
        .toLocalTime()
        .format(DateTimeFormatter.ofPattern("HH:mm"))
}
