package com.aiagents.service

import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.aiagents.R
import com.aiagents.CONTAINER_NOTIFICATION_CHANNEL_ID
import com.aiagents.data.repository.WorkspaceRepository
import com.aiagents.workspace.PersistentShellRunner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

private const val TAG = "ContainerService"

/**
 * 常驻容器前台服务：持有通知使 App 进程在后台不被回收，
 * 从而让 [PersistentShellRunner] 里的常驻 proot 会话持续存活
 * （后台任务、环境状态、定时任务依赖它）。
 */
class ContainerService : Service() {

    companion object {
        const val ACTION_START = "com.aiagents.action.CONTAINER_START"
        const val ACTION_STOP = "com.aiagents.action.CONTAINER_STOP"
        const val NOTIFICATION_ID = 2002
    }

    private val persistentShellRunner: PersistentShellRunner by inject()
    private val workspaceRepository: WorkspaceRepository by inject()

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopAllContainers()
                stopSelf()
            }

            else -> {
                if (!startForegroundCompat()) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                startObserving()
            }
        }
        return START_STICKY
    }

    private fun startForegroundCompat(): Boolean {
        val notification = NotificationCompat.Builder(this, CONTAINER_NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.container_service_notification_title))
            .setContentText(getString(R.string.container_service_notification_text))
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .build()
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start foreground service", e)
            false
        }
    }

    /** 启动后预热所有已 READY 的工作区常驻会话 */
    private fun startObserving() {
        serviceScope.launch {
            runCatching {
                val workspaces = workspaceRepository.listFlow().first()
                workspaces.forEach { ws ->
                    if (ws.shellStatus == com.aiagents.workspace.WorkspaceShellStatus.READY.name) {
                        runCatching {
                            workspaceRepository.executeCommand(ws.id, "echo container-ready", timeoutMillis = 5000)
                        }
                    }
                }
                Log.i(TAG, "container service started, active sessions warmed up")
            }
        }
    }

    private fun stopAllContainers() {
        runCatching { persistentShellRunner.stopAll() }
    }

    override fun onDestroy() {
        stopAllContainers()
        serviceScope.cancel()
        super.onDestroy()
    }
}
