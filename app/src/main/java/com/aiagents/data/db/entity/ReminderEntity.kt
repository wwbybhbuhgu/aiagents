package com.aiagents.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * AI Agent 提醒(闹钟)记录。由 AI 工具创建, AlarmManager 调度触发。
 *
 * @param repeat 重复规则: "none"(一次性) | "daily"(每天同一时刻)
 * @param type 触发方式: "notification"(仅通知, 默认) | "alarm"(严格闹钟: 全屏 + 闹钟铃声)
 * @param snoozeMinutes 贪睡时长(分钟); 0 表示不允许贪睡, 只能点确认
 * @param snoozeLabel 贪睡按钮文案(AI 指定); 空则用默认文案
 * @param dismissLabel 确认/关闭按钮文案(AI 指定); 空则用默认文案
 */
@Entity(tableName = "reminders")
data class ReminderEntity(
    @PrimaryKey
    val id: String,
    val conversationId: String,
    val title: String,
    val message: String,
    /** 下一次触发时间(epoch millis) */
    val triggerAtMillis: Long,
    val repeat: String = "none",
    @ColumnInfo(defaultValue = "notification")
    val type: String = "notification",
    @ColumnInfo(defaultValue = "5")
    val snoozeMinutes: Long = 5,
    val snoozeLabel: String? = null,
    val dismissLabel: String? = null,
    val enabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    val lastTriggeredAt: Long? = null,
)
