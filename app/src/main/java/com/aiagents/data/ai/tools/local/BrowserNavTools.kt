package com.aiagents.data.ai.tools.local

import com.aiagents.ai.core.Tool
import com.aiagents.ai.ui.UIMessagePart
import com.aiagents.data.automation.BrowserSession
import com.aiagents.data.event.AppEvent
import com.aiagents.data.event.AppEventBus
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private fun navTool(name: String, description: String, action: () -> Unit): Tool = Tool(
    name = name,
    description = description,
    parameters = { null },
    needsApproval = { false },
    execute = {
        if (!browserReady()) {
            browserUnavailable()
        } else {
            action()
            listOf(UIMessagePart.Text(buildJsonObject { put("ok", true) }.toString()))
        }
    },
)

/** 内置浏览器后退。 */
internal fun buildBrowserGoBackTool(): Tool = navTool(
    name = "browser_go_back",
    description = "Go back in the built-in browser history.",
) { BrowserSession.goBack() }

/** 内置浏览器前进。 */
internal fun buildBrowserGoForwardTool(): Tool = navTool(
    name = "browser_go_forward",
    description = "Go forward in the built-in browser history.",
) { BrowserSession.goForward() }

/** 内置浏览器刷新。 */
internal fun buildBrowserReloadTool(): Tool = navTool(
    name = "browser_reload",
    description = "Reload the current page in the built-in browser.",
) { BrowserSession.reload() }

/** 关闭内置浏览器自动化页面。 */
internal fun buildBrowserCloseTool(eventBus: AppEventBus): Tool = Tool(
    name = "browser_close",
    description = "Close the built-in AI browser automation page and release the browser session.",
    parameters = { null },
    needsApproval = { false },
    execute = {
        BrowserSession.detach(BrowserSession.state.webView ?: return@Tool browserUnavailable())
        eventBus.emit(AppEvent.CloseBuiltInBrowser)
        listOf(UIMessagePart.Text(buildJsonObject { put("ok", true); put("closed", true) }.toString()))
    },
)
