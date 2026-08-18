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

/** 设置内置浏览器的 User-Agent。 */
internal fun buildBrowserSetUserAgentTool(): Tool = Tool(
    name = "browser_set_user_agent",
    description = """
        Set the built-in browser's User-Agent string for this automation session, then the next page load uses it.
        Useful to emulate a desktop browser or a specific device. Passing an empty value resets to the default UA.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("user_agent", buildJsonObject { put("type", "string"); put("description", "The User-Agent string to use; empty to reset to default") })
            },
            required = listOf("user_agent"),
        )
    },
    needsApproval = { false },
    execute = {
        if (!browserReady()) {
            browserUnavailable()
        } else {
            val ua = it.jsonObject["user_agent"]?.jsonPrimitive?.contentOrNull
            BrowserSession.updateUserAgent(ua)
            listOf(
                UIMessagePart.Text(
                    buildJsonObject {
                        put("ok", true)
                        put("user_agent", BrowserSession.userAgent ?: "default")
                    }.toString()
                )
            )
        }
    },
)
