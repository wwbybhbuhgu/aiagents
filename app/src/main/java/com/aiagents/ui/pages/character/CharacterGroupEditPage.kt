package com.aiagents.ui.pages.character

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.aiagents.data.datastore.SettingsStore
import com.aiagents.data.model.Assistant
import com.aiagents.data.model.Avatar
import com.aiagents.data.model.CharacterGroup
import com.aiagents.ui.context.LocalNavController
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowLeft01
import me.rerere.hugeicons.stroke.Tick01
import org.koin.compose.koinInject
import kotlin.uuid.Uuid

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CharacterGroupEditPage(groupId: String?) {
    val navController = LocalNavController.current
    val settingsStore = koinInject<SettingsStore>()
    val settings by settingsStore.settingsFlow.collectAsState(initial = null)
    val scope = rememberCoroutineScope()

    val assistants = settings?.assistants ?: emptyList()
    val parsedGroupId = groupId?.let { runCatching { Uuid.parse(it) }.getOrNull() }
    val existingGroup = parsedGroupId?.let { id -> settings?.characterGroups?.find { it.id == id } }

    var name by remember(existingGroup) { mutableStateOf(existingGroup?.name ?: "") }
    var description by remember(existingGroup) { mutableStateOf(existingGroup?.description ?: "") }
    val selectedMemberIds = remember(existingGroup) {
        mutableStateListOf<Uuid>().apply {
            existingGroup?.memberIds?.let { addAll(it) }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (existingGroup != null) "编辑群组" else "新建群组") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(HugeIcons.ArrowLeft01, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            if (name.isNotBlank() && selectedMemberIds.size >= 2) {
                                val group = CharacterGroup(
                                    id = existingGroup?.id ?: Uuid.random(),
                                    name = name.trim(),
                                    description = description.trim(),
                                    memberIds = selectedMemberIds.toList(),
                                    avatar = existingGroup?.avatar ?: Avatar.Dummy,
                                    createdAt = existingGroup?.createdAt ?: System.currentTimeMillis(),
                                    updatedAt = System.currentTimeMillis(),
                                )
                                scope.launch {
                                    settingsStore.update { s ->
                                        val existing = s.characterGroups.filter { it.id != group.id }
                                        s.copy(characterGroups = existing + group)
                                    }
                                }
                                navController.popBackStack()
                            }
                        },
                        enabled = name.isNotBlank() && selectedMemberIds.size >= 2,
                    ) {
                        Icon(HugeIcons.Tick01, contentDescription = "保存")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 16.dp),
        ) {
            item {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("群组名称") },
                    singleLine = true,
                )
            }

            item {
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("描述（可选）") },
                    minLines = 2,
                    maxLines = 4,
                )
            }

            item {
                Column {
                    Text(
                        text = "选择成员（至少2位）",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        text = "已选 ${selectedMemberIds.size} 位成员",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (selectedMemberIds.size >= 2)
                            MaterialTheme.colorScheme.primary
                        else
                            MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            items(assistants, key = { it.id }) { assistant ->
                AssistantSelectRow(
                    assistant = assistant,
                    isSelected = selectedMemberIds.contains(assistant.id),
                    onToggle = {
                        if (selectedMemberIds.contains(assistant.id)) {
                            selectedMemberIds.remove(assistant.id)
                        } else {
                            selectedMemberIds.add(assistant.id)
                        }
                    },
                )
            }

            if (assistants.isEmpty()) {
                item {
                    Text(
                        text = "暂无可用助手, 请先创建助手",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun AssistantSelectRow(
    assistant: Assistant,
    isSelected: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val avatar = assistant.avatar
            when (avatar) {
                is Avatar.Image -> {
                    coil3.compose.AsyncImage(
                        model = avatar.url,
                        contentDescription = null,
                        modifier = Modifier.size(40.dp),
                    )
                }
                is Avatar.Emoji -> {
                    Text(
                        text = avatar.content,
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.size(40.dp),
                    )
                }
                else -> {
                    Text(
                        text = assistant.name.take(1).uppercase(),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.size(40.dp),
                    )
                }
            }

            Spacer(Modifier.width(12.dp))

            Column {
                Text(
                    text = assistant.name.ifBlank { "未命名助手" },
                    style = MaterialTheme.typography.bodyLarge,
                )
                val prompt = assistant.systemPrompt.take(60)
                if (prompt.isNotBlank()) {
                    Text(
                        text = prompt,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
        }

        Switch(
            checked = isSelected,
            onCheckedChange = { onToggle() },
        )
    }
}
