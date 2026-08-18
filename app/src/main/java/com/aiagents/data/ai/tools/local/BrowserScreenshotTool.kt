package com.aiagents.data.ai.tools.local

import android.content.Context
import com.aiagents.ai.core.Tool
import com.aiagents.ai.ui.UIMessagePart
import com.aiagents.data.automation.BrowserSession
import com.aiagents.data.automation.ShizukuController
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

private const val BROWSER_SCREENSHOT_PREFIX = "browser_"

/** 截取内置浏览器当前视口。 */
internal fun buildBrowserScreenshotTool(context: Context): Tool = Tool(
    name = "browser_take_screenshot",
    description = """
        Take a screenshot of the built-in browser's current viewport and save it to the shared AI-Agent/screenshots
        directory. Returns the absolute file path and workspace path (/screenshots/<name>), so you can analyze the
        page (via image tools) or read coordinates for browser_click.
    """.trimIndent().replace("\n", " "),
    parameters = { null },
    needsApproval = { false },
    execute = {
        if (!browserReady()) {
            browserUnavailable()
        } else {
            val dir = ShizukuController.screenshotDir(context)
            val file = File(dir, "${BROWSER_SCREENSHOT_PREFIX}${System.currentTimeMillis()}.png")
            val ok = BrowserSession.screenshot(file)
            listOf(
                UIMessagePart.Text(
                    buildJsonObject {
                        put("ok", ok)
                        if (ok) {
                            put("path", file.absolutePath)
                            put("workspace_path", "/screenshots/${file.name}")
                            put("size_bytes", file.length())
                            put("viewport_width", BrowserSession.viewportWidth)
                            put("viewport_height", BrowserSession.viewportHeight)
                        } else {
                            put("error", "Screenshot failed: browser not attached or not rendered")
                        }
                    }.toString()
                )
            )
        }
    },
)
