package com.aiagents.data.market

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL

/**
 * Extension marketplace repository.
 *
 * Uses GitHub as backend:
 * - manifest.json in repo root lists all entries
 * - Individual entry details fetched from entries/{id}.json
 * - Binary assets (scripts, toolpkg) downloaded from GitHub releases
 */
class MarketRepository {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    var storeOwner: String = "wwbybhbuhgu"
    var storeRepo: String = "aiagents-market"

    /** Proxy address (host:port), null = direct */
    var proxyAddress: String? = null

    private val baseUrl: String
        get() = "https://raw.githubusercontent.com/$storeOwner/$storeRepo/main"

    private fun openConnection(url: URL): HttpURLConnection {
        val conn = if (proxyAddress != null) {
            val parts = proxyAddress!!.split(":")
            val host = parts[0]
            val port = parts.getOrNull(1)?.toIntOrNull() ?: 7890
            url.openConnection(Proxy(Proxy.Type.HTTP, InetSocketAddress(host, port)))
        } else {
            url.openConnection()
        } as HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 15000
        return conn
    }

    suspend fun fetchManifest(): Result<MarketManifest> = withContext(Dispatchers.IO) {
        try {
            val conn = openConnection(URL("$baseUrl/manifest.json"))
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

    suspend fun fetchEntry(entryId: String): Result<MarketEntry> = withContext(Dispatchers.IO) {
        try {
            val conn = openConnection(URL("$baseUrl/entries/$entryId.json"))

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

    suspend fun downloadAsset(
        downloadUrl: String,
        fileName: String,
        targetDir: java.io.File,
    ): Result<java.io.File> = withContext(Dispatchers.IO) {
        try {
            val conn = openConnection(URL(downloadUrl))
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

    fun filterByType(entries: List<MarketEntry>, type: MarketType?): List<MarketEntry> {
        if (type == null) return entries
        return entries.filter { it.type == type.wireValue }
    }

    fun filterByCategory(entries: List<MarketEntry>, categoryId: String?): List<MarketEntry> {
        if (categoryId == null) return entries
        return entries.filter { it.categoryId == categoryId }
    }

    fun sortEntries(entries: List<MarketEntry>, sort: MarketSort): List<MarketEntry> {
        return when (sort) {
            MarketSort.UPDATED -> entries.sortedByDescending { it.updatedAt ?: "" }
            MarketSort.DOWNLOADS -> entries.sortedByDescending { it.downloads }
            MarketSort.LIKES -> entries.sortedByDescending { it.likes }
        }
    }
}
