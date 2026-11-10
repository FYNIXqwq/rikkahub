package me.rerere.ai.provider.providers.openai

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.provider.ImageEditParams
import me.rerere.ai.provider.ImageGenerationOptions
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.util.json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * OpenRouter 没有 /images/edits，图片编辑要走图像生成接口的 input_references (#1993)
 */
class OpenAIProviderImageEditTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private var captured: Request? = null

    private val provider = OpenAIProvider(
        OkHttpClient.Builder()
            .addInterceptor { chain ->
                captured = chain.request()
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body("""{"data":[{"b64_json":"AAAA"}]}""".toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()
    )

    private fun edit(baseUrl: String, size: String = "auto"): Request {
        val image = tempFolder.newFile("ref.png").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val items = runBlocking {
            provider.editImage(
                providerSetting = ProviderSetting.OpenAI(baseUrl = baseUrl, apiKey = "key"),
                params = ImageEditParams(
                    model = Model(modelId = "bytedance-seed/seedream-4.5"),
                    prompt = "make it watercolor",
                    images = listOf(image.absolutePath),
                    size = size,
                ),
            ).toList()
        }
        assertEquals(listOf("AAAA"), items.map { it.data })
        return captured!!
    }

    @Test
    fun `openrouter sends reference images as input_references to the generation endpoint`() {
        val request = edit("https://openrouter.ai/api/v1", size = "2048x2048")

        assertEquals("https://openrouter.ai/api/v1/images/generations", request.url.toString())
        val body = json.parseToJsonElement(Buffer().also { request.body!!.writeTo(it) }.readUtf8()).jsonObject
        assertEquals("bytedance-seed/seedream-4.5", body["model"]!!.jsonPrimitive.content)
        assertEquals("make it watercolor", body["prompt"]!!.jsonPrimitive.content)
        assertEquals("2048x2048", body["size"]!!.jsonPrimitive.content)
        val reference = body["input_references"]!!.jsonArray.single().jsonObject
        assertEquals("image_url", reference["type"]!!.jsonPrimitive.content)
        assertEquals(
            "data:image/png;base64,AQID",
            reference["image_url"]!!.jsonObject["url"]!!.jsonPrimitive.content
        )
    }

    @Test
    fun `openrouter omits auto size`() {
        val request = edit("https://openrouter.ai/api/v1")

        val body = json.parseToJsonElement(Buffer().also { request.body!!.writeTo(it) }.readUtf8()).jsonObject
        assertFalse("size" in body)
    }

    @Test
    fun `other hosts keep using multipart images edits`() {
        val request = edit("https://api.openai.com/v1")

        assertEquals("https://api.openai.com/v1/images/edits", request.url.toString())
        assertTrue(request.body is MultipartBody)
    }

    @Test
    fun `conversation file URI is accepted for multipart editing`() = runBlocking {
        val file = tempFolder.newFile("reference photo.png").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        provider.editImage(setting(), ImageEditParams(
            model = Model(modelId = "gpt-image-2.5-sunburst"),
            prompt = "change the background",
            images = listOf(file.toURI().toString()),
            customBody = ImageGenerationOptions(complexity = "high", quality = "high").toImageApiParameters(),
        )).toList()
        val body = captured!!.body as MultipartBody
        val raw = Buffer().also { body.writeTo(it) }.readUtf8()
        assertTrue(raw.contains("reference photo.png"))
        assertTrue(raw.contains("name=\"quality\""))
        assertFalse(raw.contains("name=\"complexity\""))
        assertFalse(raw.contains("name=\"reasoning\""))
    }

    @Test
    fun `remote and inline references use the documented JSON images array`() = runBlocking {
        val references = listOf("https://example.com/reference.png", "data:image/png;base64,AQID")
        val result = provider.editImage(setting(), ImageEditParams(
            model = Model(modelId = "gpt-image-2.5-sunburst"),
            prompt = "combine the references",
            images = references,
            customBody = ImageGenerationOptions(outputFormat = "webp", complexity = "high").toImageApiParameters(),
        )).toList()
        val request = captured!!
        assertEquals("https://api.openai.com/v1/images/edits", request.url.toString())
        val body = json.parseToJsonElement(Buffer().also { request.body!!.writeTo(it) }.readUtf8()).jsonObject
        assertEquals(references, body["images"]!!.jsonArray.map { it.jsonObject["image_url"]!!.jsonPrimitive.content })
        assertFalse("complexity" in body)
        assertFalse("reasoning" in body)
        assertEquals("image/webp", result.single().mimeType)
    }

    private fun setting() = ProviderSetting.OpenAI(baseUrl = "https://api.openai.com/v1", apiKey = "test-key")
}
