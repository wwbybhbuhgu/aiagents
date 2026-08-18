package com.aiagents.data.ai.tools.local

import com.aiagents.ai.core.InputSchema
import com.aiagents.ai.core.Tool
import com.aiagents.ai.ui.UIMessagePart
import com.aiagents.data.automation.BrowserSession
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** 读取浏览器页面最近发起的网络请求。 */
internal fun buildBrowserNetworkRequestsTool(): Tool = Tool(
    name = "browser_network_requests",
    description = """
        Return the most recent network requests made by the built-in browser page (URLs and kind:
        navigation or resource). Useful to see what the page loaded or detect failures.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("limit", buildJsonObject { put("type", "integer"); put("description", "Max requests to return (default 20, max 100)") })
            },
            required = listOf(),
        )
    },
    needsApproval = { false },
    execute = {
        if (!browserReady()) {
            browserUnavailable()
        } else {
            val limit = (it.jsonObject["limit"]?.jsonPrimitive?.intOrNull ?: 20).coerceIn(1, 100)
            val requests = BrowserSession.recentNetworkRequests(limit)
            listOf(
                UIMessagePart.Text(
                    buildJsonObject {
                        put("ok", true)
                        put("count", requests.size)
                        put(
                            "requests",
                            buildJsonArray {
                                requests.forEach { r ->
                                    add(
                                        buildJsonObject {
                                            put("url", r.url)
                                            put("kind", r.kind)
                                        }
                                    )
                                }
                            }
                        )
                    }.toString()
                )
            )
        }
    },
)
