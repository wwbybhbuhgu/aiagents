package com.aiagents.data.repository

import android.content.Context
import com.aiagents.AppScope
import com.aiagents.data.db.dao.MemoryDAO
import com.aiagents.data.files.MemoryManager
import com.aiagents.data.model.AssistantMemory
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch

/**
 * 文件化记忆仓库: 记忆以目录 + MEMORY.md 形式保存在共享存储
 * AI-Agent/memories/<scope>/<entry>/ 下, 可由 AI 用 workspace 文件工具/脚本直接读写。
 * 保留与旧实现一致的 Flow API, 供 UI 与工具调用。
 */
class MemoryRepository(
    private val context: Context,
    private val memoryManager: MemoryManager,
    private val memoryDAO: MemoryDAO,
    private val appScope: AppScope,
) {
    companion object {
        const val GLOBAL_MEMORY_ID = "__global__"
    }

    init {
        // 一次性把旧的数据库记忆迁移为文件(以根目录 .db-migrated 标记)
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { migrateFromDatabase() }
        }
    }

    fun getMemoriesOfAssistantFlow(assistantId: String): Flow<List<AssistantMemory>> =
        memoryPollingFlow { memoryManager.listMemories(assistantId) }

    suspend fun getMemoriesOfAssistant(assistantId: String): List<AssistantMemory> =
        memoryManager.listMemories(assistantId)

    fun getGlobalMemoriesFlow(): Flow<List<AssistantMemory>> =
        memoryPollingFlow { memoryManager.listMemories(GLOBAL_MEMORY_ID) }

    suspend fun getGlobalMemories(): List<AssistantMemory> =
        memoryManager.listMemories(GLOBAL_MEMORY_ID)

    suspend fun getMemory(assistantId: String, id: String): AssistantMemory? =
        memoryManager.getMemory(assistantId, id)

    suspend fun deleteMemoriesOfAssistant(assistantId: String) {
        memoryManager.deleteMemoriesOfAssistant(assistantId)
    }

    suspend fun updateContent(
        assistantId: String,
        id: String,
        content: String,
        description: String? = null,
    ): AssistantMemory = memoryManager.updateMemory(assistantId, id, content, description)

    suspend fun addMemory(
        assistantId: String,
        name: String,
        content: String,
        description: String? = null,
    ): AssistantMemory = memoryManager.addMemory(assistantId, name, content, description)

    suspend fun deleteMemory(assistantId: String, id: String) {
        memoryManager.deleteMemory(assistantId, id)
    }

    /** 轮询式 Flow: 记忆是磁盘文件, 可能被 AI 脚本/外部直接修改, 周期重读以保持同步 */
    private fun memoryPollingFlow(read: suspend () -> List<AssistantMemory>): Flow<List<AssistantMemory>> =
        flow {
            var last: List<AssistantMemory>? = null
            while (true) {
                val current = read()
                if (current != last) {
                    last = current
                    emit(current)
                }
                delay(2_000)
            }
        }.flowOn(Dispatchers.IO)

    private suspend fun migrateFromDatabase() {
        val root = memoryManager.getMemoriesDir()
        val marker = File(root, ".db-migrated")
        if (marker.exists()) return
        val entities = memoryDAO.getAllMemories()
        if (entities.isNotEmpty()) {
            entities.groupBy { it.assistantId }.forEach { (assistantId, rows) ->
                rows.forEach { row ->
                    val id = "memory-${row.id}"
                    if (memoryManager.getMemory(assistantId, id) == null) {
                        memoryManager.addMemory(assistantId, id, row.content, null)
                    }
                }
            }
        }
        marker.writeText("done")
    }
}
