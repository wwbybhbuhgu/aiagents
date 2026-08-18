package com.aiagents.data.ai.tools.local

import com.aiagents.ai.core.InputSchema
import com.aiagents.ai.core.Tool
import com.aiagents.ai.ui.UIMessagePart
import com.aiagents.data.automation.BrowserSession
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** 悬停到页面元素。 */
internal fun buildBrowserHoverTool(): Tool = Tool(
    name = "browser_hover",
    description = """
        Hover over an element in the built-in browser page, by (x, y) viewport coordinates or a CSS selector.
        Useful to reveal dropdowns or hover menus.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("x", buildJsonObject { put("type", "integer"); put("description", "X coordinate in the browser viewport") })
                put("y", buildJsonObject { put("type", "integer"); put("description", "Y coordinate in the browser viewport") })
                put("selector", buildJsonObject { put("type", "string"); put("description", "CSS selector of the element to hover") })
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
            val x = it.jsonObject["x"]?.jsonPrimitive?.intOrNull ?: -1
            val y = it.jsonObject["y"]?.jsonPrimitive?.intOrNull ?: -1
            val script = buildString {
                append("(() => { var el = null; ")
                if (!selector.isNullOrBlank()) {
                    append("el = document.querySelector(")
                    append(BrowserSession.jsStringLiteral(selector))
                    append("); ")
                } else if (x >= 0 && y >= 0) {
                    append("el = document.elementFromPoint($x, $y); ")
                }
                append("if (!el) return 'NOT_FOUND'; ")
                append("var e1 = new MouseEvent('mouseover', {bubbles:true, cancelable:true}); ")
                append("var e2 = new MouseEvent('mouseenter', {bubbles:false, cancelable:true}); ")
                append("var e3 = new MouseEvent('mousemove', {bubbles:true, cancelable:true}); ")
                append("el.dispatchEvent(e1); el.dispatchEvent(e2); el.dispatchEvent(e3); ")
                append("return 'HOVERED'; })()")
            }
            val result = jsStringResult(BrowserSession.evaluateJs(script).getOrDefault(""))
            listOf(
                UIMessagePart.Text(
                    buildJsonObject {
                        put("ok", result == "HOVERED")
                        put("result", result)
                    }.toString()
                )
            )
        }
    },
)
