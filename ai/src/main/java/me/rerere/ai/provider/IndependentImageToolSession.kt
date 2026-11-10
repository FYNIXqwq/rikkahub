package me.rerere.ai.provider

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import java.util.UUID

data class ImageGenerationPlan(val prompt: String, val action: String, val referenceImages: List<String>)

/** One chat run's client-side image tool. Images are referenced by opaque IDs, not model-supplied paths. */
class IndependentImageToolSession(
    private val options: ImageGenerationOptions,
    initialMessages: List<UIMessage>,
    private val generate: suspend (ImageGenerationPlan) -> List<UIMessagePart>,
) {
    private val references = linkedMapOf<String, String>()
    private var attempted = false

    init {
        observe(initialMessages)
    }

    val tool = Tool(
        name = NAME,
        description = "Generate or edit an image. Write a detailed, self-contained visual prompt. " +
            "For edits or reference-guided generation, use reference_images IDs from image labels in the conversation. " +
            "Never invent IDs or supply paths/URLs. The resulting image is shown to the user automatically.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("prompt", buildJsonObject {
                        put("type", "string")
                        put("minLength", 1)
                        put("maxLength", 32000)
                        put("description", "Complete visual instructions, including details to preserve for edits.")
                    })
                    put("action", buildJsonObject {
                        put("type", "string")
                        put("enum", JsonArray(listOf("generate", "edit").map(::JsonPrimitive)))
                    })
                    put("reference_images", buildJsonObject {
                        put("type", "array")
                        put("maxItems", 16)
                        put("items", buildJsonObject { put("type", "string") })
                        put("description", "Reference image IDs in desired order. Required when editing an existing image.")
                    })
                },
                required = listOf("prompt", "action"),
            )
        },
        systemPrompt = { _, _ ->
            "Use $NAME for image requests. Reference images carry [reference_image: ID] labels. " +
                "These IDs are internal; do not repeat them to the user. " +
                "Configured image action: ${options.action}. " +
                "After a successful call, acknowledge the result without generating it again unless the user requested another image."
        },
        execute = { input ->
            attempted = true
            generate(parsePlan(input))
        },
    )

    fun observe(messages: List<UIMessage>) {
        messages.forEach { register(it.parts) }
        if (messages.lastOrNull()?.role == MessageRole.ASSISTANT &&
            messages.last().getTools().any { it.toolName == NAME && it.isExecuted }) attempted = true
    }

    /** Only outgoing copies gain labels; conversation history and UI parts remain unchanged. */
    fun labelReferences(messages: List<UIMessage>): List<UIMessage> = messages.map {
        it.copy(parts = labelParts(it.parts))
    }

    fun prepareParams(params: TextGenerationParams): TextGenerationParams {
        val customChoice = params.customBody.lastOrNull { it.key == "tool_choice" }?.value as? JsonObject
        val forceImage = options.forceTool || customChoice?.get("type") == JsonPrimitive("image_generation") ||
            (customChoice?.get("type") == JsonPrimitive("function") && customChoice["name"] == JsonPrimitive(NAME))
        val choice = if (!attempted) buildJsonObject {
            put("type", "function")
            put("name", NAME)
        } else JsonPrimitive("auto")
        return params.copy(
            model = params.model.copy(
                tools = params.model.tools - BuiltInTools.ImageGeneration,
                abilities = (params.model.abilities + ModelAbility.TOOL).distinct(),
            ),
            tools = params.tools.filterNot { it.name == NAME } + tool,
            customBody = if (forceImage) {
                params.customBody.filterNot { it.key == "tool_choice" } + CustomBody("tool_choice", choice)
            } else params.customBody,
        )
    }

    private fun parsePlan(input: JsonElement): ImageGenerationPlan {
        val obj = input.jsonObject
        val prompt = (obj["prompt"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        require(prompt.isNotBlank() && prompt.length <= 32000) { "Image prompt must contain 1 to 32000 characters" }
        val requestedAction = (obj["action"] as? JsonPrimitive)?.contentOrNull ?: "generate"
        val action = options.action.takeUnless { it == "auto" } ?: requestedAction
        require(action in listOf("generate", "edit")) { "Image action must be generate or edit" }
        val ids = when (val value = obj["reference_images"]) {
            null, JsonNull -> emptyList()
            is JsonArray -> value.map {
                (it as? JsonPrimitive)?.contentOrNull ?: error("Reference image IDs must be strings")
            }.distinct()
            else -> error("reference_images must be an array of IDs")
        }
        require(ids.size <= 16) { "At most 16 reference images are supported" }
        val images = ids.map { references[it] ?: error("Unknown reference image ID; use an ID from the conversation") }
        require(action != "edit" || images.isNotEmpty()) { "Editing requires at least one reference image" }
        return ImageGenerationPlan(prompt, action, images)
    }

    private fun register(parts: List<UIMessagePart>) {
        parts.forEach {
            when (it) {
                is UIMessagePart.Image -> references[referenceId(it.url)] = it.url
                is UIMessagePart.Tool -> register(it.output)
                else -> Unit
            }
        }
    }

    private fun labelParts(parts: List<UIMessagePart>): List<UIMessagePart> = parts.flatMap { part ->
        when (part) {
            is UIMessagePart.Image -> {
                val id = referenceId(part.url)
                references[id] = part.url
                listOf(UIMessagePart.Text("[reference_image: $id]"), part)
            }
            is UIMessagePart.Tool -> listOf(part.copy(output = labelParts(part.output)))
            else -> listOf(part)
        }
    }

    companion object {
        const val NAME = "rikkahub_generate_image"
        private fun referenceId(url: String) = "image_${UUID.nameUUIDFromBytes(url.encodeToByteArray())}"
    }
}
