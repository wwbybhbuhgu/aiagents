package com.aiagents.data.ai.tools.local

import android.content.Context
import com.aiagents.ai.core.Tool
import com.aiagents.data.event.AppEventBus

/**
 * 内置浏览器自动化工具集(AI 发起, 参考 playwright/browser MCP)。
 * 控制 [com.aiagents.data.automation.BrowserSession] 持有的内置 WebView。
 * 在助手开启 enableBrowserAutomation 时注入。
 */
internal fun buildBrowserTools(context: Context, eventBus: AppEventBus): List<Tool> = listOf(
    buildBrowserOpenTool(eventBus),
    buildBrowserInfoTool(),
    buildBrowserSetUserAgentTool(),
    buildBrowserGoBackTool(),
    buildBrowserGoForwardTool(),
    buildBrowserReloadTool(),
    buildBrowserCloseTool(eventBus),
    buildBrowserSnapshotTool(),
    buildBrowserScreenshotTool(context),
    buildBrowserSetViewportTool(),
    buildBrowserClickTool(),
    buildBrowserHoverTool(),
    buildBrowserDragTool(),
    buildBrowserScrollTool(),
    buildBrowserScrollToTool(),
    buildBrowserTypeTool(),
    buildBrowserPressKeyTool(),
    buildBrowserFillFormTool(),
    buildBrowserSelectOptionTool(),
    buildBrowserEvaluateTool(),
    buildBrowserWaitForTool(),
    buildBrowserConsoleMessagesTool(),
    buildBrowserNetworkRequestsTool(),
    buildBrowserHandleDialogTool(),
)
