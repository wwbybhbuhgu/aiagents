package com.aiagents.data.ai.tools.local

import kotlinx.coroutines.delay
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import com.aiagents.ai.core.InputSchema
import com.aiagents.ai.core.Tool
import com.aiagents.ai.ui.UIMessagePart

/**
 * 等待工具: 让 AI 在动作之间暂停指定时长(如等页面加载、动画结束、UI 稳定),
 * 无需再用工作区 shell 执行 sleep。
 */
internal fun buildWaitTool(): Tool = Tool(
    name = "wait",
    description = """
        Wait (sleep) for a specified duration before continuing.
        Use this to pause between actions — e.g. wait for a page to load, an animation to finish,
        a UI to settle, or a network request to complete — instead of running a shell sleep.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("ms", buildJsonObject {
                    put("type", "integer")
                    put("description", "Duration to wait in milliseconds (max 300000).")
                })
            },
            required = listOf("ms")
        )
    },
    execute = {
        val ms = it.jsonObject["ms"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
            ?: error("ms is required")
        val waited = ms.coerceIn(0, 300_000)
        delay(waited)
        val payload = buildJsonObject {
            put("success", true)
            put("waited_ms", waited)
        }
        listOf(UIMessagePart.Text(payload.toString()))
    }
)
