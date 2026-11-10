package me.rerere.rikkahub.data.ai

import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.BuiltInTools
import me.rerere.ai.provider.CustomBody
import me.rerere.ai.provider.CustomHeader
import me.rerere.ai.provider.Model
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class BackgroundTextGenerationParamsTest {
    @Test
    fun `background tasks cannot activate the chat image tool`() {
        val model = Model(tools = setOf(BuiltInTools.Search, BuiltInTools.ImageGeneration))
        val params = backgroundTextGenerationParams(model, Uuid.random(), ReasoningLevel.MAX)
        assertFalse(BuiltInTools.ImageGeneration in params.model.tools)
        assertTrue(BuiltInTools.Search in params.model.tools)
        assertTrue(BuiltInTools.ImageGeneration in model.tools)
        assertEquals(ReasoningLevel.MAX, params.reasoningLevel)
    }

    @Test
    fun `background generation params include model custom request configuration`() {
        val headers = listOf(CustomHeader(name = "X-Gateway-Token", value = "test-token"))
        val bodies = listOf(CustomBody(key = "gateway_mode", value = JsonPrimitive("strict")))
        val model = Model(
            modelId = "custom-chat-model",
            customHeaders = headers,
            customBodies = bodies,
        )

        val conversationId = Uuid.random()
        val params = backgroundTextGenerationParams(model, conversationId)

        assertEquals(model, params.model)
        assertEquals(ReasoningLevel.AUTO, params.reasoningLevel)
        assertEquals(headers, params.customHeaders)
        assertEquals(bodies, params.customBody)
        assertEquals(conversationId.toString(), params.sessionId)
    }
}
