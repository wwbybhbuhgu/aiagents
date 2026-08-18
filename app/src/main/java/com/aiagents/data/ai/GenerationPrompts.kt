package com.aiagents.data.ai

import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import com.aiagents.data.model.AssistantMemory
import com.aiagents.utils.JsonInstantPretty

internal fun buildMemoryPrompt(memories: List<AssistantMemory>, memoryRootPath: String) =
    buildString {
        appendLine()
        append("**Memories**")
        appendLine()
        append("这些记忆由 memory_tool 存储, 以文件形式保存在 '$memoryRootPath'(每条记忆是一个目录, 含 MEMORY.md 与可选附件)。")
        appendLine()
        append("下面只列出每条记忆的概括(description), 需要完整文本时用 memory_tool 的 `read` 按 id 获取, 也可用文件工具直接读 '$memoryRootPath/<id>/MEMORY.md'。")
        appendLine()
        append("可在之后的对话中参考; 如需调整可用 memory_tool 或直接读写这些文件。")
        appendLine()
        if (memories.isEmpty()) {
            append("(当前没有记忆)")
        } else {
            val json = buildJsonArray {
                memories.forEach { memory ->
                    add(buildJsonObject {
                        put("id", memory.id)
                        put("name", memory.name)
                        put("description", memory.description)
                    })
                }
            }
            append(JsonInstantPretty.encodeToString(json))
        }
        appendLine()
    }
