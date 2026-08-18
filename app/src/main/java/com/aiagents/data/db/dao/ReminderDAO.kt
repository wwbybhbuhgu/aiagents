package com.aiagents.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.aiagents.data.db.entity.ReminderEntity

@Dao
interface ReminderDAO {
    @Query("SELECT * FROM reminders ORDER BY triggerAtMillis ASC")
    suspend fun getAll(): List<ReminderEntity>

    @Query("SELECT * FROM reminders WHERE conversationId = :conversationId ORDER BY triggerAtMillis ASC")
    suspend fun getByConversation(conversationId: String): List<ReminderEntity>

    @Query("SELECT * FROM reminders WHERE id = :id")
    suspend fun getById(id: String): ReminderEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(reminder: ReminderEntity)

    @Query("DELETE FROM reminders WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM reminders WHERE conversationId = :conversationId")
    suspend fun deleteByConversation(conversationId: String)
}
