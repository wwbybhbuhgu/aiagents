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

/** 处理页面弹出的 JS 对话框(alert/confirm/prompt)。 */
internal fun buildBrowserHandleDialogTool(): Tool = Tool(
    name = "browser_handle_dialog",
    description = """
        Answer a JavaScript dialog (alert/confirm/prompt) currently shown by the built-in browser page.
        Call browser_info or check state first; if there is no pending dialog it returns "no pending dialog".
        For a prompt, provide the text to submit in "input".
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("accept", buildJsonObject { put("type", "boolean"); put("description", "Accept (true) or dismiss (false) the dialog") })
                put("input", buildJsonObject { put("type", "string"); put("description", "Text to submit for a prompt dialog") })
            },
            required = listOf("accept"),
        )
    },
    needsApproval = { false },
    execute = {
        if (!browserReady()) {
            browserUnavailable()
        } else {
            val accept = it.jsonObject["accept"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false
            val input = it.jsonObject["input"]?.jsonPrimitive?.contentOrNull
            val result = BrowserSession.answerDialog(accept, input)
            listOf(
                UIMessagePart.Text(
                    buildJsonObject {
                        put("ok", true)
                        put("result", result)
                    }.toString()
                )
            )
        }
    },
)
