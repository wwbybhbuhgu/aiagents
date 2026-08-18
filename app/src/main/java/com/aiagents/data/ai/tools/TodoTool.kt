package com.aiagents.data.ai.tools

import java.util.concurrent.ConcurrentHashMap
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

/** 对话级待办项 */
data class TodoItem(
    val id: Long,
    val content: String,
    val status: String = "pending", // pending | in_progress | completed
    val createdAt: Long,
    val updatedAt: Long,
)

/**
 * 对话级待办存储（会话内有效）。
 * 与宿主 Agent 的 todo 工具语义一致：每个对话一份独立清单，便于主 Agent
 * 拆分任务、跟踪进度；委派给子 Agent 的任务也通过它共享。
 */
object TodoStore {
    private val store = ConcurrentHashMap<String, MutableList<TodoItem>>()
    private val counters = ConcurrentHashMap<String, Long>()

    fun list(conversationId: String): List<TodoItem> =
        store[conversationId]?.toList().orEmpty()

    fun create(conversationId: String, content: String): TodoItem {
        val id = counters.merge(conversationId, 1L) { old, _ -> old + 1 } ?: 1L
        val now = System.currentTimeMillis()
        val item = TodoItem(id = id, content = content, createdAt = now, updatedAt = now)
        store.getOrPut(conversationId) { mutableListOf() }.add(item)
        return item
    }

    fun update(conversationId: String, id: Long, content: String?, status: String?): TodoItem? {
        val list = store[conversationId] ?: return null
        val index = list.indexOfFirst { it.id == id }
        if (index < 0) return null
        val old = list[index]
        val updated = old.copy(
            content = content ?: old.content,
            status = status ?: old.status,
            updatedAt = System.currentTimeMillis(),
        )
        list[index] = updated
        return updated
    }

    fun delete(conversationId: String, id: Long): Boolean {
        val list = store[conversationId] ?: return false
        return list.removeAll { it.id == id }
    }

    fun clear(conversationId: String) {
        store.remove(conversationId)
        counters.remove(conversationId)
    }
}

private const val TODO_STATUS_ENUM = "pending,in_progress,completed"

/**
 * 构建 todo_write 工具。与宿主 Agent 的 todo 工具 1:1 对齐：
 * 主 Agent 用它拆分/跟踪多步任务，子 Agent 委派回来后据此汇报进度。
 */
fun buildTodoTools(conversationId: String): List<Tool> = listOf(
    Tool(
        name = "todo_write",
        description = """
            Manages the task list for the current conversation. Use `action` to control the operation:
            - `create`: add a new todo item with `content`
            - `list`: list all todo items (optionally filter by `status`)
            - `update`: update `content` and/or `status` of an existing item by `id`
            - `delete`: remove an item by `id`
            - `clear`: remove all items
            Status values: $TODO_STATUS_ENUM.
            Whenever the user's request involves more than one step (research, planning, automation,
            or any flow chaining multiple tools), break the goal into concrete todos with `create`
            BEFORE executing: list each step, then mark items `in_progress` while working and
            `completed` when done. Keep the list current so later turns and delegated agents can
            track progress, and report the outcome against it.
            Today is the current session date; keep items concise.
        """.trimIndent(),
        systemPrompt = { _, _ ->
            """
            **Planning & Tracking**
            Use the `todo_write` tool to plan and track multi-step work before and while executing.
            - Before starting any non-trivial task, create one todo per concrete step.
            - Mark the step you are working on as `in_progress`, then flip it to `completed` once finished.
            - Keep the list up to date across tool calls; finish by summarizing results against the todos.
            """.trimIndent()
        },
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("action", buildJsonObject {
                        put("type", "string")
                        put(
                            "enum",
                            buildJsonArray {
                                add("create")
                                add("list")
                                add("update")
                                add("delete")
                                add("clear")
                            }
                        )
                        put("description", "Operation to perform")
                    })
                    put("id", buildJsonObject {
                        put("type", "integer")
                        put("description", "The id of the todo item (required for update/delete)")
                    })
                    put("content", buildJsonObject {
                        put("type", "string")
                        put("description", "The content of the todo item (required for create, optional for update)")
                    })
                    put("status", buildJsonObject {
                        put("type", "string")
                        put("enum", buildJsonArray {
                            add("pending")
                            add("in_progress")
                            add("completed")
                        })
                        put("description", "The status of the todo item (optional for update)")
                    })
                },
                required = listOf("action"),
            )
        },
        execute = {
            val params = it.jsonObject
            val action = params["action"]?.jsonPrimitive?.contentOrNull ?: error("action is required")
            when (action) {
                "create" -> {
                    val content = params["content"]?.jsonPrimitive?.contentOrNull
                        ?.takeIf { it.isNotBlank() } ?: error("content is required")
                    TodoStore.create(conversationId, content)
                    listOf(UIMessagePart.Text(currentTodoListJson(conversationId)))
                }

                "list" -> {
                    val status = params["status"]?.jsonPrimitive?.contentOrNull
                    val items = TodoStore.list(conversationId)
                        .filter { status == null || it.status == status }
                    listOf(UIMessagePart.Text(todoListJson(items)))
                }

                "update" -> {
                    val id = params["id"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
                        ?: error("id is required")
                    val content = params["content"]?.jsonPrimitive?.contentOrNull
                    val status = params["status"]?.jsonPrimitive?.contentOrNull
                    if (content == null && status == null) error("content or status is required")
                    if (status != null && status !in listOf("pending", "in_progress", "completed")) {
                        error("invalid status: $status")
                    }
                    val item = TodoStore.update(conversationId, id, content, status)
                        ?: error("todo item not found: $id")
                    listOf(UIMessagePart.Text(currentTodoListJson(conversationId)))
                }

                "delete" -> {
                    val id = params["id"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
                        ?: error("id is required")
                    val deleted = TodoStore.delete(conversationId, id)
                    listOf(UIMessagePart.Text(currentTodoListJson(conversationId)))
                }

                "clear" -> {
                    TodoStore.clear(conversationId)
                    listOf(UIMessagePart.Text(currentTodoListJson(conversationId)))
                }

                else -> error("unknown action: $action")
            }
        },
    ),
)

/** 当前对话完整 todo 列表 JSON（所有操作后返回, 供卡片渲染完整状态） */
private fun currentTodoListJson(conversationId: String): String =
    todoListJson(TodoStore.list(conversationId))

private fun todoListJson(items: List<TodoItem>): String = buildJsonObject {
    put("todos", buildJsonArray {
        items.forEach { add(it.toJson()) }
    })
}.toString()

private fun TodoItem.toJson() = buildJsonObject {
    put("id", id)
    put("content", content)
    put("status", status)
    put("createdAt", createdAt)
    put("updatedAt", updatedAt)
}
