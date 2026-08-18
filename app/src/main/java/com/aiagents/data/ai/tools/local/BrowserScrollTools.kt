package com.aiagents.data.ai.tools.local

import com.aiagents.ai.core.InputSchema
import com.aiagents.ai.core.Tool
import com.aiagents.ai.ui.UIMessagePart
import com.aiagents.data.automation.BrowserSession
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

private fun scrollTool(name: String, description: String, mode: String): Tool = Tool(
    name = name,
    description = description,
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("dx", buildJsonObject { put("type", "integer"); put("description", "Horizontal offset (px)") })
                put("dy", buildJsonObject { put("type", "integer"); put("description", "Vertical offset (px)") })
            },
            required = if (mode == "by") listOf("dy") else listOf("dx", "dy"),
        )
    },
    needsApproval = { false },
    execute = {
        if (!browserReady()) {
            browserUnavailable()
        } else {
            val x = it.jsonObject["dx"]?.jsonPrimitive?.intOrNull ?: 0
            val y = it.jsonObject["dy"]?.jsonPrimitive?.intOrNull ?: 0
            if (mode == "by") BrowserSession.scroll(x, y) else BrowserSession.scrollTo(x, y)
            listOf(
                UIMessagePart.Text(
                    buildJsonObject {
                        put("ok", true)
                        put(mode, "$x,$y")
                    }.toString()
                )
            )
        }
    },
)

/** 相对滚动。 */
internal fun buildBrowserScrollTool(): Tool = scrollTool(
    name = "browser_scroll",
    description = "Scroll the built-in browser page by an offset (dx, dy) in pixels (positive y scrolls down).",
    mode = "by",
)

/** 绝对滚动。 */
internal fun buildBrowserScrollToTool(): Tool = scrollTool(
    name = "browser_scroll_to",
    description = "Scroll the built-in browser page to an absolute position (dx, dy) in pixels.",
    mode = "to",
)
