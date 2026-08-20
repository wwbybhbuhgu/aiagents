package com.aiagents.data.ai.tools

import android.content.Context
import com.aiagents.workspace.WorkspaceStorageArea
import com.aiagents.data.files.FileFolders
import com.aiagents.data.repository.WorkspaceRepository
import androidx.core.net.toUri
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import com.aiagents.ai.core.InputSchema
import com.aiagents.ai.core.Tool
import com.aiagents.ai.ui.UIMessagePart
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit

/**
 * 表情包搜索工具 - 移植自 dsh-meme (DeepSeek Harness 表情包插件)
 *
 * 图库 (安装于 filesDir/memes, bind mount 到每个工作区容器 /memes):
 * 1. official-001 (92 张, Astrbot 官方初始表情包)
 * 2. dafeiyu-001 (24 张, DeepSeek 鲸鱼娘 chibi)
 *
 * 两种来源由 AI 通过 source 参数选择:
 * - local (默认): 本地内置图库, 情绪桶随机抽, 完全离线
 * - web: 斗图站远程搜索 (http://101.200.84.220/api/v1/meme/search), 下载到工作区后内联
 *
 * 检索逻辑与 memes.js 一致:
 * - 6 个情绪桶 happy/angry/sad/shy/confused/daily
 * - 模型先选情绪 tag, 系统从该情绪随机抽 limit 张(带 caption),
 *   模型看描述觉得贴就把图内联到回复
 * - query 可选: 没传 tag 时用口语词推断情绪
 *
 * 返回完整可加载 content:// URL (每个工作区都可见),
 * AI 在 markdown 中用 `![alt](content://...)` 或 HTML 卡片 <img src="..."> 直接引用。
 */
fun buildStickerSearchTool(
    context: Context,
    workspaceId: String?,
    workspaceRepository: WorkspaceRepository? = null,
    proxyAddress: String? = null,
): Tool {
    // 惰性加载: 首次调用时读一次索引并缓存
    val indexCache = ConcurrentHashMap<String, List<MemeIndexEntry>>()

    fun loadIndex(): List<MemeIndexEntry> {
        return indexCache.getOrPut("memes") {
            context.assets.open("memes/index.jsonl").bufferedReader().useLines { lines ->
                lines.mapNotNull { line ->
                    if (line.isBlank()) return@mapNotNull null
                    val obj = kotlinx.serialization.json.Json.parseToJsonElement(line).jsonObject
                    MemeIndexEntry(
                        packId = obj["packId"]?.jsonPrimitive?.contentOrNull ?: "official-001",
                        path = obj["path"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null,
                        tag = obj["tag"]?.jsonPrimitive?.contentOrNull ?: "",
                        fileName = obj["file_name"]?.jsonPrimitive?.contentOrNull ?: "",
                        caption = obj["caption"]?.jsonPrimitive?.contentOrNull ?: "",
                        keywords = obj["keywords"]?.jsonPrimitive?.contentOrNull ?: "",
                    )
                }.toList()
            }
        }
    }

    return Tool(
        name = "search_sticker",
        description = """
            Pick a meme/sticker for your reply. Two sources, pick source yourself:
            - source="local" (default): built-in library (116 stickers, fully offline).
              Flow: pick a mood tag → the system randomly draws limit candidates (each with a short caption)
              → look at the captions, if one fits, embed its image inline using the returned contentUrl:
              `![alt](content://com.aiagents.debug.workspacefile/...)` (NO angle brackets).
              If none fit, call again with the same tag (new batch) or switch mood.
            - source="web": search a remote meme battle site (斗图) by a full description query,
              returns a downloaded image whose contentUrl you embed the same way.
              Do NOT pass tag for web mode; instead pass a complete description of the meme you
              want in query (e.g. 小丑/哭泣的狗/摆烂猫/捂脸笑), describing what the meme shows.
              Use this when the user asks for a specific popular meme/梗图 not in the local library,
              or when they say 斗图/接招/来个梗.
            Mood dictionary: happy 开心(卖萌/可爱/喜欢) / angry 生气 / sad 难过(无语/求饶) /
            shy 害羞 / confused 困惑惊讶 / daily 日常(睡觉/上班/早上好). Default mood: happy.
            Includes the DeepSeek whale-girl "大肥鱼" pack. Use a meme when the mood is right; keep replies short.
        """.trimIndent(),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("source", buildJsonObject {
                        put("type", "string")
                        put("description", "local (built-in offline library, default) or web (remote 斗图 meme search by query).")
                    })
                    put("tag", buildJsonObject {
                        put("type", "string")
                        put("description", "Mood for local mode (preferred): happy/angry/sad/shy/confused/daily. Default happy. Ignored in web mode.")
                    })
                    put("query", buildJsonObject {
                        put("type", "string")
                        put("description", "web mode (required): complete description of the meme you want (e.g. 小丑/哭泣的狗/摆烂猫). Local mode (optional): only used to infer mood if tag is not given (e.g. 害羞/生气).")
                    })
                    put("limit", buildJsonObject {
                        put("type", "number")
                        put("description", "How many candidates to draw (1-20, default 5). Local mode only.")
                    })
                },
            )
        },
        needsApproval = { false },
        execute = {
            val params = it.jsonObject
            val source = params["source"]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase() ?: "local"
            val tag = params["tag"]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase() ?: ""
            val query = params["query"]?.jsonPrimitive?.contentOrNull?.trim() ?: ""
            val limit = params["limit"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()?.coerceIn(1, 20) ?: 5

            try {
                if (source == "web") {
                    val result = withContext(Dispatchers.IO) {
                        searchWebMeme(query, context, workspaceId, workspaceRepository, proxyAddress)
                    }
                    listOf(UIMessagePart.Text(result.toString()))
                } else {
                    val result = withContext(Dispatchers.IO) {
                        sampleMood(loadIndex(), tag, query, limit, context, workspaceId)
                    }
                    if (result.mood == null) {
                        listOf(UIMessagePart.Text(
                            buildJsonObject {
                                put("status", "no_results")
                                put("message", "先选一个情绪 tag 再搜。字典: happy 开心 / angry 生气 / sad 难过 / shy 害羞 / confused 困惑惊讶 / daily 日常")
                            }.toString()
                        ))
                    } else if (result.candidates.isEmpty()) {
                        listOf(UIMessagePart.Text(
                            buildJsonObject {
                                put("status", "no_results")
                                put("message", "情绪「${result.mood}」下没有图。换一个: happy/angry/sad/shy/confused/daily")
                            }.toString()
                        ))
                    } else {
                        listOf(UIMessagePart.Text(
                            buildJsonObject {
                                put("status", "success")
                                put("mood", result.mood)
                                put("count", result.candidates.size)
                                put("stickers", result.candidates)
                            }.toString()
                        ))
                    }
                }
            } catch (e: Exception) {
                listOf(UIMessagePart.Text(
                    buildJsonObject {
                        put("status", "error")
                        put("message", "Search failed: ${e.message}")
                    }.toString()
                ))
            }
        },
    )
}

/** 斗图站远程搜索: 调 API 拿图片 URL, 下载到工作区 /workspace/images, 返回可内联 contentUrl */
private suspend fun searchWebMeme(
    query: String,
    context: Context,
    workspaceId: String?,
    workspaceRepository: WorkspaceRepository?,
    proxyAddress: String?,
): JsonObject {
    if (query.isBlank()) {
        return buildJsonObject {
            put("status", "error")
            put("message", "web 模式必须传 query(如 小丑/哭泣/摆烂), 表示要搜的梗词")
        }
    }
    val apiUrl = "http://101.200.84.220/api/v1/meme/search"
    val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .followRedirects(true)
        .apply {
            if (proxyAddress != null) {
                val parts = proxyAddress.split(":")
                if (parts.size == 2) {
                    val host = parts[0]
                    val port = parts[1].toIntOrNull() ?: 7890
                    proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress(host, port)))
                }
            }
        }
        .build()

    val url = runCatching {
        val req = Request.Builder()
            .url("$apiUrl?query=${java.net.URLEncoder.encode(query, "UTF-8")}")
            .header("User-Agent", "AIAgents/2.4.7 (Android)")
            .get()
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return@use null
            resp.body?.string()?.trim()
        }
    }.getOrNull()?.takeIf { it.startsWith("http") } ?: return buildJsonObject {
        put("status", "error")
        put("message", "斗图站搜索失败, 换一个 query 或改用 source=local")
    }

    // 下载图片到工作区 /workspace/images 或 tool_outputs 兜底
    val fileName = "meme_${System.currentTimeMillis()}.jpg"
    val bytes = runCatching {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "AIAgents/2.4.7 (Android)")
            .get()
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return@use null
            val body = resp.body?.bytes() ?: return@use null
            if (body.isEmpty() || body.size > 20 * 1024 * 1024) return@use null
            body
        }
    }.getOrNull() ?: return buildJsonObject {
        put("status", "error")
        put("message", "斗图图片下载失败, 换一个 query 或改用 source=local")
    }

    val (uri, containerPath) = runCatching {
        if (!workspaceId.isNullOrBlank() && workspaceRepository != null) {
            val entry = workspaceRepository.importFile(
                id = workspaceId,
                area = WorkspaceStorageArea.FILES,
                destinationPath = "/workspace/images",
                fileName = fileName,
                inputStream = bytes.inputStream(),
            )
            val rootfs = "/workspace/images/${entry.name}"
            val contentUri = "content://${context.packageName}.workspacefile/$workspaceId$rootfs"
            contentUri to rootfs
        } else {
            val target = File(context.filesDir, FileFolders.TOOL_OUTPUTS).apply { mkdirs() }
            val f = File(target, fileName)
            f.writeBytes(bytes)
            f.toUri().toString() to "/tool_outputs/$fileName"
        }
    }.getOrElse {
        val target = File(context.filesDir, FileFolders.TOOL_OUTPUTS).apply { mkdirs() }
        val f = File(target, fileName)
        f.writeBytes(bytes)
        f.toUri().toString() to "/tool_outputs/$fileName"
    }

    return buildJsonObject {
        put("status", "success")
        put("source", "web")
        put("query", query)
        put("url", url)
        put("contentUrl", uri)
        put("containerPath", containerPath)
        put("markdown", "![$query]($uri)")
    }
}

private data class MemeIndexEntry(
    val packId: String,
    val path: String,
    val tag: String,
    val fileName: String,
    val caption: String,
    val keywords: String,
)

/** 6 个情绪桶: 情绪 -> 细分 tag (移植自 dsh-meme memes.js) */
private val MOODS: Map<String, List<String>> = mapOf(
    "happy" to listOf("happy", "like", "meow", "givemoney", "color"),
    "angry" to listOf("angry", "fool", "baka"),
    "sad" to listOf("sad", "sigh"),
    "shy" to listOf("shy"),
    "confused" to listOf("confused", "surprised", "see"),
    "daily" to listOf("daily", "sleep", "morning", "work", "cpu", "reply"),
)

/** 情绪桶 -> 口语词 (移植自 dsh-meme memes.js) */
private val MOOD_WORDS: Map<String, List<String>> = mapOf(
    "happy" to listOf("开心", "高兴", "兴奋", "喜欢", "卖萌", "可爱", "比心", "哈哈", "欢迎", "得意", "好耶", "满意"),
    "angry" to listOf("生气", "愤怒", "暴躁", "笨蛋", "傻瓜", "嫌弃"),
    "sad" to listOf("难过", "哭", "委屈", "叹气", "无语", "求饶", "怂", "晕"),
    "shy" to listOf("害羞", "腼腆", "脸红", "花痴"),
    "confused" to listOf("困惑", "疑惑", "惊讶", "问号", "懵", "惊吓", "震惊"),
    "daily" to listOf("困", "睡觉", "早上好", "打招呼", "你好", "上班", "下班", "摸鱼", "工作", "熬夜", "吃饭", "干饭", "饿", "日常"),
)

/** tag 优先; 否则用 query 里的口语词推断情绪 (移植自 dsh-meme resolveMood) */
private fun resolveMood(tag: String, query: String): String? {
    val t = tag.trim().lowercase()
    if (t.isNotEmpty()) {
        if (MOODS.containsKey(t)) return t
        for ((mood, fine) in MOODS) {
            if (fine.contains(t)) return mood
        }
    }
    val q = query.trim().lowercase()
    if (q.isEmpty()) return null
    if (MOODS.containsKey(q)) return q
    for ((mood, words) in MOOD_WORDS) {
        if (words.any { q.contains(it.lowercase()) }) return mood
    }
    return null
}

/** 按情绪取池, 随机抽 n 张给模型看 caption (移植自 dsh-meme sampleMood + list) */
private fun sampleMood(
    index: List<MemeIndexEntry>,
    tag: String,
    query: String,
    n: Int,
    context: Context,
    workspaceId: String?,
): SampleResult {
    val mood = resolveMood(tag, query)
    if (mood == null) return SampleResult(mood = null, candidates = JsonArray(emptyList()))
    val fineTags = MOODS[mood] ?: emptyList()
    val memes = index.filter { it.tag in fineTags }
    val picked = memes.shuffled().take(n)
    val candidates = buildJsonArray {
        picked.forEach { entry ->
            // 容器内绝对路径: /memes/<packId>/<path> (bind mount, 每个工作区可见)
            val containerPath = "/memes/${entry.packId}/${entry.path}"
            // 完整可加载 URL: content://<app>.workspacefile/<workspaceId>/memes/<packId>/<path>
            val contentUrl = if (!workspaceId.isNullOrBlank()) {
                "content://${context.packageName}.workspacefile/$workspaceId$containerPath"
            } else {
                containerPath
            }
            add(buildJsonObject {
                put("path", entry.path)
                put("tag", entry.tag)
                put("caption", entry.caption)
                put("containerPath", containerPath)
                put("contentUrl", contentUrl)
                put("source", entry.packId)
                put("markdown", "![${entry.caption}]($contentUrl)")
            })
        }
    }
    return SampleResult(mood = mood, candidates = candidates)
}

private data class SampleResult(
    val mood: String?,
    val candidates: JsonArray,
)