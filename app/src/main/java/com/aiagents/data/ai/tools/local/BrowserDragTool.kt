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

/** 在浏览器页面内拖拽。 */
internal fun buildBrowserDragTool(): Tool = Tool(
    name = "browser_drag",
    description = """
        Drag from (x1, y1) to (x2, y2) inside the built-in browser viewport. Useful for sliders, reordering,
        canvas drawing, or swipe-like gestures. Coordinates are viewport pixels, top-left origin.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("x1", buildJsonObject { put("type", "integer"); put("description", "Start X") })
                put("y1", buildJsonObject { put("type", "integer"); put("description", "Start Y") })
                put("x2", buildJsonObject { put("type", "integer"); put("description", "End X") })
                put("y2", buildJsonObject { put("type", "integer"); put("description", "End Y") })
            },
            required = listOf("x1", "y1", "x2", "y2"),
        )
    },
    needsApproval = { false },
    execute = {
        if (!browserReady()) {
            browserUnavailable()
        } else {
            val x1 = it.jsonObject["x1"]?.jsonPrimitive?.intOrNull ?: 0
            val y1 = it.jsonObject["y1"]?.jsonPrimitive?.intOrNull ?: 0
            val x2 = it.jsonObject["x2"]?.jsonPrimitive?.intOrNull ?: 0
            val y2 = it.jsonObject["y2"]?.jsonPrimitive?.intOrNull ?: 0
            BrowserSession.drag(x1, y1, x2, y2)
            listOf(
                UIMessagePart.Text(
                    buildJsonObject {
                        put("ok", true)
                        put("dragged", "$x1,$y1 -> $x2,$y2")
                    }.toString()
                )
            )
        }
    },
)
