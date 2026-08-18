package com.aiagents.data.ai.tools.local

import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.aiagents.ai.core.InputSchema
import com.aiagents.ai.core.Tool
import com.aiagents.ai.ui.UIMessagePart
import com.aiagents.data.event.AppEvent
import com.aiagents.data.event.AppEventBus
import com.aiagents.service.ScreenTextRepository
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * 屏幕文本工具: 通过无障碍服务读取当前屏幕上的文本。
 * 用于回答"你屏幕上显示什么/这个页面写了什么"类问题。
 * 未开启无障碍时, 引导用户去系统设置开启(不代替用户操作)。
 */
internal fun buildScreenTextTool(context: Context, eventBus: AppEventBus): Tool = Tool(
    name = "get_screen_text",
    description = """
        Read the current on-screen text via the accessibility service.
        Use this when the user asks what is displayed on the screen, what a page shows,
        or when context on the current screen would help answer.
        Returns the visible text nodes of the current window.
        If accessibility is not enabled, returns instructions to enable it in system settings.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("snapshots", buildJsonObject {
                    put("type", "integer")
                    put("description", "Number of recent screen snapshots to return. Default 1, max 5.")
                })
            }
        )
    },
    needsApproval = { false },
    execute = {
        val params = it.jsonObject
        val snapshots = params["snapshots"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()?.coerceIn(1, 5) ?: 1

        if (!isAccessibilityEnabled(context)) {
            eventBus.emit(AppEvent.OpenAccessibilitySettings)
            listOf(
                UIMessagePart.Text(
                    buildJsonObject {
                        put("error", "NO_PERMISSION")
                        put(
                            "message",
                            "Accessibility service is not enabled. The system accessibility settings page has been opened; please ask the user to enable the AI Agents accessibility service and try again."
                        )
                    }.toString()
                )
            )
        } else {
            val data = ScreenTextRepository.latestText(snapshots)
            if (data.isEmpty()) {
                listOf(
                    UIMessagePart.Text(
                        buildJsonObject {
                            put("source", "accessibility")
                            put("connected", ScreenTextRepository.isConnected())
                            put("message", "No screen text captured yet. Ask the user to look at the screen and try again.")
                        }.toString()
                    )
                )
            } else {
                listOf(
                    UIMessagePart.Text(
                        buildJsonObject {
                            put("source", "accessibility")
                            put("connected", true)
                            put("snapshots", buildJsonArray {
                                data.forEach { snapshot ->
                                    add(buildJsonObject {
                                        put("package", snapshot.packageName)
                                        put("timestamp_ms", snapshot.timestamp)
                                        put("texts", buildJsonArray {
                                            snapshot.texts.forEach { add(it) }
                                        })
                                    })
                                }
                            })
                        }.toString()
                    )
                )
            }
        }
    },
)

private fun isAccessibilityEnabled(context: Context): Boolean {
    val expected = "${context.packageName}/${com.aiagents.service.ScreenTextAccessibilityService::class.java.name}"
    val enabledServices = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
    ).orEmpty()
    return enabledServices.split(':').any { it.trim() == expected }
}

internal fun openAccessibilitySettings(context: Context) {
    runCatching {
        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }
}
