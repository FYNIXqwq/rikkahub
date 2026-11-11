package me.rerere.ai.provider

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Per-chat-model settings for the Responses API image_generation tool. */
@Serializable
data class ImageGenerationOptions(
    val model: String = "gpt-image-2.5-sunburst",
    val action: String = "auto",
    val size: String = "auto",
    val quality: String = "auto",
    val background: String = "auto",
    val outputFormat: String = "png",
    val outputCompression: Int = 100,
    val partialImages: Int = 0,
    val moderation: String = "auto",
    val complexity: String? = null,
    val inputFidelity: String? = null,
    val forceTool: Boolean = false,
    val executionMode: ImageGenerationExecutionMode = ImageGenerationExecutionMode.AUTO,
) {
    val isImage2: Boolean get() = IMAGE_2_MODEL.matches(model.trim())
    val isImage25: Boolean get() = IMAGE_25_MODEL.matches(model.trim())
    val usesFixedComplexity: Boolean get() = isImage2 || isImage25
    val usesFixedInputFidelity: Boolean get() = isImage2 ||
        isImage25 || model.startsWith("gpt-image-2.6-")

    fun usesIndependentRequests(provider: ProviderSetting?): Boolean =
        provider is ProviderSetting.OpenAI && provider.useResponseApi &&
            executionMode == ImageGenerationExecutionMode.INDEPENDENT

    fun usesTwoStepRequests(provider: ProviderSetting?): Boolean =
        provider is ProviderSetting.OpenAI && provider.useResponseApi &&
            (executionMode == ImageGenerationExecutionMode.TWO_STEP ||
                (executionMode == ImageGenerationExecutionMode.AUTO && usesFixedComplexity))

    val supportsComplexity: Boolean get() = model.trim().startsWith("gpt-image-2.6-")
    val supportsExtendedQuality: Boolean get() = model.trim().startsWith("gpt-image-2.5-") || supportsComplexity
    val supportsFlexibleSize: Boolean get() = model.trim().startsWith("gpt-image-2")
    val supportsInputFidelity: Boolean get() = model.trim().startsWith("gpt-image-1.5") || isMini
    val isMini: Boolean get() = model.trim().startsWith("gpt-image-1-mini")
    val formats: List<String> get() = listOf("png", "jpeg", "webp")
    val qualities: List<String> get() = listOf("auto", "low", "medium", "high") +
        if (supportsExtendedQuality) listOf("xhigh", "max") else emptyList()
    val supportsTransparency: Boolean get() = outputFormat in listOf("png", "webp")
    val supportsCompression: Boolean get() = outputFormat in listOf("jpeg", "webp")

    /** Reconcile dependent selections when the image model or output format changes. */
    fun normalized(): ImageGenerationOptions {
        val format = outputFormat.takeIf { it in formats } ?: "png"
        return copy(
            outputFormat = format,
            quality = quality.takeIf { it in qualities } ?: "auto",
            background = if (background == "transparent" && format !in listOf("png", "webp")) "auto" else background,
        )
    }

    /** Errors are keyed by the corresponding form field, so invalid drafts can be shown inline. */
    fun validationErrors(): Map<String, String> = buildMap {
        val size = this@ImageGenerationOptions.size
        if (model.isBlank() || model.any(Char::isWhitespace)) put("model", "请输入不含空格的生图模型 ID")
        if (action !in listOf("auto", "generate", "edit")) put("action", "请选择有效的生成模式")
        if (quality !in qualities) put("quality", "当前生图模型不支持此画质")
        if (outputFormat !in formats) put("outputFormat", "当前生图模型不支持此输出格式")
        if (background !in listOf("auto", "opaque", "transparent") ||
            (background == "transparent" && !supportsTransparency)) {
            put("background", "透明背景需要 PNG 或 WebP")
        }
        if (size != "auto") {
            val match = Regex("^(\\d+)x(\\d+)$").matchEntire(size)
            val width = match?.groupValues?.get(1)?.toLongOrNull() ?: 0
            val height = match?.groupValues?.get(2)?.toLongOrNull() ?: 0
            if (!supportsFlexibleSize) {
                if (size !in STANDARD_SIZES) put("size", "当前模型仅支持自动、1024×1024、1536×1024、1024×1536")
            } else if (width !in 16..3840 || height !in 16..3840 || width % 16 != 0L || height % 16 != 0L ||
                width > height * 3 || height > width * 3 || width * height !in 655_360..8_294_400) {
                put("size", "宽高须为 16 的倍数、单边不超过 3840，比例在 1:3～3:1，总像素为 655360～8294400")
            }
        }
        if (outputCompression !in 0..100) put("outputCompression", "压缩设置须为 0～100")
        if (partialImages !in 0..3) put("partialImages", "预览图数量须为 0～3")
        if (moderation !in listOf("auto", "low")) put("moderation", "请选择有效的审核等级")
        // These optional tool fields can also be implemented by custom model IDs and gateways.
        // Preserve explicit choices; the endpoint decides model-specific availability.
        if (complexity != null && complexity !in EFFORTS) put("complexity", "请选择有效的规划复杂度")
        if (inputFidelity != null && inputFidelity !in listOf("low", "high")) put("inputFidelity", "请选择有效的输入保真度")
    }

    fun toToolJson(stream: Boolean): JsonObject {
        require(validationErrors().isEmpty()) { validationErrors().values.first() }
        return buildJsonObject {
            put("type", "image_generation")
            put("model", model)
            if (action != "auto") put("action", action)
            if (size != "auto") put("size", size)
            if (quality != "auto") put("quality", quality)
            if (background != "auto") put("background", background)
            if (outputFormat != "png") put("output_format", outputFormat)
            if (supportsCompression && outputCompression != 100) put("output_compression", outputCompression)
            if (stream && partialImages > 0) put("partial_images", partialImages)
            if (moderation != "auto") put("moderation", moderation)
            if (!usesFixedComplexity) complexity?.let { put("complexity", it) }
            inputFidelity?.let { put("input_fidelity", it) }
        }
    }

    /** Images API parameters are deliberately built from a whitelist, never from chat request bodies. */
    fun toImageApiParameters(): List<CustomBody> {
        require(validationErrors().isEmpty()) { validationErrors().values.first() }
        val fields = buildJsonObject {
            if (quality != "auto") put("quality", quality)
            if (background != "auto") put("background", background)
            put("output_format", outputFormat)
            if (supportsCompression) put("output_compression", outputCompression)
            if (moderation != "auto") put("moderation", moderation)
            if (!usesFixedComplexity) complexity?.let { put("complexity", it) }
            if (!usesFixedInputFidelity) inputFidelity?.let { put("input_fidelity", it) }
        }
        return fields.map { (key, value) -> CustomBody(key, value) }
    }

    companion object {
        private val IMAGE_2_MODEL = Regex("^gpt-image-2(-\\d{4}-\\d{2}-\\d{2})?$")
        private val IMAGE_25_MODEL = Regex("^gpt-image-2\\.5-(sunburst|flare)(-\\d{4}-\\d{2}-\\d{2})?$")
        val STANDARD_SIZES = listOf("1024x1024", "1536x1024", "1024x1536")
        val EFFORTS = listOf("low", "medium", "high")
        val MODELS = listOf(
            "gpt-image-2.5-sunburst",
            "gpt-image-2.5-flare",
            "gpt-image-2.6-sunburst",
            "gpt-image-2.6-flare",
            "gpt-image-2",
            "gpt-image-1.5",
            "gpt-image-1-mini",
            "chatgpt-image-latest",
        )
    }
}

@Serializable
enum class ImageGenerationExecutionMode {
    @SerialName("auto")
    AUTO,

    @SerialName("two_step")
    TWO_STEP,

    @SerialName("hosted")
    HOSTED,

    @SerialName("independent")
    INDEPENDENT,
}

fun Model.hasValidImageGenerationOptions(): Boolean =
    BuiltInTools.ImageGeneration !in tools || imageGenerationOptions.validationErrors().isEmpty()
