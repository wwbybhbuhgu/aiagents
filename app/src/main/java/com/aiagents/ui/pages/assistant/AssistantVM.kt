package com.aiagents.ui.pages.assistant

import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import com.aiagents.data.datastore.Settings
import com.aiagents.data.datastore.SettingsStore
import com.aiagents.data.files.FilesManager
import com.aiagents.data.model.Assistant
import com.aiagents.data.model.Avatar
import com.aiagents.data.repository.ConversationRepository
import com.aiagents.data.repository.MemoryRepository
import com.aiagents.data.repository.WorkspaceRepository
import kotlin.uuid.Uuid

class AssistantVM(
    private val settingsStore: SettingsStore,
    private val memoryRepository: MemoryRepository,
    private val conversationRepo: ConversationRepository,
    private val filesManager: FilesManager,
    private val workspaceRepository: WorkspaceRepository,
) : ViewModel() {
    val settings: StateFlow<Settings> = settingsStore.settingsFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, Settings.dummy())

    fun updateSettings(settings: Settings) {
        viewModelScope.launch {
            settingsStore.update(settings)
        }
    }

    fun addAssistant(assistant: Assistant) {
        viewModelScope.launch {
            val settings = settings.value
            // 容器必选: 新助手自动创建并绑定工作区
            val bound = if (assistant.workspaceId == null) {
                val workspace = runCatching { workspaceRepository.create(assistant.name.ifBlank { "workspace" }) }
                    .getOrNull()
                if (workspace != null) assistant.copy(workspaceId = Uuid.parse(workspace.id)) else assistant
            } else {
                assistant
            }
            settingsStore.update(
                settings.copy(
                    assistants = settings.assistants.plus(bound)
                )
            )
        }
    }

    fun removeAssistant(assistant: Assistant) {
        viewModelScope.launch {
            cleanupAssistantFiles(assistant)

            val settings = settings.value
            settingsStore.update(
                settings.copy(
                    assistants = settings.assistants.filter { it.id != assistant.id }
                )
            )
            memoryRepository.deleteMemoriesOfAssistant(assistant.id.toString())
            conversationRepo.deleteConversationOfAssistant(assistant.id)
        }
    }

    private fun cleanupAssistantFiles(assistant: Assistant) {
        val uris = buildList {
            (assistant.avatar as? Avatar.Image)?.let { add(it.url.toUri()) }
            assistant.background?.let { add(it.toUri()) }
        }

        if (uris.isNotEmpty()) {
            filesManager.deleteChatFiles(uris)
        }
    }

    fun copyAssistant(assistant: Assistant) {
        viewModelScope.launch {
            val settings = settings.value
            val copiedAssistant = assistant.copy(
                id = kotlin.uuid.Uuid.random(),
                name = "${assistant.name} (Clone)",
                avatar = if(assistant.avatar is Avatar.Image) Avatar.Dummy else assistant.avatar,
            )
            settingsStore.update(
                settings.copy(
                    assistants = settings.assistants.plus(copiedAssistant)
                )
            )
        }
    }

    fun getMemories(assistant: Assistant) =
        if (assistant.useGlobalMemory) {
            memoryRepository.getGlobalMemoriesFlow()
        } else {
            memoryRepository.getMemoriesOfAssistantFlow(assistant.id.toString())
        }
}
