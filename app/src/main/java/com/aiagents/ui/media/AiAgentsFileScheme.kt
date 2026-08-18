package com.aiagents.ui.media

import java.io.ByteArrayOutputStream
import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import com.aiagents.data.repository.WorkspaceRepository
import okio.Buffer
import okio.FileSystem

/**
 * 私有文件协议：`aiagents-file://<workspaceId>/<容器内绝对路径>`
 *
 * 聊天/页面渲染内部文件（图片等）时直接使用容器完整路径，
 * 由 [WorkspaceFileFetcher] 从工作区容器读取字节交给 Coil 解码。
 */
object AiAgentsFileScheme {
    const val SCHEME = "aiagents-file"

    /** 构造私有协议 URL */
    fun build(workspaceId: String, path: String): String = "$SCHEME://$workspaceId$path"

    /** 解析私有协议 URL 为 (workspaceId, 容器内绝对路径) */
    fun parse(uri: String): Pair<String, String>? {
        val prefix = "$SCHEME://"
        if (!uri.startsWith(prefix)) return null
        val rest = uri.removePrefix(prefix)
        val slash = rest.indexOf('/')
        if (slash <= 0) return null
        return rest.substring(0, slash) to rest.substring(slash)
    }
}

/** Coil Fetcher：从工作区容器读取文件（支持私有协议） */
class WorkspaceFileFetcher(
    private val uri: String,
    private val workspaceRepository: WorkspaceRepository,
) : Fetcher {
    override suspend fun fetch(): FetchResult {
        val (workspaceId, path) = AiAgentsFileScheme.parse(uri)
            ?: error("Invalid ${AiAgentsFileScheme.SCHEME} uri: $uri")
        val size = workspaceRepository.rootfsFileSize(workspaceId, path)
        val bytes = ByteArrayOutputStream(size.toInt()).also { out ->
            workspaceRepository.exportRootfsFile(workspaceId, path, out)
        }.toByteArray()
        return SourceFetchResult(
            source = ImageSource(Buffer().write(bytes), FileSystem.SYSTEM),
            mimeType = guessMimeType(path),
            dataSource = DataSource.DISK,
        )
    }

    class Factory(
        private val workspaceRepository: WorkspaceRepository,
    ) : Fetcher.Factory<Any> {
        override fun create(data: Any, options: Options, imageLoader: ImageLoader): Fetcher? {
            if (data is String && data.startsWith("${AiAgentsFileScheme.SCHEME}://")) {
                return WorkspaceFileFetcher(data, workspaceRepository)
            }
            return null
        }
    }
}

private fun guessMimeType(path: String): String = when (path.substringAfterLast('.', "").lowercase()) {
    "png" -> "image/png"
    "jpg", "jpeg" -> "image/jpeg"
    "gif" -> "image/gif"
    "webp" -> "image/webp"
    "bmp" -> "image/bmp"
    "svg" -> "image/svg+xml"
    "heic", "heif" -> "image/heic"
    "avif" -> "image/avif"
    "mp4" -> "video/mp4"
    "webm" -> "video/webm"
    "mp3" -> "audio/mpeg"
    "wav" -> "audio/wav"
    else -> "application/octet-stream"
}
