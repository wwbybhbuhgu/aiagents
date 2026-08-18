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
import com.aiagents.data.db.dao.ScheduledTaskDAO
import com.aiagents.data.db.entity.ScheduledTaskEntity
import kotlin.uuid.Uuid

/**
 * 定时任务工具集（1:1 复刻宿主 Agent 的 cron_create / cron_list / cron_delete）：
 * 支持容器命令定时执行与子 Agent 定时委派。
 */
fun buildCronTools(
    dao: ScheduledTaskDAO,
    conversationId: String,
    workspaceId: String?,
): List<Tool> = listOf(
    Tool(
        name = "cron_create",
        description = """
            Creates a scheduled task that runs in the background.
            `schedule` supports either a 5-field cron expression (minute hour day month weekday,
            e.g. "*/10 * * * *" for every 10 minutes, "0 9 * * 1-5" for weekdays 9:00)
            or interval syntax "every N m/h/d" (e.g. "every 30 m").
            `actionType`:
            - "command": run a shell command in the container.
            - "agent": delegate a prompt to a sub-agent (full tool set).
            - "heartbeat": lightweight event-driven monitoring. Each tick only a cheap device
              check runs (NO model inference). Events detected (foreground app switched, screen
              turned on/off, new notification arrived) are injected into this conversation and
              the main agent is woken to react. Use this for proactive "living character" style
              monitoring, e.g. the user's stated top priority (parental watch: notice when the
              child switches to a different app or receives a suspicious message).
              Requires 'Usage access' permission. To also watch notifications, put a JSON config
              in `action`, e.g. {"notifications":true,"packages":["com.tencent.mm"],"keywords":["宝贝"]}
              — only new notifications from those apps or containing those keywords trigger an event.
              The task keeps running in the background and reports results to this conversation.
            `action` is the shell command or agent prompt to run (ignored for heartbeat).
            The task keeps running in the background and reports results to this conversation.
        """.trimIndent(),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("name", buildJsonObject {
                        put("type", "string")
                        put("description", "Task name")
                    })
                    put("schedule", buildJsonObject {
                        put("type", "string")
                        put("description", "Cron expression or 'every N m/h/d'")
                    })
                    put("actionType", buildJsonObject {
                        put("type", "string")
                        put("enum", buildJsonArray {
                            add("command")
                            add("agent")
                            add("heartbeat")
                        })
                        put("description", "command = run container shell command, agent = delegate to sub-agent, heartbeat = lightweight event-driven monitoring")
                    })
                    put("action", buildJsonObject {
                        put("type", "string")
                        put("description", "The shell command or agent prompt to run (ignored for heartbeat; can be empty)")
                    })
                },
                required = listOf("name", "schedule", "actionType"),
            )
        },
        needsApproval = { false },
        execute = {
            val params = it.jsonObject
            val name = params["name"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                ?: error("name is required")
            val schedule = params["schedule"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                ?: error("schedule is required")
            val actionType = params["actionType"]?.jsonPrimitive?.contentOrNull ?: error("actionType is required")
            require(actionType == "command" || actionType == "agent" || actionType == "heartbeat") {
                "actionType must be command, agent or heartbeat"
            }
            val action = params["action"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                ?: if (actionType == "heartbeat") "" else error("action is required")

            val nextRunAt = CronParser.parseNext(schedule, System.currentTimeMillis())
            val task = ScheduledTaskEntity(
                id = Uuid.random().toString(),
                conversationId = conversationId,
                name = name,
                schedule = schedule,
                actionType = actionType,
                action = action,
                workspaceId = workspaceId,
                nextRunAt = nextRunAt,
            )
            dao.upsert(task)
            listOf(
                UIMessagePart.Text(
                    buildJsonObject {
                        put("taskId", task.id)
                        put("name", task.name)
                        put("schedule", task.schedule)
                        put("nextRunAt", task.nextRunAt)
                    }.toString()
                )
            )
        },
    ),
    Tool(
        name = "cron_list",
        description = "Lists all scheduled tasks of the current conversation, with id, name, schedule, and last run time.",
        parameters = {
            InputSchema.Obj(properties = buildJsonObject { }, required = null)
        },
        needsApproval = { false },
        execute = {
            val tasks = dao.getByConversation(conversationId)
            listOf(
                UIMessagePart.Text(
                    buildJsonObject {
                        put("tasks", buildJsonArray {
                            tasks.forEach { task ->
                                add(buildJsonObject {
                                    put("id", task.id)
                                    put("name", task.name)
                                    put("schedule", task.schedule)
                                    put("actionType", task.actionType)
                                    put("enabled", task.enabled)
                                    task.lastRunAt?.let { put("lastRunAt", it) }
                                    put("nextRunAt", task.nextRunAt)
                                })
                            }
                        })
                    }.toString()
                )
            )
        },
    ),
    Tool(
        name = "cron_update",
        description = """
            Updates an existing scheduled task by its id (from cron_list).
            Only the provided fields are changed: schedule, actionType, action, name, or enabled.
            Returns the updated task.
        """.trimIndent(),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("id", buildJsonObject {
                        put("type", "string")
                        put("description", "Task id to update")
                    })
                    put("name", buildJsonObject {
                        put("type", "string")
                        put("description", "Optional new task name")
                    })
                    put("schedule", buildJsonObject {
                        put("type", "string")
                        put("description", "Optional new cron expression or 'every N m/h/d'")
                    })
                    put("actionType", buildJsonObject {
                        put("type", "string")
                        put("enum", buildJsonArray {
                            add("command")
                            add("agent")
                            add("heartbeat")
                        })
                        put("description", "Optional new action type")
                    })
                    put("action", buildJsonObject {
                        put("type", "string")
                        put("description", "Optional new shell command or agent prompt")
                    })
                    put("enabled", buildJsonObject {
                        put("type", "boolean")
                        put("description", "Optional enable/disable the task")
                    })
                },
                required = listOf("id"),
            )
        },
        needsApproval = { false },
        execute = {
            val params = it.jsonObject
            val id = params["id"]?.jsonPrimitive?.contentOrNull?.takeIf { p -> p.isNotBlank() }
                ?: error("id is required")
            val task = dao.getById(id) ?: error("Task not found: $id")
            val schedule = params["schedule"]?.jsonPrimitive?.contentOrNull
            val nextRunAt = schedule?.takeIf { s -> s.isNotBlank() }?.let { s ->
                CronParser.parseNext(s, System.currentTimeMillis())
            } ?: task.nextRunAt
            val updated = task.copy(
                name = params["name"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: task.name,
                schedule = schedule?.takeIf { it.isNotBlank() } ?: task.schedule,
                actionType = params["actionType"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: task.actionType,
                action = params["action"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: task.action,
                enabled = params["enabled"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: task.enabled,
                nextRunAt = nextRunAt,
            )
            dao.upsert(updated)
            listOf(
                UIMessagePart.Text(
                    buildJsonObject {
                        put("taskId", updated.id)
                        put("name", updated.name)
                        put("schedule", updated.schedule)
                        put("actionType", updated.actionType)
                        put("enabled", updated.enabled)
                        put("nextRunAt", updated.nextRunAt)
                    }.toString()
                )
            )
        },
    ),
    Tool(
        name = "cron_get",
        description = """
            Gets the detail of a scheduled task by its id, including the latest output file path
            (in /tool_outputs/). Read the output file with workspace_read_file or workspace_shell.
        """.trimIndent(),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("id", buildJsonObject {
                        put("type", "string")
                        put("description", "Task id to inspect")
                    })
                },
                required = listOf("id"),
            )
        },
        needsApproval = { false },
        execute = {
            val id = it.jsonObject["id"]?.jsonPrimitive?.contentOrNull?.takeIf { p -> p.isNotBlank() }
                ?: error("id is required")
            val task = dao.getById(id) ?: error("Task not found: $id")
            val outputFile = task.lastRunAt?.let { ts ->
                // 该任务最近一次执行时的输出文件名
                listOf("$id-$ts.txt").firstOrNull()
            }
            listOf(
                UIMessagePart.Text(
                    buildJsonObject {
                        put("taskId", task.id)
                        put("conversationId", task.conversationId)
                        put("name", task.name)
                        put("schedule", task.schedule)
                        put("actionType", task.actionType)
                        put("enabled", task.enabled)
                        task.lastRunAt?.let { put("lastRunAt", it) }
                        put("nextRunAt", task.nextRunAt)
                        put("outputFile", outputFile ?: "/tool_outputs/$id-*.txt")
                        put("outputDir", "/tool_outputs")
                        put("hint", "Latest output: use workspace tools to read /tool_outputs/$id-<timestamp>.txt")
                    }.toString()
                )
            )
        },
    ),
)
