package com.aiagents.ai.provider.providers.openai

import kotlinx.coroutines.flow.Flow
import com.aiagents.ai.provider.ProviderSetting
import com.aiagents.ai.provider.TextGenerationParams
import com.aiagents.ai.ui.MessageChunk
import com.aiagents.ai.ui.UIMessage

interface OpenAIImpl {
    suspend fun generateText(
        providerSetting: ProviderSetting.OpenAI,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): MessageChunk

    suspend fun streamText(
        providerSetting: ProviderSetting.OpenAI,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): Flow<MessageChunk>
}
