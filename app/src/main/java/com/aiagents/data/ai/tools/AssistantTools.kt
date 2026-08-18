package com.aiagents.data.ai.tools

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.coroutines.flow.first
import com.aiagents.ai.core.InputSchema
import com.aiagents.ai.core.Tool
import com.aiagents.ai.ui.UIMessage
import com.aiagents.ai.ui.UIMessagePart
import com.aiagents.data.datastore.SettingsStore
import com.aiagents.data.datastore.getAssistantById
import com.aiagents.data.model.Assistant
import com.aiagents.data.model.AssistantCardParser
import com.aiagents.data.model.Lorebook
import kotlin.uuid.Uuid

/**
 * 构建角色管理工具：
 * - `create_assistant`：AI 用角色卡参数创建新助手, 创建前按名字查重, 重名自动追加 -n 后缀。
 * - `edit_assistant`：AI 编辑指定助手（id）的字段, 只能编辑"其他"助手, 禁止编辑自己/当前助手。
 *
 * 所有参数都由 AI 自行填写; 用户只需设定目标(最重要的事情)。
 *
 * @param currentAssistantId 当前正在对话的助手 id; 编辑工具禁止以它为修改目标。
 */
fun buildAssistantTools(
    settingsStore: SettingsStore,
    currentAssistantId: Uuid? = null,
): List<Tool> = listOf(
    Tool(
        name = "create_assistant",
        description = """
            Creates a new assistant (character) modeled on a Tavern/SillyTavern character card.
            You supply the character fields yourself; the user only sets the overall goal.
            Fields map to the standard character-card schema (all strings):
            - name (required): character name
            - system_prompt: the underlying system/roleplay instructions
            - description: character description / appearance
            - personality: personality traits
            - scenario: the initial situation/world
            - first_mes: the very first greeting message the character sends
            - alternate_greetings: list of alternate opening lines
            - mes_example: example dialogue
            - creator_notes: notes from the character creator
            - tags: list of topic tags
            - world_book: optional world/lorebook. JSON map where each value has:
              {key:[triggerWords], content:"...", constant:bool, position:int, depth:int, scan_depth:int, order:int}
              These become keyword-triggered lore entries bound to this character.
            Creates a NEW assistant (never edits an existing one). Names are deduplicated: if the
            requested name already exists, the new assistant is saved with a "-n" suffix (n = the
            next available index, e.g. "Alice-2") and the result reports that the name was taken.
            Returns the new assistant id, final (deduplicated) name, and whether the name was taken.
            Fill all parameters yourself based on the user's request / top priority.
        """.trimIndent(),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("name", buildJsonObject {
                        put("type", "string")
                        put("description", "Character / assistant name (required)")
                    })
                    put("system_prompt", buildJsonObject {
                        put("type", "string")
                        put("description", "Core system / roleplay instructions")
                    })
                    put("description", buildJsonObject {
                        put("type", "string")
                        put("description", "Character description and appearance")
                    })
                    put("personality", buildJsonObject {
                        put("type", "string")
                        put("description", "Personality traits")
                    })
                    put("scenario", buildJsonObject {
                        put("type", "string")
                        put("description", "Initial scenario / world")
                    })
                    put("first_mes", buildJsonObject {
                        put("type", "string")
                        put("description", "Opening greeting message the character sends first")
                    })
                    put("alternate_greetings", buildJsonObject {
                        put("type", "array")
                        put("items", buildJsonObject { put("type", "string") })
                        put("description", "Alternate opening lines (optional)")
                    })
                    put("mes_example", buildJsonObject {
                        put("type", "string")
                        put("description", "Example dialogue (optional)")
                    })
                    put("creator_notes", buildJsonObject {
                        put("type", "string")
                        put("description", "Creator notes (optional)")
                    })
                    put("tags", buildJsonObject {
                        put("type", "array")
                        put("items", buildJsonObject { put("type", "string") })
                        put("description", "Topic tags (optional)")
                    })
                    put("world_book", buildJsonObject {
                        put("type", "object")
                        put("description", "Optional lorebook/world book. Map of entries, each with key:[triggers], content, constant, position(0-4), depth, scan_depth, order.")
                    })
                },
                required = listOf("name"),
            )
        },
        needsApproval = { false },
        execute = {
            val params = it.jsonObject
            val requestedName = params["name"]?.jsonPrimitive?.contentOrNull
                ?.takeIf { n -> n.isNotBlank() }
                ?: error("name is required")

            // 查重: 同名已存在时自动追加 -n 后缀(n 为下一个可用序号)
            val existingNames = settingsStore.settingsFlowRaw.first().assistants
                .map { a -> a.name }
                .toSet()
            val (finalName, nameTaken) = deduplicateName(requestedName, existingNames)

            val data = buildJsonObject {
                put("name", finalName)
                params["system_prompt"]?.jsonPrimitiveOrNullString()?.let { put("system_prompt", it) }
                params["description"]?.jsonPrimitiveOrNullString()?.let { put("description", it) }
                params["personality"]?.jsonPrimitiveOrNullString()?.let { put("personality", it) }
                params["scenario"]?.jsonPrimitiveOrNullString()?.let { put("scenario", it) }
                params["first_mes"]?.jsonPrimitiveOrNullString()?.let { put("first_mes", it) }
                params["creator_notes"]?.jsonPrimitiveOrNullString()?.let { put("creator_notes", it) }
                params["mes_example"]?.jsonPrimitiveOrNullString()?.let { put("mes_example", it) }
                (params["alternate_greetings"] as? JsonArray)?.let { put("alternate_greetings", it) }
                (params["tags"] as? JsonArray)?.let { put("tags", it) }
                params["world_book"]?.let { put("character_book", it) }
            }
            val cardJson = buildJsonObject {
                put("spec", "chara_card_v3")
                put("data", data)
            }

            val result = AssistantCardParser.parse(cardJson)
            val lorebook = result.lorebook
            val assistant = result.assistant

            // 通过 SettingsStore 保存助手与世界书
            val assistantId = persist(settingsStore, assistant, lorebook)

            listOf(
                UIMessagePart.Text(
                    buildJsonObject {
                        put("status", if (nameTaken) "created_with_renamed" else "created")
                        put("assistantId", assistantId.toString())
                        put("name", assistant.name)
                        put("nameTaken", nameTaken)
                        put("requestedName", requestedName)
                        put("worldBookSaved", lorebook != null)
                        put(
                            "message",
                            if (nameTaken) {
                                "名字 \"$requestedName\" 已存在, 已创建为重名助手 \"${assistant.name}\"" +
                                    if (lorebook != null) ", 并保存了世界书(${lorebook.entries.size} 条)" else ""
                            } else {
                                "已创建角色助手 \"${assistant.name}\"" +
                                    if (lorebook != null) ", 并保存了世界书(${lorebook.entries.size} 条)" else ""
                            }
                        )
                    }.toString()
                )
            )
        },
    ),
    Tool(
        name = "edit_assistant",
        description = """
            Edits an existing assistant (character) by id.
            Use `assistantId` to target which assistant to edit, then pass only the fields to change.
            Editable fields:
            - name: rename. Renames are deduplicated: if the new name is already used by another
              assistant, the target is renamed with a "-n" suffix (e.g. "Alice-2").
            - system_prompt: core system / roleplay instructions
            - description: character description and appearance
            - personality: personality traits
            - scenario: initial situation / world
            - first_mes: opening greeting. Passing it replaces the preset greeting message.
            - alternate_greetings: list of alternate opening lines
            - mes_example: example dialogue
            - creator_notes: notes from the character creator
            - tags: list of topic tags
            - background: chat background image URI (or empty string to clear)
            IMPORTANT: you may only edit OTHER assistants. Editing your own assistant (the one that
            is currently talking to the user) is forbidden and will be rejected.
            Returns the updated assistant id and name, or an error if the target does not exist,
            equals the current assistant, or has no fields to change.
        """.trimIndent(),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("assistantId", buildJsonObject {
                        put("type", "string")
                        put("description", "Target assistant id to edit (required). Cannot be the current assistant.")
                    })
                    put("name", buildJsonObject {
                        put("type", "string")
                        put("description", "New name (deduplicated with -n suffix if taken)")
                    })
                    put("system_prompt", buildJsonObject {
                        put("type", "string")
                        put("description", "New core system / roleplay instructions")
                    })
                    put("description", buildJsonObject {
                        put("type", "string")
                        put("description", "New description and appearance")
                    })
                    put("personality", buildJsonObject {
                        put("type", "string")
                        put("description", "New personality traits")
                    })
                    put("scenario", buildJsonObject {
                        put("type", "string")
                        put("description", "New scenario / world")
                    })
                    put("first_mes", buildJsonObject {
                        put("type", "string")
                        put("description", "New opening greeting (replaces preset greeting)")
                    })
                    put("alternate_greetings", buildJsonObject {
                        put("type", "array")
                        put("items", buildJsonObject { put("type", "string") })
                        put("description", "New alternate opening lines")
                    })
                    put("mes_example", buildJsonObject {
                        put("type", "string")
                        put("description", "New example dialogue")
                    })
                    put("creator_notes", buildJsonObject {
                        put("type", "string")
                        put("description", "New creator notes")
                    })
                    put("tags", buildJsonObject {
                        put("type", "array")
                        put("items", buildJsonObject { put("type", "string") })
                        put("description", "New topic tags")
                    })
                    put("background", buildJsonObject {
                        put("type", "string")
                        put("description", "New chat background image URI (or empty string to clear)")
                    })
                },
                required = listOf("assistantId"),
            )
        },
        needsApproval = { false },
        execute = {
            val params = it.jsonObject
            val targetId = params["assistantId"]?.jsonPrimitive?.contentOrNull
                ?.let { raw -> kotlin.runCatching { Uuid.parse(raw) }.getOrNull() }
                ?: error("assistantId is required and must be a valid uuid")

            // 禁止编辑自己/当前助手
            if (targetId == currentAssistantId) {
                listOf(
                    UIMessagePart.Text(
                        buildJsonObject {
                            put("status", "rejected")
                            put("message", "禁止编辑当前助手(自己): 只能编辑其他助手, 或创建新助手")
                        }.toString()
                    )
                )
            } else {
                val edited = editAssistant(settingsStore, targetId, params)
                listOf(
                    UIMessagePart.Text(
                        buildJsonObject {
                            put("status", if (edited.renamed) "renamed" else "updated")
                            put("assistantId", edited.assistant.id.toString())
                            put("name", edited.assistant.name)
                            put("nameTaken", edited.renamed)
                            put("message", edited.message)
                        }.toString()
                    )
                )
            }
        },
    ),
)

/** 查重并生成不冲突的名字: 同名已存在时追加 -n(n 从 2 开始), 返回 (最终名, 是否重名) */
private fun deduplicateName(requested: String, existing: Set<String>): Pair<String, Boolean> {
    if (requested !in existing) return requested to false
    var n = 2
    while ("$requested-$n" in existing) n++
    return "$requested-$n" to true
}

private data class AssistantEditResult(
    val assistant: Assistant,
    val renamed: Boolean,
    val message: String,
)

/** 按 id 编辑指定助手字段; 目标不存在时抛错。重命名自动查重。 */
private suspend fun editAssistant(
    settingsStore: SettingsStore,
    targetId: Uuid,
    params: JsonElement,
): AssistantEditResult {
    val p = params.jsonObject
    val currentSettings = settingsStore.settingsFlowRaw.first()
    val target = currentSettings.getAssistantById(targetId)
        ?: error("未找到要编辑的助手: $targetId")

    var renamed = false
    var name = target.name
    var systemPrompt = target.systemPrompt
    var presetMessages = target.presetMessages
    var background = target.background

    p["name"]?.jsonPrimitiveOrNullString()?.let { newName ->
        if (newName != target.name) {
            val otherNames = currentSettings.assistants
                .filter { it.id != targetId }
                .map { it.name }
                .toSet()
            val (finalName, taken) = deduplicateName(newName, otherNames)
            name = finalName
            renamed = taken
        }
    }
    p["system_prompt"]?.jsonPrimitiveOrNullString()?.let { systemPrompt = it }
    p["description"]?.jsonPrimitiveOrNullString()?.let {
        systemPrompt = replacePromptSection(systemPrompt, "Description of the character", it)
    }
    p["personality"]?.jsonPrimitiveOrNullString()?.let {
        systemPrompt = replacePromptSection(systemPrompt, "Personality of the character", it)
    }
    p["scenario"]?.jsonPrimitiveOrNullString()?.let {
        systemPrompt = replacePromptSection(systemPrompt, "Scenario", it)
    }
    p["creator_notes"]?.jsonPrimitiveOrNullString()?.let {
        systemPrompt = replacePromptSection(systemPrompt, "Creator notes", it)
    }
    p["mes_example"]?.jsonPrimitiveOrNullString()?.let {
        systemPrompt = replacePromptSection(systemPrompt, "Example dialogue", it)
    }
    p["alternate_greetings"]?.takeIf { it is JsonArray }?.let { raw ->
        val greetings = (raw as JsonArray).mapNotNull { e -> e.jsonPrimitiveOrNullString() }
        systemPrompt = replacePromptSection(systemPrompt, "Alternate greetings", null) // 先移除旧的
        if (greetings.isNotEmpty()) {
            systemPrompt = appendAlternateGreetings(systemPrompt, greetings)
        }
    }
    p["tags"]?.takeIf { it is JsonArray }?.let { raw ->
        val tags = (raw as JsonArray).mapNotNull { e -> e.jsonPrimitiveOrNullString() }
        if (tags.isNotEmpty()) {
            systemPrompt = replacePromptSection(systemPrompt, "Tags", tags.joinToString(", "))
        }
    }
    p["first_mes"]?.jsonPrimitiveOrNullString()?.let {
        presetMessages = listOf(UIMessage.assistant(it))
    }
    p["background"]?.let { raw ->
        background = raw.jsonPrimitiveOrNullString()?.takeIf { it.isNotBlank() }
    }

    val edited = target.copy(
        name = name,
        systemPrompt = systemPrompt,
        presetMessages = presetMessages,
        background = background,
    )
    if (edited == target) {
        error("没有需要修改的字段: 请至少传入一个要修改的字段")
    }

    settingsStore.update { settings ->
        settings.copy(assistants = settings.assistants.map { a -> if (a.id == targetId) edited else a })
    }

    val suffix = if (renamed) "（原名字已存在, 已重命名为带 -n 后缀的名字）" else ""
    return AssistantEditResult(
        assistant = edited,
        renamed = renamed,
        message = "已更新助手 \"${edited.name}\"$suffix",
    )
}

/**
 * 在 systemPrompt 中替换 `## <section>` 段落的内容; 段落不存在时新增。
 * [newValue] 为 null 时仅移除该段落。
 */
private fun replacePromptSection(
    prompt: String,
    section: String,
    newValue: String?,
): String {
    val marker = "## $section"
    val startIdx = prompt.indexOf(marker)
    if (startIdx < 0) {
        if (newValue.isNullOrBlank()) return prompt
        return prompt.trimEnd() + "\n\n$marker\n$newValue\n"
    }
    // 段落内容起始 = 标题行末尾之后
    val headerEnd = prompt.indexOf('\n', startIdx)
    val contentStart = if (headerEnd >= 0) headerEnd + 1 else prompt.length
    // 段落结束 = 下一个 "## " 标题之前(或文末)
    val nextHeader = prompt.indexOf("\n## ", contentStart)
    val contentEnd = if (nextHeader >= 0) nextHeader else prompt.length
    return when {
        newValue.isNullOrBlank() -> {
            // 移除整段(含其前置空行), 保留其余部分
            prompt.removeRange(startIdx, contentEnd).trimEnd() + "\n"
        }
        else -> {
            prompt.replaceRange(contentStart, contentEnd, newValue + "\n")
        }
    }
}

/** 在 prompt 末尾追加备选开场白段落(用于 edit_assistant 更新 alternate_greetings) */
private fun appendAlternateGreetings(prompt: String, greetings: List<String>): String = buildString {
    append(prompt.trimEnd())
    appendLine()
    appendLine()
    appendLine("## Alternate greetings")
    greetings.take(3).forEachIndexed { i, g ->
        appendLine("[greeting ${i + 1}]")
        appendLine(g)
        appendLine()
    }
}

private fun JsonElement.jsonPrimitiveOrNullString(): String? =
    (this as? JsonPrimitive)?.contentOrNull

private suspend fun persist(
    settingsStore: SettingsStore,
    assistant: Assistant,
    lorebook: Lorebook?,
): Uuid {
    val savedId = assistant.id
    settingsStore.update { settings ->
        val withLorebook = if (lorebook != null) {
            settings.copy(lorebooks = settings.lorebooks + lorebook)
        } else {
            settings
        }
        val finalAssistant = if (lorebook != null) {
            assistant.copy(lorebookIds = assistant.lorebookIds + lorebook.id)
        } else {
            assistant
        }
        withLorebook.copy(assistants = withLorebook.assistants + finalAssistant)
    }
    return savedId
}
