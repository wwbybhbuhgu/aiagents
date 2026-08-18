package com.aiagents.data.db.migrations

import androidx.room.DeleteColumn
import androidx.room.migration.AutoMigrationSpec

@DeleteColumn(tableName = "workspaces", columnName = "tool_approvals")
class Migration_25_26 : AutoMigrationSpec
