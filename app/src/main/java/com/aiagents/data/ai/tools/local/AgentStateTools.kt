package com.aiagents.data.ai.tools.local

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import com.aiagents.ai.core.InputSchema
import com.aiagents.ai.core.Tool
import com.aiagents.ai.ui.UIMessagePart
import com.aiagents.data.model.Assistant
import java.io.File
import java.time.Instant

/**
 * Agent 状态持久化工具（agent_state_get / agent_state_set）。
 *
 * 每个助手（Agent）有独立的状态存储，支持任意键值对。
 * 用于存储 Agent 的上下文状态、进度、配置等任意信息。
 * 
 * @param context Android Context
 * @param assistantId 当前助手 ID，用于隔离不同助手的状态
 */
internal fun buildAgentStateTools(
    context: Context,
    assistantId: String? = null,
): List<Tool> {
    // 按助手 ID 隔离状态存储
    val stateDir = if (assistantId != null) {
        File(context.filesDir, "agent_states/$assistantId").apply { mkdirs() }
    } else {
        File(context.filesDir, "agent_states/global").apply { mkdirs() }
    }
    return listOf(
        buildAgentStateGet(stateDir),
        buildAgentStateSet(stateDir),
    )
}

private fun buildAgentStateGet(dir: File): Tool = Tool(
    name = "agent_state_get",
    description = """
        Read the persistent state of this agent. State is isolated per assistant/conversation.
        Use this to restore context, track progress, or remember arbitrary information across turns.
        Returns the stored key-value pairs as a JSON object. Returns empty object if no state saved yet.
    """.trimIndent(),
    parameters = {
        InputSchema.Obj(properties = buildJsonObject { })
    },
    needsApproval = { false },
    execute = {
        val state = readState(dir)
        val result = buildJsonObject {
            state.data.forEach { (key, value) ->
                put(key, value)
            }
            if (state.updatedAt != null) {
                put("_updated_at", state.updatedAt)
            }
        }
        listOf(UIMessagePart.Text(result.toString()))
    },
)

private fun buildAgentStateSet(dir: File): Tool = Tool(
    name = "agent_state_set",
    description = """
        Update the persistent state of this agent. Store any key-value pairs you need.
        State is isolated per assistant and persists across conversations.
        - To SET a key: provide "key" and "value"
        - To DELETE a key: provide "key" and set "delete" to true
        - To CLEAR all state: set "clear_all" to true
        Example uses: track task progress, remember user preferences, store computed values, etc.
    """.trimIndent(),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("key", buildJsonObject {
                    put("type", "string")
                    put("description", "State key to set or delete (optional)")
                })
                put("value", buildJsonObject {
                    put("type", "string")
                    put("description", "Value to store for the key (optional, ignored if delete=true)")
                })
                put("delete", buildJsonObject {
                    put("type", "boolean")
                    put("description", "If true, delete the specified key (optional, default false)")
                })
                put("clear_all", buildJsonObject {
                    put("type", "boolean")
                    put("description", "If true, clear ALL state for this agent (optional, default false)")
                })
            },
            required = null,
        )
    },
    needsApproval = { false },
    execute = {
        val params = it.jsonObject
        val clearAll = params["clear_all"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false
        
        if (clearAll) {
            writeState(dir, AgentState())
            listOf(UIMessagePart.Text(buildJsonObject { put("status", "cleared") }.toString()))
        } else {
            val key = params["key"]?.jsonPrimitive?.contentOrNull?.trim()
            if (key.isNullOrBlank()) {
                listOf(UIMessagePart.Text(buildJsonObject { 
                    put("error", "key is required unless clear_all=true") 
                }.toString()))
            } else {
                val shouldDelete = params["delete"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false
                val value = params["value"]?.jsonPrimitive?.contentOrNull
                
                val current = readState(dir)
                val newData = current.data.toMutableMap()
                
                if (shouldDelete) {
                    newData.remove(key)
                } else if (value != null) {
                    newData[key] = value
                }
                
                val newState = AgentState(
                    data = newData,
                    updatedAt = Instant.now().toString()
                )
                writeState(dir, newState)
                
                listOf(UIMessagePart.Text(buildJsonObject { 
                    put("status", if (shouldDelete) "deleted" else "saved")
                    put("key", key)
                }.toString()))
            }
        }
    },
)

@Serializable
private data class AgentState(
    val data: Map<String, String> = emptyMap(),
    val updatedAt: String? = null,
)

private val stateJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = false
}

private fun readState(dir: File): AgentState {
    val file = File(dir, "state.json")
    if (!file.exists()) return AgentState()
    return runCatching { stateJson.decodeFromString<AgentState>(file.readText()) }
        .getOrDefault(AgentState())
}

private fun writeState(dir: File, state: AgentState) {
    runCatching {
        val file = File(dir, "state.json")
        file.writeText(stateJson.encodeToString(state))
    }
}
