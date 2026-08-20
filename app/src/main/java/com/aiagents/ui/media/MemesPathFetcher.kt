package com.aiagents.ui.media

import android.content.Context
import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import com.aiagents.data.memes.MemeAssetsInstaller
import okio.Buffer
import okio.FileSystem
import java.io.File

/**
 * 内置表情包路径协议：`/memes/<packId>/<path>`
 *
 * 图库安装在 filesDir/memes(经 bind mount 挂载到工作区容器 /memes)。
 * 聊天 Markdown / HTML 卡片里 AI 引用 `/memes/...` 时, Coil 无法直接打开该虚拟路径,
 * 由本 Fetcher 把它映射到宿主机真实文件 filesDir/memes/<packId>/<path> 交给 Coil 解码。
 */
class MemesPathFetcher(
    private val path: String,
    private val context: Context,
) : Fetcher {
    override suspend fun fetch(): FetchResult {
        val file = File(context.filesDir, MemeAssetsInstaller.MEMES_DIR + path.removePrefix("/memes"))
        require(file.isFile) { "Meme file not found: $path" }
        val bytes = file.readBytes()
        return SourceFetchResult(
            source = ImageSource(Buffer().write(bytes), FileSystem.SYSTEM),
            mimeType = guessMimeType(file.name),
            dataSource = DataSource.DISK,
        )
    }

    class Factory(private val context: Context) : Fetcher.Factory<Any> {
        override fun create(data: Any, options: Options, imageLoader: ImageLoader): Fetcher? {
            if (data is String && data.startsWith("/memes/")) {
                return MemesPathFetcher(data, context)
            }
            return null
        }
    }
}

private fun guessMimeType(fileName: String): String = when (fileName.substringAfterLast('.', "").lowercase()) {
    "png" -> "image/png"
    "jpg", "jpeg" -> "image/jpeg"
    "gif" -> "image/gif"
    "webp" -> "image/webp"
    else -> "application/octet-stream"
}