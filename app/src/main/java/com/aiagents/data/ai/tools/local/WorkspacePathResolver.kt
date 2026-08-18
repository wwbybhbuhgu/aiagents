package com.aiagents.data.ai.tools.local

import android.content.Context
import android.os.Environment
import com.aiagents.data.files.FileFolders
import com.aiagents.data.repository.WorkspaceRepository
import com.aiagents.di.AI_AGENT_SHARED_DIR
import java.io.File

/**
 * 工作区路径 → 设备真实路径的桥接器。
 *
 * 手机自动化工具(install_apk / set_wallpaper 等)在真实 Android 文件系统上工作,
 * 需要的是"设备路径"；而 AI 在沙箱 proot 容器里使用的是"Rootfs 路径"
 * (/workspace、/screenshots、/sd、/upload 等)。本类负责把 AI 传入的路径自动解析为设备路径:
 *
 * - 已经是设备路径(/storage/emulated/0、/sdcard、/data 等)时原样返回
 * - bind mount(/screenshots、/sd、/skills、/memories、/upload、/tool_outputs)直接映射到宿主机目录
 * - /workspace 及其它 Rootfs 内部路径经默认工作区解析; 落在应用私有目录且需要被 shell 读取时,
 *   自动复制到共享目录 /storage/emulated/0/AI-Agent/tmp 并返回该地址(临时暴露给外部)。
 */
class WorkspacePathResolver(
    private val context: Context,
    private val workspaceRepository: WorkspaceRepository,
) {
    /**
     * 把 [path](工作区路径或设备路径)解析为设备上的真实路径。
     *
     * @param needsShellRead 结果是否需要被 shell(Shizuku) 进程读取; 为 true 时,
     *                       应用私有目录下的文件会先复制到共享 tmp 目录。
     * @return 设备真实路径; 文件不存在或无法解析时返回 null。
     */
    suspend fun toDevicePath(path: String, needsShellRead: Boolean = false): String? {
        val raw = path.trim().replace('\\', '/').trimEnd('/')
        if (raw.isBlank()) return null

        // 已是设备路径: 直接使用
        if (isDevicePath(raw)) {
            return File(raw).takeIf { it.exists() }?.absolutePath ?: raw
        }

        // 相对路径: 相对工作区文件根(/workspace)解析
        val normalized = if (raw.startsWith("/")) {
            raw
        } else {
            "/workspace/${raw.removePrefix("./")}"
        }

        // 全局 bind mount 前缀 → 宿主机真实目录
        GLOBAL_MOUNTS.firstOrNull { mount ->
            normalized == "/$mount" || normalized.startsWith("/$mount/")
        }?.let { mount ->
            val hostDir = bindMountHostDir(mount) ?: return null
            val file = File(hostDir, normalized.removePrefix("/$mount").trimStart('/'))
            return if (file.exists()) file.absolutePath else null
        }

        // 其余按默认工作区 Rootfs 路径解析(/workspace 及容器内部路径)
        val workspace = workspaceRepository.getDefaultWorkspace() ?: return null
        val file = runCatching {
            workspaceRepository.resolveRootfsHostFile(workspace.id, normalized)
        }.getOrNull() ?: return null
        if (!file.exists()) return null
        if (needsShellRead && !isShellReadable(file)) {
            return exposeToShared(file)?.absolutePath
        }
        return file.absolutePath
    }

    private fun isDevicePath(p: String): Boolean =
        p.startsWith("/storage/emulated/0") ||
            p.startsWith("/storage/self") ||
            p.startsWith("/sdcard") ||
            p.startsWith("/data/") ||
            p.startsWith("/system/") ||
            p.startsWith("/vendor/") ||
            p.startsWith("/mnt/") ||
            p.startsWith("/cache/")

    /** 全局 bind mount 的宿主机源目录(与 RepositoryModule 中的挂载表保持一致) */
    private fun bindMountHostDir(mount: String): File? = when (mount) {
        "screenshots" -> sharedDir(FileFolders.SCREENSHOTS)
        "skills" -> sharedDir(FileFolders.SKILLS)
        "memories" -> sharedDir(FileFolders.MEMORIES)
        "sd" -> sharedDir(FileFolders.SD_DIR)
        "upload" -> File(context.filesDir, FileFolders.UPLOAD)
        "tool_outputs" -> File(context.filesDir, FileFolders.TOOL_OUTPUTS)
        else -> null
    }

    private fun sharedDir(sub: String): File =
        File(Environment.getExternalStorageDirectory(), "$AI_AGENT_SHARED_DIR/$sub")

    /** 应用私有目录下的文件对 shell 不可读, 需要复制到共享位置 */
    private fun isShellReadable(file: File): Boolean =
        !file.absolutePath.startsWith(context.filesDir.absolutePath)

    /** 复制到共享 tmp 目录并放开权限, 供 shell(Shizuku) 读取 */
    private fun exposeToShared(file: File): File? = runCatching {
        val tmp = sharedDir("tmp").apply {
            mkdirs()
            setReadable(true, false)
            setExecutable(true, false)
        }
        val target = File(tmp, "${System.currentTimeMillis()}_${file.name}")
        file.copyTo(target, overwrite = true)
        target.setReadable(true, false)
        Runtime.getRuntime().exec(arrayOf("chmod", "664", target.absolutePath)).waitFor()
        target
    }.getOrNull()

    companion object {
        /** 全局 bind mount 目标(不含 /workspace, 它按工作区解析) */
        private val GLOBAL_MOUNTS = listOf(
            "screenshots", "skills", "memories", "sd", "upload", "tool_outputs",
        )
    }
}
