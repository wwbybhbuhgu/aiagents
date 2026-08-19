package com.aiagents.ui.media

import android.content.Context
import android.net.Uri
import coil3.request.Options
import coil3.key.Keyer
import java.io.File

/**
 * 工作区图片缓存 Keyer
 *
 * content://workspacefile 图片可能被 AI 反复修改, 若 Coil 按 URL 缓存,
 * 修改后仍会显示旧图。这里把宿主机文件的最后修改时间并入缓存 key,
 * 使文件一变, 内存/磁盘缓存 key 即失效并重新加载, 保证实时更新;
 * 未变化时 key 稳定, 不影响滚动复用。
 */
class WorkspaceMtimeKeyer(
    private val authorityPrefix: String,
) : Keyer<Any> {

    override fun key(data: Any, options: Options): String {
        val url = data as? String ?: return data.toString()
        val prefix = "content://$authorityPrefix/"
        if (!url.startsWith(prefix)) return url
        val file = resolveHostFile(options.context, url) ?: return url
        // 文件可能不存在/正在写入, 用 mtime 作为版本
        return "$url#m=${file.lastModified()}"
    }

    /** 解析 content://<authority>/<workspaceId>/<容器绝对路径> -> 宿主机真实文件 */
    private fun resolveHostFile(context: Context, url: String): File? {
        return runCatching {
            val uri = Uri.parse(url)
            val segments = uri.pathSegments ?: return null
            if (segments.size < 2) return null
            val workspaceId = segments[0]
            val containerPath = "/" + segments.drop(1).joinToString("/")
            // /workspace/xxx -> filesDir/workspaces/<id>/files/xxx
            val filesRoot = File(
                context.filesDir,
                "workspaces/$workspaceId/files",
            )
            val relative = if (containerPath == "/workspace") "" else containerPath.removePrefix("/workspace/")
            val target = if (relative.isBlank()) filesRoot else File(filesRoot, relative)
            target
        }.getOrNull()
    }
}
