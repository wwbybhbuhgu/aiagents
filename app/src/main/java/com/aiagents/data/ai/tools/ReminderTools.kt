package com.aiagents.data.ai.tools

import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import com.aiagents.ai.core.InputSchema
import com.aiagents.ai.core.Tool
import com.aiagents.ai.ui.UIMessagePart
import com.aiagents.data.db.dao.ReminderDAO
import com.aiagents.data.db.entity.ReminderEntity
import com.aiagents.service.ReminderScheduler
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.uuid.Uuid

/**
 * AI 提醒工具集: AI 可通过自然语言创建/管理闹钟提醒。
 * 触发由 ReminderScheduler(AlarmManager) 落地, 应用被杀也能准点提醒。
 */
fun buildReminderTools(
    dao: ReminderDAO,
    scheduler: ReminderScheduler,
    conversationId: String,
): List<Tool> = listOf(
    Tool(
        name = "reminder_create",
        description = """
            创建设备提醒(闹钟), 即使应用关闭也会准时触发。
            `when` 支持三种格式: epoch 毫秒、"yyyy-MM-dd HH:mm"(本地时间)、"in N minutes/hours/days"
            (例如 "in 30 minutes"、"in 2 hours")。
            `repeat` 取值: "none"(一次性, 默认) | "daily"(每天同一时刻重复)。
            `type` 取值: "notification"(定时通知, 默认, 只弹通知条) | "alarm"(严格闹钟:
            锁屏/息屏也能弹出全屏响铃界面, 用于起床闹钟、重要且紧急的提醒)。
            严格闹钟的两个按钮文案必须由 AI 按提醒场景填写, 不要用通用词"贪睡/关闭":
            `snoozeMinutes` 贪睡(稍后)时长, 填 0 表示不允许稍后(只能点确认);
            `snoozeLabel` 稍后按钮上的文字, 要贴合场景(睡觉→"再睡 10 分钟"、午饭→"再吃一会儿"、
            学习→"再休息 5 分钟"等等);
            `dismissLabel` 确认/完成按钮上的文字, 也要贴合场景(睡觉→"起床"、午饭→"开始吃饭"、
            学习→"开始专注"等等)。
            当 type=alarm 时 snoozeMinutes/snoozeLabel/dismissLabel 三个参数都必须提供,
            否则创建会失败; type=notification 时忽略这三个参数。
            `title` 是提醒标题, `message` 是正文。
            若应用缺少精确闹钟权限, 提醒会按最多 1 分钟容差调度, 仅在相关时在回复中提及。
        """.trimIndent(),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("title", buildJsonObject {
                        put("type", "string")
                        put("description", "提醒标题(简短)")
                    })
                    put("message", buildJsonObject {
                        put("type", "string")
                        put("description", "提醒正文内容")
                    })
                    put("when", buildJsonObject {
                        put("type", "string")
                        put("description", "触发时间: epoch 毫秒 / yyyy-MM-dd HH:mm / 'in N minutes/hours/days'")
                    })
                    put("repeat", buildJsonObject {
                        put("type", "string")
                        put("enum", buildJsonArray {
                            add("none")
                            add("daily")
                        })
                        put("description", "none = 一次性(默认), daily = 每天同一时刻重复")
                    })
                    put("type", buildJsonObject {
                        put("type", "string")
                        put("enum", buildJsonArray {
                            add("notification")
                            add("alarm")
                        })
                        put("description", "notification = 定时通知(默认), alarm = 严格全屏响铃闹钟")
                    })
                    put("snoozeMinutes", buildJsonObject {
                        put("type", "integer")
                        put("description", "严格闹钟贪睡时长(分钟), 0 表示不允许贪睡只能点确认, 默认 5")
                    })
                    put("snoozeLabel", buildJsonObject {
                        put("type", "string")
                        put("description", "贪睡按钮文字(可结合场景, 如'再玩 5 分钟'), 留空用默认")
                    })
                    put("dismissLabel", buildJsonObject {
                        put("type", "string")
                        put("description", "确认/关闭按钮文字(可结合场景, 如'开始专注'), 留空用默认")
                    })
                },
                required = listOf("title", "when"),
            )
        },
        needsApproval = { false },
        execute = {
            val params = it.jsonObject
            val title = params["title"]?.jsonPrimitive?.contentOrNull?.takeIf { t -> t.isNotBlank() }
                ?: error("title is required")
            val whenDesc = params["when"]?.jsonPrimitive?.contentOrNull?.takeIf { w -> w.isNotBlank() }
                ?: error("when is required")
            val triggerAt = parseTriggerTime(whenDesc)
            val repeat = params["repeat"]?.jsonPrimitive?.contentOrNull?.takeIf { r -> r.isNotBlank() } ?: "none"
            require(repeat == "none" || repeat == "daily") { "repeat must be none or daily" }
            val type = params["type"]?.jsonPrimitive?.contentOrNull?.takeIf { t -> t.isNotBlank() } ?: "notification"
            require(type == "notification" || type == "alarm") { "type must be notification or alarm" }
            val snoozeMinutes = params["snoozeMinutes"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
                ?.coerceIn(0, 120) ?: 5L
            val snoozeLabel = params["snoozeLabel"]?.jsonPrimitive?.contentOrNull?.takeIf { s -> s.isNotBlank() }
            val dismissLabel = params["dismissLabel"]?.jsonPrimitive?.contentOrNull?.takeIf { d -> d.isNotBlank() }
            val message = params["message"]?.jsonPrimitive?.contentOrNull ?: title

            if (type == "alarm") {
                require(snoozeLabel != null) {
                    "type=alarm 时必须提供 snoozeLabel(稍后按钮文案, 按场景填写, 如'再睡 10 分钟')"
                }
                require(dismissLabel != null) {
                    "type=alarm 时必须提供 dismissLabel(确认按钮文案, 按场景填写, 如'起床')"
                }
            }

            val reminder = ReminderEntity(
                id = Uuid.random().toString(),
                conversationId = conversationId,
                title = title,
                message = message,
                triggerAtMillis = triggerAt,
                repeat = repeat,
                type = type,
                snoozeMinutes = snoozeMinutes,
                snoozeLabel = snoozeLabel,
                dismissLabel = dismissLabel,
            )
            dao.upsert(reminder)
            scheduler.schedule(reminder)
            listOf(
                UIMessagePart.Text(
                    buildJsonObject {
                        put("reminderId", reminder.id)
                        put("title", reminder.title)
                        put("message", reminder.message)
                        put("triggerAtMillis", reminder.triggerAtMillis)
                        put("triggerAt", formatLocalTime(reminder.triggerAtMillis))
                        put("repeat", reminder.repeat)
                        put("type", reminder.type)
                        put("snoozeMinutes", reminder.snoozeMinutes)
                        put("exactAlarm", scheduler.canScheduleExact())
                    }.toString()
                )
            )
        },
    ),
    Tool(
        name = "reminder_list",
        description = "列出当前会话的所有提醒, 包含 id、标题、触发时间、重复规则、类型和启用状态。",
        parameters = {
            InputSchema.Obj(properties = buildJsonObject { }, required = null)
        },
        needsApproval = { false },
        execute = {
            val reminders = dao.getByConversation(conversationId)
            listOf(
                UIMessagePart.Text(
                    buildJsonObject {
                        put("reminders", buildJsonArray {
                            reminders.forEach { r ->
                                add(buildJsonObject {
                                    put("id", r.id)
                                    put("title", r.title)
                                    put("message", r.message)
                                    put("triggerAtMillis", r.triggerAtMillis)
                                    put("triggerAt", formatLocalTime(r.triggerAtMillis))
                                    put("repeat", r.repeat)
                                    put("type", r.type)
                                    put("snoozeMinutes", r.snoozeMinutes)
                                    put("enabled", r.enabled)
                                    r.lastTriggeredAt?.let { put("lastTriggeredAt", it) }
                                })
                            }
                        })
                    }.toString()
                )
            )
        },
    ),
    Tool(
        name = "reminder_update",
        description = """
            按 id(来自 reminder_list)更新一条已有提醒。
            只修改传入的字段: title, message, when(新的触发时间), repeat, type, snoozeMinutes, enabled。
            返回更新后的提醒。
        """.trimIndent(),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("id", buildJsonObject {
                        put("type", "string")
                        put("description", "要更新的提醒 id")
                    })
                    put("title", buildJsonObject {
                        put("type", "string")
                        put("description", "可选的新标题")
                    })
                    put("message", buildJsonObject {
                        put("type", "string")
                        put("description", "可选的新正文")
                    })
                    put("when", buildJsonObject {
                        put("type", "string")
                        put("description", "可选的新触发时间(格式同 reminder_create)")
                    })
                    put("repeat", buildJsonObject {
                        put("type", "string")
                        put("enum", buildJsonArray {
                            add("none")
                            add("daily")
                        })
                        put("description", "可选的新重复规则")
                    })
                    put("type", buildJsonObject {
                        put("type", "string")
                        put("enum", buildJsonArray {
                            add("notification")
                            add("alarm")
                        })
                        put("description", "可选的新触发类型: notification 或 alarm")
                    })
                    put("snoozeMinutes", buildJsonObject {
                        put("type", "integer")
                        put("description", "可选的新稍后时长(分钟), 0 表示不允许稍后只能点确认")
                    })
                    put("snoozeLabel", buildJsonObject {
                        put("type", "string")
                        put("description", "可选的新稍后按钮文案(按场景填写)")
                    })
                    put("dismissLabel", buildJsonObject {
                        put("type", "string")
                        put("description", "可选的新确认按钮文案(按场景填写)")
                    })
                    put("enabled", buildJsonObject {
                        put("type", "boolean")
                        put("description", "可选 启用/停用(停用后不会触发)")
                    })
                },
                required = listOf("id"),
            )
        },
        needsApproval = { false },
        execute = {
            val params = it.jsonObject
            val id = params["id"]?.jsonPrimitive?.contentOrNull?.takeIf { i -> i.isNotBlank() }
                ?: error("id is required")
            val reminder = dao.getById(id) ?: error("Reminder not found: $id")
            val whenDesc = params["when"]?.jsonPrimitive?.contentOrNull?.takeIf { w -> w.isNotBlank() }
            val repeat = params["repeat"]?.jsonPrimitive?.contentOrNull?.takeIf { r -> r.isNotBlank() }
            if (repeat != null) require(repeat == "none" || repeat == "daily") { "repeat must be none or daily" }
            val type = params["type"]?.jsonPrimitive?.contentOrNull?.takeIf { t -> t.isNotBlank() }
            if (type != null) require(type == "notification" || type == "alarm") { "type must be notification or alarm" }
            val snoozeMinutes = params["snoozeMinutes"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()?.coerceIn(0, 120)
            val snoozeLabel = params["snoozeLabel"]?.jsonPrimitive?.contentOrNull?.takeIf { s -> s.isNotBlank() }
            val dismissLabel = params["dismissLabel"]?.jsonPrimitive?.contentOrNull?.takeIf { d -> d.isNotBlank() }
            val updated = reminder.copy(
                title = params["title"]?.jsonPrimitive?.contentOrNull?.takeIf { t -> t.isNotBlank() } ?: reminder.title,
                message = params["message"]?.jsonPrimitive?.contentOrNull?.takeIf { m -> m.isNotBlank() } ?: reminder.message,
                triggerAtMillis = whenDesc?.let { parseTriggerTime(it) } ?: reminder.triggerAtMillis,
                repeat = repeat ?: reminder.repeat,
                type = type ?: reminder.type,
                snoozeMinutes = snoozeMinutes ?: reminder.snoozeMinutes,
                snoozeLabel = snoozeLabel ?: reminder.snoozeLabel,
                dismissLabel = dismissLabel ?: reminder.dismissLabel,
                enabled = params["enabled"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: reminder.enabled,
            )
            if (updated.type == "alarm") {
                require(!updated.snoozeLabel.isNullOrBlank()) {
                    "type=alarm 时必须提供 snoozeLabel(稍后按钮文案, 按场景填写)"
                }
                require(!updated.dismissLabel.isNullOrBlank()) {
                    "type=alarm 时必须提供 dismissLabel(确认按钮文案, 按场景填写)"
                }
            }
            dao.upsert(updated)
            scheduler.schedule(updated)
            listOf(
                UIMessagePart.Text(
                    buildJsonObject {
                        put("reminderId", updated.id)
                        put("title", updated.title)
                        put("triggerAtMillis", updated.triggerAtMillis)
                        put("triggerAt", formatLocalTime(updated.triggerAtMillis))
                        put("repeat", updated.repeat)
                        put("type", updated.type)
                        put("snoozeMinutes", updated.snoozeMinutes)
                        put("enabled", updated.enabled)
                    }.toString()
                )
            )
        },
    ),
    Tool(
        name = "reminder_delete",
        description = "按 id(来自 reminder_list)删除一条提醒, 已排定的闹钟会一并取消。",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("id", buildJsonObject {
                        put("type", "string")
                        put("description", "要删除的提醒 id")
                    })
                },
                required = listOf("id"),
            )
        },
        needsApproval = { false },
        execute = {
            val id = it.jsonObject["id"]?.jsonPrimitive?.contentOrNull?.takeIf { i -> i.isNotBlank() }
                ?: error("id is required")
            dao.getById(id) ?: error("Reminder not found: $id")
            dao.deleteById(id)
            scheduler.cancel(id)
            listOf(UIMessagePart.Text(buildJsonObject { put("deleted", true); put("reminderId", id) }.toString()))
        },
    ),
)

/** 解析触发时间: epoch millis / "yyyy-MM-dd HH:mm" / "in N m|min|h|hour|d|day" */
fun parseTriggerTime(input: String, now: Long = System.currentTimeMillis()): Long {
    val trimmed = input.trim()
    trimmed.toLongOrNull()?.let { epoch ->
        if (epoch > 1_000_000_000_000L) return epoch
    }
    Regex("""in\s+(\d+)\s*(m|min|minute|minutes|h|hour|hours|d|day|days)""", RegexOption.IGNORE_CASE)
        .find(trimmed)?.let { m ->
            val amount = m.groupValues[1].toLong()
            val factor = when {
                m.groupValues[2].lowercase().startsWith("d") -> 24L * 60 * 60 * 1000
                m.groupValues[2].lowercase().startsWith("h") -> 60L * 60 * 1000
                else -> 60L * 1000
            }
            return now + amount * factor
        }
    runCatching {
        val normalized = trimmed.replace("T", " ").replace("t", " ")
        val dt = LocalDateTime.parse(normalized, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
        return dt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }
    error("无法解析时间: $input (支持 epoch millis / yyyy-MM-dd HH:mm / in N minutes/hours/days)")
}

private fun formatLocalTime(epochMillis: Long): String {
    return java.time.Instant.ofEpochMilli(epochMillis)
        .atZone(ZoneId.systemDefault())
        .toLocalDateTime()
        .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
}
