package com.aiagents.data.ai.transformers

import com.aiagents.ai.core.MessageRole
import com.aiagents.ai.ui.UIMessage
import com.aiagents.ai.ui.UIMessagePart
import com.aiagents.data.proxy.ProxyManager
import org.koin.core.component.KoinComponent
import org.koin.core.component.get

/**
 * 代理环境变量注入转换器。
 *
 * 当代理已启用且内核运行时，往系统提示词追加代理环境变量信息，
 * 告知 AI 在工作区终端、curl、git 等场景中自动使用代理访问国外资源。
 * 未启用代理时不注入任何内容。
 */
object ProxyInjectionTransformer : InputMessageTransformer, KoinComponent {

    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        val proxyManager = try {
            get<ProxyManager>()
        } catch (_: Exception) {
            return messages
        }

        if (!proxyManager.isRunning) return messages

        val env = proxyManager.proxyEnv()
        if (env.isEmpty()) return messages

        val httpProxy = env["HTTP_PROXY"] ?: return messages

        val injection = buildString {
            appendLine("## Proxy (已启用)")
            appendLine("An HTTP/SOCKS proxy is active on this device. All outbound network requests should go through it.")
            appendLine()
            appendLine("Environment variables (already set in workspace shells):")
            appendLine("  HTTP_PROXY=$httpProxy")
            appendLine("  HTTPS_PROXY=$httpProxy")
            appendLine("  ALL_PROXY=$httpProxy")
            appendLine()
            appendLine("When running terminal commands (curl, wget, git, npm, pip, etc.) in workspace_shell,")
            appendLine("the proxy environment is automatically available. No extra setup is needed.")
            appendLine("If you need to explicitly pass the proxy in a command, use:")
            appendLine("  export https_proxy=$httpProxy http_proxy=$httpProxy all_proxy=$httpProxy")
            appendLine()
            appendLine("For requests from the app itself (e.g. fetching URLs for the user),")
            appendLine("use the proxy address directly: $httpProxy")
        }

        return injectAfterSystemPrompt(messages, injection)
    }

    private fun injectAfterSystemPrompt(
        messages: List<UIMessage>,
        injection: String,
    ): List<UIMessage> {
        val result = messages.toMutableList()
        val systemIndex = result.indexOfFirst { it.role == MessageRole.SYSTEM }

        if (systemIndex >= 0) {
            val systemMessage = result[systemIndex]
            val originalText = systemMessage.parts
                .filterIsInstance<UIMessagePart.Text>()
                .joinToString("") { it.text }

            result[systemIndex] = systemMessage.copy(
                parts = listOf(UIMessagePart.Text(originalText + "\n\n" + injection))
            )
        } else {
            result.add(0, UIMessage.system(injection))
        }

        return result
    }
}
