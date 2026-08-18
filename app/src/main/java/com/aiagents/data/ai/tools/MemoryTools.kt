package com.aiagents.data.ai.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
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
import com.aiagents.data.model.AssistantMemory

/**
 * 记忆工具(文件化, skill 模式): 每条记忆 = /memories/<scope>/<entry>/MEMORY.md 目录。
 * - 创建时必须提供概括(description), 用于列表快速识别, 与 skill 的 description 一致。
 * - `list` 只返回概括(name + description), `read` 返回完整文本与附件清单。
 * - 也鼓励直接用 workspace 文件工具/脚本把附件写入该目录。
 */
fun buildMemoryTools(
    json: Json,
    scope: String,
    memoryRootPath: String,
    onCreation: suspend (name: String, description: String, content: String) -> AssistantMemory,
    onUpdate: suspend (id: String, description: String?, content: String?) -> AssistantMemory,
    onDelete: suspend (id: String) -> Unit,
    onList: suspend () -> List<AssistantMemory>,
    onRead: suspend (id: String) -> AssistantMemory?,
): List<Tool> = listOf(
    Tool(
        name = "memory_tool",
        description = buildString {
            append("记忆工具: 用于跨对话长期保存与更新用户信息。记忆以文件形式存储在 '$memoryRootPath' 目录下,")
            append("每条记忆是一个子目录(内含 MEMORY.md 正文, 可附带图片等附件)。")
            append("操作: `create`(新增)、`read`(读取完整内容)、`edit`(编辑)、`delete`(删除)、`list`(列出概括)。")
            append("- 新增: `create` + `name` + `description`(概括) + `content`(完整内容)")
            append("- 查看概括: `list`(返回每条记忆的 id/name/description)")
            append("- 查看完整内容: `read` + `id`")
            append("- 编辑: `edit` + `id` + 可选的 `content`/`description`")
            append("- 删除: `delete` + `id`")
            append("相似内容应合并, 优先更新已有条目; 内容过时/无关时删除。")
            append("主动记忆: 对话中了解到的用户关键偏好、事实、计划与约定, 应主动 `create` 写入, 供后续对话持续参考, 不必等用户要求。")
            append("记忆的概括会出现在之后的对话 <memories> 中供你参考, 需要细节时再用 `read` 获取完整文本; 用户需要时也可在对话中展示完整内容。")
            append("可存储: 偏好称呼、喜好、计划、工作笔记、聊天风格偏好、重要日期与约定等。")
            append("如需为某条记忆附带图片等附件, 可用 workspace 文件工具直接写入 '$memoryRootPath/<id>/' 目录。")
        }.trimIndent().replace("\n", " "),
        systemPrompt = { _, _ ->
            buildString {
                appendLine("**记忆(文件形式)**")
                appendLine("你的记忆保存在 '$memoryRootPath' 目录, 每条记忆是一个子目录:")
                appendLine("  - <id>/MEMORY.md  完整记忆(带 name/description frontmatter)")
                appendLine("  - <id>/...        可选附件(图片等)")
                appendLine("每条记忆都有一个 `description` 概括, 方便列表快速识别; `memory_tool` 的 `list` 返回概括, `read` 返回完整文本与附件。")
                appendLine("主动维护记忆: 遇到值得长期记住的用户信息时直接用 `memory_tool` 的 `create`/`edit` 更新, 重要信息丢失会降低后续对话质量。")
                appendLine("你也可用 workspace 文件工具/脚本直接读写这些文件(便于自动化与附件管理)。")
            }
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
                                add("read")
                                add("edit")
                                add("delete")
                                add("list")
                            }
                        )
                        put("description", "操作: create(新增) / read(读取) / edit(编辑) / delete(删除) / list(列出概括)")
                    })
                    put("name", buildJsonObject {
                        put("type", "string")
                        put(
                            "description",
                            "记忆名称(仅字母/数字/下划线/点/短横线, 用作目录名, create 必填)"
                        )
                    })
                    put("id", buildJsonObject {
                        put("type", "string")
                        put("description", "记忆 id(目录名), read/edit/delete 必填")
                    })
                    put("description", buildJsonObject {
                        put("type", "string")
                        put("description", "记忆概括(一句话总结该条记忆的核心内容, create 必填)")
                    })
                    put("content", buildJsonObject {
                        put("type", "string")
                        put("description", "记忆完整内容正文(create 必填)")
                    })
                },
                required = listOf("action")
            )
        },
        execute = {
            val params = it.jsonObject
            val action = params["action"]?.jsonPrimitive?.contentOrNull ?: error("action 必填")
            val payload = when (action) {
                "create" -> {
                    val name = params["name"]?.jsonPrimitive?.contentOrNull
                        ?: error("name 必填(记忆名称, 用作目录名)")
                    val description = params["description"]?.jsonPrimitive?.contentOrNull
                        ?: error("description 必填(请用一句话概括该条记忆)")
                    val content = params["content"]?.jsonPrimitive?.contentOrNull
                        ?: error("content 必填")
                    memoryResultJson(scope, onCreation(name, description, content))
                }

                "read" -> {
                    val id = params["id"]?.jsonPrimitive?.contentOrNull ?: error("id 必填")
                    val memory = onRead(id) ?: error("记忆 '$id' 不存在")
                    memoryResultJson(scope, memory)
                }

                "edit" -> {
                    val id = params["id"]?.jsonPrimitive?.contentOrNull ?: error("id 必填")
                    val description = params["description"]?.jsonPrimitive?.contentOrNull
                    val content = params["content"]?.jsonPrimitive?.contentOrNull
                    if (description == null && content == null) {
                        error("edit 需要至少提供 description 或 content 之一")
                    }
                    memoryResultJson(scope, onUpdate(id, description, content))
                }

                "delete" -> {
                    val id = params["id"]?.jsonPrimitive?.contentOrNull ?: error("id 必填")
                    onDelete(id)
                    buildJsonObject {
                        put("scope", scope)
                        put("id", id)
                        put("success", true)
                    }
                }

                "list" -> {
                    buildJsonObject {
                        put("scope", scope)
                        put("path", memoryRootPath)
                        put("memories", buildJsonArray {
                            onList().forEach { memory ->
                                add(buildJsonObject {
                                    put("id", memory.id)
                                    put("name", memory.name)
                                    put("description", memory.description)
                                })
                            }
                        })
                    }
                }

                else -> error("未知 action: $action, 必须是 [create, read, edit, delete, list]")
            }
            listOf(UIMessagePart.Text(payload.toString()))
        }
    )
)

private fun memoryResultJson(scope: String, memory: AssistantMemory): JsonElement {
    return buildJsonObject {
        put("scope", scope)
        put("id", memory.id)
        put("name", memory.name)
        put("description", memory.description)
        put("content", memory.content)
    }
}
