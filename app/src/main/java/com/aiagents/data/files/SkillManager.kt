package com.aiagents.data.files

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.aiagents.data.datastore.SettingsStore
import com.aiagents.di.AI_AGENT_SHARED_DIR

class SkillManager(
    private val context: Context,
    private val settingsStore: SettingsStore,
) {
    companion object {
        private const val TAG = "SkillManager"

        /**
         * 系统内置 skill: 不对用户显示开关(无法关闭), 但始终对模型可用。
         * 这些 skill 由应用内置/随包提供, 用户列表与开关中隐藏, 避免用户误以为"关不掉是 bug"。
         */
        val SYSTEM_SKILLS: Set<String> = setOf("ai-agents-user-guide")

        fun isSystemSkill(name: String): Boolean = name in SYSTEM_SKILLS
    }

    // ---- SD 卡挂载（SAF 目录） ----

    /** 当前挂载的 SD 目录 URI（null = 内部存储） */
    fun sdSkillsUri(): Uri? =
        settingsStore.settingsFlow.value.skillsSdUri?.let { Uri.parse(it) }

    /**
     * 配置技能挂载到 SD 目录（SAF 选择后调用）：
     * 保存 URI → 内部技能同步到 SD → SD 技能拉取到内部（合并）。
     */
    suspend fun configureSdSkills(uri: Uri) = withContext(Dispatchers.IO) {
        settingsStore.update { it.copy(skillsSdUri = uri.toString()) }
        syncSkillsToSd()
        syncSkillsFromSd()
    }

    /** 解除 SD 挂载（回退内部存储） */
    suspend fun clearSdSkills() = withContext(Dispatchers.IO) {
        settingsStore.update { it.copy(skillsSdUri = null) }
    }

    /** 技能变更后把内部技能同步到 SD */
    fun syncSkillsToSd() {
        val uri = sdSkillsUri() ?: return
        runCatching {
            val sdRoot = DocumentFile.fromTreeUri(context, uri) ?: return
            val internalDir = getSkillsDir()
            internalDir.listFiles()?.filter { it.isDirectory }?.forEach { skillDir ->
                val sdSkill = sdRoot.findFile(skillDir.name)
                    ?: sdRoot.createDirectory(skillDir.name)
                    ?: return@forEach
                skillDir.listFiles()?.forEach { file ->
                    if (file.isFile) {
                        val sdFile = sdSkill.findFile(file.name)
                            ?: sdSkill.createFile("text/plain", file.name)
                            ?: return@forEach
                        context.contentResolver.openOutputStream(sdFile.uri)?.use { out ->
                            file.inputStream().use { it.copyTo(out) }
                        }
                    }
                }
            }
        }.onFailure { Log.w(TAG, "syncSkillsToSd failed", it) }
    }

    /** 启动时把 SD 上的技能拉取到内部（缺的才拉，不覆盖本地修改） */
    fun syncSkillsFromSd() {
        val uri = sdSkillsUri() ?: return
        runCatching {
            val sdRoot = DocumentFile.fromTreeUri(context, uri) ?: return
            val internalDir = getSkillsDir()
            sdRoot.listFiles()?.filter { it.isDirectory }?.forEach { sdSkill ->
                val internalSkill = File(internalDir, sdSkill.name)
                if (!internalSkill.exists()) {
                    internalSkill.mkdirs()
                    sdSkill.listFiles()?.forEach { file ->
                        if (file.isFile) {
                            context.contentResolver.openInputStream(file.uri)?.use { input ->
                                File(internalSkill, file.name).outputStream().use { input.copyTo(it) }
                            }
                        }
                    }
                }
            }
        }.onFailure { Log.w(TAG, "syncSkillsFromSd failed", it) }
    }

    fun getSkillsDir(): File {
        // 技能目录放在共享存储根目录 AI-Agent/skills 下(用户可见, proot 容器可访问)
        val dir = File(Environment.getExternalStorageDirectory(), "$AI_AGENT_SHARED_DIR/${FileFolders.SKILLS}")
        if (!dir.exists()) dir.mkdirs()
        // 共享存储(FUSE)目录 setReadable 不生效, chmod 放开让其它应用/容器可读
        runCatching {
            Runtime.getRuntime().exec(arrayOf("chmod", "771", dir.absolutePath)).waitFor()
        }
        return dir
    }

    fun listSkills(): List<SkillMetadata> {
        val skillsDir = getSkillsDir()
        return skillsDir.listFiles()
            ?.filter { it.isDirectory }
            ?.mapNotNull { dir ->
                val skillFile = dir.resolve("SKILL.md")
                if (!skillFile.exists()) return@mapNotNull null
                parseSkillFile(skillFile, dir)
            }
            ?.filterNot { isSystemSkill(it.name) }
            ?: emptyList()
    }

    /** 返回全部 skill(含系统内置 skill), 供模型工具使用 */
    fun listAllSkills(): List<SkillMetadata> {
        val skillsDir = getSkillsDir()
        return skillsDir.listFiles()
            ?.filter { it.isDirectory }
            ?.mapNotNull { dir ->
                val skillFile = dir.resolve("SKILL.md")
                if (!skillFile.exists()) return@mapNotNull null
                parseSkillFile(skillFile, dir)
            }
            ?: emptyList()
    }

    fun readSkillBody(skillName: String): String? {
        val skillFile = resolveSkillDir(skillName)?.resolve("SKILL.md") ?: return null
        if (!skillFile.exists()) return null
        return SkillFrontmatterParser.extractBody(skillFile.readText())
    }

    fun readSkillContent(skillName: String): String? {
        val skillFile = resolveSkillDir(skillName)?.resolve("SKILL.md") ?: return null
        if (!skillFile.exists()) return null
        return skillFile.readText()
    }

    fun saveSkill(name: String, content: String): SkillMetadata? {
        // 通过原子写入(staging + rename)落盘，避免直接 mkdirs 失败时
        // writeText 抛出 FileNotFoundException 导致崩溃
        if (!saveSkillFileBytesAtomically(name, mapOf("SKILL.md" to content.toByteArray()))) {
            return null
        }
        val skillDir = resolveSkillDir(name) ?: return null
        return parseSkillFile(skillDir.resolve("SKILL.md"), skillDir)
    }

    suspend fun deleteSkill(name: String): Boolean = withContext(Dispatchers.IO) {
        val skillDir = resolveSkillDir(name) ?: return@withContext false
        val deleted = skillDir.deleteRecursively()
        if (deleted) {
            settingsStore.update { settings ->
                settings.copy(
                    assistants = settings.assistants.map { assistant ->
                        if (assistant.enabledSkills.contains(name)) {
                            assistant.copy(enabledSkills = assistant.enabledSkills - name)
                        } else {
                            assistant
                        }
                    }
                )
            }
        }
        deleted
    }

    /**
     * 清理所有助手 enabledSkills 中已不存在于磁盘的技能名。
     *
     * 当用户在 App 外直接删除 /skills/ 目录下的技能时，不会走 [deleteSkill] 的清理逻辑，
     * 导致 enabledSkills 残留"幽灵"技能名，使扩展入口角标计数偏大。
     */
    suspend fun pruneOrphanedEnabledSkills(): List<SkillMetadata> = withContext(Dispatchers.IO) {
        val skills = listSkills()
        val existing = skills.mapTo(HashSet()) { it.name }
        settingsStore.update { settings ->
            var changed = false
            val newAssistants = settings.assistants.map { assistant ->
                val pruned = assistant.enabledSkills.filterTo(LinkedHashSet()) { it in existing }
                if (pruned.size != assistant.enabledSkills.size) {
                    changed = true
                    assistant.copy(enabledSkills = pruned)
                } else {
                    assistant
                }
            }
            if (changed) settings.copy(assistants = newAssistants) else settings
        }
        skills
    }

    fun getSkillDir(skillName: String): File? = resolveSkillDir(skillName)

    fun saveSkillFile(skillName: String, relativePath: String, content: String): Boolean {
        val skillDir = resolveSkillDir(skillName) ?: return false
        val target = SkillPaths.resolveSkillFile(skillDir, relativePath) ?: return false
        target.parentFile?.mkdirs()
        target.writeText(content)
        return true
    }

    fun saveSkillFilesAtomically(skillName: String, files: Map<String, String>): Boolean {
        return saveSkillFileBytesAtomically(
            skillName = skillName,
            files = files.mapValues { it.value.toByteArray() },
        )
    }

    fun saveSkillFileBytesAtomically(skillName: String, files: Map<String, ByteArray>): Boolean {
        val skillsDir = getSkillsDir()
        val targetDir = resolveSkillDir(skillName) ?: return false
        val stagingDir = createTempSkillDir(skillsDir, skillName, "staging") ?: return false
        var backupDir: File? = null

        try {
            for ((relativePath, content) in files) {
                val target = SkillPaths.resolveSkillFile(stagingDir, relativePath) ?: return false
                target.parentFile?.mkdirs()
                target.writeBytes(content)
            }

            if (!stagingDir.resolve("SKILL.md").exists()) return false

            if (targetDir.exists()) {
                backupDir = createTempSkillDir(skillsDir, skillName, "backup") ?: return false
                if (!targetDir.renameTo(backupDir)) return false
            }

            if (!stagingDir.renameTo(targetDir)) {
                if (backupDir != null && !targetDir.exists()) {
                    backupDir.renameTo(targetDir)
                }
                return false
            }

            backupDir?.deleteRecursively()
            return true
        } catch (e: Exception) {
            Log.w(TAG, "saveSkillFilesAtomically: Failed to save $skillName", e)
            if (backupDir != null && !targetDir.exists()) {
                backupDir.renameTo(targetDir)
            }
            return false
        } finally {
            if (stagingDir.exists()) {
                stagingDir.deleteRecursively()
            }
            if (backupDir?.exists() == true && targetDir.exists()) {
                backupDir.deleteRecursively()
            }
        }
    }

    fun deleteSkillFile(skillName: String, relativePath: String): Boolean {
        val skillDir = resolveSkillDir(skillName) ?: return false
        val target = SkillPaths.resolveSkillFile(skillDir, relativePath) ?: return false
        return target.delete()
    }

    fun resolveSkillFile(skillName: String, relativePath: String): File? {
        val skillDir = resolveSkillDir(skillName) ?: return null
        return SkillPaths.resolveSkillFile(skillDir, relativePath)
    }

    private fun resolveSkillDir(skillName: String): File? {
        return SkillPaths.resolveSkillDir(getSkillsDir(), skillName)
    }

    private fun createTempSkillDir(skillsRoot: File, skillName: String, suffix: String): File? {
        repeat(100) { attempt ->
            val candidate = skillsRoot.resolve(".$skillName.$suffix.$attempt.tmp")
            if (!candidate.exists() && candidate.mkdirs()) {
                return candidate
            }
        }
        return null
    }

    private fun parseSkillFile(skillFile: File, skillDir: File): SkillMetadata? {
        return runCatching {
            val content = skillFile.readText()
            val frontmatter = SkillFrontmatterParser.parse(content)
            val name = frontmatter["name"]?.takeIf { it.isNotBlank() } ?: return null
            val description = frontmatter["description"]?.takeIf { it.isNotBlank() } ?: return null
            SkillMetadata(
                name = name,
                description = description,
                compatibility = frontmatter["compatibility"],
                allowedTools = frontmatter["allowed-tools"]?.split(" ")?.filter { it.isNotBlank() } ?: emptyList(),
                skillDir = skillDir,
            )
        }.getOrElse {
            Log.w(TAG, "parseSkillFile: Failed to parse ${skillFile.absolutePath}", it)
            null
        }
    }
}

data class SkillMetadata(
    val name: String,
    val description: String,
    val compatibility: String? = null,
    val allowedTools: List<String> = emptyList(),
    val skillDir: File,
) {
    val skillFile: File get() = skillDir.resolve("SKILL.md")
}
