package com.aiagents.workspace

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

class WorkspaceManager(
    private val baseDir: File,
    private val config: WorkspaceConfig = WorkspaceConfig(),
    private val shellRunner: WorkspaceShellRunner = HostShellRunner(),
    private val bindMounts: List<WorkspaceBindMount> = emptyList(),
    /** 工作区 SD 区域: 对应安卓外部存储下的专用文件夹（所有工作区共享, 容器内挂载为 /sd） */
    private val sdDir: File? = null,
) {
    private val fileSystem = WorkspaceFileSystem(config)

    // 按 target 长度降序, 保证 /a/b 优先于 /a 匹配
    private val sortedBindMounts = bindMounts.sortedByDescending { it.target.trimEnd('/').length }

    init {
        baseDir.mkdirs()
    }

    fun ensureWorkspace(root: String): File {
        val dir = workspaceDir(root)
        filesDir(root).apply { mkdirs(); makeWorldAccessible(); createNomediaIfMissing() }
        linuxDir(root).mkdirs()
        tempDir(root).apply { mkdirs(); makeWorldAccessible() }
        return dir
    }

    fun workspaceDir(root: String): File {
        requireValidRoot(root)
        return File(baseDir, root)
    }

    /** 放开目录到 o+rx(755), 使 proot --root-id 容器内 root 可读取 bind mount 源目录 */
    private fun File.makeWorldAccessible(): File {
        setReadable(true, false)
        setExecutable(true, false)
        return this
    }

    /** 在目录下创建 .nomedia, 阻止安卓相册扫描工具产出的文件 */
    private fun File.createNomediaIfMissing() {
        val nomedia = File(this, ".nomedia")
        if (!nomedia.exists()) {
            runCatching { nomedia.createNewFile() }
        }
    }

    fun filesDir(root: String): File = File(workspaceDir(root), FILES_DIR)

    fun linuxDir(root: String): File = File(workspaceDir(root), LINUX_DIR)

    fun tempDir(root: String): File = File(workspaceDir(root), TEMP_DIR)

    fun hasRootfs(root: String): Boolean = File(linuxDir(root), "bin/sh").isFile

    fun deleteWorkspace(root: String): Boolean = workspaceDir(root).deleteRecursively()

    fun listFiles(
        root: String,
        path: String = "",
        area: WorkspaceStorageArea = WorkspaceStorageArea.FILES,
    ): List<WorkspaceFileEntry> {
        // 绝对路径(含 /workspace、/upload 等挂载点)统一走 Rootfs 路径解析,
        // 使 UI 文件管理器能看到 bind mount 目录里的真实文件
        if (path.trim().startsWith("/")) {
            val location = resolveRootfsPath(root, path)
            return fileSystem.list(location.rootDir, location.relativePath)
        }
        // 相对路径: 检查是否是 bind mount 子目录(如 upload), 是则列出挂载点 source 的真实内容
        val relative = path.trim().trim('/')
        if (relative.isNotEmpty() && relative != ".") {
            val mount = sortedBindMounts.firstOrNull { m ->
                val target = m.target.trim('/')
                target == relative || relative.startsWith("$target/")
            }
            if (mount != null) {
                val mountName = mount.target.trim('/')
                val sub = relative.removePrefix(mountName).trimStart('/')
                return fileSystem.list(mount.source, sub).map { entry ->
                    // 补回挂载前缀, 使文件管理器的路径能正确拼接(sd/hhh.txt 而非 hhh.txt)
                    entry.copy(path = "$mountName/${entry.path}".trimEnd('/'))
                }
            }
        }
        // 浏览 FILES 根目录时, 把全局 bind mount 目录(如 upload)作为子目录列出,
        // 使文件管理器能看到上传目录
        if (relative.isEmpty() || relative == ".") {
            val ownEntries = fileSystem.list(areaDir(root, area), "")
            val mountDirs = sortedBindMounts.mapNotNull { mount ->
                val name = mount.target.trim('/')
                if (name.isBlank() || name.contains('/')) return@mapNotNull null
                val source = mount.source
                if (!source.exists() || !source.isDirectory) return@mapNotNull null
                WorkspaceFileEntry(
                    path = name,
                    name = name,
                    isDirectory = true,
                    sizeBytes = 0L,
                    updatedAt = source.lastModified(),
                )
            }
            return (ownEntries + mountDirs).distinctBy { it.path }
        }
        return fileSystem.list(areaDir(root, area), path)
    }

    fun readText(
        root: String,
        path: String,
        charset: Charset = StandardCharsets.UTF_8,
    ): String = fileSystem.readText(resolveAreaOrMountDir(root, path), resolveAreaOrMountRel(path), charset)

    fun writeText(
        root: String,
        path: String,
        text: String,
        overwrite: Boolean = true,
        charset: Charset = StandardCharsets.UTF_8,
    ): WorkspaceFileEntry =
        fileSystem.writeText(resolveAreaOrMountDir(root, path), resolveAreaOrMountRel(path), text, overwrite, charset)

    fun importFile(
        root: String,
        destinationPath: String,
        area: WorkspaceStorageArea = WorkspaceStorageArea.FILES,
        fileName: String,
        inputStream: InputStream,
    ): WorkspaceFileEntry {
        val base = if (destinationPath.trim().startsWith("/")) {
            resolveRootfsPath(root, destinationPath).let { it.rootDir to it.relativePath }
        } else {
            areaDir(root, area) to destinationPath
        }
        val targetPath = if (base.second.isBlank()) fileName else "${base.second}/$fileName"
        return fileSystem.importBytes(base.first, targetPath, inputStream)
    }

    fun exportFile(
        root: String,
        path: String,
        area: WorkspaceStorageArea = WorkspaceStorageArea.FILES,
        outputStream: OutputStream,
    ) {
        fileSystem.exportBytes(resolveAreaOrMountDir(root, path), resolveAreaOrMountRel(path), outputStream)
    }

    fun deleteFile(
        root: String,
        path: String,
        recursive: Boolean,
        area: WorkspaceStorageArea = WorkspaceStorageArea.FILES,
    ): Boolean {
        return fileSystem.delete(resolveAreaOrMountDir(root, path), resolveAreaOrMountRel(path), recursive)
    }

    fun moveFile(
        root: String,
        source: String,
        target: String,
        overwrite: Boolean = false,
    ): WorkspaceFileEntry {
        // 源和目标是同一区域(如都在 /workspace 或都在挂载点内)时直接移动;
        // 跨区域移动时, 源按挂载解析, 目标也按挂载解析, 但最终以源目录为基准
        val srcDir = resolveAreaOrMountDir(root, source)
        val srcRel = resolveAreaOrMountRel(source)
        val dstDir = resolveAreaOrMountDir(root, target)
        val dstRel = resolveAreaOrMountRel(target)
        if (srcDir == dstDir) {
            return fileSystem.move(srcDir, srcRel, dstRel, overwrite)
        }
        // 跨目录: 先导出再导入(简单可靠)
        val tmp = java.io.File.createTempFile("mv", ".tmp")
        tmp.deleteOnExit()
        fileSystem.exportBytes(srcDir, srcRel, tmp.outputStream())
        val entry = fileSystem.importBytes(dstDir, dstRel, tmp.inputStream())
        fileSystem.delete(srcDir, srcRel, recursive = false)
        tmp.delete()
        return entry
    }

    /**
     * 相对路径若命中 bind mount 子目录(如 upload/sd/skills), 返回挂载源目录;
     * 否则返回文件区目录。配合 [resolveAreaOrMountRel] 使用。
     */
    private fun resolveAreaOrMountDir(root: String, path: String): File {
        val relative = path.trim().trim('/')
        if (relative.isEmpty() || relative == "." || !path.startsWith("/")) {
            sortedBindMounts.firstOrNull { mount ->
                val target = mount.target.trim('/')
                target == relative || relative.startsWith("$target/")
            }?.let { return it.source }
        }
        return filesDir(root)
    }

    /** 去掉 bind mount 前缀后的相对路径 */
    private fun resolveAreaOrMountRel(path: String): String {
        val relative = path.trim().trim('/')
        sortedBindMounts.firstOrNull { mount ->
            val target = mount.target.trim('/')
            target == relative || relative.startsWith("$target/")
        }?.let { mount ->
            return relative.removePrefix(mount.target.trim('/')).trimStart('/')
        }
        return path.trimStart('/')
    }

    fun fileSize(
        root: String,
        path: String,
        area: WorkspaceStorageArea = WorkspaceStorageArea.FILES,
    ): Long {
        val file = fileSystem.resolve(areaDir(root, area), path)
        require(file.exists()) { "File does not exist: $path" }
        require(file.isFile) { "Path is not a file: $path" }
        return file.length()
    }

    /**
     * 把 Rootfs 内的绝对路径映射到宿主机上的真实文件。
     *
     * bind mount 的 source 本身就是 Android 侧的普通目录, 因此 /skills 这类挂载路径
     * 可以直接用文件 IO 访问, 无需经过 PRoot; 只是 Rootfs 目录里对应位置是个空挂载点,
     * 按 [WorkspaceStorageArea.LINUX] 解析必然落空。
     */
    fun resolveRootfsPath(root: String, path: String): RootfsLocation {
        val trimmed = path.trim().trimEnd('/').ifBlank { "/" }
        require(trimmed.startsWith("/")) { "Rootfs path must be absolute: $path" }

        sortedBindMounts.forEach { mount ->
            val target = mount.target.trimEnd('/')
            if (trimmed == target) return RootfsLocation(mount.source, "")
            if (trimmed.startsWith("$target/")) {
                return RootfsLocation(mount.source, trimmed.removePrefix("$target/"))
            }
        }

        if (trimmed == ROOTFS_WORKSPACE_DIR || trimmed.startsWith("$ROOTFS_WORKSPACE_DIR/")) {
            return RootfsLocation(
                rootDir = filesDir(root),
                relativePath = trimmed.removePrefix(ROOTFS_WORKSPACE_DIR).trimStart('/'),
            )
        }

        // SD 区域: /sd -> 安卓外部存储专用文件夹
        sdDir?.let { sd ->
            if (trimmed == ROOTFS_SD_DIR || trimmed.startsWith("$ROOTFS_SD_DIR/")) {
                return RootfsLocation(
                    rootDir = sd,
                    relativePath = trimmed.removePrefix(ROOTFS_SD_DIR).trimStart('/'),
                )
            }
        }

        // 内核伪文件系统: 显式拒绝, 而不是回落到一个必然读不到的物理路径
        KERNEL_FS_MOUNTS.firstOrNull { trimmed == it || trimmed.startsWith("$it/") }?.let {
            error("$it is a kernel filesystem and cannot be read as a file, use workspace_shell instead")
        }

        return RootfsLocation(linuxDir(root), trimmed.trimStart('/'))
    }

    fun rootfsFileSize(root: String, path: String): Long =
        resolveRootfsFile(root, path).also { it.requireReadableFile(path) }.length()

    /** 把 Rootfs 内绝对路径映射为宿主机上的真实文件(设备路径)。 */
    fun resolveRootfsHostFile(root: String, path: String): File {
        val location = resolveRootfsPath(root, path)
        return fileSystem.resolve(location.rootDir, location.relativePath)
    }

    fun exportRootfsFile(root: String, path: String, outputStream: OutputStream) {
        val file = resolveRootfsFile(root, path)
        file.requireReadableFile(path)
        outputStream.use { out -> file.inputStream().use { it.copyTo(out) } }
    }

    private fun resolveRootfsFile(root: String, path: String): File {
        val location = resolveRootfsPath(root, path)
        return fileSystem.resolve(location.rootDir, location.relativePath)
    }

    private fun File.requireReadableFile(path: String) {
        require(exists()) { "File does not exist: $path" }
        require(isFile) { "Path is not a file: $path" }
    }

    fun glob(
        root: String,
        pattern: String,
        path: String = "",
        cwd: String? = null,
    ): List<WorkspaceFileEntry> {
        val location = resolveRootfsPath(root, normalizeRootfsPath(path, cwd))
        return fileSystem.glob(location.rootDir, pattern, location.relativePath)
    }

    fun grep(
        root: String,
        query: String,
        path: String = "",
        regex: Boolean = false,
        ignoreCase: Boolean = true,
        includeGlob: String? = null,
        cwd: String? = null,
    ): List<WorkspaceSearchMatch> {
        val location = resolveRootfsPath(root, normalizeRootfsPath(path, cwd))
        return fileSystem.grep(location.rootDir, query, location.relativePath, regex, ignoreCase, includeGlob)
    }

    fun executeCommand(
        root: String,
        command: String,
        cwd: String = "",
        timeoutMillis: Long = DEFAULT_COMMAND_TIMEOUT_MS,
        stdin: ByteArray? = null,
    ): WorkspaceCommandResult {
        require(command.isNotBlank()) { "Command is required" }
        val workingDir = fileSystem.resolve(filesDir(root), cwd)
        require(workingDir.exists()) { "Working directory does not exist: $cwd" }
        require(workingDir.isDirectory) { "Working path is not a directory: $cwd" }

        return shellRunner.execute(
            WorkspaceShellContext(
                root = root,
                command = command,
                cwd = cwd,
                filesDir = filesDir(root),
                linuxDir = linuxDir(root),
                tempDir = tempDir(root),
                workingDir = workingDir,
                timeoutMillis = timeoutMillis,
                stdin = stdin,
                bindMounts = bindMounts,
            )
        )
    }

    private fun requireValidRoot(root: String) {
        require(root.matches(ROOT_NAME_REGEX)) {
            "Invalid workspace root name: $root"
        }
    }

    /**
     * 把工具调用传入的路径规范化为 Rootfs 内绝对路径。
     * - 已以 `/` 开头的绝对路径（/workspace/...、/upload/... 等挂载点）原样保留
     * - 相对路径基于工作区配置的工作目录 [cwd] 解析（cwd 为空时按 /workspace 根目录）
     */
    private fun normalizeRootfsPath(path: String, cwd: String? = null): String {
        val trimmed = path.trim()
        if (trimmed.isEmpty() || trimmed == ".") {
            return cwd?.trim()?.takeIf { it.startsWith("/") } ?: ROOTFS_WORKSPACE_DIR
        }
        if (trimmed.startsWith("/")) return trimmed
        val base = cwd?.trim()?.takeIf { it.startsWith("/") } ?: ROOTFS_WORKSPACE_DIR
        return "$base/${trimmed.trimStart('/')}"
    }

    /** 工作区 SD 区域的宿主机目录（安卓外部存储专用文件夹） */
    fun sdRoot(): File? = sdDir

    /** 读取 SD 区域文件文本 */
    fun readSdText(path: String, charset: Charset = StandardCharsets.UTF_8): String {
        val sd = sdDir ?: error("SD area not configured")
        return fileSystem.readText(sd, path, charset)
    }

    private fun areaDir(root: String, area: WorkspaceStorageArea): File = when (area) {
        WorkspaceStorageArea.FILES -> filesDir(root)
        WorkspaceStorageArea.LINUX -> linuxDir(root)
        WorkspaceStorageArea.SD -> sdDir ?: filesDir(root)
    }

    fun cleanupAllTempDirs() {
        val roots = baseDir.listFiles()?.filter { it.isDirectory } ?: return
        for (dir in roots) {
            val root = dir.name
            if (!root.matches(ROOT_NAME_REGEX)) continue
            // PRoot temp files
            tempDir(root).let { if (it.exists()) it.deleteRecursively() }
            // Rootfs /tmp and /var/tmp
            File(linuxDir(root), "tmp").let { if (it.exists()) it.deleteRecursively() }
            File(linuxDir(root), "var/tmp").let { if (it.exists()) it.deleteRecursively() }
        }
    }

    companion object {
        private const val FILES_DIR = "files"
        private const val LINUX_DIR = "linux"
        private const val TEMP_DIR = "tmp"
        const val DEFAULT_COMMAND_TIMEOUT_MS = 30_000L

        /** Rootfs 内工作区文件区的挂载点 */
        const val ROOTFS_WORKSPACE_DIR = "/workspace"

        /** Rootfs 内 SD 区域的挂载点（对应安卓外部存储专用文件夹） */
        const val ROOTFS_SD_DIR = "/sd"

        /** 由宿主机透传的内核伪文件系统, 只能通过 shell 访问 */
        val KERNEL_FS_MOUNTS = listOf("/dev", "/proc", "/sys")

        private val ROOT_NAME_REGEX = Regex("[A-Za-z0-9._-]+")
    }
}

/** Rootfs 内绝对路径在宿主机上的落点 */
data class RootfsLocation(
    val rootDir: File,
    val relativePath: String,
)
