package me.rerere.ai.provider.providers.openai

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import me.rerere.ai.provider.EmbeddingGenerationParams
import me.rerere.ai.provider.EmbeddingGenerationResult
import me.rerere.ai.provider.ImageEditParams
import me.rerere.ai.provider.ImageGenerationParams
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderQuota
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.TextGenerationResult
import me.rerere.ai.provider.runQuotaQuery
import me.rerere.ai.ui.ImageGenerationItem
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.util.KeyRoulette
import me.rerere.ai.util.configureReferHeaders
import me.rerere.ai.util.json
import me.rerere.ai.util.mergeCustomBody
import me.rerere.ai.util.mergeCustomHeaders
import me.rerere.common.http.await
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.net.URI
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

private const val TAG = "OpenAIProvider"

class OpenAIProvider(
    private val client: OkHttpClient,
    context: Context? = null
) : Provider<ProviderSetting.OpenAI> {
    private val keyRoulette = if (context != null) KeyRoulette.lru(context) else KeyRoulette.default()

    private val chatCompletionsAPI = ChatCompletionsAPI(client = client, keyRoulette = keyRoulette)
    private val responseAPI = ResponseAPI(client = client, keyRoulette = keyRoulette)

    override suspend fun listModels(providerSetting: ProviderSetting.OpenAI): List<Model> =
        withContext(Dispatchers.IO) {
            val key = keyRoulette.next(providerSetting.apiKey, providerSetting.id.toString())
            val request = Request.Builder()
                .url("${providerSetting.baseUrl}/models")
                .headers(providerSetting.mergeCustomHeaders())
                .addHeader("Authorization", "Bearer $key")
                .get()
                .build()

            val response = client.newCall(request).await()
            if (!response.isSuccessful) {
                error("Failed to get models: ${response.code} ${response.body?.string()}")
            }

            val bodyStr = response.body?.string() ?: ""
            val bodyJson = json.parseToJsonElement(bodyStr).jsonObject
            val data = bodyJson["data"]?.jsonArray ?: return@withContext emptyList()

            data.mapNotNull { modelJson ->
                val modelObj = modelJson.jsonObject
                val id = modelObj["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null

                Model(
                    modelId = id,
                    displayName = id,
                )
            }
        }

    override suspend fun getQuota(providerSetting: ProviderSetting.OpenAI): ProviderQuota? =
        client.runQuotaQuery(providerSetting) {
            keyRoulette.next(providerSetting.apiKey, providerSetting.id.toString())
        }

    override suspend fun streamText(
        providerSetting: ProviderSetting.OpenAI,
        messages: List<UIMessage>,
        params: TextGenerationParams
    ): Flow<StreamChunk> = if (providerSetting.useResponseApi) {
        responseAPI.streamText(
            providerSetting = providerSetting,
            messages = messages,
            params = params
        )
    } else {
        chatCompletionsAPI.streamText(
            providerSetting = providerSetting,
            messages = messages,
            params = params
        )
    }

    override suspend fun generateText(
        providerSetting: ProviderSetting.OpenAI,
        messages: List<UIMessage>,
        params: TextGenerationParams
    ): TextGenerationResult = if (providerSetting.useResponseApi) {
        responseAPI.generateText(
            providerSetting = providerSetting,
            messages = messages,
            params = params
        )
    } else {
        chatCompletionsAPI.generateText(
            providerSetting = providerSetting,
            messages = messages,
            params = params
        )
    }

    override suspend fun generateEmbedding(
        providerSetting: ProviderSetting.OpenAI,
        params: EmbeddingGenerationParams
    ): EmbeddingGenerationResult = withContext(Dispatchers.IO) {
        require(params.input.isNotEmpty()) { "Embedding input cannot be empty" }

        val key = keyRoulette.next(providerSetting.apiKey, providerSetting.id.toString())
        val requestBody = json.encodeToString(
            buildJsonObject {
                put("model", params.model.modelId)
                if (params.input.size == 1) {
                    put("input", params.input.first())
                } else {
                    putJsonArray("input") {
                        params.input.forEach { add(JsonPrimitive(it)) }
                    }
                }
                params.dimensions?.let { put("dimensions", it) }
            }.mergeCustomBody(params.customBody)
        )

        val request = Request.Builder()
            .url("${providerSetting.baseUrl}/embeddings")
            .headers(providerSetting.mergeCustomHeaders(params.customHeaders))
            .addHeader("Authorization", "Bearer $key")
            .addHeader("Content-Type", "application/json")
            .post(requestBody.toRequestBody("application/json".toMediaType()))
            .build()

        val response = client.newCall(request).await()
        if (!response.isSuccessful) {
            error("Failed to generate embedding: ${response.code} ${response.body?.string()}")
        }

        val bodyStr = response.body?.string() ?: ""
        val bodyJson = json.parseToJsonElement(bodyStr).jsonObject
        val data = bodyJson["data"]?.jsonArray ?: error("No data in response")
        val model = bodyJson["model"]?.jsonPrimitive?.contentOrNull ?: params.model.modelId

        val embeddings = data.map { embeddingJson ->
            val embeddingArray = embeddingJson.jsonObject["embedding"]?.jsonArray
                ?: error("No embedding in response")
            embeddingArray.map { it.jsonPrimitive.content.toFloat() }
        }

        EmbeddingGenerationResult(
            model = model,
            embeddings = embeddings
        )
    }

    override suspend fun generateImage(
        providerSetting: ProviderSetting,
        params: ImageGenerationParams
    ): Flow<ImageGenerationItem> = flow {
        require(providerSetting is ProviderSetting.OpenAI) {
            "Expected OpenAI provider setting"
        }

        val key = keyRoulette.next(providerSetting.apiKey, providerSetting.id.toString())
        val request = buildImageGenerationRequest(providerSetting, params, key)
        val items = withContext(Dispatchers.IO) {
            client.newCall(request).await().use { response ->
                if (!response.isSuccessful) error("Failed to generate image: ${response.code} ${response.body.string()}")
                parseImageResponse(response.body.string(), params.customBody.imageOutputFormat())
            }
        }
        items.forEach { emit(it) }
    }

    internal fun buildImageGenerationRequest(
        providerSetting: ProviderSetting.OpenAI,
        params: ImageGenerationParams,
        key: String,
    ): Request {
        val requestBody = json.encodeToString(
            buildJsonObject {
                put("model", params.model.modelId)
                put("prompt", params.prompt)
                put("n", params.numOfImages)

                // 只匹配 x.ai 本身及其子域名，避免 "xxx-max.ai" 之类的中转域名被误判
                val host = providerSetting.baseUrl.toHttpUrlOrNull()?.host?.lowercase()
                val isGrok = host == "x.ai" || host?.endsWith(".x.ai") == true ||
                    params.model.modelId.contains("grok", ignoreCase = true)

                if (params.size.isNotBlank() && !params.size.equals("auto", ignoreCase = true) && !isGrok) {
                    put("size", params.size)
                }
            }
                .mergeCustomBody(params.customBody)
        )

        return Request.Builder()
            .url("${providerSetting.baseUrl.trimEnd('/')}/images/generations")
            .headers(providerSetting.mergeCustomHeaders(params.customHeaders))
            .addHeader("Authorization", "Bearer $key")
            .addHeader("Content-Type", "application/json")
            .post(requestBody.toRequestBody("application/json".toMediaType()))
            .configureReferHeaders(providerSetting.baseUrl)
            .build()

    }

    override suspend fun editImage(
        providerSetting: ProviderSetting,
        params: ImageEditParams
    ): Flow<ImageGenerationItem> = flow {
        require(providerSetting is ProviderSetting.OpenAI) {
            "Expected OpenAI provider setting"
        }
        require(params.images.isNotEmpty()) {
            "At least one image is required"
        }

        val key = keyRoulette.next(providerSetting.apiKey, providerSetting.id.toString())

        // OpenRouter 没有 /images/edits，参考图通过图像生成接口的 input_references 传入
        if (providerSetting.baseUrl.toHttpUrlOrNull()?.host?.lowercase() == "openrouter.ai") {
            editImageWithInputReferences(providerSetting, params, key).forEach { emit(it) }
            return@flow
        }

        // Conversation references can be file URIs, remote images, or inline images.
        // Keep multipart for local files; the official JSON edits API accepts URL/data references.
        if (params.images.any { it.isImageUrlReference() }) {
            editImageWithJsonReferences(providerSetting, params, key).forEach { emit(it) }
            return@flow
        }

        val bodyBuilder = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("model", params.model.modelId)
            .addFormDataPart("prompt", params.prompt)
            .addFormDataPart("n", params.numOfImages.toString())
        if (params.size.isNotBlank()) {
            bodyBuilder.addFormDataPart("size", params.size)
        }

        val imageFieldName = if (params.images.size == 1) "image" else "image[]"
        params.images.forEach { path ->
            val imageFile = localImageFile(path)
            require(imageFile.exists()) {
                "Image file does not exist: $path"
            }
            require(imageFile.extension.lowercase() in SUPPORTED_EDIT_IMAGE_EXTENSIONS) {
                "Unsupported image file type for OpenAI edit: ${imageFile.extension}"
            }
            bodyBuilder.addFormDataPart(
                imageFieldName,
                imageFile.name,
                imageFile.asRequestBody(imageFile.imageMediaType().toMediaType())
            )
        }

        params.customBody.forEach { customBody ->
            val value = when (val element = customBody.value) {
                is JsonPrimitive -> element.contentOrNull ?: element.toString()
                else -> element.toString()
            }
            bodyBuilder.addFormDataPart(customBody.key, value)
        }

        val request = Request.Builder()
            .url("${providerSetting.baseUrl}/images/edits")
            .headers(providerSetting.mergeCustomHeaders(params.customHeaders))
            .addHeader("Authorization", "Bearer $key")
            .post(bodyBuilder.build())
            .configureReferHeaders(providerSetting.baseUrl)
            .build()

        val items = withContext(Dispatchers.IO) {
            val response = client.newCall(request).await()
            if (!response.isSuccessful) {
                error("Failed to edit image: ${response.code} ${response.body?.string()}")
            }
            parseImageResponse(response.body.string(), params.customBody.imageOutputFormat())
        }

        items.forEach { emit(it) }
    }

    @OptIn(ExperimentalEncodingApi::class)
    private suspend fun editImageWithInputReferences(
        providerSetting: ProviderSetting.OpenAI,
        params: ImageEditParams,
        key: String,
    ): List<ImageGenerationItem> = withContext(Dispatchers.IO) {
        val requestBody = json.encodeToString(
            buildJsonObject {
                put("model", params.model.modelId)
                put("prompt", params.prompt)
                put("n", params.numOfImages)
                if (params.size.isNotBlank() && !params.size.equals("auto", ignoreCase = true)) {
                    put("size", params.size)
                }
                putJsonArray("input_references") {
                    params.images.forEach { path ->
                        addJsonObject {
                            put("type", "image_url")
                            putJsonObject("image_url") {
                                put(
                                    "url",
                                    imageReferenceUrl(path)
                                )
                            }
                        }
                    }
                }
            }
                .mergeCustomBody(params.customBody)
        )

        val request = Request.Builder()
            .url("${providerSetting.baseUrl}/images/generations")
            .headers(providerSetting.mergeCustomHeaders(params.customHeaders))
            .addHeader("Authorization", "Bearer $key")
            .addHeader("Content-Type", "application/json")
            .post(requestBody.toRequestBody("application/json".toMediaType()))
            .configureReferHeaders(providerSetting.baseUrl)
            .build()

        val response = client.newCall(request).await()
        if (!response.isSuccessful) {
            error("Failed to edit image: ${response.code} ${response.body?.string()}")
        }
        parseImageResponse(response.body.string(), params.customBody.imageOutputFormat())
    }

    private suspend fun editImageWithJsonReferences(
        setting: ProviderSetting.OpenAI,
        params: ImageEditParams,
        key: String,
    ): List<ImageGenerationItem> = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            params.customBody.forEach { put(it.key, it.value) }
            put("model", params.model.modelId)
            put("prompt", params.prompt)
            put("n", params.numOfImages)
            if (params.size.isNotBlank()) put("size", params.size)
            putJsonArray("images") {
                params.images.forEach { reference ->
                    addJsonObject { put("image_url", imageReferenceUrl(reference)) }
                }
            }
        }
        val request = Request.Builder()
            .url("${setting.baseUrl.trimEnd('/')}/images/edits")
            .headers(setting.mergeCustomHeaders(params.customHeaders))
            .addHeader("Authorization", "Bearer $key")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .configureReferHeaders(setting.baseUrl)
            .build()
        client.newCall(request).await().use { response ->
            if (!response.isSuccessful) error("Failed to edit image: ${response.code} ${response.body.string()}")
            parseImageResponse(response.body.string(), params.customBody.imageOutputFormat())
        }
    }

    private fun String.isImageUrlReference() = startsWith("https://") || startsWith("http://") || startsWith("data:image/")

    private fun localImageFile(path: String): File = if (path.startsWith("file:")) File(URI(path)) else File(path)

    private fun imageReferenceUrl(reference: String): String {
        if (reference.isImageUrlReference()) return reference
        val file = localImageFile(reference)
        require(file.isFile) { "Image file does not exist: $reference" }
        require(file.extension.lowercase() in SUPPORTED_EDIT_IMAGE_EXTENSIONS) { "Unsupported image file type: ${file.extension}" }
        return "data:${file.imageMediaType()};base64,${Base64.encode(file.readBytes())}"
    }

    private fun List<me.rerere.ai.provider.CustomBody>.imageOutputFormat(): String =
        (lastOrNull { it.key == "output_format" }?.value as? JsonPrimitive)?.contentOrNull ?: "png"

    internal suspend fun parseImageResponse(bodyStr: String, fallbackFormat: String = "png"): List<ImageGenerationItem> {
        val body = json.parseToJsonElement(bodyStr).jsonObject
        val defaultFormat = body["output_format"]?.jsonPrimitive?.contentOrNull ?: fallbackFormat
        val data = body["data"]?.jsonArray ?: error("No data in image response")
        return data.map { element ->
            val obj = element.jsonObject
            val b64Json = obj["b64_json"]?.jsonPrimitive?.contentOrNull
            if (b64Json != null) {
                val outputFormat = obj["output_format"]?.jsonPrimitive?.contentOrNull ?: defaultFormat
                ImageGenerationItem(
                    data = b64Json,
                    mimeType = outputFormat.toImageMimeType(),
                )
            } else {
                val url = obj["url"]?.jsonPrimitive?.contentOrNull
                    ?: error("No b64_json or url in image response")
                downloadImageAsBase64(url)
            }
        }
    }

    @OptIn(ExperimentalEncodingApi::class)
    private suspend fun downloadImageAsBase64(url: String): ImageGenerationItem {
        val request = Request.Builder()
            .url(url)
            .get()
            .build()

        val response = client.newCall(request).await()
        if (!response.isSuccessful) {
            error("Failed to download generated image: ${response.code} ${response.body.string()}")
        }

        val body = response.body
        val mimeType = body.contentType()?.toString() ?: "image/png"
        val base64 = Base64.encode(body.bytes())

        return ImageGenerationItem(
            data = base64,
            mimeType = mimeType
        )
    }

    private fun File.imageMediaType(): String = when (extension.lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        else -> "image/png"
    }

    private fun String.toImageMimeType(): String = when (lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        else -> "image/png"
    }

    companion object {
        private val SUPPORTED_EDIT_IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "webp")
    }
}
