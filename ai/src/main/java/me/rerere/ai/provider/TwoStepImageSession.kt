package me.rerere.ai.provider

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.agent.StepRequest
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.util.json

enum class ImageGenerationPhase { PLAN, RENDER }

/** Both stages use Responses; only RENDER has the native image_generation tool. */
class TwoStepImageSession(private val options: ImageGenerationOptions) {
    private var phase = ImageGenerationPhase.PLAN

    fun prepareStep(step: StepRequest): StepRequest {
        phase = if (step.messages.lastOrNull()?.let(::shouldContinue) == true) {
            ImageGenerationPhase.RENDER
        } else ImageGenerationPhase.PLAN
        val instruction = if (phase == ImageGenerationPhase.PLAN) {
            "Decide whether the user's current request calls for generating or editing an image. " +
                "Return the required JSON object. For an image request, set generate_image=true and text to a " +
                "self-contained visual plan, including composition, exact text, and reference images/details to preserve. " +
                "Do not claim the image is already generated. For ordinary chat, questions, clarification, or refusals, " +
                "set generate_image=false and text to your normal answer. Follow applicable safety rules. " +
                "Use the user's language. Image action preference: ${options.action}. " +
                if (options.forceTool) "The user enabled image generation for this turn; plan an image unless clarification or refusal is necessary." else ""
        } else {
            "Generate or edit the image now using the native image_generation tool and the visual plan in the " +
                "last assistant message. Use the conversation's reference images when relevant. " +
                "Do not return the planning JSON or repeat the plan."
        }
        val messages = step.messages.toMutableList()
        val systemIndex = messages.indexOfFirst { it.role == MessageRole.SYSTEM }
        if (systemIndex >= 0) {
            val system = messages[systemIndex]
            messages[systemIndex] = system.copy(parts = system.parts + UIMessagePart.Text(instruction))
        } else messages.add(0, UIMessage.system(instruction))
        val model = step.params.model.copy(tools = if (phase == ImageGenerationPhase.PLAN) {
            step.params.model.tools - BuiltInTools.ImageGeneration
        } else setOf(BuiltInTools.ImageGeneration))
        return StepRequest(messages, step.params.copy(
            model = model,
            reasoningLevel = if (phase == ImageGenerationPhase.RENDER) ReasoningLevel.AUTO else step.params.reasoningLevel,
            imageGenerationPhase = phase,
        ))
    }

    /** Keep reasoning visible while withholding the planner's transport JSON from chat and persistence. */
    fun previewStep(message: UIMessage, newPartsFrom: Int): UIMessage = if (phase == ImageGenerationPhase.PLAN) {
        message.copy(parts = message.parts.take(newPartsFrom) +
            message.parts.drop(newPartsFrom).filterNot { it is UIMessagePart.Text })
    } else message

    fun finishStep(message: UIMessage, newPartsFrom: Int): UIMessage {
        if (phase == ImageGenerationPhase.RENDER) {
            return message.copy(parts = message.parts.map { part ->
                if (part is UIMessagePart.Text && part.metadata?.get(PLAN_STATE) == JsonPrimitive("ready")) {
                    part.copy(metadata = JsonObject(part.metadata.orEmpty() + (PLAN_STATE to JsonPrimitive("completed"))))
                } else part
            })
        }
        val added = message.parts.drop(newPartsFrom)
        // Other enabled tools can gather context before the planner supplies its final structured answer.
        if (added.any { it is UIMessagePart.Tool }) return message
        val raw = added.filterIsInstance<UIMessagePart.Text>().joinToString("") { it.text }.trim()
        val result = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull()
        if (result == null) {
            // Refusal text can arrive outside the requested schema. Never turn it into an image request.
            check(raw.isNotBlank() && !raw.startsWith("{")) { "图像规划未完整返回，请重试；尚未开始生图。" }
            return message
        }
        val generate = (result["generate_image"] as? JsonPrimitive)?.booleanOrNull
            ?: error("图像规划缺少生成决定；尚未开始生图。")
        val text = (result["text"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
            ?.takeIf { it.isNotBlank() } ?: error("图像规划内容为空；尚未开始生图。")
        return message.copy(parts = message.parts.take(newPartsFrom) +
            added.filterNot { it is UIMessagePart.Text } + UIMessagePart.Text(text,
                if (generate) buildJsonObject { put(PLAN_STATE, "ready") } else null))
    }

    fun shouldContinue(message: UIMessage): Boolean {
        if (message.role != MessageRole.ASSISTANT) return false
        val planIndex = message.parts.indexOfLast {
            it is UIMessagePart.Text && it.metadata?.get(PLAN_STATE) == JsonPrimitive("ready")
        }
        // If cancellation followed an image, do not automatically generate it again when resuming.
        return planIndex >= 0 && message.parts.drop(planIndex + 1).none { it is UIMessagePart.Image && it.url.isNotBlank() }
    }

    companion object {
        private const val PLAN_STATE = "rikkahub_image_plan_state"

        val planningFormat: JsonObject = buildJsonObject {
            put("type", "json_schema")
            put("name", "image_generation_plan")
            put("strict", true)
            put("schema", buildJsonObject {
                put("type", "object")
                put("additionalProperties", false)
                put("properties", buildJsonObject {
                    put("generate_image", buildJsonObject { put("type", "boolean") })
                    put("text", buildJsonObject { put("type", "string") })
                })
                put("required", buildJsonArray { add(JsonPrimitive("generate_image")); add(JsonPrimitive("text")) })
            })
        }
    }
}
