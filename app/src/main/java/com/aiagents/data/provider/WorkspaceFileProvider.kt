package com.aiagents.data.provider

import android.content.ContentProvider
import android.content.ContentValues
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.net.Uri
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import com.aiagents.data.repository.WorkspaceRepository
import kotlinx.coroutines.runBlocking
import org.koin.core.context.GlobalContext

/**
 * 工作区文件 ContentProvider
 *
 * 提供 `content://<authority>/<workspaceId>/<容器绝对路径>` 形式的 URI,
 * 路径部分直接对应容器内绝对路径(如 /workspace/white.png), 无 files 等中间层。
 * 供 Markdown 内联图片/视频/音频展示使用: AI 在回复中写 `![](content://...)`,
 * Coil/渲染端通过 ContentResolver 读取时由本 Provider 从工作区 rootfs 导出文件。
 */
class WorkspaceFileProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        return openFile(uri, mode, null)
    }

    override fun openFile(
        uri: Uri,
        mode: String,
        signal: CancellationSignal?,
    ): ParcelFileDescriptor {
        require(mode == "r" || mode == "rwt" || mode == "rt") { "Only read access supported: $mode" }
        val segments = uri.pathSegments
        require(segments.size >= 2) { "Invalid workspace file uri: $uri" }
        val workspaceId = segments[0]
        val containerPath = segments.drop(1).joinToString("/").let { "/$it" }
        val repository: WorkspaceRepository = GlobalContext.get().get()
        val hostFile = runBlocking {
            repository.resolveRootfsHostFile(workspaceId, containerPath)
        }
        return ParcelFileDescriptor.open(hostFile, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun openAssetFile(
        uri: Uri,
        mode: String,
        signal: CancellationSignal?,
    ): AssetFileDescriptor? {
        return AssetFileDescriptor(openFile(uri, mode, signal), 0, AssetFileDescriptor.UNKNOWN_LENGTH)
    }

    override fun getType(uri: Uri): String? {
        val extension = uri.lastPathSegment?.substringAfterLast('.', "")?.lowercase()
        return when (extension) {
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "svg" -> "image/svg+xml"
            "bmp" -> "image/bmp"
            "heic", "heif" -> "image/heic"
            "avif" -> "image/avif"
            "mp4" -> "video/mp4"
            "webm" -> "video/webm"
            "mkv" -> "video/x-matroska"
            "mov" -> "video/quicktime"
            "mp3" -> "audio/mpeg"
            "wav" -> "audio/wav"
            "ogg" -> "audio/ogg"
            "m4a", "aac" -> "audio/mp4"
            "flac" -> "audio/flac"
            "pdf" -> "application/pdf"
            "txt", "md" -> "text/plain"
            "json" -> "application/json"
            "zip" -> "application/zip"
            else -> "application/octet-stream"
        }
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0
}