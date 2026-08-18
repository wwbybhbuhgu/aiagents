package com.aiagents.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.Intent.FLAG_ACTIVITY_NEW_TASK
import androidx.core.app.NotificationCompat
import com.aiagents.R
import com.aiagents.ALARM_NOTIFICATION_CHANNEL_ID
import com.aiagents.AlarmActivity
import com.aiagents.REMINDER_NOTIFICATION_CHANNEL_ID
import com.aiagents.RouteActivity
import com.aiagents.data.db.dao.ReminderDAO
import com.aiagents.data.db.entity.ReminderEntity
import com.aiagents.utils.NotificationUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.java.KoinJavaComponent

/**
 * 提醒广播接收器:
 * - 收到闹钟触发广播 → 发通知, 若是 daily 重复则计算下次触发并重排
 * - 收到开机广播 → 重排所有启用的提醒(重启后闹钟丢失)
 */
class ReminderReceiver : BroadcastReceiver(), KoinComponent {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ReminderScheduler.ACTION_REMINDER_ALARM -> onAlarmFired(context, intent)
            Intent.ACTION_BOOT_COMPLETED -> {
                val scheduler = KoinJavaComponent.get<ReminderScheduler>(ReminderScheduler::class.java)
                scheduler.rescheduleAll()
            }
        }
    }

    private fun onAlarmFired(context: Context, intent: Intent) {
        val reminderId = intent.getStringExtra(ReminderScheduler.EXTRA_REMINDER_ID) ?: return
        val fallbackType = intent.getStringExtra(ReminderScheduler.EXTRA_TYPE)

        // 后台执行: 查询 DB 决定触发方式(通知/严格闹钟), 并更新状态/重排
        CoroutineScope(Dispatchers.IO).launch {
            runCatching {
                val dao = KoinJavaComponent.get<ReminderDAO>(ReminderDAO::class.java)
                val scheduler = KoinJavaComponent.get<ReminderScheduler>(ReminderScheduler::class.java)
                val reminder = dao.getById(reminderId)
                val title = reminder?.title?.takeIf { it.isNotBlank() }
                    ?: intent.getStringExtra(ReminderScheduler.EXTRA_TITLE)?.takeIf { it.isNotBlank() }
                    ?: context.getString(R.string.reminder_notification_title)
                val message = reminder?.message?.takeIf { it.isNotBlank() }
                    ?: intent.getStringExtra(ReminderScheduler.EXTRA_MESSAGE).orEmpty()

                if (reminder?.type == "alarm" || (reminder == null && fallbackType == "alarm")) {
                    notifyAlarm(
                        context,
                        reminderId,
                        title,
                        message,
                        reminder?.snoozeMinutes ?: 5L,
                        reminder?.snoozeLabel,
                        reminder?.dismissLabel,
                    )
                } else {
                    notifyReminder(context, reminderId, title, message)
                }

                val current = dao.getById(reminderId) ?: return@runCatching
                if (current.repeat == "daily") {
                    val next = computeNextDailyTrigger(current.triggerAtMillis)
                    val updated = current.copy(
                        triggerAtMillis = next,
                        lastTriggeredAt = System.currentTimeMillis(),
                    )
                    dao.upsert(updated)
                    scheduler.schedule(updated)
                } else {
                    dao.upsert(
                        current.copy(
                            enabled = false,
                            lastTriggeredAt = System.currentTimeMillis(),
                        )
                    )
                }
            }
        }
    }

    /** 普通定时通知: 沿用 reminders 渠道(默认通知音 + 震动) */
    private fun notifyReminder(context: Context, reminderId: String, title: String, message: String) {
        NotificationUtil.notify(
            context,
            REMINDER_NOTIFICATION_CHANNEL_ID,
            reminderId.hashCode(),
        ) {
            this.title = title
            content = message
            autoCancel = true
            useDefaults = true
            contentIntent = buildOpenAppIntent(context)
        }
    }

    /** 严格闹钟: 启动响铃前台服务(应用被杀也能在后台持续响铃), 失败时降级为普通闹钟通知 */
    private fun notifyAlarm(
        context: Context,
        reminderId: String,
        title: String,
        message: String,
        snoozeMinutes: Long,
        snoozeLabel: String?,
        dismissLabel: String?,
    ) {
        val started = runCatching {
            context.startForegroundService(
                AlarmOverlayService.buildStartIntent(
                    context,
                    reminderId,
                    title,
                    message,
                    snoozeMinutes,
                    snoozeLabel,
                    dismissLabel,
                )
            )
            true
        }.getOrDefault(false)
        if (!started) {
            fallbackAlarmNotification(context, reminderId, title, message)
        }
    }

    /** 降级方案: 前台服务启动失败时直接发一条带全屏意图的闹钟通知 */
    private fun fallbackAlarmNotification(context: Context, reminderId: String, title: String, message: String) {
        val activityIntent = Intent(context, AlarmActivity::class.java).apply {
            putExtra(ReminderScheduler.EXTRA_REMINDER_ID, reminderId)
            putExtra(ReminderScheduler.EXTRA_TITLE, title)
            putExtra(ReminderScheduler.EXTRA_MESSAGE, message)
            addFlags(FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val pendingIntent = android.app.PendingIntent.getActivity(
            context,
            reminderId.hashCode(),
            activityIntent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
        )
        NotificationUtil.notify(
            context,
            ALARM_NOTIFICATION_CHANNEL_ID,
            reminderId.hashCode(),
        ) {
            this.title = title
            content = message
            autoCancel = false
            ongoing = true
            priority = NotificationCompat.PRIORITY_MAX
            visibility = NotificationCompat.VISIBILITY_PUBLIC
            category = NotificationCompat.CATEGORY_ALARM
            useBigTextStyle = true
            contentIntent = pendingIntent
            fullScreenIntent = pendingIntent
        }
    }

    private fun buildOpenAppIntent(context: Context): android.app.PendingIntent {
        return android.app.PendingIntent.getActivity(
            context,
            0,
            Intent(context, RouteActivity::class.java).addFlags(FLAG_ACTIVITY_NEW_TASK),
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /** 计算"每天同一时刻"的下一次触发: 若今天时刻已过则明天, 否则今天 */
    private fun computeNextDailyTrigger(previous: Long): Long {
        val zone = java.time.ZoneId.systemDefault()
        val prev = java.time.Instant.ofEpochMilli(previous).atZone(zone)
        val now = java.time.ZonedDateTime.now(zone)
        var candidate = prev.toLocalTime().atDate(now.toLocalDate()).atZone(zone)
        if (!candidate.toInstant().isAfter(now.toInstant())) {
            candidate = candidate.plusDays(1)
        }
        return candidate.toInstant().toEpochMilli()
    }
}
