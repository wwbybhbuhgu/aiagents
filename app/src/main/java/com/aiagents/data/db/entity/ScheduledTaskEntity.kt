package com.aiagents.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 定时任务（App 内调度器）。
 *
 * @param actionType 触发动作: command(容器命令) | agent(委派子 Agent)
 * @param action 命令文本或 agent 提示词
 * @param schedule 5 段 cron 表达式（分 时 日 月 周）或 "every N" 间隔语法
 */
@Entity(tableName = "scheduled_tasks")
data class ScheduledTaskEntity(
    @PrimaryKey
    val id: String,
    val conversationId: String,
    val name: String,
    val schedule: String,
    val actionType: String,
    val action: String,
    val workspaceId: String? = null,
    val enabled: Boolean = true,
    val lastRunAt: Long? = null,
    val nextRunAt: Long,
    val createdAt: Long = System.currentTimeMillis(),
    /** 最近一次执行的输出文件名(容器内 /tool_outputs/ 下), 供 cron_get 定位 */
    val lastOutputFile: String? = null,
)
