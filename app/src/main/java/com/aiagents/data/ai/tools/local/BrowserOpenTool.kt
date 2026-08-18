package com.aiagents.data.ai.tools.local

import com.aiagents.ai.core.InputSchema
import com.aiagents.ai.core.Tool
import com.aiagents.ai.ui.UIMessagePart
import com.aiagents.data.automation.BrowserSession
import com.aiagents.data.event.AppEvent
import com.aiagents.data.event.AppEventBus
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** 打开内置浏览器自动化页面。 */
internal fun buildBrowserOpenTool(eventBus: AppEventBus): Tool = Tool(
    name = "browser_open",
    description = """
        Open a URL in the app's built-in AI browser (in-app WebView) and bring the browser to the front.
        Use this to start or switch browser automation. Subsequent browser_* tools control this session.
        The built-in browser is different from open_url (which opens the system browser).
        Optionally set a custom User-Agent via the "user_agent" parameter (e.g. a desktop browser UA) to
        make the page render as if opened by that browser.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("url", buildJsonObject {
                    put("type", "string")
                    put("description", "The http(s) URL to open in the built-in browser")
                })
                put("user_agent", buildJsonObject {
                    put("type", "string")
                    put("description", "Optional custom User-Agent string, e.g. a desktop Chrome UA. Leave empty to use the default")
                })
            },
            required = listOf("url"),
        )
    },
    needsApproval = { false },
    execute = {
        val params = it.jsonObject
        val url = params["url"]?.jsonPrimitive?.contentOrNull
        if (url.isNullOrBlank()) {
            listOf(UIMessagePart.Text("""{"error":"BAD_PARAMS","message":"url is required"}"""))
        } else {
            val ua = params["user_agent"]?.jsonPrimitive?.contentOrNull
            BrowserSession.updateUserAgent(ua)
            BrowserSession.loadUrl(url)
            eventBus.emit(AppEvent.OpenBuiltInBrowser(url))
            listOf(
                UIMessagePart.Text(
                    buildJsonObject {
                        put("ok", true)
                        put("opened", true)
                        put("browser", "built_in")
                        put("user_agent", BrowserSession.userAgent ?: "default")
                    }.toString()
                )
            )
        }
    },
)
