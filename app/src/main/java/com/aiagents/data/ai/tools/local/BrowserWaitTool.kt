package com.aiagents.data.ai.tools.local

import com.aiagents.ai.core.InputSchema
import com.aiagents.ai.core.Tool
import com.aiagents.ai.ui.UIMessagePart
import com.aiagents.data.automation.BrowserSession
import kotlinx.coroutines.delay
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** 等待页面元素出现或页面加载完成。 */
internal fun buildBrowserWaitForTool(): Tool = Tool(
    name = "browser_wait_for",
    description = """
        Wait until a CSS selector matches an element in the built-in browser page (or the page finishes loading),
        up to a timeout. Returns whether the condition was met. Use this after navigation or before interacting
        with dynamically-loaded content.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("selector", buildJsonObject { put("type", "string"); put("description", "CSS selector to wait for") })
                put("timeout_ms", buildJsonObject { put("type", "integer"); put("description", "Max wait time in ms (default 5000)") })
            },
            required = listOf(),
        )
    },
    needsApproval = { false },
    execute = {
        if (!browserReady()) {
            browserUnavailable()
        } else {
            val selector = it.jsonObject["selector"]?.jsonPrimitive?.contentOrNull
            val timeoutMs = (it.jsonObject["timeout_ms"]?.jsonPrimitive?.intOrNull ?: 5000).coerceIn(0, 60000)
            if (selector.isNullOrBlank()) {
                // 等待页面加载完成
                val result = jsStringResult(
                    BrowserSession.evaluateJs("({ ready: document.readyState, url: location.href })").getOrDefault("{}")
                )
                listOf(
                    UIMessagePart.Text(
                        buildJsonObject {
                            put("ok", result.contains("\"complete\""))
                            put("page_state", result.take(2000))
                        }.toString()
                    )
                )
            } else {
                val sel = BrowserSession.jsStringLiteral(selector)
                val deadline = System.currentTimeMillis() + timeoutMs
                var found = false
                var lastError = ""
                while (System.currentTimeMillis() < deadline) {
                    val check = BrowserSession.evaluateJs("!!document.querySelector($sel)")
                        .getOrDefault("false")
                    val parsed = jsStringResult(check)
                    if (parsed == "true") {
                        found = true
                        break
                    }
                    if (parsed != "false") lastError = parsed
                    delay(200)
                }
                listOf(
                    UIMessagePart.Text(
                        buildJsonObject {
                            put("ok", found)
                            put("selector", selector)
                            put("result", if (found) "FOUND" else "TIMEOUT")
                            if (lastError.isNotBlank()) put("error", lastError)
                        }.toString()
                    )
                )
            }
        }
    },
)
