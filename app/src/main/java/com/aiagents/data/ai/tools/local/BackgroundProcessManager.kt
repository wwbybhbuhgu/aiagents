package com.aiagents.data.ai.tools.local

import java.io.BufferedReader
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 后台进程管理器: 支持 Shell / Node.js 等后台进程的启动、输出读取、终止。
 *
 * 每个进程分配一个 UUID, AI 通过 ID 读取输出或终止进程。
 */
class BackgroundProcessManager {

    /** 代理环境变量 (从 ProxyManager 获取) */
    var proxyEnv: Map<String, String> = emptyMap()

    data class ManagedProcess(
        val id: String,
        val type: String,           // "shell" | "node"
        val command: String,
        val process: Process,
        val stdoutReader: BufferedReader,
        val stderrReader: BufferedReader,
        val startedAt: Long = System.currentTimeMillis(),
        val killed: AtomicBoolean = AtomicBoolean(false),
    ) {
        val stdoutLog: StringBuilder = StringBuilder()
        val stderrLog: StringBuilder = StringBuilder()
        val isRunning: Boolean get() = process.isAlive && !killed.get()
    }

    private val processes = ConcurrentHashMap<String, ManagedProcess>()

    /** 启动一个后台进程 */
    fun start(type: String, command: String, workDir: File?): String {
        val id = UUID.randomUUID().toString().take(8)
        val pb = ProcessBuilder("sh", "-c", command)
        workDir?.let { pb.directory(it) }
        pb.redirectErrorStream(false)
        // 注入代理环境变量
        if (proxyEnv.isNotEmpty()) {
            val env = pb.environment()
            env.putAll(proxyEnv)
        }

        val proc = pb.start()
        val managed = ManagedProcess(
            id = id,
            type = type,
            command = command,
            process = proc,
            stdoutReader = proc.inputStream.bufferedReader(),
            stderrReader = proc.errorStream.bufferedReader(),
        )
        processes[id] = managed

        // 后台线程持续读取输出
        Thread({
            try {
                val reader = managed.stdoutReader
                while (proc.isAlive) {
                    val line = reader.readLine() ?: break
                    synchronized(managed.stdoutLog) {
                        managed.stdoutLog.appendLine(line)
                    }
                }
                val remaining = reader.readText()
                if (!remaining.isNullOrEmpty()) {
                    synchronized(managed.stdoutLog) { managed.stdoutLog.append(remaining) }
                }
            } catch (_: Exception) {}
        }, "bg-stdout-$id").isDaemon = true

        Thread({
            try {
                val reader = managed.stderrReader
                while (proc.isAlive) {
                    val line = reader.readLine() ?: break
                    synchronized(managed.stderrLog) {
                        managed.stderrLog.appendLine(line)
                    }
                }
                val remaining = reader.readText()
                if (!remaining.isNullOrEmpty()) {
                    synchronized(managed.stderrLog) { managed.stderrLog.append(remaining) }
                }
            } catch (_: Exception) {}
        }, "bg-stderr-$id").isDaemon = true

        return id
    }

    /** 读取进程输出 */
    fun readOutput(id: String): Map<String, Any?>? {
        val p = processes[id] ?: return null
        readRemaining(p)
        return mapOf(
            "id" to p.id,
            "type" to p.type,
            "command" to p.command,
            "running" to p.isRunning,
            "exitCode" to if (p.isRunning) null else p.process.exitValue(),
            "stdout" to p.stdoutLog.toString().trim(),
            "stderr" to p.stderrLog.toString().trim(),
            "startedAt" to p.startedAt,
        )
    }

    /** 终止进程 */
    fun kill(id: String): Boolean {
        val p = processes[id] ?: return false
        p.killed.set(true)
        p.process.destroyForcibly()
        readRemaining(p)
        return true
    }

    /** 列出所有进程 */
    fun list(): List<Map<String, Any?>> {
        processes.values.forEach { readRemaining(it) }
        return processes.values.map { p ->
            mapOf(
                "id" to p.id,
                "type" to p.type,
                "command" to p.command,
                "running" to p.isRunning,
                "exitCode" to if (p.isRunning) null else p.process.exitValue(),
            )
        }
    }

    /** 清理已结束的进程记录 */
    fun cleanup() {
        processes.entries.removeIf { !it.value.isRunning }
    }

    private fun readRemaining(p: ManagedProcess) {
        try {
            while (p.stdoutReader.ready()) {
                val line = p.stdoutReader.readLine() ?: break
                synchronized(p.stdoutLog) { p.stdoutLog.appendLine(line) }
            }
        } catch (_: Exception) {}
        try {
            while (p.stderrReader.ready()) {
                val line = p.stderrReader.readLine() ?: break
                synchronized(p.stderrLog) { p.stderrLog.appendLine(line) }
            }
        } catch (_: Exception) {}
    }
}
