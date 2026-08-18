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

/** 向焦点元素输入文本。 */
internal fun buildBrowserTypeTool(): Tool = Tool(
    name = "browser_type",
    description = """
        Type text into the currently focused element in the built-in browser page (it replaces the current selection).
        Combine with browser_click or browser_fill_form to target a specific input.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("text", buildJsonObject { put("type", "string"); put("description", "Text to type") })
            },
            required = listOf("text"),
        )
    },
    needsApproval = { false },
    execute = {
        if (!browserReady()) {
            browserUnavailable()
        } else {
            val text = it.jsonObject["text"]?.jsonPrimitive?.contentOrNull
            if (text.isNullOrBlank()) {
                listOf(UIMessagePart.Text("""{"error":"BAD_PARAMS","message":"text is required"}"""))
            } else {
                val lit = BrowserSession.jsStringLiteral(text)
                val script = "(() => { var el = document.activeElement; " +
                    "if (!el) return 'NO_FOCUS'; " +
                    "var start = el.selectionStart != null ? el.selectionStart : el.value.length; " +
                    "var end = el.selectionEnd != null ? el.selectionEnd : el.value.length; " +
                    "el.value = el.value.slice(0, start) + $lit + el.value.slice(end); " +
                    "el.dispatchEvent(new Event('input', {bubbles:true})); " +
                    "el.dispatchEvent(new Event('change', {bubbles:true})); " +
                    "return 'TYPED'; })()"
                val result = jsStringResult(BrowserSession.evaluateJs(script).getOrDefault(""))
                listOf(
                    UIMessagePart.Text(
                        buildJsonObject {
                            put("ok", result == "TYPED")
                            put("result", result)
                        }.toString()
                    )
                )
            }
        }
    },
)

/** 向页面发送按键。 */
internal fun buildBrowserPressKeyTool(): Tool = Tool(
    name = "browser_press_key",
    description = """
        Send a keyboard key to the focused element in the built-in browser page. Common keys: Enter, Tab,
        Escape, ArrowUp, ArrowDown, ArrowLeft, ArrowRight, Backspace, Delete, Home, End, PageUp, PageDown.
        Enter will also submit the enclosing form when possible.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("key", buildJsonObject { put("type", "string"); put("description", "Key name to press, e.g. Enter, Tab, Escape") })
            },
            required = listOf("key"),
        )
    },
    needsApproval = { false },
    execute = {
        if (!browserReady()) {
            browserUnavailable()
        } else {
            val key = it.jsonObject["key"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            if (key.isEmpty()) {
                listOf(UIMessagePart.Text("""{"error":"BAD_PARAMS","message":"key is required"}"""))
            } else {
                val lit = BrowserSession.jsStringLiteral(key)
                val script = "(() => { var el = document.activeElement; " +
                    "var target = el || document.body; " +
                    "var e1 = new KeyboardEvent('keydown', {key: $lit, bubbles:true, cancelable:true}); " +
                    "var e2 = new KeyboardEvent('keyup', {key: $lit, bubbles:true, cancelable:true}); " +
                    "target.dispatchEvent(e1); target.dispatchEvent(e2); " +
                    "if ($lit === 'Enter') { var f = el && el.form; if (f) { try { f.requestSubmit(); } catch(e) { f.submit(); } } } " +
                    "return 'SENT'; })()"
                val result = jsStringResult(BrowserSession.evaluateJs(script).getOrDefault(""))
                listOf(
                    UIMessagePart.Text(
                        buildJsonObject {
                            put("ok", result == "SENT")
                            put("key", key)
                            put("result", result)
                        }.toString()
                    )
                )
            }
        }
    },
)

/** 用 JS 直接填充表单控件。 */
internal fun buildBrowserFillFormTool(): Tool = Tool(
    name = "browser_fill_form",
    description = """
        Set the value of a form control (input/textarea/contenteditable) matching a CSS selector in the built-in
        browser page, then fire input/change events. Use this to fill fields reliably.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("selector", buildJsonObject { put("type", "string"); put("description", "CSS selector of the input") })
                put("value", buildJsonObject { put("type", "string"); put("description", "Value to set") })
            },
            required = listOf("selector", "value"),
        )
    },
    needsApproval = { false },
    execute = {
        if (!browserReady()) {
            browserUnavailable()
        } else {
            val selector = it.jsonObject["selector"]?.jsonPrimitive?.contentOrNull
            val value = it.jsonObject["value"]?.jsonPrimitive?.contentOrNull
            if (selector.isNullOrBlank() || value == null) {
                listOf(UIMessagePart.Text("""{"error":"BAD_PARAMS","message":"selector and value are required"}"""))
            } else {
                val sel = BrowserSession.jsStringLiteral(selector)
                val valLit = BrowserSession.jsStringLiteral(value)
                val script = "(() => { var el = document.querySelector($sel); " +
                    "if (!el) return 'NOT_FOUND'; " +
                    "var proto = (el.tagName === 'TEXTAREA') ? HTMLTextAreaElement.prototype : " +
                    "(el.tagName === 'SELECT') ? HTMLSelectElement.prototype : HTMLInputElement.prototype; " +
                    "var setter = Object.getOwnPropertyDescriptor(proto, 'value').set; " +
                    "setter.call(el, $valLit); " +
                    "el.dispatchEvent(new Event('input', {bubbles:true})); " +
                    "el.dispatchEvent(new Event('change', {bubbles:true})); " +
                    "return 'FILLED'; })()"
                val result = jsStringResult(BrowserSession.evaluateJs(script).getOrDefault(""))
                listOf(
                    UIMessagePart.Text(
                        buildJsonObject {
                            put("ok", result == "FILLED")
                            put("result", result)
                        }.toString()
                    )
                )
            }
        }
    },
)

/** 选择下拉选项。 */
internal fun buildBrowserSelectOptionTool(): Tool = Tool(
    name = "browser_select_option",
    description = "Select an option in a <select> dropdown matching a CSS selector in the built-in browser page.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("selector", buildJsonObject { put("type", "string"); put("description", "CSS selector of the select element") })
                put("value", buildJsonObject { put("type", "string"); put("description", "Option value to select") })
            },
            required = listOf("selector", "value"),
        )
    },
    needsApproval = { false },
    execute = {
        if (!browserReady()) {
            browserUnavailable()
        } else {
            val selector = it.jsonObject["selector"]?.jsonPrimitive?.contentOrNull
            val value = it.jsonObject["value"]?.jsonPrimitive?.contentOrNull
            if (selector.isNullOrBlank() || value == null) {
                listOf(UIMessagePart.Text("""{"error":"BAD_PARAMS","message":"selector and value are required"}"""))
            } else {
                val sel = BrowserSession.jsStringLiteral(selector)
                val valLit = BrowserSession.jsStringLiteral(value)
                val script = "(() => { var sel = document.querySelector($sel); " +
                    "if (!sel) return 'NOT_FOUND'; " +
                    "var setter = Object.getOwnPropertyDescriptor(HTMLSelectElement.prototype, 'value').set; " +
                    "setter.call(sel, $valLit); " +
                    "sel.dispatchEvent(new Event('change', {bubbles:true})); " +
                    "return 'SELECTED'; })()"
                val result = jsStringResult(BrowserSession.evaluateJs(script).getOrDefault(""))
                listOf(
                    UIMessagePart.Text(
                        buildJsonObject {
                            put("ok", result == "SELECTED")
                            put("result", result)
                        }.toString()
                    )
                )
            }
        }
    },
)
