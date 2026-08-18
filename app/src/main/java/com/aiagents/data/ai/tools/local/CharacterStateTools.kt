package com.aiagents.data.ai.tools.local

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import com.aiagents.ai.core.InputSchema
import com.aiagents.ai.core.Tool
import com.aiagents.ai.ui.UIMessagePart
import java.io.File
import java.time.Instant

/**
 * 角色状态持久化工具（character_state_get / character_state_set）。
 *
 * 存的是角色的"实时状态"（情绪、信任值、冷却、亲密度等），区别于 memory_tool 存的
 * "知识/偏好"。每次对话开始可读取、结束时写入同一份 JSON, 让角色跨对话保持连续感
 * （例如病娇的情绪不会每次对话都重置）。
 */
internal fun buildCharacterStateTools(context: Context): List<Tool> {
    val dir = File(context.filesDir, "character_states").apply { mkdirs() }
    return listOf(
        buildCharacterStateGet(dir),
        buildCharacterStateSet(dir),
    )
}

private fun buildCharacterStateGet(dir: File): Tool = Tool(
    name = "character_state_get",
    description = """
        Read the persistent state of this character/conversation (emotion, trust/suspicion level,
        affinity, cooldown status, relationship, active tags, free-form notes).
        State persists across conversations, so a long-running character keeps a continuous
        "life": read it at the start of a turn, update it with character_state_set when it changes.
        Returns the raw stored state, or empty fields if none was saved yet.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(properties = buildJsonObject { })
    },
    needsApproval = { false },
    execute = {
        val state = readState(dir)
        listOf(
            UIMessagePart.Text(
                buildJsonObject {
                    put("emotion", state.emotion)
                    put("trust", state.trust)
                    put("affinity", state.affinity)
                    put("cooldown_note", state.cooldownText)
                    put("relationship", state.relationship)
                    put("tags", buildJsonArray {
                        state.tags.forEach { add(it) }
                    })
                    put("notes", state.notes)
                    if (state.updatedAt != null) {
                        put("updated_at", state.updatedAt)
                    }
                }.toString()
            )
        )
    },
)

private fun buildCharacterStateSet(dir: File): Tool = Tool(
    name = "character_state_set",
    description = """
        Update the persistent state of this character/conversation. Any field omitted keeps its
        previous value; provide the ones that changed. State persists across conversations.
        Recommended fields (all optional, free-form):
        - emotion: current mood / emotional level (e.g. "calm", "jealous", "blackened 🔴")
        - trust: relationship trust or suspicion level, e.g. a number 0-100 or a short label
        - affinity: long-term affinity accumulated over interactions
        - cooldown_text: cooldown note, e.g. "just argued, keeps cold shoulder for 2h"
        - relationship: overall relationship stage
        - tags: short status tags (e.g. "is_watching_user", "last_argued")
        - notes: any other uninterrupted context worth remembering about THIS character
        Write at least once per significant turn so future turns stay consistent.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("emotion", buildJsonObject {
                    put("type", "string")
                    put("description", "Current mood / emotional level (optional)")
                })
                put("trust", buildJsonObject {
                    put("type", "string")
                    put("description", "Trust or suspicion level, e.g. a 0-100 number or a short label (optional)")
                })
                put("affinity", buildJsonObject {
                    put("type", "string")
                    put("description", "Long-term affinity value (optional)")
                })
                put("cooldown_text", buildJsonObject {
                    put("type", "string")
                    put("description", "Cooldown note, e.g. 'just argued, cold shoulder for 2h' (optional)")
                })
                put("relationship", buildJsonObject {
                    put("type", "string")
                    put("description", "Overall relationship stage (optional)")
                })
                put("tags", buildJsonObject {
                    put("type", "array")
                    put("items", buildJsonObject {
                        put("type", "string")
                    })
                    put("description", "Short status tags (optional)")
                })
                put("notes", buildJsonObject {
                    put("type", "string")
                    put("description", "Any other state worth persisting (optional)")
                })
            },
            required = null,
        )
    },
    needsApproval = { false },
    execute = {
        val params = it.jsonObject
        val current = readState(dir)
        var state = current
        params["emotion"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { n -> n.isNotEmpty() }
            ?.let { v -> state = state.copy(emotion = v) }
        params["trust"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { n -> n.isNotEmpty() }
            ?.let { v -> state = state.copy(trust = v) }
        params["affinity"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { n -> n.isNotEmpty() }
            ?.let { v -> state = state.copy(affinity = v) }
        params["cooldown_text"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { n -> n.isNotEmpty() }
            ?.let { v -> state = state.copy(cooldownText = v) }
        params["relationship"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { n -> n.isNotEmpty() }
            ?.let { v -> state = state.copy(relationship = v) }
        params["tags"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }
            ?.filter { t -> t.isNotBlank() }?.takeIf { t -> t.isNotEmpty() || current.tags.isNotEmpty() }
            ?.let { v -> state = state.copy(tags = v.toMutableList()) }
        params["notes"]?.jsonPrimitive?.contentOrNull?.trim()?.let { v -> state = state.copy(notes = v) }

        state = state.copy(updatedAt = Instant.now().toString())
        writeState(dir, state)
        listOf(UIMessagePart.Text(buildJsonObject { put("status", "saved") }.toString()))
    },
)

@Serializable
private data class CharacterState(
    val emotion: String = "",
    val trust: String = "",
    val affinity: String = "",
    val cooldownText: String = "",
    val relationship: String = "",
    val tags: MutableList<String> = mutableListOf(),
    val notes: String = "",
    val updatedAt: String? = null,
)

private val stateJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = false
}

private fun readState(dir: File): CharacterState {
    val file = File(dir, "state.json")
    if (!file.exists()) return CharacterState()
    return runCatching { stateJson.decodeFromString<CharacterState>(file.readText()) }
        .getOrDefault(CharacterState())
}

private fun writeState(dir: File, state: CharacterState) {
    runCatching {
        val file = File(dir, "state.json")
        file.writeText(stateJson.encodeToString(state))
    }
}