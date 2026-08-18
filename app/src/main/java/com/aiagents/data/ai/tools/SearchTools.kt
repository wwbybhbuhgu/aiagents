package com.aiagents.data.ai.tools

import android.content.Context
import java.io.File
import java.time.LocalDate
import java.util.concurrent.TimeUnit
import kotlin.uuid.Uuid
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import com.aiagents.ai.core.InputSchema
import com.aiagents.ai.core.Tool
import com.aiagents.ai.ui.UIMessagePart
import com.aiagents.data.datastore.Settings
import com.aiagents.data.files.FileFolders
import com.aiagents.utils.JsonInstantPretty
import com.aiagents.utils.toLocalString
import com.aiagents.search.SearchService
import com.aiagents.search.SearchServiceOptions
import okhttp3.OkHttpClient
import okhttp3.Request

fun createSearchTools(
    context: Context,
    settings: Settings,
    compress: suspend (toolParams: JsonObject, content: String) -> String = { _, content -> content },
): Set<Tool> {
    // 用户已配置的搜索引擎列表: AI 只能从中选择
    val availableEngines = settings.searchServices.map { it.displayName }
    val defaultEngine = settings.searchServices.getOrNull(settings.searchServiceSelected)?.displayName
    return buildSet {
        add(
            Tool(
                name = "search_web",
                description = """
                    Search the web for up-to-date or specific information.
                    Use this when the user asks for the latest news, current facts, or needs verification.
                    Generate focused keywords and run multiple searches if needed.
                    Today is ${LocalDate.now().toLocalString(true)}.

                    Available search engines (choose from these names only): ${availableEngines.joinToString(", ")}
                    Use the "engine" parameter to pick one; omit it to use the default (${defaultEngine ?: availableEngines.firstOrNull() ?: "N/A"}).
                    Use the optional "resultSize" parameter to control how many results to return (defaults to the configured value).

                    Response format:
                    - items[].id (short id), title, url, text
                    - images[]: image urls related to the query (may be empty)

                    Citations:
                    - After using results, add `[citation,domain](id)` after the sentence.
                    - Multiple citations are allowed.
                    - If no results are cited, omit citations.

                    Images:
                    - When images help the user understand the answer, embed relevant ones using Markdown: `![](url)`.
                    - Embed 2 to 4 images, and only use urls from `images[]` (never fabricate or alter urls).
                    - Usually place the images at the very beginning of your reply; skip them entirely if none are relevant.
                    - Each image is also downloaded and saved locally; the `downloaded_images[]` array maps original urls to local paths like `/tool_outputs/search_img_xxx.jpg`.
                    - Use `image_analysis` or an OCR tool on a local path (e.g. `/tool_outputs/search_img_xxx.jpg`) when you need to actually read the image content.

                    Example:
                    The capital of France is Paris. [citation,example.com](abc123)
                    The population is about 2.1 million. [citation,example.com](abc123) [citation,example2.com](def456)
                    """.trimIndent(),
                parameters = {
                    val options = settings.searchServices.getOrElse(
                        index = settings.searchServiceSelected,
                        defaultValue = { SearchServiceOptions.DEFAULT })
                    val service = SearchService.getService(options)
                    // AI 可传 engine 指定搜索引擎, 但只能从用户已配置的引擎中选
                    val base = service.parameters(options) as? InputSchema.Obj
                    val properties = buildJsonObject {
                        base?.properties?.forEach { (k, v) -> put(k, v) }
                        put("engine", buildJsonObject {
                            put("type", "string")
                            put("description", "Search engine to use. Choose from: ${availableEngines.joinToString(", ")}. Optional, defaults to ${defaultEngine ?: availableEngines.firstOrNull() ?: "default"}.")
                        })
                        put("resultSize", buildJsonObject {
                            put("type", "integer")
                            put("description", "Number of search results to return. Optional; defaults to the configured value (${settings.searchCommonOptions.resultSize}).")
                        })
                    }
                    InputSchema.Obj(properties = properties, required = base?.required)
                },
                execute = {
                    val options = resolveSearchOptions(settings, it.jsonObject)
                    val service = SearchService.getService(options)
                    // AI 可自由指定结果个数; 未指定时回退到设置里的默认值
                    val aiResultSize = it.jsonObject["resultSize"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
                    val effectiveCommonOptions = if (aiResultSize != null && aiResultSize > 0) {
                        settings.searchCommonOptions.copy(resultSize = aiResultSize)
                    } else {
                        settings.searchCommonOptions
                    }
                    val result = service.search(
                        params = it.jsonObject,
                        commonOptions = effectiveCommonOptions,
                        serviceOptions = options,
                    )
                    val results =
                        JsonInstantPretty.encodeToJsonElement(result.getOrThrow()).jsonObject.let { json ->
                            val map = json.toMutableMap()
                            map["items"] =
                                JsonArray(map["items"]!!.jsonArray.mapIndexed { index, item ->
                                    JsonObject(item.jsonObject.toMutableMap().apply {
                                        put("id", JsonPrimitive(Uuid.random().toString().take(6)))
                                        put("index", JsonPrimitive(index + 1))
                                    })
                                })
                            val imageUrls = (map["images"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()
                            if (imageUrls.isNotEmpty()) {
                                map["downloaded_images"] = JsonArray(downloadSearchImages(context, imageUrls))
                            }
                            JsonObject(map)
                        }
                    listOf(UIMessagePart.Text(results.toString()))
                }
            )
        )

        val options = settings.searchServices.getOrElse(
            index = settings.searchServiceSelected,
            defaultValue = { SearchServiceOptions.DEFAULT })
        val service = SearchService.getService(options)
        if (service.scrapingParameters(options) != null) {
            add(
                Tool(
                    name = "scrape_web",
                    description = """
                        Scrape a URL for detailed page content.
                        Use this when the user requests content from a specific page or when search snippets are insufficient.
                        Avoid using it for common questions unless the user asks.
                        The scraped content is summarized by the compression model before being returned.
                        """.trimIndent(),
                    parameters = {
                        val options = settings.searchServices.getOrElse(
                            index = settings.searchServiceSelected,
                            defaultValue = { SearchServiceOptions.DEFAULT })
                        val service = SearchService.getService(options)
                        service.scrapingParameters(options)
                    },
                    execute = {
                        val options = resolveSearchOptions(settings, it.jsonObject)
                        val service = SearchService.getService(options)
                        val result = service.scrape(
                            params = it.jsonObject,
                            commonOptions = settings.searchCommonOptions,
                            serviceOptions = options,
                        )
                        val payload = JsonInstantPretty.encodeToJsonElement(result.getOrThrow()).jsonObject
                        val raw = payload.toString()
                        val processed = runCatching { compress(it.jsonObject, raw) }.getOrElse { raw }
                        listOf(UIMessagePart.Text(processed))
                    }
                ))
        }
    }
}

/** 按 engine 参数名从用户已配置的引擎中选择; 未指定或不存在时回退到默认引擎 */
private fun resolveSearchOptions(settings: Settings, params: JsonObject): SearchServiceOptions {
    val engine = params["engine"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
    if (engine.isNotEmpty()) {
        settings.searchServices.firstOrNull { it.displayName == engine }?.let { return it }
    }
    return settings.searchServices.getOrElse(
        index = settings.searchServiceSelected,
        defaultValue = { SearchServiceOptions.DEFAULT })
}

private const val SEARCH_IMAGE_MAX_BYTES = 20 * 1024 * 1024
private const val SEARCH_IMAGE_MAX_COUNT = 20

/** 下载搜索到的网络图片到工具输出目录(filesDir/tool_outputs), 返回 [{url, path, size}] 列表 */
private fun downloadSearchImages(context: Context, imageUrls: List<String>): List<JsonObject> {
    val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()
    val dir = File(context.filesDir, FileFolders.TOOL_OUTPUTS).apply { mkdirs() }
    val timestamp = System.currentTimeMillis()
    val results = mutableListOf<JsonObject>()
    imageUrls.take(SEARCH_IMAGE_MAX_COUNT).forEachIndexed { index, url ->
        if (!url.startsWith("http://") && !url.startsWith("https://")) return@forEachIndexed
        val fileName = "search_img_${timestamp}_${index + 1}.jpg"
        val target = File(dir, fileName)
        runCatching {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "AIAgents/2.4.5 (Android)")
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use
                val bytes = response.body?.bytes() ?: return@use
                if (bytes.isEmpty() || bytes.size > SEARCH_IMAGE_MAX_BYTES) return@use
                target.writeBytes(bytes)
                results += buildJsonObject {
                    put("url", url)
                    put("path", "/tool_outputs/$fileName")
                    put("size", bytes.size)
                }
            }
        }
    }
    return results
}
