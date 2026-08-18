package com.aiagents.data.event

import com.aiagents.ai.ui.UIMessage
import kotlin.uuid.Uuid

sealed class AppEvent {
    data class Speak(val text: String) : AppEvent()
    data object OpenUsageAccessSettings : AppEvent()
    data object RequestNotificationsPermission : AppEvent()
    data object RequestSmsPermission : AppEvent()
    data object RequestRecordAudioPermission : AppEvent()
    data object OpenAccessibilitySettings : AppEvent()
    data object ShowOverlayPermissionGuide : AppEvent()
    data object OpenNotificationListenerSettings : AppEvent()
    data object OpenLocationSettings : AppEvent()
    data object OpenUnknownAppSources : AppEvent()
    data object OpenWriteSettings : AppEvent()
    data object OpenBatteryOptimizationSettings : AppEvent()

    /** 在内置浏览器(应用内 WebView)中打开 URL, 供 AI 浏览器自动化使用; 与系统浏览器打开链接区分。 */
    data class OpenBuiltInBrowser(val url: String) : AppEvent()

    /** 关闭内置浏览器自动化页面。 */
    data object CloseBuiltInBrowser : AppEvent()

    /** 请求一组运行时权限(危险权限 C 类), 由 RouteActivity 弹系统授权框。 */
    data class RequestRuntimePermissions(val permissions: List<String>) : AppEvent()

    /** MCP OAuth 授权完成后经 deep link 回传的结果。 */
    data class McpOAuthCallback(
        val state: String?,
        val code: String?,
        val error: String?,
    ) : AppEvent()

    /** 聊天生成过程中的流式更新，由 ChatNotificationManager 消费用于 Live Update 通知。 */
    data class ChatGenerationUpdate(
        val conversationId: Uuid,
        val lastMessage: UIMessage,
        val senderName: String,
    ) : AppEvent()

    /**
     * 聊天生成结束（完成、失败或取消）。
     * [contentPreview] 为 null 时仅取消 Live Update 通知，不发送完成通知。
     */
    data class ChatGenerationEnded(
        val conversationId: Uuid,
        val senderName: String,
        val contentPreview: String?,
    ) : AppEvent()
}
