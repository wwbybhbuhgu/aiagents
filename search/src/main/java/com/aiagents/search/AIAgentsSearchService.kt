package com.aiagents.search

import android.util.Log
import androidx.compose.runtime.Composable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import com.aiagents.ai.core.InputSchema
import com.aiagents.search.SearchResult.SearchResultItem
import com.aiagents.search.SearchService.Companion.httpClient
import com.aiagents.search.SearchService.Companion.json
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

private const val TAG = "AIAgentsSearchService"

object AIAgentsSearchService : SearchService<SearchServiceOptions.AIAgentsOptions> {
    override val name: String = "AIAgents"

    @Composable
    override fun Description() {
    }

    override fun parameters(options: SearchServiceOptions.AIAgentsOptions): InputSchema? =
        InputSchema.Obj(
            properties = buildJsonObject {
                put("query", buildJsonObject {
                    put("type", "string")
                    put("description", "search keyword")
                })
            },
            required = listOf("query")
        )

    override fun scrapingParameters(options: SearchServiceOptions.AIAgentsOptions): InputSchema? =
        null

    override suspend fun search(
        params: JsonObject,
        commonOptions: SearchCommonOptions,
        serviceOptions: SearchServiceOptions.AIAgentsOptions
    ): Result<SearchResult> = withContext(Dispatchers.IO) {
        runCatching {
            val query = params["query"]?.jsonPrimitive?.content ?: error("query is required")
            val body = buildJsonObject {
                put("q", JsonPrimitive(query))
                put("depth", JsonPrimitive(serviceOptions.depth))
                put("outputType", JsonPrimitive("sourcedAnswer"))
                put("includeImages", JsonPrimitive("false"))
            }

            val request = Request.Builder()
                .url("https://api.rikka-ai.com/v1/search")
                .post(body.toString().toRequestBody())
                .addHeader("Authorization", "Bearer ${serviceOptions.apiKey}")
                .addHeader("Content-Type", "application/json")
                .build()

            Log.i(TAG, "search: $query")

            val response = httpClient.newCall(request).await()
            if (response.isSuccessful) {
                val responseBody = response.body.string().let {
                    json.decodeFromString<AIAgentsSearchResponse>(it)
                }

                return@withContext Result.success(
                    SearchResult(
                        answer = responseBody.answer,
                        items = responseBody.sources.take(commonOptions.resultSize).map {
                            SearchResultItem(
                                title = it.name,
                                url = it.url,
                                text = it.snippet
                            )
                        }
                    )
                )
            } else {
                error("response failed #${response.code}: ${response.body?.string()}")
            }
        }
    }

    override suspend fun scrape(
        params: JsonObject,
        commonOptions: SearchCommonOptions,
        serviceOptions: SearchServiceOptions.AIAgentsOptions
    ): Result<ScrapedResult> {
        error("AIAgents does not support scraping")
    }

    @Serializable
    data class AIAgentsSearchResponse(
        val answer: String,
        val sources: List<Source>
    )

    @Serializable
    data class Source(
        val name: String,
        val url: String,
        val snippet: String
    )
}
