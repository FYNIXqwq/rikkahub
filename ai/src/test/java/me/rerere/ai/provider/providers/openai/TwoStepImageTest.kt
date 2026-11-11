package me.rerere.ai.provider.providers.openai

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import me.rerere.ai.agent.*
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.*
import me.rerere.ai.provider.stream.SseEvent
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.util.json
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test

class TwoStepImageTest {
    private val api = ResponseAPI(OkHttpClient())
    private val setting = ProviderSetting.OpenAI(useResponseApi = true)
    private val history = listOf(UIMessage.user("Draw a cat"))
    private fun params(imageModel: String = "gpt-image-2.5-sunburst") = TextGenerationParams(
        model = Model(modelId = "gpt-6-astra", abilities = listOf(ModelAbility.REASONING, ModelAbility.TOOL),
            tools = setOf(BuiltInTools.ImageGeneration),
            imageGenerationOptions = ImageGenerationOptions(model = imageModel, complexity = "high", partialImages = 2)),
        reasoningLevel = ReasoningLevel.MAX,
    )

    private fun hooks(session: TwoStepImageSession, params: TextGenerationParams) = object : AgentHooks {
        override suspend fun prepareStep(messages: List<UIMessage>) = session.prepareStep(StepRequest(messages, params))
        override suspend fun previewStep(message: UIMessage, newPartsFrom: Int) = session.previewStep(message, newPartsFrom)
        override suspend fun finishStep(message: UIMessage, newPartsFrom: Int) = session.finishStep(message, newPartsFrom)
        override suspend fun shouldContinue(message: UIMessage) = session.shouldContinue(message)
    }

    @Test
    fun `streaming and nonstreaming keep max planning then use native image events without effort inheritance`() = runBlocking {
        for (imageModel in listOf("gpt-image-2", "gpt-image-2.5-sunburst")) {
            for (stream in listOf(true, false)) {
                val params = params(imageModel)
                val session = TwoStepImageSession(params.model.imageGenerationOptions)
                val provider = ResponsesFixture()
                val events = runAgent(AgentRequest(provider, setting, history, stream = stream), hooks(session, params)).toList()
                val done = events.last() as AgentEvent.Finished
                assertEquals(AgentFinishReason.Completed, done.reason)
                assertEquals(2, provider.bodies.size)
                val (plan, render) = provider.bodies
                assertEquals("max", plan["reasoning"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
                assertFalse(plan.containsKey("tools"))
                assertEquals("json_schema", plan["text"]!!.jsonObject["format"]!!.jsonObject["type"]!!.jsonPrimitive.content)
                assertFalse(render["reasoning"]!!.jsonObject.containsKey("effort"))
                val imageTool = render["tools"]!!.jsonArray.single().jsonObject
                assertEquals("image_generation", imageTool["type"]!!.jsonPrimitive.content)
                assertEquals(imageModel, imageTool["model"]!!.jsonPrimitive.content)
                assertFalse(imageTool.containsKey("complexity"))
                assertEquals(stream, imageTool.containsKey("partial_images"))
                assertEquals("image_generation", render["tool_choice"]!!.jsonObject["type"]!!.jsonPrimitive.content)
                assertTrue(render["input"].toString().contains("A cat on a blue background"))
                assertEquals(listOf("data:image/png;base64,ZmluYWw="), done.message.parts.filterIsInstance<UIMessagePart.Image>().map { it.url })
                assertTrue(done.message.getTools().isEmpty())
                assertEquals(2, done.message.stepCount)
                assertFalse(session.shouldContinue(done.message))
                val updates = events.filterIsInstance<AgentEvent.MessageUpdated>()
                assertTrue(updates.none { it.message.toText().contains("generate_image") })
                if (stream) assertTrue(updates.any { update -> update.message.parts.any {
                    it is UIMessagePart.Image && it.url.endsWith("cHJldmlldw==")
                } })
                assertEquals(ReasoningLevel.MAX, params.reasoningLevel)
            }
        }
    }

    @Test
    fun `ordinary chat and refusals stop before native image generation`() = runBlocking {
        for (plan in listOf("""{"generate_image":false,"text":"Hello."}""", "I cannot help with that request.")) {
            val params = params()
            val provider = ResponsesFixture(plan)
            val events = runAgent(AgentRequest(provider, setting, history), hooks(TwoStepImageSession(params.model.imageGenerationOptions), params)).toList()
            assertEquals(1, provider.bodies.size)
            assertTrue((events.last() as AgentEvent.Finished).message.parts.none { it is UIMessagePart.Image })
        }
    }

    @Test
    fun `custom bodies cannot reintroduce image tools while planning or effort while rendering`() {
        val params = params().copy(customBody = listOf(
            CustomBody("reasoning", buildJsonObject { put("effort", "high"); put("summary", "auto") }),
            CustomBody("reasoning_effort", JsonPrimitive("max")),
            CustomBody("complexity", JsonPrimitive("high")),
            CustomBody("tools", buildJsonArray { add(buildJsonObject { put("type", "image_generation"); put("complexity", "high") }) }),
            CustomBody("tool_choice", buildJsonObject { put("type", "image_generation") }),
            CustomBody("text", buildJsonObject { put("format", buildJsonObject { put("type", "json_object") }) }),
        ))
        val session = TwoStepImageSession(params.model.imageGenerationOptions)
        val plan = session.prepareStep(StepRequest(history, params))
        val planBody = api.buildRequestBody(ResponseProviderCapabilities(), plan.messages, plan.params, true)
        assertFalse(planBody.containsKey("tools"))
        val ready = session.finishStep(assistantPlan(), 0)
        val render = session.prepareStep(StepRequest(history + ready, params))
        val body = api.buildRequestBody(ResponseProviderCapabilities(), render.messages, render.params, true)
        assertFalse(body["reasoning"]!!.jsonObject.containsKey("effort"))
        assertFalse(body.containsKey("reasoning_effort"))
        assertFalse(body.containsKey("complexity"))
        assertFalse(body["tools"]!!.jsonArray.single().jsonObject.containsKey("complexity"))
        assertEquals("text", body["text"]!!.jsonObject["format"]!!.jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a persisted plan resumes at rendering and does not affect the next user turn`() {
        val params = params()
        val original = TwoStepImageSession(params.model.imageGenerationOptions)
        val ready = json.decodeFromString<UIMessage>(json.encodeToString(original.finishStep(assistantPlan(), 0)))
        val resumed = TwoStepImageSession(params.model.imageGenerationOptions)
        assertEquals(ImageGenerationPhase.RENDER, resumed.prepareStep(StepRequest(history + ready, params)).params.imageGenerationPhase)
        val done = resumed.finishStep(ready.copy(parts = ready.parts + UIMessagePart.Image("file:///saved.png")), ready.parts.size)
        assertFalse(resumed.shouldContinue(done))
        assertFalse(resumed.shouldContinue(ready.copy(parts = ready.parts + UIMessagePart.Image("file:///saved-before-cancel.png"))))
        val next = resumed.prepareStep(StepRequest(history + done + UIMessage.user("Thanks"), params))
        assertEquals(ImageGenerationPhase.PLAN, next.params.imageGenerationPhase)
        assertEquals(ReasoningLevel.MAX, next.params.reasoningLevel)
    }

    @Test
    fun `malformed or truncated plans cannot trigger an image call`() = runBlocking {
        for (invalid in listOf("", "{", """{"generate_image":true}""", """{"generate_image":true,"text":""}""")) {
            val params = params()
            val provider = ResponsesFixture(invalid)
            val failure = runCatching { runAgent(AgentRequest(provider, setting, history),
                hooks(TwoStepImageSession(params.model.imageGenerationOptions), params)).toList() }.exceptionOrNull()
            assertNotNull(failure)
            assertEquals(1, provider.bodies.size)
        }
    }

    @Test
    fun `continuation respects step limits and cancellation between the two requests`() = runBlocking {
        val params = params()
        val session = TwoStepImageSession(params.model.imageGenerationOptions)
        val provider = ResponsesFixture()
        val events = runAgent(AgentRequest(provider, setting, history, maxSteps = 1), hooks(session, params)).toList()
        assertEquals(AgentFinishReason.StepLimitReached, (events.last() as AgentEvent.Finished).reason)
        assertEquals(1, provider.bodies.size)
        val stoppedProvider = ResponsesFixture()
        val stoppedSession = TwoStepImageSession(params.model.imageGenerationOptions)
        val cancellation = runCatching {
            runAgent(AgentRequest(stoppedProvider, setting, history), hooks(stoppedSession, params)).collect {
                if (it is AgentEvent.MessageUpdated && stoppedSession.shouldContinue(it.message)) throw CancellationException("stop")
            }
        }.exceptionOrNull()
        assertTrue(cancellation is CancellationException)
        assertEquals(1, stoppedProvider.bodies.size)
    }

    @Test
    fun `automatic routing is limited to known image models on API key Responses providers`() {
        for (model in listOf("gpt-image-2", "gpt-image-2-2026-04-21", "gpt-image-2.5-flare-2026-09-08")) {
            assertTrue(ImageGenerationOptions(model = model).usesTwoStepRequests(setting))
        }
        assertFalse(ImageGenerationOptions().usesTwoStepRequests(setting.copy(useResponseApi = false)))
        assertFalse(ImageGenerationOptions().usesTwoStepRequests(ProviderSetting.Google()))
        assertFalse(ImageGenerationOptions(model = "gpt-image-2.6-sunburst").usesTwoStepRequests(setting))
        assertFalse(ImageGenerationOptions(executionMode = ImageGenerationExecutionMode.HOSTED).usesTwoStepRequests(setting))
        val explicit = ImageGenerationOptions(executionMode = ImageGenerationExecutionMode.TWO_STEP)
        assertEquals(explicit, json.decodeFromString<ImageGenerationOptions>(json.encodeToString(explicit)))
    }

    private fun assistantPlan() = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text(PLAN)))

    private inner class ResponsesFixture(private val plan: String = PLAN) : Provider<ProviderSetting> {
        val bodies = mutableListOf<JsonObject>()
        override suspend fun listModels(providerSetting: ProviderSetting) = emptyList<Model>()
        private fun response(messages: List<UIMessage>, params: TextGenerationParams, stream: Boolean): JsonObject {
            val body = api.buildRequestBody(ResponseProviderCapabilities(), messages, params, stream)
            bodies += body
            val item = if (params.imageGenerationPhase == ImageGenerationPhase.PLAN) buildJsonObject {
                put("id", "msg_plan"); put("type", "message"); put("role", "assistant")
                put("content", buildJsonArray { add(buildJsonObject { put("type", "output_text"); put("text", plan) }) })
            } else imageItem("ZmluYWw=")
            return buildJsonObject { put("status", "completed"); put("output", buildJsonArray { add(item) }) }
        }
        override suspend fun generateText(providerSetting: ProviderSetting, messages: List<UIMessage>, params: TextGenerationParams) =
            api.parseResponseOutput(response(messages, params, false))
        override suspend fun streamText(providerSetting: ProviderSetting, messages: List<UIMessage>, params: TextGenerationParams): Flow<StreamChunk> = flow {
            val response = response(messages, params, true)
            val decoder = ResponseApiStreamDecoder()
            suspend fun event(value: JsonObject) {
                decoder.accept(SseEvent(event = value["type"]!!.jsonPrimitive.content, data = value.toString())).chunks.forEach { emit(it) }
            }
            if (params.imageGenerationPhase == ImageGenerationPhase.PLAN) {
                event(buildJsonObject { put("type", "response.reasoning_summary_text.delta"); put("item_id", "rs_plan"); put("delta", "Planning the image.") })
                event(buildJsonObject { put("type", "response.output_text.delta"); put("item_id", "msg_plan"); put("delta", plan) })
            } else {
                event(buildJsonObject { put("type", "response.output_item.added"); put("item", imageItem(null)) })
                event(buildJsonObject { put("type", "response.image_generation_call.partial_image"); put("item_id", "ig_render");
                    put("partial_image_index", 0); put("partial_image_b64", "cHJldmlldw=="); put("output_format", "png") })
                event(buildJsonObject { put("type", "response.output_item.done"); put("item", imageItem("ZmluYWw=")) })
            }
            event(buildJsonObject { put("type", "response.completed"); put("response", response) })
        }
    }

    private fun imageItem(data: String?) = buildJsonObject {
        put("id", "ig_render"); put("type", "image_generation_call")
        put("status", if (data == null) "in_progress" else "completed")
        put("result", data?.let(::JsonPrimitive) ?: JsonNull); put("output_format", "png")
    }

    companion object {
        private const val PLAN = """{"generate_image":true,"text":"A cat on a blue background"}"""
    }
}
