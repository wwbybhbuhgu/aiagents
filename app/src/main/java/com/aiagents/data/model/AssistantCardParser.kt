package com.aiagents.data.model

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import com.aiagents.ai.ui.UIMessage
import com.aiagents.data.model.PromptInjection
import com.aiagents.data.model.InjectionPosition
import com.aiagents.utils.jsonPrimitiveOrNull
import kotlin.uuid.Uuid

/**
 * 从 Tavern/SillyTavern 角色卡 JSON 解析出 [Assistant]，并顺带解析内嵌的世界书
 * （character_book）为 [Lorebook]。被 UI 导入器与 AI 的 `create_assistant` 工具复用。
 *
 * 相比旧 AssistantImporter 的解析，新增：
 * - alternate_greetings：备选开场白
 * - mes_example：示例对话
 * - character_book：世界书 → Lorebook（关键词注入）
 * - creator / creator_notes / tags 等元数据
 */
object AssistantCardParser {

    /** 解析结果：助手本体 + 可选的世界书（Lorebook） */
    data class Result(
        val assistant: Assistant,
        val lorebook: Lorebook? = null,
    )

    /**
     * @param json 解析后的角色卡 JSON 对象（顶层含 spec + data）
     * @param background 可选背景图 URI
     */
    fun parse(
        json: JsonObject,
        background: String? = null,
    ): Result {
        val spec = json["spec"]?.jsonPrimitive?.contentOrNull
            ?: json["spec_version"]?.jsonPrimitive?.contentOrNull
            ?: error("Missing spec field in character card")

        val data = json["data"]?.jsonObject
            ?: error("Missing data field in character card")

        val name = data["name"]?.jsonPrimitiveOrNull?.contentOrNull
            ?: error("Missing name field in character card")

        val firstMessage = data["first_mes"]?.jsonPrimitiveOrNull?.contentOrNull
        val system = data["system_prompt"]?.jsonPrimitiveOrNull?.contentOrNull
        val description = data["description"]?.jsonPrimitiveOrNull?.contentOrNull
        val personality = data["personality"]?.jsonPrimitiveOrNull?.contentOrNull
        val scenario = data["scenario"]?.jsonPrimitiveOrNull?.contentOrNull
        val mesExample = data["mes_example"]?.jsonPrimitiveOrNull?.contentOrNull
            ?: data["example_dialogue"]?.jsonPrimitiveOrNull?.contentOrNull
        val creator = data["creator"]?.jsonPrimitiveOrNull?.contentOrNull
        val creatorNotes = data["creator_notes"]?.jsonPrimitiveOrNull?.contentOrNull
        val charVersion = data["char_version"]?.jsonPrimitiveOrNull?.contentOrNull
        val tags: List<String> = (data["tags"] as? List<*>)?.mapNotNull { it?.toString() } ?: emptyList()
        val alternateGreetings: List<String> = (data["alternate_greetings"] as? List<*>)
            ?.mapNotNull { it?.toString() } ?: emptyList()

        @Suppress("UNUSED_VARIABLE")
        val unusedSpec = spec // spec 目前对解析无差异影响, 保留兼容

        val prompt = buildString {
            appendLine("You are roleplaying as $name.")
            appendLine()
            if (!creator.isNullOrBlank()) {
                appendLine("(Character creator: $creator)")
                appendLine()
            }
            if (!creatorNotes.isNullOrBlank()) {
                appendLine("## Creator notes")
                appendLine(creatorNotes)
                appendLine()
            }
            if (tags.isNotEmpty()) {
                appendLine("## Tags")
                appendLine(tags.joinToString(", "))
                appendLine()
            }
            if (!charVersion.isNullOrBlank()) {
                appendLine("(Character version: $charVersion)")
                appendLine()
            }
            if (!system.isNullOrBlank()) {
                appendLine(system)
                appendLine()
            }
            appendLine("## Description of the character")
            appendLine(description ?: "Empty")
            appendLine()
            appendLine("## Personality of the character")
            appendLine(personality ?: "Empty")
            appendLine()
            appendLine("## Scenario")
            appendLine(scenario ?: "Empty")
            if (alternateGreetings.isNotEmpty()) {
                appendLine()
                appendLine("## Alternate greetings")
                alternateGreetings.take(3).forEachIndexed { i, g ->
                    appendLine("[greeting ${i + 1}]")
                    appendLine(g)
                    appendLine()
                }
            }
            if (!mesExample.isNullOrBlank()) {
                appendLine()
                appendLine("## Example dialogue")
                appendLine(mesExample.orEmpty())
            }
        }

        val assistant = Assistant(
            name = name,
            presetMessages = if (firstMessage != null) listOf(UIMessage.assistant(firstMessage)) else emptyList(),
            systemPrompt = prompt,
            background = background,
        )

        val lorebook = parseWorldBook(name, data["character_book"]?.jsonObject)
        return Result(assistant = assistant, lorebook = lorebook)
    }

    /** 解析世界书 character_book → Lorebook */
    private fun parseWorldBook(assistantName: String, book: JsonObject?): Lorebook? {
        if (book == null) return null
        val rawEntries = book["entries"]?.jsonObject ?: return null
        val lorebookEntries = rawEntries.mapNotNull { (_, value) ->
            val entry = value as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
            val keys = entry["key"]?.jsonPrimitiveOrNull?.contentOrNull
                ?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
            val content = entry["content"]?.jsonPrimitiveOrNull?.contentOrNull.orEmpty()
            if (content.isBlank() && keys.isEmpty()) return@mapNotNull null
            val comment = entry["comment"]?.jsonPrimitiveOrNull?.contentOrNull.orEmpty()
            PromptInjection.RegexInjection(
                id = Uuid.random(),
                name = comment.ifEmpty { keys.firstOrNull() ?: "entry" },
                enabled = true,
                priority = entry["order"]?.jsonPrimitiveOrNull?.contentOrNull?.toIntOrNull() ?: 0,
                position = mapSillyPosition(entry["position"]?.jsonPrimitiveOrNull?.contentOrNull?.toIntOrNull() ?: 1),
                injectDepth = entry["depth"]?.jsonPrimitiveOrNull?.contentOrNull?.toIntOrNull() ?: 4,
                content = content,
                keywords = keys,
                useRegex = false,
                caseSensitive = false,
                scanDepth = entry["scan_depth"]?.jsonPrimitiveOrNull?.contentOrNull?.toIntOrNull() ?: 4,
                constantActive = entry["constant"]?.jsonPrimitiveOrNull?.contentOrNull?.toBooleanStrictOrNull() ?: false,
            )
        }

        return Lorebook(
            id = Uuid.random(),
            name = "世界书·$assistantName",
            description = "",
            enabled = true,
            entries = lorebookEntries,
        )
    }

    private fun mapSillyPosition(position: Int): InjectionPosition = when (position) {
        0 -> InjectionPosition.BEFORE_SYSTEM_PROMPT
        1 -> InjectionPosition.AFTER_SYSTEM_PROMPT
        2, 3 -> InjectionPosition.TOP_OF_CHAT
        4 -> InjectionPosition.AT_DEPTH
        else -> InjectionPosition.AFTER_SYSTEM_PROMPT
    }
}
