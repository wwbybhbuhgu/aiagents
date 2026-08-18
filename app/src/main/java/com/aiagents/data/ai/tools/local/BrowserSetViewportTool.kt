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

/** 设置浏览器视口(分辨率)。 */
internal fun buildBrowserSetViewportTool(): Tool = Tool(
    name = "browser_set_viewport",
    description = """
        Set the built-in browser viewport (rendering resolution) in pixels, then reload the page so it re-renders
        at the new size. Use width=-1 and height=-1 to reset to fill the screen. Useful to emulate a desktop/tablet layout.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("width", buildJsonObject { put("type", "integer"); put("description", "Viewport width in px (-1 to reset)") })
                put("height", buildJsonObject { put("type", "integer"); put("description", "Viewport height in px (-1 to reset)") })
            },
            required = listOf("width", "height"),
        )
    },
    needsApproval = { false },
    execute = {
        if (!browserReady()) {
            browserUnavailable()
        } else {
            val w = it.jsonObject["width"]?.jsonPrimitive?.intOrNull ?: -1
            val h = it.jsonObject["height"]?.jsonPrimitive?.intOrNull ?: -1
            if (w <= 0 || h <= 0) {
                BrowserSession.resetViewport()
            } else {
                BrowserSession.setViewport(w, h)
            }
            listOf(
                UIMessagePart.Text(
                    buildJsonObject {
                        put("ok", true)
                        put("viewport_width", BrowserSession.viewportWidth)
                        put("viewport_height", BrowserSession.viewportHeight)
                    }.toString()
                )
            )
        }
    },
)
