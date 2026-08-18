package com.aiagents.data.files

import android.util.Base64
import com.aiagents.common.http.await
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/**
 * 从 GitHub 整目录下载 Skill。
 *
 * 一个 Skill 是目录而非单个 SKILL.md，可能包含 scripts/、references/、assets/ 等子目录与二进制资源。
 * 本类负责递归拉取整个目录、保留相对路径，所有文件统一按字节流下载（不做文本/二进制区分），
 * 由调用方通过 [SkillManager.saveSkillFileBytesAtomically] 落盘。
 */
class SkillDownloader(
    private val client: OkHttpClient,
) {
    /**
     * 下载整个 Skill 目录，返回「相对路径 -> 字节内容」映射。
     * 相对路径以 skillPath 去掉前缀后的路径为准（如 `SKILL.md`、`scripts/validate.py`、`assets/icon.png`）。
     */
    suspend fun downloadSkill(
        owner: String,
        repo: String,
        branch: String,
        skillPath: String,
    ): Map<String, ByteArray> {
        val files = fetchFileList(owner, repo, branch, skillPath)
        val result = LinkedHashMap<String, ByteArray>()
        for (file in files) {
            val relativePath = file.removePrefix("$skillPath/")
            val bytes = downloadFileBytes(owner, repo, branch, file) ?: continue
            result[relativePath] = bytes
        }
        return result
    }

    /**
     * 获取 Skill 目录下所有文件路径（相对仓库根）。
     * 优先 Trees API（一次请求拿全树）；若被截断或失败，回退 Contents API 逐级递归。
     */
    private suspend fun fetchFileList(
        owner: String,
        repo: String,
        branch: String,
        skillPath: String,
    ): List<String> {
        val tree = fetchTree(owner, repo, branch)
        if (tree != null && !tree.truncated) {
            val files = tree.tree
                .filter { it.type == "blob" && it.path.startsWith("$skillPath/") }
                .map { it.path }
            if (files.isNotEmpty()) return files
        }
        // 回退：Contents API 递归
        val result = mutableListOf<String>()
        listContentsRecursively(owner, repo, branch, skillPath, result)
        return result
    }

    private suspend fun fetchTree(owner: String, repo: String, branch: String): GitTreeResponse? {
        val url = "https://api.github.com/repos/$owner/$repo/git/trees/$branch?recursive=1"
        val json = getJson(url) ?: return null
        return runCatching {
            val nodes = mutableListOf<TreeNode>()
            val arr = json.getJSONArray("tree")
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                nodes.add(
                    TreeNode(
                        path = obj.getString("path"),
                        type = obj.getString("type"),
                        sha = obj.getString("sha"),
                    )
                )
            }
            GitTreeResponse(
                tree = nodes,
                truncated = json.optBoolean("truncated", false),
            )
        }.getOrNull()
    }

    private suspend fun listContentsRecursively(
        owner: String,
        repo: String,
        branch: String,
        dirPath: String,
        result: MutableList<String>,
    ) {
        val url = "https://api.github.com/repos/$owner/$repo/contents/$dirPath?ref=$branch"
        val json = getJson(url) ?: return
        val array = runCatching { JSONArray(json.toString()) }.getOrNull() ?: return
        for (i in 0 until array.length()) {
            val item = array.getJSONObject(i)
            when (item.getString("type")) {
                "file" -> result.add(item.getString("path"))
                "dir" -> listContentsRecursively(owner, repo, branch, item.getString("path"), result)
            }
        }
    }

    /** 下载单个文本文件（如 README.md / SKILL.md），用于详情页预览。 */
    suspend fun downloadTextFile(
        owner: String,
        repo: String,
        branch: String,
        filePath: String,
    ): String? {
        val bytes = downloadFileBytes(owner, repo, branch, filePath) ?: return null
        return runCatching { String(bytes, Charsets.UTF_8) }.getOrNull()
    }

    /**
     * 列出仓库 skills/ 目录下的所有技能，并读取每个 SKILL.md 的 name/description 元数据。
     * 用于商店动态展示完整列表。
     */
    suspend fun fetchStoreSkills(
        owner: String,
        repo: String,
        branch: String,
        skillsPath: String,
    ): List<StoreSkill> {
        val dirs = listSkillDirs(owner, repo, branch, skillsPath)
        val result = mutableListOf<StoreSkill>()
        for (dir in dirs) {
            val skillMd = downloadTextFile(owner, repo, branch, "$dir/SKILL.md") ?: continue
            val fm = SkillFrontmatterParser.parse(skillMd)
            val name = fm["name"]?.trim()?.takeIf { it.isNotBlank() } ?: dir.substringAfterLast('/')
            val description = fm["description"]?.trim() ?: ""
            result.add(
                StoreSkill(
                    owner = owner,
                    repo = repo,
                    branch = branch,
                    skillPath = dir,
                    name = name,
                    description = description,
                )
            )
        }
        return result
    }

    /** 列出 skillsPath 下的所有子目录（每个子目录是一个技能）。 */
    private suspend fun listSkillDirs(
        owner: String,
        repo: String,
        branch: String,
        skillsPath: String,
    ): List<String> {
        val url = "https://api.github.com/repos/$owner/$repo/contents/$skillsPath?ref=$branch"
        val json = getJson(url) ?: return emptyList()
        val array = runCatching { JSONArray(json.toString()) }.getOrNull() ?: return emptyList()
        val result = mutableListOf<String>()
        for (i in 0 until array.length()) {
            val item = array.getJSONObject(i)
            if (item.getString("type") == "dir") {
                result.add(item.getString("path"))
            }
        }
        return result
    }

    /** 统一按字节流下载：Contents API 返回 base64，解码为字节数组（文本/二进制通用）。 */
    private suspend fun downloadFileBytes(
        owner: String,
        repo: String,
        branch: String,
        filePath: String,
    ): ByteArray? {
        val url = "https://api.github.com/repos/$owner/$repo/contents/$filePath?ref=$branch"
        val json = getJson(url) ?: return null
        return runCatching {
            val content = json.getString("content")
            Base64.decode(content, Base64.DEFAULT)
        }.getOrNull()
    }

    private suspend fun getJson(url: String): JSONObject? {
        val request = Request.Builder()
            .url(url)
            .get()
            .addHeader("Accept", "application/vnd.github+json")
            .build()
        return try {
            val response = client.newCall(request).await()
            response.use {
                if (it.isSuccessful) {
                    runCatching { JSONObject(it.body?.string().orEmpty()) }.getOrNull()
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            null
        }
    }
}

private data class TreeNode(
    val path: String,
    val type: String,
    val sha: String,
)

private data class GitTreeResponse(
    val tree: List<TreeNode>,
    val truncated: Boolean,
)
