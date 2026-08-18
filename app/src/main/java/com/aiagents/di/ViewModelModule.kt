package com.aiagents.di

import com.aiagents.ui.pages.assistant.AssistantVM
import com.aiagents.ui.pages.assistant.detail.AssistantDetailVM
import com.aiagents.ui.pages.backup.BackupVM
import com.aiagents.ui.pages.chat.ChatDrawerVM
import com.aiagents.ui.pages.chat.ChatVM
import com.aiagents.ui.pages.debug.DebugVM
import com.aiagents.ui.pages.favorite.FavoriteVM
import com.aiagents.ui.pages.search.SearchVM
import com.aiagents.ui.pages.history.HistoryVM
import com.aiagents.ui.pages.stats.StatsVM
import com.aiagents.ui.pages.extensions.PromptVM
import com.aiagents.ui.pages.extensions.QuickMessagesVM
import com.aiagents.ui.pages.extensions.skills.SkillDetailVM
import com.aiagents.ui.pages.extensions.skills.SkillsVM
import com.aiagents.ui.pages.extensions.workspace.WorkspaceDetailVM
import com.aiagents.ui.pages.extensions.workspace.WorkspaceOnboardingVM
import com.aiagents.ui.pages.extensions.workspace.WorkspaceVM
import com.aiagents.ui.pages.setting.SettingVM
import com.aiagents.ui.pages.share.handler.ShareHandlerVM
import org.koin.core.module.dsl.viewModel
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

val viewModelModule = module {
    viewModel<ChatVM> { params ->
        ChatVM(
            id = params.get(),
            context = get(),
            settingsStore = get(),
            conversationRepo = get(),
            chatService = get(),
            updateChecker = get(),
            analytics = get(),
            filesManager = get(),
            favoriteRepository = get(),
        )
    }
    viewModelOf(::ChatDrawerVM)
    viewModelOf(::SettingVM)
    viewModelOf(::DebugVM)
    viewModelOf(::HistoryVM)
    viewModelOf(::AssistantVM)
    viewModel<AssistantDetailVM> {
        AssistantDetailVM(
            id = it.get(),
            settingsStore = get(),
            memoryRepository = get(),
            filesManager = get(),
            skillManager = get(),
            workspaceRepository = get(),
        )
    }
    viewModel<ShareHandlerVM> {
        ShareHandlerVM(
            text = it.get(),
            settingsStore = get(),
        )
    }
    viewModelOf(::BackupVM)
    viewModelOf(::PromptVM)
    viewModelOf(::QuickMessagesVM)
    viewModelOf(::SkillsVM)
    viewModelOf(::SkillDetailVM)
    viewModelOf(::WorkspaceVM)
    viewModel<WorkspaceDetailVM> {
        WorkspaceDetailVM(
            id = it.get(),
            repository = get(),
        )
    }
    viewModelOf(::WorkspaceOnboardingVM)
    viewModelOf(::FavoriteVM)
    viewModelOf(::SearchVM)
    viewModelOf(::StatsVM)
}
