package com.aiagents.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.aiagents.R
import com.aiagents.data.db.dao.ScheduledTaskDAO
import com.aiagents.data.db.entity.ScheduledTaskEntity
import com.aiagents.ui.components.nav.BackButton
import com.aiagents.ui.theme.CustomColors
import com.aiagents.utils.toLocalString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.Clock01
import org.koin.compose.koinInject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingScheduledTasksPage() {
    val dao = koinInject<ScheduledTaskDAO>()
    val scope = rememberCoroutineScope()
    var tasks by remember { mutableStateOf<List<ScheduledTaskEntity>>(emptyList()) }
    var showAddDialog by remember { mutableStateOf(false) }
    var editingTask by remember { mutableStateOf<ScheduledTaskEntity?>(null) }

    fun refresh() {
        scope.launch {
            tasks = withContext(Dispatchers.IO) { dao.getAll() }
        }
    }

    LaunchedEffect(Unit) { refresh() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.setting_page_scheduled_tasks)) },
                navigationIcon = { BackButton() },
                actions = {
                    IconButton(onClick = { showAddDialog = true }) {
                        Icon(
                            imageVector = HugeIcons.Add01,
                            contentDescription = stringResource(R.string.setting_page_scheduled_tasks_add),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = CustomColors.topBarColors.containerColor,
                ),
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(16.dp),
        ) {
            if (tasks.isEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.setting_page_scheduled_tasks_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 24.dp),
                    )
                }
            }
            items(tasks, key = { it.id }) { task ->
                ScheduledTaskCard(
                    task = task,
                    onEdit = { editingTask = task },
                    onToggle = { enabled ->
                        scope.launch {
                            withContext(Dispatchers.IO) { dao.upsert(task.copy(enabled = enabled)) }
                            refresh()
                        }
                    },
                    onDelete = {
                        scope.launch {
                            withContext(Dispatchers.IO) { dao.deleteById(task.id) }
                            refresh()
                        }
                    },
                )
            }
        }
    }

    if (showAddDialog) {
        ScheduledTaskDialog(
            initial = null,
            onDismiss = { showAddDialog = false },
            onSave = { name, schedule, actionType, action ->
                scope.launch {
                    withContext(Dispatchers.IO) {
                        dao.upsert(
                            ScheduledTaskEntity(
                                id = kotlin.uuid.Uuid.random().toString(),
                                conversationId = "",
                                name = name,
                                schedule = schedule,
                                actionType = actionType,
                                action = action,
                                nextRunAt = runCatching {
                                    com.aiagents.data.ai.tools.CronParser.parseNext(schedule, System.currentTimeMillis())
                                }.getOrDefault(System.currentTimeMillis() + 60_000L),
                            )
                        )
                    }
                    showAddDialog = false
                    refresh()
                }
            },
        )
    }

    editingTask?.let { task ->
        ScheduledTaskDialog(
            initial = task,
            onDismiss = { editingTask = null },
            onSave = { name, schedule, actionType, action ->
                scope.launch {
                    withContext(Dispatchers.IO) {
                        dao.upsert(
                            task.copy(
                                name = name,
                                schedule = schedule,
                                actionType = actionType,
                                action = action,
                                nextRunAt = runCatching {
                                    com.aiagents.data.ai.tools.CronParser.parseNext(schedule, System.currentTimeMillis())
                                }.getOrDefault(task.nextRunAt),
                            )
                        )
                    }
                    editingTask = null
                    refresh()
                }
            },
        )
    }
}

@Composable
private fun ScheduledTaskCard(
    task: ScheduledTaskEntity,
    onEdit: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    var showDeleteConfirm by remember { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = task.name,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = task.schedule,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = task.enabled, onCheckedChange = onToggle)
            }
            Text(
                text = "类型: ${task.actionType} · 下次: ${java.time.Instant.ofEpochMilli(task.nextRunAt).atZone(java.time.ZoneId.systemDefault()).toLocalDateTime().toLocalString()}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            task.lastOutputFile?.let {
                Text(
                    text = "输出: /tool_outputs/$it",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = onEdit,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.common_edit))
                }
                OutlinedButton(
                    onClick = { showDeleteConfirm = true },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.common_delete))
                }
            }
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text(stringResource(R.string.setting_page_scheduled_tasks_delete_title)) },
            text = { Text(stringResource(R.string.setting_page_scheduled_tasks_delete_confirm, task.name)) },
            confirmButton = {
                Button(onClick = {
                    showDeleteConfirm = false
                    onDelete()
                }) {
                    Text(stringResource(R.string.common_delete))
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showDeleteConfirm = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }
}

@Composable
private fun ScheduledTaskDialog(
    initial: ScheduledTaskEntity?,
    onDismiss: () -> Unit,
    onSave: (name: String, schedule: String, actionType: String, action: String) -> Unit,
) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var schedule by remember { mutableStateOf(initial?.schedule ?: "every 30 m") }
    var actionType by remember { mutableStateOf(initial?.actionType ?: "agent") }
    var action by remember { mutableStateOf(initial?.action ?: "") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.setting_page_scheduled_tasks_edit_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.setting_page_scheduled_tasks_name)) },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = schedule,
                    onValueChange = { schedule = it },
                    label = { Text(stringResource(R.string.setting_page_scheduled_tasks_schedule)) },
                    supportingText = { Text(stringResource(R.string.setting_page_scheduled_tasks_schedule_hint)) },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = actionType,
                    onValueChange = { actionType = it },
                    label = { Text(stringResource(R.string.setting_page_scheduled_tasks_action_type)) },
                    supportingText = { Text(stringResource(R.string.setting_page_scheduled_tasks_action_type_hint)) },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = action,
                    onValueChange = { action = it },
                    label = { Text(stringResource(R.string.setting_page_scheduled_tasks_action)) },
                    minLines = 3,
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (name.isNotBlank() && schedule.isNotBlank() && action.isNotBlank()) {
                        onSave(name.trim(), schedule.trim(), actionType.trim(), action.trim())
                    }
                },
                enabled = name.isNotBlank() && schedule.isNotBlank() && action.isNotBlank(),
            ) {
                Text(stringResource(R.string.common_save))
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_cancel))
            }
        },
    )
}