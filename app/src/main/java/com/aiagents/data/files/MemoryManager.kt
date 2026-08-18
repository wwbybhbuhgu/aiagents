package com.aiagents.data.files

import android.content.Context
import android.os.Environment
import android.util.Log
import com.aiagents.data.model.AssistantMemory
import com.aiagents.di.AI_AGENT_SHARED_DIR
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * 文件化记忆管理器: 每条记忆 = 共享目录下的一个子目录(与 skill 同构, 可复用文件工具/脚本直接读写)。
 *
 * 目录布局:
 *   /sdcard/AI-Agent/memories/<scope>/<entry-name>/
 *       MEMORY.md    记忆正文(Markdown, 可带 frontmatter: name / description / updated)
 *       ...          可选附件(图片等, AI 可用 workspace 文件工具直接写入)
 *
 * scope 为 `__global__`(全局共享记忆)或助手 UUID(助手隔离记忆)。
 */
class MemoryManager(private val context: Context) {

    companion object {
        private const val TAG = "MemoryManager"
        const val MEMORY_FILE_NAME = "MEMORY.md"
        const val GLOBAL_SCOPE = "__global__"

        /**
         * 条目目录名校验: 允许 Unicode 字母/数字/标点/符号/空格(支持中文等命名),
         * 但禁止路径分隔符(/、\)与 . / .., 防止目录穿越。
         */
        private val ENTRY_ID_REGEX = Regex("[\\p{L}\\p{N}\\p{P}\\p{S} ]+")
    }

    /** 记忆根目录: 共享存储根目录 AI-Agent/memories 下(用户可见, proot 容器可访问) */
    fun getMemoriesDir(): File {
        val dir = File(Environment.getExternalStorageDirectory(), "$AI_AGENT_SHARED_DIR/${FileFolders.MEMORIES}")
        if (!dir.exists()) dir.mkdirs()
        // 共享存储(FUSE)目录 setReadable 不生效, chmod 放开让其它应用/容器可读
        runCatching {
            Runtime.getRuntime().exec(arrayOf("chmod", "771", dir.absolutePath)).waitFor()
        }
        return dir
    }

    /** 某 scope(助手/全局)对应的目录 */
    fun getScopeDir(scope: String): File {
        val dir = File(getMemoriesDir(), sanitizeScope(scope))
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /** 校验并解析某条记忆的目录, 非法 id 返回 null */
    fun resolveEntryDir(scope: String, id: String): File? {
        if (!isValidEntryId(id)) return null
        return File(getScopeDir(scope), id)
    }

    fun listMemories(scope: String): List<AssistantMemory> {
        val scopeDir = getScopeDir(scope)
        return scopeDir.listFiles()
            ?.filter { it.isDirectory }
            ?.mapNotNull { dir ->
                val memoryFile = dir.resolve(MEMORY_FILE_NAME)
                if (!memoryFile.exists()) return@mapNotNull null
                parseMemoryFile(memoryFile, dir.name)
            }
            ?.sortedBy { it.name.lowercase() }
            ?: emptyList()
    }

    fun getMemory(scope: String, id: String): AssistantMemory? {
        val entryDir = resolveEntryDir(scope, id) ?: return null
        val memoryFile = entryDir.resolve(MEMORY_FILE_NAME)
        if (!memoryFile.exists()) return null
        return parseMemoryFile(memoryFile, entryDir.name)
    }

    /** 新增记忆: 创建目录并原子写入 MEMORY.md */
    fun addMemory(scope: String, id: String, content: String, description: String?): AssistantMemory {
        require(isValidEntryId(id)) { "非法的记忆名称: '$id'(仅允许字母/数字/标点/空格等安全字符, 且不能含路径分隔符) " }
        val entryDir = resolveEntryDir(scope, id) ?: error("非法的记忆名称: '$id'")
        if (!writeMemoryFileAtomically(scope, entryDir, content, description)) {
            error("写入记忆失败: $id")
        }
        return parseMemoryFile(entryDir.resolve(MEMORY_FILE_NAME), entryDir.name)
            ?: error("写入记忆失败: $id")
    }

    /** 更新记忆内容 */
    fun updateMemory(scope: String, id: String, content: String, description: String?): AssistantMemory {
        val entryDir = resolveEntryDir(scope, id) ?: error("非法的记忆 id: '$id'")
        if (!writeMemoryFileAtomically(scope, entryDir, content, description)) {
            error("更新记忆失败: $id")
        }
        return parseMemoryFile(entryDir.resolve(MEMORY_FILE_NAME), entryDir.name)
            ?: error("更新记忆失败: $id")
    }

    fun deleteMemory(scope: String, id: String): Boolean {
        val entryDir = resolveEntryDir(scope, id) ?: return false
        return entryDir.deleteRecursively()
    }

    fun deleteMemoriesOfAssistant(scope: String) {
        val scopeDir = File(getMemoriesDir(), sanitizeScope(scope))
        if (scopeDir.exists()) {
            scopeDir.deleteRecursively()
        }
    }

    /** 往某条记忆目录里保存附件(图片等); 返回写入的文件 */
    fun saveAttachment(scope: String, id: String, relativePath: String, bytes: ByteArray): File? {
        val entryDir = resolveEntryDir(scope, id) ?: return null
        val target = resolveAttachment(entryDir, relativePath) ?: return null
        target.parentFile?.mkdirs()
        target.writeBytes(bytes)
        return target
    }

    fun resolveAttachmentFile(scope: String, id: String, relativePath: String): File? {
        val entryDir = resolveEntryDir(scope, id) ?: return null
        return resolveAttachment(entryDir, relativePath)
    }

    /** Rootfs 内记忆路径(/memories/<scope>/<id>), 供记忆 prompt 提示 AI 直接脚本读写 */
    fun rootfsPath(scope: String, id: String): String = "/memories/$scope/$id"

    private fun writeMemoryFileAtomically(scope: String, entryDir: File, content: String, description: String?): Boolean {
        val memoriesDir = getMemoriesDir()
        val stagingDir = createTempDir(memoriesDir, entryDir.name, "staging") ?: return false
        var backupDir: File? = null
        try {
            val target = stagingDir.resolve(MEMORY_FILE_NAME)
            target.writeText(buildMemoryFileContent(entryDir.name, description, content))

            if (entryDir.exists()) {
                backupDir = createTempDir(memoriesDir, entryDir.name, "backup") ?: return false
                if (!entryDir.renameTo(backupDir)) return false
            }
            if (!stagingDir.renameTo(entryDir)) {
                if (backupDir != null && !entryDir.exists()) {
                    backupDir.renameTo(entryDir)
                }
                return false
            }
            backupDir?.deleteRecursively()
            return true
        } catch (e: Exception) {
            Log.w(TAG, "writeMemoryFileAtomically: Failed to save memory $scope/${entryDir.name}", e)
            if (backupDir != null && !entryDir.exists()) {
                backupDir.renameTo(entryDir)
            }
            return false
        } finally {
            if (stagingDir.exists()) {
                stagingDir.deleteRecursively()
            }
            if (backupDir?.exists() == true && entryDir.exists()) {
                backupDir.deleteRecursively()
            }
        }
    }

    private fun buildMemoryFileContent(id: String, description: String?, content: String): String = buildString {
        appendLine("---")
        appendLine("name: $id")
        if (!description.isNullOrBlank()) {
            appendLine("description: $description")
        }
        appendLine("updated: ${LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)}")
        appendLine("---")
        appendLine()
        append(content.trim())
        appendLine()
    }

    private fun parseMemoryFile(memoryFile: File, id: String): AssistantMemory? {
        return runCatching {
            val content = memoryFile.readText()
            val frontmatter = SkillFrontmatterParser.parse(content)
            val body = SkillFrontmatterParser.extractBody(content)
            AssistantMemory(
                id = id,
                name = frontmatter["name"]?.takeIf { it.isNotBlank() } ?: id,
                description = frontmatter["description"] ?: "",
                content = body,
            )
        }.getOrElse {
            Log.w(TAG, "parseMemoryFile: Failed to parse ${memoryFile.absolutePath}", it)
            null
        }
    }

    private fun resolveAttachment(entryDir: File, relativePath: String): File? {
        if (relativePath.isBlank()) return null
        val canonicalEntryDir = entryDir.canonicalFile
        val canonicalTarget = canonicalEntryDir.resolve(relativePath).canonicalFile
        val rootPath = canonicalEntryDir.path
        val targetPath = canonicalTarget.path
        return canonicalTarget.takeIf {
            targetPath == rootPath || targetPath.startsWith(rootPath + File.separator)
        }
    }

    private fun createTempDir(root: File, name: String, suffix: String): File? {
        repeat(100) { attempt ->
            val candidate = root.resolve(".$name.$suffix.$attempt.tmp")
            if (!candidate.exists() && candidate.mkdirs()) {
                return candidate
            }
        }
        return null
    }

    private fun sanitizeScope(scope: String): String {
        val sanitized = scope.replace(Regex("[^A-Za-z0-9._-]"), "_")
        return sanitized.ifBlank { GLOBAL_SCOPE }
    }

    private fun isValidEntryId(id: String): Boolean {
        if (id.isBlank()) return false
        if (id == "." || id == "..") return false
        if (id.contains('/') || id.contains('\\')) return false
        return ENTRY_ID_REGEX.matches(id)
    }
}
