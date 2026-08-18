package com.aiagents.data.ai.tools.local

import com.aiagents.ai.core.InputSchema
import com.aiagents.ai.core.Tool
import com.aiagents.ai.ui.UIMessagePart
import com.aiagents.data.automation.BrowserSession
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** 在浏览器页面执行任意 JS。 */
internal fun buildBrowserEvaluateTool(): Tool = Tool(
    name = "browser_evaluate",
    description = """
        Execute JavaScript in the built-in browser page and return the result string.
        Useful for reading page text (document.body.innerText), extracting links, clicking elements
        (document.querySelector(selector).click()), or manipulating the page. Return only serializable data.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("script", buildJsonObject { put("type", "string"); put("description", "The JavaScript expression to evaluate") })
            },
            required = listOf("script"),
        )
    },
    needsApproval = { false },
    execute = {
        if (!browserReady()) {
            browserUnavailable()
        } else {
            val script = it.jsonObject["script"]?.jsonPrimitive?.contentOrNull
            if (script.isNullOrBlank()) {
                listOf(UIMessagePart.Text("""{"error":"BAD_PARAMS","message":"script is required"}"""))
            } else {
                BrowserSession.evaluateJs(script).fold(
                    onSuccess = { result ->
                        listOf(
                            UIMessagePart.Text(
                                buildJsonObject {
                                    put("ok", true)
                                    put("result", result.take(8000))
                                }.toString()
                            )
                        )
                    },
                    onFailure = { e -> browserUnavailable(e.message ?: "JS execution failed") },
                )
            }
        }
    },
)
