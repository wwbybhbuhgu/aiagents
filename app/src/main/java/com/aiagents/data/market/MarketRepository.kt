package com.aiagents.data.market

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URL

/**
 * Extension marketplace repository.
 *
 * Uses GitHub as backend:
 * - manifest.json in repo root lists all entries
 * - Individual entry details fetched from entries/{id}.json
 * - Binary assets (scripts, toolpkg) downloaded from GitHub releases
 *
 * Store repo will be configured via Settings.
 */
class MarketRepository(private val context: Context? = null) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    // Default store repo - can be overridden in settings
    var storeOwner: String = "wwbybhbuhgu"
    var storeRepo: String = "aiagents-market"

    private val baseUrl: String
        get() = "https://raw.githubusercontent.com/$storeOwner/$storeRepo/main"

    private val apiBaseUrl: String
        get() = "https://api.github.com/repos/$storeOwner/$storeRepo"

    /**
     * Fetch the marketplace manifest (list of all entries).
     */
    suspend fun fetchManifest(): Result<MarketManifest> = withContext(Dispatchers.IO) {
        try {
            val url = URL("$baseUrl/manifest.json")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 15000
            conn.readTimeout = 15000
            conn.setRequestProperty("Accept", "application/json")

            if (conn.responseCode != 200) {
                return@withContext Result.failure(Exception("HTTP ${conn.responseCode}"))
            }

            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val manifest = json.decodeFromString<MarketManifest>(body)
            Result.success(manifest)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Fetch details for a specific entry.
     */
    suspend fun fetchEntry(entryId: String): Result<MarketEntry> = withContext(Dispatchers.IO) {
        try {
            val url = URL("$baseUrl/entries/$entryId.json")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 15000
            conn.readTimeout = 15000

            if (conn.responseCode != 200) {
                return@withContext Result.failure(Exception("HTTP ${conn.responseCode}"))
            }

            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val entry = json.decodeFromString<MarketEntry>(body)
            Result.success(entry)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Download a file from a URL to the workspace.
     */
    suspend fun downloadAsset(
        downloadUrl: String,
        fileName: String,
        targetDir: java.io.File,
    ): Result<java.io.File> = withContext(Dispatchers.IO) {
        try {
            val url = URL(downloadUrl)
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 30000
            conn.readTimeout = 60000
            conn.setRequestProperty("User-Agent", "AiAgents/1.0")

            if (conn.responseCode != 200) {
                return@withContext Result.failure(Exception("HTTP ${conn.responseCode}"))
            }

            targetDir.mkdirs()
            val targetFile = java.io.File(targetDir, fileName)

            conn.inputStream.use { input ->
                targetFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }

            Result.success(targetFile)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Search entries by query string.
     */
    fun searchEntries(entries: List<MarketEntry>, query: String): List<MarketEntry> {
        if (query.isBlank()) return entries
        val q = query.lowercase()
        return entries.filter { entry ->
            entry.title.lowercase().contains(q) ||
            entry.description.lowercase().contains(q) ||
            entry.tags.any { it.lowercase().contains(q) } ||
            entry.author.login.lowercase().contains(q)
        }
    }

    /**
     * Filter entries by type.
     */
    fun filterByType(entries: List<MarketEntry>, type: MarketType?): List<MarketEntry> {
        if (type == null) return entries
        return entries.filter { it.type == type.wireValue }
    }

    /**
     * Filter entries by category.
     */
    fun filterByCategory(entries: List<MarketEntry>, categoryId: String?): List<MarketEntry> {
        if (categoryId == null) return entries
        return entries.filter { it.categoryId == categoryId }
    }

    /**
     * Sort entries.
     */
    fun sortEntries(entries: List<MarketEntry>, sort: MarketSort): List<MarketEntry> {
        return when (sort) {
            MarketSort.UPDATED -> entries.sortedByDescending { it.updatedAt ?: "" }
            MarketSort.DOWNLOADS -> entries.sortedByDescending { it.downloads }
            MarketSort.LIKES -> entries.sortedByDescending { it.likes }
        }
    }
}
