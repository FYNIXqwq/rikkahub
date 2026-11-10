package me.rerere.ai.provider.providers.openai

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.agent.AgentEvent
import me.rerere.ai.agent.AgentFinishReason
import me.rerere.ai.agent.AgentHooks
import me.rerere.ai.agent.AgentRequest
import me.rerere.ai.agent.StepRequest
import me.rerere.ai.agent.runAgent
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.BuiltInTools
import me.rerere.ai.provider.CustomBody
import me.rerere.ai.provider.ImageGenerationExecutionMode
import me.rerere.ai.provider.ImageGenerationOptions
import me.rerere.ai.provider.ImageGenerationParams
import me.rerere.ai.provider.IndependentImageToolSession
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.util.json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class IndependentImageToolTest {
    private val setting = ProviderSetting.OpenAI(apiKey = "test-key", useResponseApi = true)
    private val model = Model(modelId = "gpt-6-astra", abilities = listOf(ModelAbility.REASONING),
        tools = setOf(BuiltInTools.ImageGeneration))
    private val history = listOf(UIMessage.user("Draw a cat"))
    private val api = ResponseAPI(OkHttpClient())

    @Test
    fun `auto mode separates image 25 for API key Responses providers and hosted mode remains available`() {
        assertTrue(ImageGenerationOptions().usesIndependentRequests(setting))
        assertFalse(ImageGenerationOptions().usesIndependentRequests(setting.copy(useResponseApi = false)))
        assertFalse(ImageGenerationOptions().usesIndependentRequests(ProviderSetting.Google()))
        assertFalse(ImageGenerationOptions(executionMode = ImageGenerationExecutionMode.HOSTED).usesIndependentRequests(setting))
        assertFalse(ImageGenerationOptions(model = "gpt-image-2.6-sunburst").usesIndependentRequests(setting))
        assertTrue(ImageGenerationOptions(model = "gpt-image-2.5-flare-2026-09-08").usesIndependentRequests(setting))
        assertFalse(ImageGenerationOptions(model = "gpt-image-2.5-custom").usesIndependentRequests(setting))
        assertTrue(ImageGenerationOptions(model = "custom", executionMode = ImageGenerationExecutionMode.INDEPENDENT)
            .usesIndependentRequests(setting))
    }

    @Test
    fun `maximum chat reasoning and image 25 generation are separate HTTP requests with no forced loop`() = runBlocking {
        val requests = mutableListOf<Pair<String, kotlinx.serialization.json.JsonObject>>()
        var chatCalls = 0
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val body = json.parseToJsonElement(Buffer().also { request.body!!.writeTo(it) }.readUtf8()).jsonObject
            val path = request.url.encodedPath
            requests += path to body
            val response = when (path) {
                "/v1/responses" -> {
                    chatCalls++
                    val forced = (body["tool_choice"] as? kotlinx.serialization.json.JsonObject)?.get("type") == JsonPrimitive("function")
                    if (chatCalls == 1 || forced) {
                        """{"id":"resp_$chatCalls","model":"gpt-6-astra","status":"completed","output":[{"type":"function_call","id":"fc_$chatCalls","call_id":"call_$chatCalls","name":"${IndependentImageToolSession.NAME}","arguments":"{\"prompt\":\"A carefully composed cat portrait\",\"action\":\"generate\"}"}]}"""
                    } else {
                        """{"id":"resp_done","model":"gpt-6-astra","status":"completed","output":[{"type":"message","id":"msg_done","role":"assistant","content":[{"type":"output_text","text":"Done."}]}]}"""
                    }
                }
                "/v1/images/generations" -> """{"data":[{"b64_json":"aW1hZ2U="}]}"""
                else -> error("Unexpected request: $path")
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(response.toResponseBody("application/json".toMediaType())).build()
        }.build()
        val provider = OpenAIProvider(client)
        val responses = ResponseAPI(client)
        // Exercise the production request builders/parsers without Android's platform logger.
        val chatProvider = object : Provider<ProviderSetting> {
            override suspend fun listModels(providerSetting: ProviderSetting) = emptyList<Model>()
            override suspend fun streamText(providerSetting: ProviderSetting, messages: List<UIMessage>, params: TextGenerationParams) =
                error("This test exercises non-streaming transport")
            override suspend fun generateText(providerSetting: ProviderSetting, messages: List<UIMessage>, params: TextGenerationParams): me.rerere.ai.provider.TextGenerationResult {
                val body = responses.buildRequestBody(ResponseProviderCapabilities(), messages, params, false)
                val request = Request.Builder().url("https://api.openai.com/v1/responses")
                    .post(body.toString().toRequestBody("application/json".toMediaType())).build()
                return client.newCall(request).execute().use { responses.parseResponseOutput(json.parseToJsonElement(it.body.string()).jsonObject) }
            }
        }
        val options = ImageGenerationOptions(quality = "max", complexity = "high", inputFidelity = "high", forceTool = true)
        val session = IndependentImageToolSession(options, history) { plan ->
            val request = provider.buildImageGenerationRequest(setting, ImageGenerationParams(
                model = Model(modelId = options.model), prompt = plan.prompt, size = options.size,
                customBody = options.toImageApiParameters(), partialImages = 0,
            ), "test-key")
            client.newCall(request).execute().use { response ->
                provider.parseImageResponse(response.body.string()).map { UIMessagePart.Image("data:${it.mimeType};base64,${it.data}") }
            }
        }
        val params = TextGenerationParams(model = model.copy(imageGenerationOptions = options), reasoningLevel = ReasoningLevel.MAX)
        val events = runAgent(
            AgentRequest(chatProvider, setting, history, listOf(session.tool), stream = false, maxSteps = 3),
            object : AgentHooks {
                override suspend fun prepareStep(messages: List<UIMessage>): StepRequest {
                    session.observe(messages)
                    return StepRequest(session.labelReferences(messages), session.prepareParams(params))
                }
            },
        ).toList()
        assertEquals(AgentFinishReason.Completed, (events.last() as AgentEvent.Finished).reason)
        assertEquals(listOf("/v1/responses", "/v1/images/generations", "/v1/responses"), requests.map { it.first })
        requests.filter { it.first == "/v1/responses" }.forEach { (_, body) ->
            assertEquals("max", body["reasoning"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
            assertFalse(body["tools"]!!.jsonArray.any { it.jsonObject["type"] == JsonPrimitive("image_generation") })
        }
        val imageRequest = requests.single { it.first == "/v1/images/generations" }.second
        assertEquals("gpt-image-2.5-sunburst", imageRequest["model"]!!.jsonPrimitive.content)
        assertEquals("max", imageRequest["quality"]!!.jsonPrimitive.content)
        assertEquals("A carefully composed cat portrait", imageRequest["prompt"]!!.jsonPrimitive.content)
        assertFalse("reasoning" in imageRequest)
        assertFalse("reasoning_effort" in imageRequest)
        assertFalse("complexity" in imageRequest)
        assertFalse("input_fidelity" in imageRequest)
        assertEquals(JsonPrimitive("auto"), requests.last().second["tool_choice"])
        val output = (events.last() as AgentEvent.Finished).message.getTools().single().output
        assertEquals(1, output.filterIsInstance<UIMessagePart.Image>().size)
        assertEquals(ReasoningLevel.MAX, params.reasoningLevel)
        assertEquals("high", options.complexity)
    }

    @Test
    fun `custom bodies cannot restore a hosted image tool in independent mode`() {
        val session = IndependentImageToolSession(ImageGenerationOptions(), history) { emptyList() }
        val params = session.prepareParams(TextGenerationParams(
            model = model, reasoningLevel = ReasoningLevel.HIGH,
            customBody = listOf(
                CustomBody("tools", JsonArray(listOf(buildJsonObject {
                    put("type", "image_generation"); put("model", "gpt-image-2.5-sunburst"); put("complexity", "high")
                }))),
                CustomBody("tool_choice", buildJsonObject { put("type", "image_generation") }),
            ),
        ))
        val body = api.buildRequestBody(ResponseProviderCapabilities(), history, params, true)
        assertEquals("high", body["reasoning"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
        val tool = body["tools"]!!.jsonArray.single().jsonObject
        assertEquals("function", tool["type"]!!.jsonPrimitive.content)
        assertEquals(IndependentImageToolSession.NAME, tool["name"]!!.jsonPrimitive.content)
        assertEquals(IndependentImageToolSession.NAME, body["tool_choice"]!!.jsonObject["name"]!!.jsonPrimitive.content)
    }

    @Test
    fun `reference IDs survive new sessions and label only outgoing copies including prior tool images`() = runBlocking {
        val image = UIMessagePart.Image("file:///conversation/photo.png")
        val previous = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Tool(
            toolCallId = "old", toolName = IndependentImageToolSession.NAME, input = "{}", output = listOf(image),
        )))
        val messages = listOf(previous, UIMessage.user("Edit the previous image"))
        val first = IndependentImageToolSession(ImageGenerationOptions(), messages) { emptyList() }
        val labeled = first.labelReferences(messages)
        val label = labeled.first().getTools().single().output.first() as UIMessagePart.Text
        val id = label.text.substringAfter("[reference_image: ").substringBefore(']')
        var references: List<String>? = null
        val resumed = IndependentImageToolSession(ImageGenerationOptions(), messages) {
            references = it.referenceImages
            listOf(UIMessagePart.Text("done"))
        }
        resumed.tool.execute(buildJsonObject {
            put("prompt", "Make the background blue"); put("action", "edit")
            put("reference_images", JsonArray(listOf(JsonPrimitive(id))))
        })
        assertEquals(listOf(image.url), references)
        assertEquals(listOf(image), previous.getTools().single().output)
    }

    @Test
    fun `unknown references do not reach image execution and forced mode is not retried after failure`() = runBlocking {
        var executions = 0
        val session = IndependentImageToolSession(ImageGenerationOptions(forceTool = true), history) {
            executions++; emptyList()
        }
        val error = runCatching { session.tool.execute(buildJsonObject {
            put("prompt", "Edit"); put("action", "edit")
            put("reference_images", JsonArray(listOf(JsonPrimitive("file:///private/not-in-conversation.png"))))
        }) }.exceptionOrNull()
        assertNotNull(error)
        assertEquals(0, executions)
        val body = api.buildRequestBody(ResponseProviderCapabilities(), history, session.prepareParams(TextGenerationParams(model)), false)
        assertEquals(JsonPrimitive("auto"), body["tool_choice"])
    }

    @Test
    fun `cancellation propagates out of the image execution callback`() = runBlocking {
        val cancellation = CancellationException("stop")
        val session = IndependentImageToolSession(ImageGenerationOptions(), history) { throw cancellation }
        assertSame(cancellation, runCatching { session.tool.execute(buildJsonObject {
            put("prompt", "Draw"); put("action", "generate")
        }) }.exceptionOrNull())
    }

    @Test
    fun `executed image tool is not forced again when resuming an assistant turn`() {
        val done = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Tool(
            toolCallId = "done", toolName = IndependentImageToolSession.NAME, input = "{}",
            output = listOf(UIMessagePart.Text("done")),
        )))
        val session = IndependentImageToolSession(ImageGenerationOptions(forceTool = true), listOf(done)) { emptyList() }
        val body = api.buildRequestBody(ResponseProviderCapabilities(), listOf(done), session.prepareParams(TextGenerationParams(model)), false)
        assertEquals(JsonPrimitive("auto"), body["tool_choice"])
    }

    @Test
    fun `image API options retain independent complexity for models which support it`() {
        val fields = ImageGenerationOptions(model = "gpt-image-2.6-sunburst", complexity = "low", partialImages = 3,
            quality = "high", outputFormat = "webp", outputCompression = 70).toImageApiParameters().associate { it.key to it.value }
        assertEquals(JsonPrimitive("low"), fields["complexity"])
        assertEquals(JsonPrimitive(70), fields["output_compression"])
        assertFalse("reasoning" in fields)
        assertFalse("partial_images" in fields)
    }
}
