package me.rerere.ai.provider.providers.openai

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.provider.BuiltInTools
import me.rerere.ai.provider.ImageGenerationOptions
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.hasValidImageGenerationOptions
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.util.json
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageGenerationOptionsTest {
    private val api = ResponseAPI(OkHttpClient())

    @Test
    fun `old model settings retain the original image generation request`() {
        val model = json.decodeFromString<Model>("""{"modelId":"gpt-6-astra","tools":[{"type":"image_generation"}]}""")
        assertEquals(ImageGenerationOptions(), model.imageGenerationOptions)
        val tool = request(model)["tools"]!!.jsonArray.single().jsonObject
        assertEquals(setOf("type", "model"), tool.keys)
        assertEquals("gpt-image-2.5-sunburst", tool["model"]!!.jsonPrimitive.content)
    }

    @Test
    fun `all raster options round trip and reach the image tool without replacing the chat model`() {
        val options = ImageGenerationOptions(
            model = "gpt-image-2.6-flare", action = "edit", size = "1536x864", quality = "max",
            background = "transparent", outputFormat = "webp", outputCompression = 75,
            partialImages = 2, moderation = "low", complexity = "high", forceTool = true,
        )
        val model =
            Model(
                modelId = "gpt-6-astra",
                tools = setOf(BuiltInTools.ImageGeneration),
                imageGenerationOptions = options
            )
        val restored = json.decodeFromString<Model>(json.encodeToString(model))
        assertEquals(model, restored)
        val body = request(restored)
        assertEquals("gpt-6-astra", body["model"]!!.jsonPrimitive.content)
        assertEquals("image_generation", body["tool_choice"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        val tool = body["tools"]!!.jsonArray.single().jsonObject
        val expected = mapOf(
            "type" to "image_generation", "model" to "gpt-image-2.6-flare", "action" to "edit",
            "size" to "1536x864", "quality" to "max", "background" to "transparent", "output_format" to "webp",
            "output_compression" to "75", "partial_images" to "2", "moderation" to "low", "complexity" to "high",
        )
        assertEquals(expected, tool.mapValues { it.value.jsonPrimitive.content })
    }

    @Test
    fun `turning off the tool stops both tool and force settings but retains configuration`() {
        val enabled = Model(
            modelId = "chat",
            tools = setOf(BuiltInTools.ImageGeneration),
            imageGenerationOptions = ImageGenerationOptions(quality = "high", forceTool = true)
        )
        val disabled = enabled.copy(tools = emptySet())
        val body = request(disabled)
        assertFalse(body.containsKey("tools"))
        assertFalse(body.containsKey("tool_choice"))
        assertEquals(request(enabled), request(disabled.copy(tools = enabled.tools)))
    }

    @Test
    fun `non streaming requests omit partial images and PNG omits compression`() {
        val model = Model(
            tools = setOf(BuiltInTools.ImageGeneration),
            imageGenerationOptions = ImageGenerationOptions(partialImages = 3, outputCompression = 50)
        )
        val tool = request(model, stream = false)["tools"]!!.jsonArray.single().jsonObject
        assertFalse(tool.containsKey("partial_images"))
        assertFalse(tool.containsKey("output_compression"))
    }

    @Test
    fun `changing image model preserves explicit planning and fidelity choices`() {
        val changed = ImageGenerationOptions(model = "gpt-image-1.5", quality = "max", complexity = "high").normalized()
        assertEquals("auto", changed.quality)
        assertEquals("high", changed.complexity)
        assertTrue(changed.validationErrors().isEmpty())
        assertEquals(
            "high",
            changed.copy(inputFidelity = "high").toToolJson(true)["input_fidelity"]!!.jsonPrimitive.content
        )
        assertEquals(
            "high",
            changed.copy(model = "custom-image-model", inputFidelity = "high").normalized().inputFidelity
        )
    }

    @Test
    fun `explicit complexity and fidelity survive defaults custom models and settings reload`() {
        for (imageModel in listOf("gpt-image-2.5-sunburst", "custom-image-model")) {
            val options = ImageGenerationOptions(
                model = imageModel,
                complexity = "medium",
                inputFidelity = "high"
            ).normalized()
            val model =
                Model(modelId = "chat", tools = setOf(BuiltInTools.ImageGeneration), imageGenerationOptions = options)
            val restored = json.decodeFromString<Model>(json.encodeToString(model))
            val tool = request(restored)["tools"]!!.jsonArray.single().jsonObject
            assertEquals("medium", tool["complexity"]!!.jsonPrimitive.content)
            assertEquals("high", tool["input_fidelity"]!!.jsonPrimitive.content)
        }
        val defaults = ImageGenerationOptions().toToolJson(true)
        assertFalse(defaults.containsKey("complexity"))
        assertFalse(defaults.containsKey("input_fidelity"))
    }

    @Test
    fun `planning and fidelity still reject invalid enum values`() {
        assertEquals(
            setOf("complexity", "inputFidelity"),
            ImageGenerationOptions(complexity = "invalid", inputFidelity = "invalid").validationErrors().keys
        )
    }

    @Test
    fun `JPEG cannot keep a transparent background`() {
        val options = ImageGenerationOptions(outputFormat = "jpeg", background = "transparent")
        assertTrue("background" in options.validationErrors())
        assertThrows(IllegalArgumentException::class.java) { options.toToolJson(true) }
        assertEquals("auto", options.normalized().background)
    }

    @Test
    fun `custom dimensions enforce divisibility aspect ratio and pixel budget without overflow`() {
        listOf("1536x864", "3840x2160", "2160x3840", "1024x1024").forEach {
            assertTrue(it, ImageGenerationOptions(size = it).validationErrors().isEmpty())
        }
        listOf(
            "",
            "autoX",
            "1000x1000",
            "16x16",
            "3840x3840",
            "3840x640",
            "-16x1024",
            "999999999999999999999x1024"
        ).forEach {
            assertTrue(it, "size" in ImageGenerationOptions(size = it).validationErrors())
        }
        assertTrue("size" in ImageGenerationOptions(model = "gpt-image-1.5", size = "1536x864").validationErrors())
    }

    @Test
    fun `invalid options prevent saving only when image generation is enabled`() {
        val model =
            Model(
                imageGenerationOptions = ImageGenerationOptions(model = "", partialImages = 4, outputCompression = -1)
            )
        assertTrue(model.hasValidImageGenerationOptions())
        assertFalse(model.copy(tools = setOf(BuiltInTools.ImageGeneration)).hasValidImageGenerationOptions())
        assertEquals(
            setOf("model", "partialImages", "outputCompression"),
            model.imageGenerationOptions.validationErrors().keys
        )
    }

    @Test
    fun `settings only offer raster formats without mask or animation fields`() {
        val options = ImageGenerationOptions(model = "gpt-image-2.6-sunburst")
        assertEquals(listOf("png", "jpeg", "webp"), options.formats)
        val saved = json.parseToJsonElement(json.encodeToString(options)).jsonObject
        assertFalse(
            saved.keys.any { it.contains("mask", true) || it.contains("frame", true) || it.contains("svg", true) }
        )
    }

    private fun request(model: Model, stream: Boolean = true) = api.buildRequestBody(
        capabilities = ResponseProviderCapabilities(),
        messages = listOf(UIMessage.user("Draw a cat")),
        params = TextGenerationParams(model = model, sessionId = "image-options-test"),
        stream = stream,
    )
}
