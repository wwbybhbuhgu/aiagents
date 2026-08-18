package com.aiagents.data.ai.transformers

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import com.aiagents.ai.ui.UIMessage
import com.aiagents.ai.ui.UIMessagePart
import com.aiagents.data.repository.WorkspaceRepository
import com.aiagents.ui.media.AiAgentsFileScheme
import java.io.File
import org.koin.java.KoinJavaComponent.getKoin

/**
 * 把 AI 回复 Markdown 中的 `aiagents-file://` 协议 URI 转换为 `content://` URI,
 * 使 Coil/AsyncImage 能通过 FileProvider + ContentResolver 直接加载工作区文件。
 */
class AiAgentsFileToContentUriTransformer(
    private val context: Context,
    private val workspaceRepository: WorkspaceRepository,
) : OutputMessageTransformer {

    override suspend fun onGenerationFinish(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        return messages.map { message ->
            message.transformTextParts { text ->
                replaceAiAgentsFileUris(text)
            }
        }
    }

    private suspend fun replaceAiAgentsFileUris(text: String): String {
        val regex = Regex("""aiagents-file://[^\s\)\"]+""")
        val matches = regex.findAll(text).toList()
        if (matches.isEmpty()) return text

        var result = text
        // 从后往前替换, 避免偏移
        for (match in matches.reversed()) {
            val contentUri = resolveToContentUri(match.value)
            if (contentUri != null) {
                result = result.substring(0, match.range.first) +
                    contentUri +
                    result.substring(match.range.last + 1)
            }
        }
        return result
    }

    private suspend fun resolveToContentUri(aiAgentsUri: String): String? {
        val (workspaceId, path) = AiAgentsFileScheme.parse(aiAgentsUri) ?: return null
        return runCatching {
            val cacheDir = File(context.cacheDir, "shared")
            cacheDir.mkdirs()
            val fileName = path.substringAfterLast('/').ifBlank { "image.png" }
            val cacheFile = File(cacheDir, fileName)
            workspaceRepository.exportRootfsFile(workspaceId, path, cacheFile.outputStream())
            val contentUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                cacheFile
            )
            contentUri.toString()
        }.getOrNull()
    }
}

/** 辅助函数: 遍历消息的 Text parts 并替换文本 */
private suspend fun UIMessage.transformTextParts(transform: suspend (String) -> String): UIMessage {
    return copy(
        parts = parts.map { part ->
            if (part is UIMessagePart.Text) {
                part.copy(text = transform(part.text))
            } else {
                part
            }
        }
    )
}