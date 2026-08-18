package com.aiagents.data.ai.transformers

import com.aiagents.ai.core.MessageRole
import com.aiagents.ai.ui.UIMessage

/**
 * 用户最高优先级注入转换器
 *
 * 把用户在助手设置里填写的"最重要的事情"（如家长监护：侦测孩子在使用什么软件、如何操作设备，
 * 并在异常时介入管理设备）注入到系统提示词。主 Agent 与委派子 Agent 都携带这份上下文,
 * 确保后台侦测/定时委派/日常行动都优先围绕该目标执行。
 */
class UserPriorityTransformer : InputMessageTransformer {
    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        val priority = ctx.assistant.userPriority.trim()
        if (priority.isEmpty()) return messages
        val block = buildUserPriorityPrompt(priority)
        val systemIndex = messages.indexOfFirst { it.role == MessageRole.SYSTEM }
        return if (systemIndex >= 0) {
            messages.toMutableList().apply {
                this[systemIndex] = this[systemIndex].appendSystemText("\n\n$block")
            }
        } else {
            listOf(UIMessage.system(block)) + messages
        }
    }
}

private fun buildUserPriorityPrompt(priority: String): String = buildString {
    appendLine("<user_priority>")
    appendLine("The user has set the following as their most important objective. Treat it as a binding priority across ALL of your work, including background monitoring, scheduled delegation, periodic heartbeat surveillance, and any device actions:")
    appendLine(priority)
    appendLine("- Decide the monitoring cadence, what to observe, and what actions to take on your own; fill every parameter yourself. When something notable is detected, proactively initiate a periodic heartbeat so you actively follow up rather than wait passively.")
    appendLine("- Respect the user's stated priorities over lower-value background activity. Avoid actions that contradict this priority.")
    append("</user_priority>")
}