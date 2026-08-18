package com.aiagents.service

import kotlinx.serialization.json.JsonPrimitive
import com.aiagents.ai.core.ReasoningLevel
import com.aiagents.ai.provider.CustomBody
import com.aiagents.ai.provider.CustomHeader
import com.aiagents.ai.provider.Model
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatServiceTest {
    @Test
    fun `background generation params include model custom request configuration`() {
        val headers = listOf(CustomHeader(name = "X-Gateway-Token", value = "test-token"))
        val bodies = listOf(CustomBody(key = "gateway_mode", value = JsonPrimitive("strict")))
        val model = Model(
            modelId = "custom-chat-model",
            customHeaders = headers,
            customBodies = bodies,
        )

        val params = backgroundTextGenerationParams(model)

        assertEquals(model, params.model)
        assertEquals(ReasoningLevel.AUTO, params.reasoningLevel)
        assertEquals(headers, params.customHeaders)
        assertEquals(bodies, params.customBody)
    }
}
