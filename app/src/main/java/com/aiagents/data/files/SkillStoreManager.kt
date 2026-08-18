package com.aiagents.data.files

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 技能商店列表管理：每日首次打开时从 GitHub 完整拉取并缓存到本地，
 * 支持英文分词 + 标题/正文模糊搜索。
 */
class SkillStoreManager(
    private val context: Context,
    private val skillDownloader: SkillDownloader,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val cacheFile = File(context.filesDir, "skill_store_cache.json")

    private val defaultSource = StoreSource(
        owner = "vercel-labs",
        repo = "agent-skills",
        branch = "main",
        skillsPath = "skills",
    )

    /**
     * 获取商店完整技能列表。
     * 每日首次调用（或 [forceRefresh]）时从 GitHub 拉取并更新缓存；网络失败时回退缓存/内置列表。
     */
    suspend fun getSkills(forceRefresh: Boolean = false): List<StoreSkill> {
        val cached = loadCache()
        val stale = cached == null || !isSameDay(cached.fetchedAt)
        if (forceRefresh || stale) {
            val fresh = skillDownloader.fetchStoreSkills(
                owner = defaultSource.owner,
                repo = defaultSource.repo,
                branch = defaultSource.branch,
                skillsPath = defaultSource.skillsPath,
            )
            if (fresh.isNotEmpty()) {
                saveCache(fresh)
                return fresh
            }
        }
        return cached?.skills?.takeIf { it.isNotEmpty() } ?: SkillStore.skills
    }

    /**
     * 英文分词 + 模糊搜索：把查询与技能名/描述都切成英文单词，
     * 查询的每个词需在标题或正文中命中（子串/前缀匹配）。
     */
    fun search(query: String, skills: List<StoreSkill>): List<StoreSkill> {
        val queryTokens = tokenize(query)
        if (queryTokens.isEmpty()) return skills
        return skills.filter { skill ->
            val titleTokens = tokenize(skill.name)
            val bodyTokens = tokenize(skill.description)
            queryTokens.all { qt ->
                titleTokens.any { matches(it, qt) } || bodyTokens.any { matches(it, qt) }
            }
        }
    }

    private fun matches(contentToken: String, queryToken: String): Boolean {
        return contentToken.contains(queryToken) || queryToken.contains(contentToken)
    }

    /** 英文分词：按非字母数字切分并转小写。 */
    private fun tokenize(text: String): List<String> {
        return text.lowercase()
            .split(Regex("[^a-z0-9]+"))
            .filter { it.isNotBlank() }
    }

    private fun loadCache(): StoreSkillCache? {
        if (!cacheFile.exists()) return null
        return runCatching { json.decodeFromString<StoreSkillCache>(cacheFile.readText()) }.getOrNull()
    }

    private fun saveCache(skills: List<StoreSkill>) {
        runCatching {
            cacheFile.writeText(
                json.encodeToString(
                    StoreSkillCache.serializer(),
                    StoreSkillCache(fetchedAt = System.currentTimeMillis(), skills = skills),
                )
            )
        }
    }

    private fun isSameDay(fetchedAt: Long): Boolean {
        val fetchedDay = Instant.ofEpochMilli(fetchedAt)
            .atZone(ZoneId.systemDefault()).toLocalDate().toEpochDay()
        return LocalDate.now().toEpochDay() == fetchedDay
    }
}

@Serializable
private data class StoreSkillCache(
    val fetchedAt: Long,
    val skills: List<StoreSkill>,
)

private data class StoreSource(
    val owner: String,
    val repo: String,
    val branch: String,
    val skillsPath: String,
)
