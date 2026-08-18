package com.aiagents.data.ai.tools.local

import com.aiagents.ai.ui.UIMessagePart
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** 浏览器未打开时的统一错误返回。 */
internal fun browserUnavailable(reason: String = "Browser not open. Call browser_open first."): List<UIMessagePart> =
    listOf(
        UIMessagePart.Text(
            buildJsonObject {
                put("ok", false)
                put("error", reason)
            }.toString()
        )
    )

/** 浏览器未打开的快速判断。 */
internal fun browserReady(): Boolean = com.aiagents.data.automation.BrowserSession.attached

/**
 * 解码 evaluateJavascript 的返回: WebView 会把 JS 值 JSON 序列化后返回,
 * 字符串会被包一层引号。这里把字符串值还原, 对象/数字原样返回文本。
 */
internal fun jsStringResult(result: String): String = runCatching {
    kotlinx.serialization.json.Json.parseToJsonElement(result).jsonPrimitive.contentOrNull
}.getOrNull() ?: result.trim('"')
