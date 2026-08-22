package com.aiagents.data.market

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNames

@Serializable
data class MarketEntry(
    val id: String = "",
    val type: String = "",              // "script", "toolpkg", "skill", "mcp"
    val title: String = "",
    val description: String = "",
    val detail: String = "",            // Markdown detail
    val author: MarketAuthor = MarketAuthor(),
    val categoryId: String = "",
    val version: String = "",
    val downloads: Int = 0,
    val likes: Int = 0,
    val featured: Boolean = false,
    val createdAt: String? = null,
    val updatedAt: String? = null,
    val sourceUrl: String? = null,      // GitHub repo URL
    val downloadUrl: String? = null,    // Direct download URL (GitHub release asset)
    val iconUrl: String? = null,        // Icon URL
    val tags: List<String> = emptyList(),
)

@Serializable
data class MarketAuthor(
    val id: String = "",
    val login: String = "",             // GitHub username
    val avatarUrl: String? = null,
)

@Serializable
data class MarketComment(
    val id: String = "",
    val entryId: String = "",
    val author: MarketAuthor = MarketAuthor(),
    val body: String = "",
    val createdAt: String = "",
)

@Serializable
data class MarketManifest(
    val version: Int = 1,
    val categories: List<MarketCategory> = emptyList(),
    val entries: List<MarketEntry> = emptyList(),
)

@Serializable
data class MarketCategory(
    val id: String = "",
    val name: String = "",
    val icon: String = "",              // Material icon name
)

enum class MarketType(val wireValue: String, val displayName: String) {
    SCRIPT("script", "Scripts"),
    TOOLPKG("toolpkg", "ToolPkg"),
    SKILL("skill", "Skills"),
    MCP("mcp", "MCP Servers"),
}

enum class MarketSort(val wireValue: String) {
    UPDATED("updated"),
    DOWNLOADS("downloads"),
    LIKES("likes"),
}
