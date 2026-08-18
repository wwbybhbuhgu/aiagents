package com.aiagents.di

import com.google.firebase.Firebase
import com.google.firebase.analytics.analytics
import com.google.firebase.crashlytics.crashlytics
import kotlinx.serialization.json.Json
import com.aiagents.AppScope
import com.aiagents.data.ai.tools.local.LocalTools
import com.aiagents.data.ai.tools.AgentRunManager
import com.aiagents.data.ai.tools.TaskScheduler
import com.aiagents.data.event.AppEventBus
import com.aiagents.service.ChatNotificationManager
import com.aiagents.service.ChatService
import com.aiagents.service.ReminderScheduler
import com.aiagents.utils.EmojiData
import com.aiagents.utils.EmojiUtils
import com.aiagents.utils.JsonInstant
import com.aiagents.utils.SoundEffectPlayer
import com.aiagents.utils.UpdateChecker
import com.aiagents.web.WebServerManager
import com.aiagents.tts.provider.TTSManager
import kotlinx.coroutines.CoroutineScope
import org.koin.dsl.module

val appModule = module {
    single<Json> { JsonInstant }

    single {
        AppEventBus()
    }

    single {
        LocalTools(get(), get(), get(), get(), get())
    }

    single {
        AgentRunManager(get())
    }

    single {
        TaskScheduler(
            scope = get(),
            dao = get(),
            workspaceRepository = get(),
            conversationRepo = get(),
            agentRunManager = get(),
            chatService = get(),
            context = get(),
        )
    }

    single {
        ReminderScheduler(
            context = get(),
            dao = get(),
        )
    }

    single {
        UpdateChecker(get())
    }

    single {
        AppScope()
    }

    // CoroutineScope 别名指向 AppScope：AgentRunManager/TaskScheduler 等按接口类型注入
    single<CoroutineScope> { get<AppScope>() }

    single<EmojiData> {
        EmojiUtils.loadEmoji(get())
    }

    single {
        TTSManager(get())
    }

    // Firebase 为占位配置(google_app_id 缺失)时初始化会抛异常, 安全降级为 null
    single {
        runCatching { Firebase.crashlytics }.getOrNull()
    }

    single {
        runCatching { Firebase.analytics }.getOrNull()
    }

    single {
        SoundEffectPlayer(get())
    }

    // 生成通知与业务解耦：ChatService 只发事件，通知由这里消费；
    // createdAtStart 保证进程启动即订阅，否则后台生成的事件会因无订阅者而丢失
    single(createdAtStart = true) {
        ChatNotificationManager(
            context = get(),
            appScope = get(),
            eventBus = get(),
            settingsStore = get(),
        )
    }

    single {
        ChatService(
            context = get(),
            appScope = get(),
            appEventBus = get(),
            settingsStore = get(),
            conversationRepo = get(),
            memoryRepository = get(),
            generationHandler = get(),
            templateTransformer = get(),
            providerManager = get(),
            localTools = get(),
            mcpManager = get(),
            filesManager = get(),
            skillManager = get(),
            workspaceRepository = get(),
            folderRepository = get(),
            agentRunManager = get()
        )
    }

    single {
        WebServerManager(
            context = get(),
            appScope = get(),
            chatService = get(),
            conversationRepo = get(),
            folderRepo = get(),
            settingsStore = get(),
            filesManager = get()
        )
    }
}
