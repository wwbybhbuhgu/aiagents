package com.aiagents.data.ai.tools.local

import com.aiagents.ai.core.Tool
import com.aiagents.ai.ui.UIMessagePart
import com.aiagents.data.automation.BrowserSession
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** 读取浏览器页面控制台消息。 */
internal fun buildBrowserConsoleMessagesTool(): Tool = Tool(
    name = "browser_console_messages",
    description = """
        Return recent console messages from the built-in browser page (errors/warnings/logs).
        Useful for debugging why a page or script is not behaving.
    """.trimIndent().replace("\n", " "),
    parameters = { null },
    needsApproval = { false },
    execute = {
        if (!browserReady()) {
            browserUnavailable()
        } else {
            val messages = BrowserSession.state.consoleMessages.takeLast(30).map { c ->
                val level = when (c.messageLevel()) {
                    android.webkit.ConsoleMessage.MessageLevel.ERROR -> "error"
                    android.webkit.ConsoleMessage.MessageLevel.WARNING -> "warning"
                    android.webkit.ConsoleMessage.MessageLevel.DEBUG -> "debug"
                    else -> "log"
                }
                "${c.message().take(400)} [@${c.lineNumber()}]"
            }
            listOf(
                UIMessagePart.Text(
                    buildJsonObject {
                        put("ok", true)
                        put("count", messages.size)
                        put("messages", kotlinx.serialization.json.JsonArray(messages.map { kotlinx.serialization.json.JsonPrimitive(it) }))
                    }.toString()
                )
            )
        }
    },
)
