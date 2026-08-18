package com.aiagents.data.ai.tools.local

import android.content.Context
import com.aiagents.ai.core.InputSchema
import com.aiagents.ai.core.Tool
import com.aiagents.ai.ui.UIMessagePart
import com.aiagents.data.event.AppEvent
import com.aiagents.data.event.AppEventBus
import com.aiagents.utils.NotificationUtil
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * 发送通知工具: 让 AI 在设备通知栏发送一条通知给用户。
 * 用于提醒用户、显示任务结果、或有需要用户注意的信息时。
 * 需要通知权限; 未授权时引导用户手动开启。
 */
internal fun buildSendNotificationTool(context: Context, eventBus: AppEventBus): Tool = Tool(
    name = "send_notification",
    description = """
        Send a notification to the user's device notification bar.
        Use this to remind the user, notify task completion, or surface important information
        that the user should see even if they are not looking at the chat.
        Returns whether the notification was sent.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("title", buildJsonObject {
                    put("type", "string")
                    put("description", "Notification title (short)")
                })
                put("content", buildJsonObject {
                    put("type", "string")
                    put("description", "Notification body text")
                })
            },
            required = listOf("title", "content"),
        )
    },
    needsApproval = { false },
    execute = {
        val params = it.jsonObject
        val title = params["title"]?.jsonPrimitive?.contentOrNull?.takeIf { p -> p.isNotBlank() }
            ?: error("title is required")
        val content = params["content"]?.jsonPrimitive?.contentOrNull?.takeIf { p -> p.isNotBlank() }
            ?: error("content is required")

        val sent = NotificationUtil.notify(
            context = context,
            channelId = "chat_completed",
            notificationId = (10000..19999).random(),
        ) {
            this.title = title
            this.content = content
        }

        if (sent) {
            listOf(
                UIMessagePart.Text(
                    buildJsonObject {
                        put("sent", true)
                        put("title", title)
                        put("content", content)
                    }.toString()
                )
            )
        } else {
            eventBus.emit(AppEvent.RequestNotificationsPermission)
            listOf(
                UIMessagePart.Text(
                    buildJsonObject {
                        put("sent", false)
                        put("error", "NO_PERMISSION")
                        put(
                            "message",
                            "Notification permission is not granted. The system permission dialog has been opened; please ask the user to allow notifications and try again."
                        )
                    }.toString()
                )
            )
        }
    },
)
