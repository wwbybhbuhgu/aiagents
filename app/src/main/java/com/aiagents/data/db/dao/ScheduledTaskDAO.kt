package com.aiagents.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.aiagents.data.db.entity.ScheduledTaskEntity

@Dao
interface ScheduledTaskDAO {
    @Query("SELECT * FROM scheduled_tasks ORDER BY createdAt DESC")
    suspend fun getAll(): List<ScheduledTaskEntity>

    @Query("SELECT * FROM scheduled_tasks WHERE conversationId = :conversationId ORDER BY createdAt DESC")
    suspend fun getByConversation(conversationId: String): List<ScheduledTaskEntity>

    @Query("SELECT * FROM scheduled_tasks WHERE enabled = 1 AND nextRunAt <= :nowMillis")
    suspend fun getDue(nowMillis: Long): List<ScheduledTaskEntity>

    @Query("SELECT * FROM scheduled_tasks WHERE id = :id")
    suspend fun getById(id: String): ScheduledTaskEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(task: ScheduledTaskEntity)

    @Query("DELETE FROM scheduled_tasks WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM scheduled_tasks WHERE conversationId = :conversationId")
    suspend fun deleteByConversation(conversationId: String)
}
