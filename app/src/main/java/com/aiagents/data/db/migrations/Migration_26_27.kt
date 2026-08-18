package com.aiagents.data.db.migrations

import androidx.room.migration.AutoMigrationSpec
import androidx.sqlite.db.SupportSQLiteDatabase

class Migration_26_27 : AutoMigrationSpec {
    override fun onPostMigrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE scheduled_tasks ADD COLUMN lastOutputFile TEXT")
    }
}
