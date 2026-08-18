package com.aiagents.data.ai.transformers

import androidx.core.net.toFile
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.aiagents.ai.ui.UIMessage
import com.aiagents.ai.ui.UIMessagePart

/**
 * 上传文件转换器（统一链路）
 *
 * 所有上传的文件（图片 / 视频 / 音频 / 文档 / 其他）统一保存到工作区后,
 * 在发送给模型前替换为「文件提示文本」：包含推测类型、文件名、工作区内路径。
 * 模型需要自行调用 workspace_* 工具读取并分析这些文件, 不自动 OCR、不提取内容。
 *
 * 提示示例:
 *   <UploadFile type="image" name="photo.png" path="/upload/xxx.png" mime="image/png">
 *
 * type 取值: image=图片, file=文件, other=其他
 */
object UploadFileTransformer : InputMessageTransformer {
    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        val hasUploads = messages.any { message ->
            message.parts.any { it.isLocalUpload() }
        }
        if (!hasUploads) return messages

        return withContext(Dispatchers.IO) {
            messages.map { message ->
                message.copy(
                    parts = message.parts.map { part ->
                        if (part.isLocalUpload()) {
                            UIMessagePart.Text(part.toUploadFileHint())
                        } else {
                            part
                        }
                    }
                )
            }
        }
    }

    /** 是否为工作区挂载目录中的本地上传文件 */
    private fun UIMessagePart.isLocalUpload(): Boolean {
        val url = when (this) {
            is UIMessagePart.Image -> url
            is UIMessagePart.Video -> url
            is UIMessagePart.Audio -> url
            is UIMessagePart.Document -> url
            else -> return false
        }
        if (!url.startsWith("file:")) return false
        val file = runCatching { url.toUri().toFile() }.getOrNull() ?: return false
        return file.parentFile?.name == "upload" && file.exists()
    }

    private fun UIMessagePart.toUploadFileHint(): String {
        val url = when (this) {
            is UIMessagePart.Image -> url
            is UIMessagePart.Video -> url
            is UIMessagePart.Audio -> url
            is UIMessagePart.Document -> url
            else -> return ""
        }
        val file = runCatching { url.toUri().toFile() }.getOrNull() ?: return ""
        val fileName = file.name
        val path = "/upload/$fileName"

        return when (this) {
            is UIMessagePart.Image -> {
                buildUploadHint(
                    type = "image",
                    name = fileName,
                    path = path,
                    mime = guessMime(this),
                )
            }

            is UIMessagePart.Document -> {
                buildUploadHint(
                    type = "file",
                    name = fileName,
                    path = path,
                    mime = mime,
                )
            }

            is UIMessagePart.Video -> {
                buildUploadHint(
                    type = "other",
                    name = fileName,
                    path = path,
                    mime = null,
                )
            }

            is UIMessagePart.Audio -> {
                buildUploadHint(
                    type = "other",
                    name = fileName,
                    path = path,
                    mime = null,
                )
            }

            else -> buildUploadHint(
                type = "other",
                name = fileName,
                path = path,
                mime = null,
            )
        }
    }

    private fun buildUploadHint(
        type: String,
        name: String,
        path: String,
        mime: String?,
    ): String = buildString {
        append("<UploadFile")
        append(" type=\"$type\"")
        append(" name=\"$name\"")
        append(" path=\"$path\"")
        if (!mime.isNullOrBlank()) {
            append(" mime=\"$mime\"")
        }
        append(">")
        appendLine()
        appendLine("The user uploaded a file. It is stored in the workspace at `$path` (type: $type).")
        appendLine("Read it with workspace tools (e.g. workspace_read_file or workspace_shell) to analyze its content. Do not assume its format based only on the file extension.")
        append("</UploadFile>")
    }

    private fun guessMime(part: UIMessagePart.Image): String? {
        val url = part.url
        return when {
            url.substringAfterLast('.', "").lowercase() in
                setOf("png", "jpg", "jpeg", "gif", "webp", "bmp", "svg", "heic", "heif", "avif", "ico") -> {
                "image/*"
            }

            else -> null
        }
    }
}
