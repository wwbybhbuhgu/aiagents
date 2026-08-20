package com.aiagents.data.ai.tools

import android.content.Context
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import com.aiagents.ai.core.InputSchema
import com.aiagents.ai.core.Tool
import com.aiagents.ai.ui.UIMessagePart
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers

/**
 * 表情包搜索工具 - 移植自 dsh-meme (DeepSeek Harness 表情包插件)
 *
 * 图库 (安装于 filesDir/memes, bind mount 到每个工作区容器 /memes):
 * 1. official-001 (92 张, Astrbot 官方初始表情包)
 * 2. dafeiyu-001 (24 张, DeepSeek 鲸鱼娘 chibi)
 *
 * 检索逻辑与 memes.js 一致:
 * - 6 个情绪桶 happy/angry/sad/shy/confused/daily
 * - 模型先选情绪 tag, 系统从该情绪随机抽 limit 张(带 caption),
 *   模型看描述觉得贴就把图内联到回复
 * - query 可选: 没传 tag 时用口语词推断情绪
 *
 * 返回容器内绝对路径 /memes/<packId>/<path> (每个工作区都可见),
 * AI 在 markdown 中用 `![alt](/memes/...)` 或 HTML 卡片 <img src="/memes/..."> 直接引用。
 */
fun buildStickerSearchTool(
    context: Context,
    workspaceId: String?,
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
            Pick a meme/sticker from the built-in library (116 stickers, fully offline).
            Flow: pick a mood tag based on the conversation → the system randomly draws limit candidates
            (each with a short caption) → look at the captions, if one fits, embed its image inline in your
            reply using the returned contentUrl: `![alt](content://com.aiagents.debug.workspacefile/...)`
            (NO angle brackets). The contentUrl is a complete image URL, usable directly in markdown.
            If none fit, call again with the same tag (a new batch is drawn) or switch mood / reply with text.
            Mood dictionary: happy 开心(卖萌/可爱/喜欢) / angry 生气 / sad 难过(无语/求饶) /
            shy 害羞 / confused 困惑惊讶 / daily 日常(睡觉/上班/早上好). Default mood: happy.
            Includes the DeepSeek whale-girl "大肥鱼" pack. Use it when the mood is right; keep replies short.
        """.trimIndent(),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("tag", buildJsonObject {
                        put("type", "string")
                        put("description", "Mood (preferred): happy/angry/sad/shy/confused/daily. Default happy.")
                    })
                    put("query", buildJsonObject {
                        put("type", "string")
                        put("description", "Optional. Only used to infer mood if tag is not given (e.g. 害羞/生气). Keep it short.")
                    })
                    put("limit", buildJsonObject {
                        put("type", "number")
                        put("description", "How many candidates to draw (1-20, default 5).")
                    })
                },
            )
        },
        needsApproval = { false },
        execute = {
            val params = it.jsonObject
            val tag = params["tag"]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase() ?: ""
            val query = params["query"]?.jsonPrimitive?.contentOrNull?.trim() ?: ""
            val limit = params["limit"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()?.coerceIn(1, 20) ?: 5

            try {
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