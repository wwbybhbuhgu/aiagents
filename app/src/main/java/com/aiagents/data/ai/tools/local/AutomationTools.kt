package com.aiagents.data.ai.tools.local

import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.aiagents.ai.core.InputSchema
import com.aiagents.ai.core.Tool
import com.aiagents.ai.ui.UIMessagePart
import com.aiagents.data.automation.AutoAccessibilityController
import com.aiagents.data.automation.FloatingWindowController
import com.aiagents.data.automation.ShizukuController
import kotlinx.coroutines.delay
import com.aiagents.data.event.AppEvent
import com.aiagents.data.event.AppEventBus
import com.aiagents.data.ai.tools.overlayGridBytes
import com.aiagents.data.ai.tools.recognizeTextBlocks
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File

private suspend fun accessibilityBlocked(eventBus: AppEventBus): List<UIMessagePart> {
    eventBus.emit(AppEvent.OpenAccessibilitySettings)
    return listOf(
        UIMessagePart.Text(
            buildJsonObject {
                put("error", "NO_PERMISSION")
                put(
                    "message",
                    "Accessibility service is not enabled. The system accessibility settings page has been opened; ask the user to enable the AI Agents accessibility service, then retry."
                )
            }.toString()
        )
    )
}

internal fun shizukuBlocked(): List<UIMessagePart> {
    ShizukuController.requestPermission()
    return listOf(
        UIMessagePart.Text(
            buildJsonObject {
                put("error", "NO_SHIZUKU")
                put("message", "Shizuku is not running or permission not granted. The permission request has been sent; ask the user to grant it (open the Shizuku manager or re-trigger) and retry.")
            }.toString()
        )
    )
}

private fun isAccessibilityEnabled(context: Context): Boolean {
    val expected = "${context.packageName}/${com.aiagents.service.ScreenTextAccessibilityService::class.java.name}"
    val enabledServices = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
    ).orEmpty()
    return enabledServices.split(':').any { it.trim() == expected }
}

/** 读取当前屏幕的视图层级(比 get_screen_text 更结构化的实时信息)。 */
internal fun buildAutoReadScreenTool(context: Context, eventBus: AppEventBus): Tool = Tool(
    name = "auto_read_screen",
    description = """
        Read the current screen's view hierarchy via the accessibility service: UI nodes with their
        text, descriptions, ids, clickable/editable/scrollable flags and coordinates (x, y, w, h).
        Use this before clicking or typing to understand the current screen layout.
        Returns a JSON tree of visible nodes. If accessibility is not enabled, opens settings to enable it.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("max_depth", buildJsonObject {
                    put("type", "integer")
                    put("description", "Maximum tree depth. Default 20.")
                })
            }
        )
    },
    needsApproval = { false },
    execute = {
        if (!isAccessibilityEnabled(context)) {
            accessibilityBlocked(eventBus)
        } else {
            val params = it.jsonObject
            val depth = params["max_depth"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()?.coerceIn(1, 40) ?: 20
            val hierarchy = AutoAccessibilityController.hierarchyJson(maxDepth = depth)
            if (hierarchy == null) {
                listOf(
                    UIMessagePart.Text(
                        buildJsonObject {
                            put("error", "NOT_CONNECTED")
                            put("message", "Accessibility service connected but no active window is available.")
                        }.toString()
                    )
                )
            } else {
                listOf(UIMessagePart.Text(hierarchy))
            }
        }
    },
)

/** 在当前屏幕点击: 按坐标(x,y)或按文本(text)定位。 */
internal fun buildAutoClickTool(context: Context, eventBus: AppEventBus): Tool = Tool(
    name = "auto_click",
    description = """
        Perform a tap on the current screen. Provide either x and y coordinates (prefer the node
        coordinates from auto_read_screen), or a text/id to locate and tap the matching node.
        Returns whether the tap was dispatched.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("x", buildJsonObject {
                    put("type", "integer")
                    put("description", "X coordinate in screen pixels.")
                })
                put("y", buildJsonObject {
                    put("type", "integer")
                    put("description", "Y coordinate in screen pixels.")
                })
                put("text", buildJsonObject {
                    put("type", "string")
                    put("description", "Locate node by matching text/contentDescription and tap its center.")
                })
                put("id", buildJsonObject {
                    put("type", "string")
                    put("description", "Locate node by view id (e.g. com.example:id/button) and tap its center.")
                })
            }
        )
    },
    needsApproval = { false },
    execute = {
        if (!isAccessibilityEnabled(context)) return@Tool accessibilityBlocked(eventBus)
        val params = it.jsonObject
        val result = if (params["text"] != null) {
            AutoAccessibilityController.clickNodeByText(
                params["text"]?.jsonPrimitive?.contentOrNull ?: ""
            )
        } else if (params["id"] != null) {
            AutoAccessibilityController.clickNodeById(
                params["id"]?.jsonPrimitive?.contentOrNull ?: ""
            )
        } else {
            val x = params["x"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
            val y = params["y"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
            if (x == null || y == null) {
                return@Tool listOf(
                    UIMessagePart.Text(
                        buildJsonObject {
                            put("error", "BAD_PARAMS")
                            put("message", "Provide either x & y, or text, or id.")
                        }.toString()
                    )
                )
            }
            AutoAccessibilityController.click(x, y)
        }
        listOf(
            UIMessagePart.Text(
                buildJsonObject {
                    put("dispatched", result)
                }.toString()
            )
        )
    },
)

/** 向当前聚焦的输入框输入文本。 */
internal fun buildAutoInputTool(context: Context, eventBus: AppEventBus): Tool = Tool(
    name = "auto_input",
    description = """
        Type text into the currently focused editable field (e.g. a search box or input).
        The field must already be focused — tap it first with auto_click if needed.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("text", buildJsonObject {
                    put("type", "string")
                    put("description", "The text to input.")
                })
            }
        )
    },
    needsApproval = { false },
    execute = {
        if (!isAccessibilityEnabled(context)) return@Tool accessibilityBlocked(eventBus)
        val text = it.jsonObject["text"]?.jsonPrimitive?.contentOrNull.orEmpty()
        if (text.isEmpty()) {
            listOf(UIMessagePart.Text("""{"error":"BAD_PARAMS","message":"text is required"}"""))
        } else {
            listOf(
                UIMessagePart.Text(
                    buildJsonObject {
                        put("input", AutoAccessibilityController.inputText(text))
                    }.toString()
                )
            )
        }
    },
)

/** 滑动屏幕(可用于翻页、拖拽)。 */
internal fun buildAutoSwipeTool(context: Context, eventBus: AppEventBus): Tool = Tool(
    name = "auto_swipe",
    description = """
        Perform a swipe gesture from (x1, y1) to (x2, y2) over the given duration in milliseconds.
        Use for scrolling lists, flipping pages, or dragging elements.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("x1", buildJsonObject { put("type", "integer") })
                put("y1", buildJsonObject { put("type", "integer") })
                put("x2", buildJsonObject { put("type", "integer") })
                put("y2", buildJsonObject { put("type", "integer") })
                put("duration_ms", buildJsonObject { put("type", "integer") })
            }
        )
    },
    needsApproval = { false },
    execute = {
        if (!isAccessibilityEnabled(context)) return@Tool accessibilityBlocked(eventBus)
        val p = it.jsonObject
        val x1 = p["x1"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
        val y1 = p["y1"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
        val x2 = p["x2"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
        val y2 = p["y2"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
        if (x1 == null || y1 == null || x2 == null || y2 == null) {
            listOf(UIMessagePart.Text("""{"error":"BAD_PARAMS","message":"x1,y1,x2,y2 are required"}"""))
        } else {
            val duration = p["duration_ms"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 400L
            listOf(
                UIMessagePart.Text(
                    buildJsonObject {
                        put("dispatched", AutoAccessibilityController.swipe(x1, y1, x2, y2, duration))
                    }.toString()
                )
            )
        }
    },
)

/** 滚动可滚动区域: up/down/left/right/top/bottom。 */
internal fun buildAutoScrollTool(context: Context, eventBus: AppEventBus): Tool = Tool(
    name = "auto_scroll",
    description = """
        Scroll the scrollable area on screen. direction: up | down | left | right | top | bottom.
        Falls back to the first scrollable node found.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("direction", buildJsonObject {
                    put("type", "string")
                    put("description", "up, down, left, right, top, or bottom.")
                })
            }
        )
    },
    needsApproval = { false },
    execute = {
        if (!isAccessibilityEnabled(context)) return@Tool accessibilityBlocked(eventBus)
        val direction = it.jsonObject["direction"]?.jsonPrimitive?.contentOrNull ?: "down"
        listOf(
            UIMessagePart.Text(
                buildJsonObject {
                    put("scrolled", AutoAccessibilityController.scroll(direction))
                    put("direction", direction)
                }.toString()
            )
        )
    },
)

/** 系统按键: 返回/主页/最近任务/回车。 */
internal fun buildAutoKeyTool(
    name: String,
    description: String,
    action: suspend () -> Boolean,
): Tool = Tool(
    name = name,
    description = description,
    parameters = { null },
    needsApproval = { false },
    execute = { listOf(UIMessagePart.Text(buildJsonObject { put("performed", action()) }.toString())) },
)

/** 截图: 通过 Shizuku 以 shell 权限执行 screencap, 保存到应用目录。
 *  支持 `grid` 直接叠加带像素坐标的网格(便于 AI 定位点击坐标), 支持 `ocr` 直接本地识别屏幕文本。 */
internal fun buildAutoScreenshotTool(context: Context, eventBus: AppEventBus): Tool = Tool(
    name = "auto_screenshot",
    description = """
        Take a full-screen screenshot via Shizuku (shell screencap, no MediaProjection prompt needed)
        and save it under the shared AI-Agent/screenshots directory. Returns the absolute file path
        and the workspace path (/screenshots/<name>).
        Options:
        - grid: overlay a fine pixel-coordinate grid on the screenshot (default true), so element coordinates
          are directly visible for tapping. grid_cols/grid_rows adjust density.
        - ocr: extract the on-screen text locally (Chinese + Latin) and return each text block with its
          bounding box and center coordinates, so you can read the screen and locate text without a vision model.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("grid", buildJsonObject {
                    put("type", "boolean")
                    put("description", "Overlay a pixel-coordinate grid on the screenshot (default true)")
                })
                put("grid_cols", buildJsonObject {
                    put("type", "integer")
                    put("description", "Grid divisions (default auto, ~48px per cell)")
                })
                put("grid_rows", buildJsonObject {
                    put("type", "integer")
                    put("description", "Grid rows (default auto by aspect ratio)")
                })
                put("ocr", buildJsonObject {
                    put("type", "boolean")
                    put("description", "Recognize screen text locally and return text blocks with coordinates (default false)")
                })
            }
        )
    },
    needsApproval = { false },
    execute = {
        if (!ShizukuController.isBinderAlive() || !ShizukuController.isPermissionGranted()) {
            shizukuBlocked()
        } else {
            val params = it.jsonObject
            val grid = params["grid"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: true
            val ocr = params["ocr"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false
            val gridCols = params["grid_cols"]?.jsonPrimitive?.intOrNull ?: 0
            val gridRows = params["grid_rows"]?.jsonPrimitive?.intOrNull ?: 0

            // 截图前隐藏悬浮窗(避免截到自身), 截完恢复
            val chatWin = FloatingWindowController.get(FloatingWindowController.CHAT)
            val wasVisible = chatWin?.visible == true
            if (wasVisible) {
                chatWin?.applyVisibility(false)
                delay(200) // 等窗口消失
            }

            val dir = ShizukuController.screenshotDir(context)
            val file = File(dir, "auto_${System.currentTimeMillis()}.png")
            val ok = ShizukuController.screenshotPng(file)

            // 恢复悬浮窗
            if (wasVisible) {
                chatWin?.applyVisibility(true)
            }

            val gridFile = if (ok && grid) {
                val gridded = overlayGridBytes(file.readBytes(), gridCols, gridRows)
                val gf = File(dir, file.nameWithoutExtension + "_grid.png")
                gf.writeBytes(gridded)
                gf
            } else {
                null
            }

            val ocrBlocks = if (ok && ocr) {
                runCatching {
                    recognizeTextBlocks(context, file.absolutePath)
                }.getOrElse { emptyList() }
            } else {
                emptyList()
            }

            listOf(
                UIMessagePart.Text(
                    buildJsonObject {
                        put("ok", ok)
                        put("path", file.absolutePath)
                        put("workspace_path", "/screenshots/${file.name}")
                        put("size_bytes", if (ok) file.length() else 0)
                        if (gridFile != null) {
                            put("grid_path", gridFile.absolutePath)
                            put("grid_workspace_path", "/screenshots/${gridFile.name}")
                            put("grid", true)
                            put("grid_note", "Coordinates: origin (0,0) at top-left, x rightward, y downward.")
                        }
                        if (ocrBlocks.isNotEmpty()) {
                            put("ocr_text", ocrBlocks.joinToString("\n") { it.text })
                            put(
                                "ocr_blocks",
                                buildJsonArray {
                                    addAll(
                                        ocrBlocks.mapIndexed { index, block ->
                                            val r = block.rect
                                            buildJsonObject {
                                                put("index", index)
                                                put("text", block.text)
                                                put("rect", buildJsonObject {
                                                    put("x", r.left)
                                                    put("y", r.top)
                                                    put("width", r.width())
                                                    put("height", r.height())
                                                })
                                                put("center", buildJsonObject {
                                                    put("x", r.left + r.width() / 2)
                                                    put("y", r.top + r.height() / 2)
                                                })
                                            }
                                        }
                                    )
                                }
                            )
                        }
                    }.toString()
                )
            )
        }
    },
)

/** 执行任意 shell 命令(以 Shizuku 权限, 通常为 shell/root)。 */
internal fun buildAutoShellTool(context: Context, eventBus: AppEventBus): Tool = Tool(
    name = "auto_shell",
    description = """
        Execute a shell command with Shizuku privileges (usually the 'shell' user, or root if granted).
        Returns stdout, stderr, and exit code. Be careful with commands that hang.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("command", buildJsonObject {
                    put("type", "string")
                    put("description", "The shell command to run.")
                })
            }
        )
    },
    needsApproval = { true },
    execute = {
        val command = it.jsonObject["command"]?.jsonPrimitive?.contentOrNull
        if (command.isNullOrBlank()) {
            listOf(UIMessagePart.Text("""{"error":"BAD_PARAMS","message":"command is required"}"""))
        } else if (!ShizukuController.isBinderAlive() || !ShizukuController.isPermissionGranted()) {
            shizukuBlocked()
        } else {
            val result = ShizukuController.exec(command)
            listOf(
                UIMessagePart.Text(
                    buildJsonObject {
                        put("exit_code", result.exitCode)
                        put("stdout", result.stdout.take(4000))
                        put("stderr", result.stderr.take(2000))
                        result.error?.let { put("error", it) }
                    }.toString()
                )
            )
        }
    },
)
