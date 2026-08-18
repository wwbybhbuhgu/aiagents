package com.aiagents.data.ai.tools.local

import com.aiagents.ai.core.Tool
import com.aiagents.ai.ui.UIMessagePart
import com.aiagents.data.automation.BrowserSession
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** 查询浏览器当前状态。 */
internal fun buildBrowserInfoTool(): Tool = Tool(
    name = "browser_info",
    description = """
        Get the current state of the built-in AI browser: current URL, page title, whether back/forward is possible,
        viewport size, and whether the browser is attached. Call this first to know where the browser is.
    """.trimIndent().replace("\n", " "),
    parameters = { null },
    needsApproval = { false },
    execute = {
        listOf(
            UIMessagePart.Text(
                buildJsonObject {
                    put("ok", BrowserSession.attached)
                    put("attached", BrowserSession.attached)
                    put("url", BrowserSession.currentUrl())
                    put("title", BrowserSession.state.pageTitle ?: "")
                    put("can_go_back", BrowserSession.state.canGoBack)
                    put("can_go_forward", BrowserSession.state.canGoForward)
                    put("loading", BrowserSession.state.isLoading)
                    put("viewport_width", BrowserSession.viewportWidth)
                    put("viewport_height", BrowserSession.viewportHeight)
                    if (!BrowserSession.attached) {
                        put("error", "Browser not open. Call browser_open first.")
                    }
                }.toString()
            )
        )
    },
)
