package com.aiagents.data.ai.transformers

import android.content.Context
import android.util.Log
import com.aiagents.ai.ui.UIMessage
import com.aiagents.ai.ui.UIMessagePart
import com.aiagents.data.memes.MemeAssetsInstaller
import java.io.File

/**
 * 表情包 `/memes/` 路径真实性校验
 *
 * 模型有时会"幻觉"出 `/memes/...` 图片路径而实际没有调用工具(例如看到提示词里的
 * `![...](/memes/...)` 格式后直接编造)。本转换器在生成完成后扫描回复文本中的
 * `/memes/...` 图片引用, 逐一校验宿主机 filesDir/memes 下文件是否真实存在:
 * - 存在: 保留(真图)
 * - 不存在: 替换为明确提示, 并附带正确的取图方式(调用 search_sticker), 避免展示假图
 */
class MemePathValidatorTransformer(
    private val context: Context,
) : OutputMessageTransformer {

    override suspend fun onGenerationFinish(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        val textDump = messages.joinToString("\n") { it.textOrEmpty() }
        if (!textDump.contains("/memes/") && !textDump.contains("workspacefile/")) return messages

        return messages.map { message ->
            val text = message.textOrEmpty()
            if (!text.contains("/memes/") && !text.contains("workspacefile/")) return@map message

            var newText = text
            // 匹配 ![alt](content://.../memes/...) 或 ![alt](/memes/...) 整段
            val fullLink = Regex("!\\[[^]]*]\\((content://[^)\\s]+/memes/[^)\\s]+|/memes/[^)\\s]+)\\)")
            fullLink.findAll(text).forEach { match ->
                val link = match.value
                val raw = Regex("content://[^)\\s]+/memes/[^)\\s]+|/memes/[^)\\s]+").find(link)?.value
                    ?: return@forEach
                val containerPath = if (raw.startsWith("content://")) {
                    "/memes" + raw.substringAfter("/memes")
                } else {
                    raw
                }
                if (!exists(containerPath)) {
                    Log.w(TAG, "Fake meme path in reply, replacing: $raw")
                    newText = newText.replace(
                        link,
                        "![图片不存在(请用 search_sticker 工具获取真实表情包)]()"
                    )
                }
            }
            // 单独的 /memes/ 路径(HTML img src / 裸路径)也检查
            val barePath = Regex("/memes/[^\\s\"'<>()]+")
            barePath.findAll(text).forEach { match ->
                val path = match.value
                if (fullLink.findAll(text).any { it.value.contains(path) }) return@forEach
                if (exists(path)) return@forEach
                Log.w(TAG, "Fake meme path in reply, replacing: $path")
                newText = newText.replace(
                    path,
                    "(图片不存在, 请用 search_sticker 工具获取真实表情包)"
                )
            }
            // 校验 content://...workspacefile/... 图片引用(斗图下载图 / 工作区图片)是否真实可读
            val contentUriRefs = Regex("content://[^\\s\"'<>()]+")
            contentUriRefs.findAll(text).forEach { match ->
                val uri = match.value
                if (fullLink.findAll(text).any { it.value.contains(uri) && it.value.contains("/memes/") }) return@forEach
                if (isReadableContentUri(uri)) return@forEach
                Log.w(TAG, "Fake content:// image in reply, replacing: $uri")
                newText = newText.replace(
                    uri,
                    "(图片不存在, 请用 search_sticker 工具获取真实表情包)"
                )
            }
            if (newText == text) {
                message
            } else {
                replaceTextPart(message, newText)
            }
        }
    }

    /** 校验宿主机 filesDir/memes/<相对路径> 是否真实存在 */
    private fun exists(containerPath: String): Boolean {
        val relative = containerPath.removePrefix("/memes/")
        val file = File(context.filesDir, "${MemeAssetsInstaller.MEMES_DIR}/$relative")
        return file.isFile
    }

    /** 通过 ContentProvider 尝试读取, 能读到说明是真实存在的文件 */
    private fun isReadableContentUri(uri: String): Boolean = runCatching {
        val parsed = android.net.Uri.parse(uri)
        val stream = context.contentResolver.openInputStream(parsed)
        if (stream != null) {
            stream.close()
            true
        } else {
            // 部分 provider 不支持 openInputStream, 退化为 MIME 类型检查
            context.contentResolver.getType(parsed) != null
        }
    }.getOrDefault(false)

    private fun replaceTextPart(message: UIMessage, newText: String): UIMessage {
        val parts = message.parts.toMutableList()
        val textIndex = parts.indexOfFirst { it is UIMessagePart.Text }
        if (textIndex >= 0) {
            val part = parts[textIndex] as UIMessagePart.Text
            parts[textIndex] = part.copy(text = newText)
        } else {
            parts.add(UIMessagePart.Text(newText))
        }
        return message.copy(parts = parts)
    }

    companion object {
        private const val TAG = "MemePathValidator"
    }
}

private fun UIMessage.textOrEmpty(): String = parts
    .filterIsInstance<UIMessagePart.Text>()
    .joinToString("") { it.text }