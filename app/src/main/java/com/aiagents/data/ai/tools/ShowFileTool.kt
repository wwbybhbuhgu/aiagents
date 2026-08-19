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
        Displays a non-image file from the workspace with a built-in viewer/player.
        Pass the absolute path inside the workspace rootfs (e.g. /workspace/media/video.mp4, /workspace/docs/notes.md).

        WHEN TO USE:
        - Use `show_file` for VIDEO, AUDIO, PDF, text/markdown documents, zip and any other non-image file.
        - For IMAGES: DO NOT use `show_file`. Instead embed the image directly in your markdown reply using the
          content:// uri. First call the `workspace_read_file`/`image_generate`/`search` tool that provides the
          image, then build the uri as `content://<app>.workspacefile/<workspaceId><path>` and embed it:
          `![alt](content://com.aiagents.debug.workspacefile/<workspaceId>/workspace/images/xxx.png)`.
          (Image path comes from the tool result, e.g. /workspace/images/xxx.png.)
        - Prefer markdown for images; only fall back to `show_file` for an image if it is a special case.

        WHY: markdown can ONLY render images inline — it cannot play audio/video or show text/document files.
        So use `show_file` for those, and markdown for images.

        URL format reminder: content uri = "content://" + "<app>.workspacefile" + "/" + workspaceId + containerPath,
        e.g. `content://com.aiagents.debug.workspacefile/<workspaceId>/workspace/images/xxx.png`. No angle brackets.
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
            // 图片: Coil 通过自定义 ContentProvider 加载 content://(同进程可读)
            extension in IMAGE_EXTENSIONS -> UIMessagePart.Image(url = contentUri)
            // 视频/音频: 用宿主机 file:// 路径, 渲染端通过 FileProvider 暴露给外部播放器
            extension in VIDEO_EXTENSIONS -> UIMessagePart.Video(url = hostFile.toUri().toString())
            extension in AUDIO_EXTENSIONS -> UIMessagePart.Audio(url = hostFile.toUri().toString())
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