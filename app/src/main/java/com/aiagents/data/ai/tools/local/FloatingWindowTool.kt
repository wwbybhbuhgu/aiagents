package com.aiagents.data.ai.tools.local

import com.aiagents.ai.core.InputSchema
import com.aiagents.ai.core.Tool
import com.aiagents.ai.ui.UIMessagePart
import com.aiagents.data.automation.FloatingWindowController
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * 悬浮窗控制工具: 允许 AI 自发地把悬浮窗最小化/恢复, 或移动到指定位置。
 * window 可为 "browser"(内置浏览器自动化) 或 "chat"(聊天悬浮窗)。
 */
internal fun buildFloatingWindowTool(): Tool = Tool(
    name = "floating_window",
    description = """
        Control the app's floating overlay windows. Overlays are small windows that float on top of other apps.
        There are two overlays: "browser" (the built-in AI browser) and "chat" (the chat floating window).
        Actions:
        - "list": list available overlays and their position/minimized state.
        - "move": move an overlay to an absolute pixel position (x, y). Coordinates are clamped to the screen.
        - "minimize": collapse an overlay into a small pill. Pass "window"="all" or a specific id.
        - "restore": expand a minimized overlay back to its full size.
        Use "list" first to discover overlays, screen size, and current states.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("window", buildJsonObject {
                    put("type", "string")
                    put("description", "Overlay id: \"browser\", \"chat\", or \"all\" (default \"all\").")
                })
                put("action", buildJsonObject {
                    put("type", "string")
                    put("description", "One of: list, move, minimize, restore, status. Default: list.")
                })
                put("x", buildJsonObject { put("type", "integer"); put("description", "Target X position (pixels) for \"move\".") })
                put("y", buildJsonObject { put("type", "integer"); put("description", "Target Y position (pixels) for \"move\".") })
            },
        )
    },
    needsApproval = { false },
    execute = {
        val params = it.jsonObject
        val windowArg = params["window"]?.jsonPrimitive?.contentOrNull ?: "all"
        val action = params["action"]?.jsonPrimitive?.contentOrNull ?: "list"
        val x = params["x"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
        val y = params["y"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()

        val targets = FloatingWindowController.list().filter { w ->
            windowArg == "all" || w.id == windowArg
        }

        when (action) {
            "list" -> listOf(
                UIMessagePart.Text(
                    buildJsonObject {
                        put("action", "list")
                        put("windows", buildJsonArray {
                            FloatingWindowController.list().forEach { w -> add(windowJson(w)) }
                        })
                    }.toString()
                )
            )

            "move" -> {
                if (x == null || y == null) {
                    listOf(
                        UIMessagePart.Text(
                            buildJsonObject {
                                put("error", "BAD_PARAMS")
                                put("message", "\"move\" requires integer x and y")
                            }.toString()
                        )
                    )
                } else if (targets.isEmpty()) {
                    listOf(UIMessagePart.Text(buildJsonObject {
                        put("error", "NOT_FOUND")
                        put("message", "No overlay \"$windowArg\" is currently showing.")
                    }.toString()))
                } else {
                    targets.forEach { it.moveTo(x, y) }
                    listOf(
                        UIMessagePart.Text(
                            buildJsonObject {
                                put("action", "move")
                                put("windows", buildJsonArray { targets.forEach { add(windowJson(it)) } })
                            }.toString()
                        )
                    )
                }
            }

            "minimize" -> {
                targets.forEach { it.setMinimizedState(true) }
                listOf(
                    UIMessagePart.Text(
                        buildJsonObject {
                            put("action", "minimize")
                            put("windows", buildJsonArray { targets.forEach { add(windowJson(it)) } })
                        }.toString()
                    )
                )
            }

            "restore" -> {
                targets.forEach { it.setMinimizedState(false) }
                listOf(
                    UIMessagePart.Text(
                        buildJsonObject {
                            put("action", "restore")
                            put("windows", buildJsonArray { targets.forEach { add(windowJson(it)) } })
                        }.toString()
                    )
                )
            }

            "status" -> {
                listOf(
                    UIMessagePart.Text(
                        buildJsonObject {
                            put("action", "status")
                            put("windows", buildJsonArray { targets.forEach { add(windowJson(it)) } })
                        }.toString()
                    )
                )
            }

            else -> listOf(
                UIMessagePart.Text(
                    buildJsonObject {
                        put("error", "BAD_PARAMS")
                        put("message", "Unknown action \"$action\". Use list, move, minimize, restore or status.")
                    }.toString()
                )
            )
        }
    },
)

private fun windowJson(w: FloatingWindowController.Window): kotlinx.serialization.json.JsonObject =
    buildJsonObject {
        put("window", w.id)
        put("x", w.x)
        put("y", w.y)
        put("minimized", w.isMinimized)
        put("screen_width", w.screenWidth)
        put("screen_height", w.screenHeight)
    }
