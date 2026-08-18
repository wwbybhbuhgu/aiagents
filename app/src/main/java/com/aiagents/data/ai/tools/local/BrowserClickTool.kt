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

/** 在浏览器页面内点击(坐标或选择器)。 */
internal fun buildBrowserClickTool(): Tool = Tool(
    name = "browser_click",
    description = """
        Click in the built-in browser page. Either give (x, y) viewport coordinates (top-left origin, x rightward,
        y downward; read them from browser_snapshot or browser_take_screenshot), or a CSS selector to click the
        first matching element.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("x", buildJsonObject { put("type", "integer"); put("description", "X coordinate in the browser viewport") })
                put("y", buildJsonObject { put("type", "integer"); put("description", "Y coordinate in the browser viewport") })
                put("selector", buildJsonObject { put("type", "string"); put("description", "CSS selector of the element to click (alternative to x/y)") })
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
            if (!selector.isNullOrBlank()) {
                val script = "(() => { var el = document.querySelector(${BrowserSession.jsStringLiteral(selector)}); " +
                    "if (!el) return 'NOT_FOUND'; el.scrollIntoView({block:'center'}); el.click(); return 'CLICKED'; })()"
                val result = jsStringResult(BrowserSession.evaluateJs(script).getOrDefault(""))
                listOf(
                    UIMessagePart.Text(
                        buildJsonObject {
                            put("ok", result == "CLICKED")
                            put("selector", selector)
                            put("result", result)
                        }.toString()
                    )
                )
            } else {
                val x = it.jsonObject["x"]?.jsonPrimitive?.intOrNull ?: 0
                val y = it.jsonObject["y"]?.jsonPrimitive?.intOrNull ?: 0
                BrowserSession.click(x, y)
                listOf(UIMessagePart.Text(buildJsonObject { put("ok", true); put("clicked", "$x,$y") }.toString()))
            }
        }
    },
)
