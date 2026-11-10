package me.rerere.rikkahub.ui.components.message.tools

import androidx.compose.runtime.Composable
import me.rerere.ai.provider.IndependentImageToolSession
import me.rerere.ai.ui.UIMessagePart
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Image02

object ImageGenerationToolUI : ToolUIRenderer {
    override val toolName = IndependentImageToolSession.NAME
    override fun icon(context: ToolUIContext) = HugeIcons.Image02

    @Composable
    override fun title(context: ToolUIContext): String = when {
        context.tool.output.any { it is UIMessagePart.Image } -> "图像已生成"
        context.tool.isExecuted -> "图像生成结果"
        context.tool.startedAt != null -> "正在生成图像"
        else -> "准备生成图像"
    }
}
