package com.aiagents.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.aiagents.ai.ui.UIMessagePart
import com.aiagents.R
import com.aiagents.asr.providers.SherpaNcnnASRController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.uuid.Uuid

private const val TAG = "MicForeground"

/**
 * 麦克风前台服务 (foregroundServiceType="microphone")。
 *
 * RECORD_AUDIO 是 while-in-use 权限: 应用退到后台后, 即使已授权, 系统仍会静默拒绝打开麦克风输入流。
 * Android 12+ 又禁止应用处于后台时创建 microphone 类型前台服务。
 *
 * 因此必须: 在主 Activity 仍在前台时先把本服务起来 → 应用此后退到后台, 只要本服务存活,
 * 悬浮窗麦克风 / local_speech_recognition 工具仍在同一个进程里共享 while-in-use 麦克风访问权。
 * 悬浮窗按钮只需 sendAsrStart()/sendAsrStop(), 不要自己去 new AudioRecord。
 */
object MicForegroundSession {
    private val _recording = MutableStateFlow(false)
    val recording: StateFlow<Boolean> = _recording.asStateFlow()

    fun setRecording(recording: Boolean) {
        _recording.value = recording
    }
}

class MicForegroundService : Service() {

    companion object {
        const val ACTION_START = "com.aiagents.action.MIC_FG_START"
        const val ACTION_STOP = "com.aiagents.action.MIC_FG_STOP"
        const val ACTION_ASR_START = "com.aiagents.action.MIC_ASR_START"
        const val ACTION_ASR_STOP = "com.aiagents.action.MIC_ASR_STOP"
        const val EXTRA_SILENCE_SECONDS = "silence_seconds"
        const val EXTRA_CONVERSATION_ID = "conversation_id"
        const val NOTIFICATION_ID = 2005
        const val CHANNEL_ID = "mic_foreground"

        /** 是否已具备使用麦克风的运行时权限。 */
        @JvmStatic
        fun hasMicPermission(context: Context): Boolean =
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED

        /**
         * 在应用仍处于前台时启动本服务。已授权 RECORD_AUDIO 才可能成功;
         * 未授权时静默跳过 (等用户授权后再调用)。
         */
        @JvmStatic
        fun start(context: Context) {
            if (!hasMicPermission(context)) return
            runCatching {
                context.startForegroundService(
                    Intent(context, MicForegroundService::class.java).setAction(ACTION_START)
                )
            }
        }

        /** 悬浮窗按钮: 开始/停止录音, 均通过已运行的前台服务执行。 */
        @JvmStatic
        fun sendAsrStart(context: Context, silenceSeconds: Int, conversationId: Uuid?) {
            val intent = Intent(context, MicForegroundService::class.java)
                .setAction(ACTION_ASR_START)
                .putExtra(EXTRA_SILENCE_SECONDS, silenceSeconds)
                .putExtra(EXTRA_CONVERSATION_ID, conversationId?.toString())
            runCatching { context.startService(intent) }
        }

        @JvmStatic
        fun sendAsrStop(context: Context) {
            runCatching {
                context.startService(
                    Intent(context, MicForegroundService::class.java).setAction(ACTION_ASR_STOP)
                )
            }
        }
    }

    private var controller: SherpaNcnnASRController? = null

    /** 当前会话最近一次识别到的文本 (流式回调中更新), 用于停止时立即发送。 */
    @Volatile
    private var lastPartial: String = ""

    /** 当前 ASR 会话对应的对话, 停止时把已识别文本发到该对话。 */
    @Volatile
    private var asrConversationId: Uuid? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_ASR_START -> startAsr(intent)
            ACTION_ASR_STOP -> stopAsr(sendResult = true)
            ACTION_STOP -> {
                stopAsr()
                runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
                stopSelf()
            }
            else -> {
                // ACTION_START 及启动即进入前台
                startForegroundCompat()
            }
        }
        return START_STICKY
    }

    private fun startForegroundCompat() {
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, buildNotification(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            } else {
                0
            },
        )
    }

    private fun startAsr(intent: Intent) {
        if (controller != null || MicForegroundSession.recording.value) {
            Log.d(TAG, "asr already running, ignore")
            return
        }
        val silenceSeconds = intent.getIntExtra(EXTRA_SILENCE_SECONDS, 3)
        val conversationId = intent.getStringExtra(EXTRA_CONVERSATION_ID)?.let {
            runCatching { Uuid.parse(it) }.getOrNull()
        }
        startForegroundCompat()

        lastPartial = ""
        asrConversationId = conversationId

        val c = SherpaNcnnASRController(this)
        controller = c
        c.silenceSeconds = silenceSeconds
        MicForegroundSession.setRecording(true)
        c.onDone = done@{ text ->
            MicForegroundSession.setRecording(false)
            controller = null
            Log.d(TAG, "onDone text='$text'")
            if (text.isNotBlank() && conversationId != null) {
                sendResultToConversation(conversationId, text)
            }
        }
        c.start { partial ->
            val clean = partial.trim()
            if (clean.isNotEmpty()) lastPartial = clean
        }
    }

    private fun stopAsr(sendResult: Boolean = false) {
        val c = controller
        controller = null
        if (c != null) {
            c.onDone = null
            c.stop()
            c.dispose()
        }
        MicForegroundSession.setRecording(false)
        val text = if (sendResult) lastPartial else ""
        val convId = asrConversationId
        lastPartial = ""
        asrConversationId = null
        if (text.isNotBlank() && convId != null) {
            sendResultToConversation(convId, text)
        }
    }

    private fun sendResultToConversation(conversationId: Uuid, text: String) {
        CoroutineScope(Dispatchers.IO).launch {
            runCatching {
                val chatService = org.koin.java.KoinJavaComponent.get<ChatService>(ChatService::class.java)
                chatService.sendMessage(
                    conversationId = conversationId,
                    content = listOf(UIMessagePart.Text(text)),
                    answer = true,
                )
            }
        }
    }

    private fun buildNotification(): Notification {
        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, MicForegroundService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.screen_reader_overlay_notification_title))
            .setContentText(getString(R.string.mic_foreground_notification_desc))
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

    override fun onDestroy() {
        stopAsr()
        super.onDestroy()
    }
}