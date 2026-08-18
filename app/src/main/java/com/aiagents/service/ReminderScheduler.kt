package com.aiagents.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.aiagents.data.db.dao.ReminderDAO
import com.aiagents.data.db.entity.ReminderEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.java.KoinJavaComponent

/**
 * 提醒调度器: 用 AlarmManager 落地 AI 创建的提醒。
 *
 * requestCode 用 reminder.id 的稳定哈希, 同一 id 的 PendingIntent 用 FLAG_UPDATE_CURRENT,
 * 因此"改/删"只需用相同 id 重新 set 或 cancel。
 *
 * 精确闹钟权限(SCHEDULE_EXACT_ALARM):
 * - 已授权 → setExactAndAllowWhileIdle, 设备休眠也能准点触发
 * - 未授权 → 降级为 setWindow(60s 容差), 保证提醒不丢
 */
class ReminderScheduler(
    private val context: Context,
    private val dao: ReminderDAO,
) : KoinComponent {

    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    private val scope: CoroutineScope
        get() = KoinJavaComponent.get(com.aiagents.AppScope::class.java)

    companion object {
        const val ACTION_REMINDER_ALARM = "com.aiagents.action.REMINDER_ALARM"
        const val EXTRA_REMINDER_ID = "reminderId"
        const val EXTRA_TITLE = "title"
        const val EXTRA_MESSAGE = "message"
        const val EXTRA_TYPE = "type"
        const val EXTRA_REPEAT = "repeat"

        /** 贪睡闹钟与正式闹钟的 PendingIntent requestCode 偏移, 两者互不覆盖 */
        const val SNOOZE_REQUEST_CODE_OFFSET = 1_000_000
    }

    fun canScheduleExact(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()
    }

    /** 跳转到"闹钟和提醒"特殊应用访问页; 无匹配 Activity 时降级到应用详情页 */
    fun openExactAlarmSettings(): Boolean {
        val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (intent.resolveActivity(context.packageManager) != null) {
            runCatching { context.startActivity(intent) }
            return true
        }
        val fallback = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(fallback) }
        return false
    }

    /** 调度单条提醒(增/改): 相同 id 重复 set 即覆盖 */
    fun schedule(reminder: ReminderEntity) {
        if (!reminder.enabled) {
            cancel(reminder.id)
            return
        }
        val pi = buildPendingIntent(reminder)
        val now = System.currentTimeMillis()
        val triggerAt = reminder.triggerAtMillis
        if (triggerAt <= now) {
            // 已到期: 立即触发
            pi.send()
            return
        }
        if (canScheduleExact()) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
        } else {
            alarmManager.setWindow(AlarmManager.RTC_WAKEUP, triggerAt, FALLBACK_WINDOW_MS, pi)
        }
    }

    /** 取消单条提醒(含其贪睡闹钟) */
    fun cancel(reminderId: String) {
        cancelPendingIntent(reminderId, 0)
        cancelPendingIntent(reminderId, SNOOZE_REQUEST_CODE_OFFSET)
    }

    /** 贪睡: 在现有闹钟之外再排一个 minutes 分钟后的一次性提醒(不影响 daily 重排) */
    fun scheduleSnooze(reminderId: String, title: String, message: String, minutes: Long) {
        val pi = buildPendingIntent(
            ReminderEntity(
                id = reminderId,
                conversationId = "",
                title = title,
                message = message,
                triggerAtMillis = 0,
                type = "alarm",
            ),
            requestCodeOffset = SNOOZE_REQUEST_CODE_OFFSET,
        )
        val triggerAt = System.currentTimeMillis() + minutes * 60_000L
        if (canScheduleExact()) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
        } else {
            alarmManager.setWindow(AlarmManager.RTC_WAKEUP, triggerAt, FALLBACK_WINDOW_MS, pi)
        }
    }

    /** 启动/开机后重排所有启用的提醒 */
    fun rescheduleAll() {
        scope.launch {
            runCatching {
                dao.getAll().filter { it.enabled }.forEach { schedule(it) }
            }
        }
    }

    private fun buildPendingIntent(
        reminder: ReminderEntity,
        requestCodeOffset: Int = 0,
    ): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            action = ACTION_REMINDER_ALARM
            putExtra(EXTRA_REMINDER_ID, reminder.id)
            putExtra(EXTRA_TITLE, reminder.title)
            putExtra(EXTRA_MESSAGE, reminder.message)
            putExtra(EXTRA_TYPE, reminder.type)
            putExtra(EXTRA_REPEAT, reminder.repeat)
        }
        return PendingIntent.getBroadcast(
            context,
            reminder.id.hashCode() + requestCodeOffset,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun cancelPendingIntent(reminderId: String, requestCodeOffset: Int) {
        val pi = buildPendingIntent(
            ReminderEntity(
                id = reminderId,
                conversationId = "",
                title = "",
                message = "",
                triggerAtMillis = 0,
            ),
            requestCodeOffset = requestCodeOffset,
        )
        alarmManager.cancel(pi)
    }

    private val FALLBACK_WINDOW_MS = 60_000L
}
