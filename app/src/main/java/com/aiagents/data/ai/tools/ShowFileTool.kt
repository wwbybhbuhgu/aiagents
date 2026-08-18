package com.aiagents.data.ai.tools

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import com.aiagents.ai.core.InputSchema
import com.aiagents.ai.core.Tool
import com.aiagents.ai.ui.UIMessagePart
import com.aiagents.data.repository.WorkspaceRepository
import java.io.File

private const val MAX_SHOW_BYTES = 256L * 1024 * 1024

private val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp", "svg", "heic", "heif", "avif")
private val VIDEO_EXTENSIONS = setOf("mp4", "webm", "mkv", "avi", "mov", "3gp", "flv", "ts")
private val AUDIO_EXTENSIONS = setOf("mp3", "wav", "ogg", "m4a", "aac", "flac", "opus", "amr", "wma")

/**
 * 构建 show_file 工具：主 Agent 传入工作区内的文件路径，
 * 直接内联展示到会话（图片/视频/音频渲染为媒体，其他文件渲染为文档）。
 * 与 file_share 不同，show_file 是"展示"而非"分享"，无需用户额外操作。
 */
fun buildShowFileTool(
    context: Context,
    workspaceRepository: WorkspaceRepository,
    workspaceId: String?,
): Tool = Tool(
    name = "show_file",
    description = """
        Displays a file from the workspace directly in the conversation.
        Pass the absolute path inside the workspace rootfs (e.g. /workspace/images/xxx.png, /workspace/media/video.mp4).
        Images render inline, audio/video render as playable media, other files render as a document card.
        Use this when the user wants to SEE or PREVIEW a generated/workspace file (images, videos, audio, PDFs, documents).
        The returned URI can be embedded in your markdown reply with ![](<uri>) to show it inline.
    """.trimIndent(),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("path", buildJsonObject {
                    put("type", "string")
                    put("description", "Absolute path of the file inside the workspace rootfs (e.g. /workspace/images/xxx.png)")
                })
            },
            required = listOf("path"),
        )
    },
    needsApproval = { false },
    execute = {
        if (workspaceId.isNullOrBlank()) {
            error("展示文件需要绑定工作区，请先为助手配置工作区")
        }
        val path = it.jsonObject["path"]?.jsonPrimitive?.contentOrNull
            ?.takeIf { p -> p.isNotBlank() && p.startsWith("/") }
            ?: error("path is required and must be absolute")
        val size = workspaceRepository.rootfsFileSize(workspaceId, path)
        require(size <= MAX_SHOW_BYTES) { "文件过大，无法展示: $path" }
        val name = path.trimEnd('/').substringAfterLast('/').ifBlank { "file" }
        val extension = name.substringAfterLast('.', "").lowercase()
        val hostFile = workspaceRepository.resolveRootfsHostFile(workspaceId, path)
        // content:// URI 路径部分直接对应容器绝对路径: content://<auth>/<workspaceId>/workspace/white.png
        val contentUri = "content://${context.packageName}.workspacefile/$workspaceId$path"

        val part = when {
            // 图片/视频/音频: Coil 通过自定义 ContentProvider 加载 content://
            extension in IMAGE_EXTENSIONS -> UIMessagePart.Image(url = contentUri)
            extension in VIDEO_EXTENSIONS -> UIMessagePart.Video(url = contentUri)
            extension in AUDIO_EXTENSIONS -> UIMessagePart.Audio(url = contentUri)
            // 文档: 渲染端通过 part.url.toUri().toFile() 生成分享/打开 FileProvider URI,
            // 需要 file:// 形式才能正确解析回真实文件
            else -> UIMessagePart.Document(
                url = hostFile.toUri().toString(),
                fileName = name,
                mime = guessMime(extension),
            )
        }

        listOf(
            part,
            UIMessagePart.Text(
                buildJsonObject {
                    put("showFile", true)
                    put("workspaceId", workspaceId)
                    put("path", path)
                    put("name", name)
                    put("uri", contentUri)
                    put("sizeBytes", size)
                    put("type", extension)
                }.toString()
            ),
        )
    },
)

private fun guessMime(extension: String): String = when (extension) {
    "pdf" -> "application/pdf"
    "txt", "md" -> "text/plain"
    "json" -> "application/json"
    "csv" -> "text/csv"
    "zip" -> "application/zip"
    "apk" -> "application/vnd.android.package-archive"
    else -> "application/octet-stream"
}