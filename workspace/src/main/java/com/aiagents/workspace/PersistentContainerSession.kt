package com.aiagents.workspace

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.Volatile

private const val TAG = "PersistentContainer"

/** 命令结束标记（bash 循环在每个命令输出后打印） */
private val END_MARKER = byteArrayOf(0x00, 'E'.code.toByte(), 'N'.code.toByte(), 'D'.code.toByte(), 0x00)
private val EXIT_PREFIX = "__EXIT__:"
private const val MAX_SESSION_OUTPUT_BYTES = 16 * 1024 * 1024

/**
 * 常驻 proot 容器会话。
 *
 * 启动一个常驻 bash 循环进程（proot 内），命令以 NUL 结尾写入 stdin，
 * 每条命令输出后打印 `__EXIT__:<code>\0END\0` 标记；因此：
 * - 容器进程持续存活，`nohup ... &` 的后台任务不会被一次性进程回收杀掉
 * - 环境变量 / 工作目录 / 已安装的包 / 已启动的服务跨命令保持
 *
 * 该会话由 [PersistentShellRunner] 按 workspace root 懒创建。
 */
class PersistentContainerSession(
    private val prootArgs: List<String>,
    private val filesDir: File,
    private val prootLoader: String? = null,
    private val prootTempDir: String? = null,
) {
    @Volatile
    private var process: Process? = null
    private val lock = Any()

    val isAlive: Boolean get() = process?.isAlive == true

    /** 启动常驻进程（幂等） */
    fun start(): Boolean {
        synchronized(lock) {
            if (process?.isAlive == true) return true
            val pb = ProcessBuilder(prootArgs)
                .directory(filesDir)
                .redirectErrorStream(false)
            // proot 依赖 loader so 与临时目录环境变量, 缺失会导致执行缓慢/异常
            prootLoader?.let { pb.environment()["PROOT_LOADER"] = it }
            prootTempDir?.let {
                pb.environment()["PROOT_TMP_DIR"] = it
                pb.environment()["TMPDIR"] = it
            }
            val p = pb.start()
            process = p
            return p.isAlive
        }
    }

    /**
     * 在常驻会话中执行命令（阻塞，带超时）。
     * 命令通过 stdin 写入（NUL 结尾），读取 stdout 直到 END 标记。
     */
    fun execute(
        command: String,
        timeoutMillis: Long = WorkspaceManager.DEFAULT_COMMAND_TIMEOUT_MS,
        stdin: ByteArray? = null,
    ): WorkspaceCommandResult {
        val p = start()?.let {
            if (it) process
            else return WorkspaceCommandResult(127, "", "Failed to start container session")
        } ?: return WorkspaceCommandResult(127, "", "Container session not available")

        synchronized(lock) {
            val deadline = System.nanoTime() + timeoutMillis * 1_000_000L
            return runCatching {
                // 1. 写入命令
                val writer = p.outputStream
                if (stdin != null) {
                    // 带 stdin 的命令先喂给一个一次性 bash 无法完成；这里直接拒绝,
                    // 由 PersistentShellRunner 回退到一次性 proot
                    return WorkspaceCommandResult(127, "", "Persistent session does not support stdin commands")
                }
                writer.write(command.toByteArray(Charsets.UTF_8))
                writer.write(0)
                writer.flush()

                // 2. 读取直到 END 标记
                val out = ByteArrayOutputStream()
                val reader = p.inputStream
                val buffer = ByteArray(8192)
                // 只保留尾部 END_MARKER.size 字节用于匹配, 避免每次全量复制 O(n²)
                val tail = ByteArray(END_MARKER.size)
                var tailLen = 0
                var markerFound = false
                var timedOut = false
                var truncated = false

                fun pushTail(b: Byte) {
                    if (tailLen < tail.size) {
                        tail[tailLen++] = b
                    } else {
                        System.arraycopy(tail, 1, tail, 0, tail.size - 1)
                        tail[tail.size - 1] = b
                    }
                }

                while (System.nanoTime() < deadline) {
                    val available = reader.available()
                    if (available > 0) {
                        val n = reader.read(buffer, 0, minOf(buffer.size, available))
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        for (i in 0 until n) pushTail(buffer[i])
                        if (out.size() > MAX_SESSION_OUTPUT_BYTES) {
                            truncated = true
                            break
                        }
                        if (tailLen == tail.size && tail.contentEquals(END_MARKER)) {
                            markerFound = true
                            break
                        }
                    } else {
                        if (Thread.currentThread().isInterrupted) {
                            // 协程取消（runInterruptible）转成线程中断
                            throw InterruptedException("Container command interrupted")
                        }
                        Thread.sleep(10)
                    }
                }
                if (!markerFound && !truncated) timedOut = true

                // 3. 解析输出与退出码
                val raw = out.toByteArray()
                val (output, exitCode) = parseResult(raw)

                if (timedOut || truncated) {
                    // 超时/截断时杀掉整个会话, 避免残留进程
                    kill()
                }
                WorkspaceCommandResult(
                    exitCode = exitCode,
                    stdout = output,
                    stderr = "",
                    timedOut = timedOut,
                    truncated = truncated,
                )
            }.getOrElse { e ->
                when (e) {
                    is InterruptedException -> {
                        kill()
                        throw e
                    }
                    else -> WorkspaceCommandResult(127, "", "Container command failed: ${e.message}")
                }
            }
        }
    }

    fun kill() {
        synchronized(lock) {
            runCatching { process?.destroy() }
            process = null
        }
    }

    private fun parseResult(raw: ByteArray): Pair<String, Int> {
        val text = String(raw, Charsets.UTF_8)
        val markerIndex = text.lastIndexOf("\u0000END\u0000")
        val body = if (markerIndex >= 0) text.substring(0, markerIndex) else text
        // 最后一行是 __EXIT__:N
        val lines = body.split('\n')
        var exitCode = 0
        val output = buildString {
            lines.forEachIndexed { index, line ->
                if (line.startsWith(EXIT_PREFIX)) {
                    if (index == lines.lastIndex) {
                        exitCode = line.removePrefix(EXIT_PREFIX).trim().toIntOrNull() ?: 0
                    }
                } else {
                    appendLine(line)
                }
            }
        }
        return output.trimEnd('\n') to exitCode
    }

    companion object {
        /**
         * 从一次性 proot 上下文构建常驻会话：命令换成 bash 命令循环。
         */
        fun from(
            context: WorkspaceShellContext,
            nativeLibraryDir: File,
            patcher: RootfsPatcher,
            proxyEnv: () -> Map<String, String> = { emptyMap() },
        ): PersistentContainerSession {
            context.tempDir.mkdirs()
            patcher.patch(context.linuxDir)
            val proot = File(nativeLibraryDir, "libproot_exec.so")
            val loader = File(nativeLibraryDir, "libproot_loader.so")

            val args = mutableListOf(
                proot.absolutePath,
                "--root-id",
                "--link2symlink",
                "-r",
                context.linuxDir.absolutePath,
                "-w",
                "/workspace",
                "-b",
                "${context.filesDir.absolutePath}:/workspace",
            )
            context.bindMounts.forEach { mount ->
                if (mount.source.exists()) {
                    args += "-b"
                    args += "${mount.source.absolutePath}:${mount.target.trimEnd('/')}"
                }
            }
            WorkspaceManager.KERNEL_FS_MOUNTS.forEach { path ->
                if (File(path).exists()) {
                    args += "-b"
                    args += path
                }
            }
            args += listOf(
                "/usr/bin/env",
                "-i",
                "HOME=/root",
                "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
                "TERM=xterm-256color",
                "LANG=C.UTF-8",
                "LC_ALL=C.UTF-8",
            )
            proxyEnv().forEach { (k, v) ->
                // 环境变量值不能含空格, 否则 proot 的 env 参数解析会出错; 简单过滤掉含空格的
                if (!k.contains(' ') && !v.contains(' ')) {
                    args += "$k=$v"
                }
            }
            args += listOf(
                "/bin/bash",
                "-l",
                "-c",
                // 常驻命令循环: NUL 分隔命令, 每条输出后打印退出码与 END 标记
                // umask 022: 新建文件默认 644(其它用户可读), 否则容器内创建的文件
                // 权限 660 导致共享存储中其它应用无法读取内容
                "umask 022; " +
                    "while IFS= read -r -d '' cmd; do " +
                    "eval \"\$cmd\" 2>&1; rc=\$?; " +
                    "printf '\\n__EXIT__:%s\\n\\000END\\000' \"\$rc\"; " +
                    "done",
            )
            return PersistentContainerSession(
                prootArgs = args,
                filesDir = context.filesDir,
                prootLoader = loader.absolutePath,
                prootTempDir = context.tempDir.absolutePath,
            )
        }
    }
}

/**
 * 常驻容器命令执行器：优先走常驻会话（支持后台任务保活），
 * 带 stdin 的命令或会话不可用时回退到一次性 proot。
 */
class PersistentShellRunner(
    private val nativeLibraryDir: File,
    private val patcher: RootfsPatcher = RootfsPatcher(),
    private val proxyEnv: () -> Map<String, String> = { emptyMap() },
) : WorkspaceShellRunner {

    private val sessions = ConcurrentHashMap<String, PersistentContainerSession>()
    private val oneShot = ProotShellRunner(nativeLibraryDir, patcher, proxyEnv)

    override fun execute(context: WorkspaceShellContext): WorkspaceCommandResult {
        if (!context.linuxDir.hasUsableRootfs()) {
            return WorkspaceCommandResult(exitCode = 127, stdout = "", stderr = "Rootfs is not installed")
        }
        // 带 stdin 的命令不支持常驻会话, 走一次性 proot
        if (context.stdin != null) {
            return oneShot.execute(context)
        }
        val session = sessions.getOrPut(context.root) {
            PersistentContainerSession.from(context, nativeLibraryDir, patcher, proxyEnv)
        }
        if (!session.isAlive) {
            // 常驻进程没了（如被杀/崩溃), 重启会话
            session.start()
        }
        val result = session.execute(context.command, context.timeoutMillis, context.stdin)
        // 会话级别的硬错误(proot 缺失等)回退一次性执行
        if (result.exitCode == 127 && result.stderr.startsWith("Failed to start")) {
            sessions.remove(context.root)
            return oneShot.execute(context)
        }
        return result
    }

    fun stopAll() {
        sessions.values.forEach { it.kill() }
        sessions.clear()
    }

    fun sessionCount(): Int = sessions.size

    private fun File.hasUsableRootfs(): Boolean =
        isDirectory && File(this, "bin/sh").isFile
}
