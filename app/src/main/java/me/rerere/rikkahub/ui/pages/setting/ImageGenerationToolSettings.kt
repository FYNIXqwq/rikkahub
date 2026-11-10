package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenu
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import me.rerere.ai.provider.ImageGenerationOptions
import me.rerere.ai.provider.ImageGenerationExecutionMode
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.CardGroupScope
import me.rerere.rikkahub.ui.components.ui.CardGroupValue
import me.rerere.rikkahub.ui.components.ui.switchItem
import kotlin.math.roundToInt

@Composable
internal fun ImageGenerationToolSettings(
    options: ImageGenerationOptions,
    onChange: (ImageGenerationOptions) -> Unit,
    providerSetting: ProviderSetting? = null,
) {
    val errors = remember(options) { options.validationErrors() }
    val independent = options.usesIndependentRequests(providerSetting)
    val independent25 = independent && options.isImage25
    var customModel by rememberSaveable { mutableStateOf(options.model !in ImageGenerationOptions.MODELS) }
    var customSize by rememberSaveable {
        mutableStateOf(options.size != "auto" && options.size !in ImageGenerationOptions.STANDARD_SIZES)
    }
    var advanced by rememberSaveable { mutableStateOf(false) }
    fun update(value: ImageGenerationOptions) = onChange(value.normalized())

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(
            "这些设置仅用于当前模型。关闭开关后会保留配置。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (errors.isNotEmpty()) {
            Text(errors.values.joinToString("\n"), color = MaterialTheme.colorScheme.error)
        }
        CardGroup(title = { Text("生成设置") }) {
            formItem {
                ImageOptionChoice(
                    "生图模型", if (customModel) "custom" else options.model,
                    ImageGenerationOptions.MODELS.map { it to it } + ("custom" to "自定义模型 ID"),
                ) { value ->
                    customModel = value == "custom"
                    if (!customModel) {
                        var changed = options.copy(model = value).normalized()
                        if (!changed.supportsFlexibleSize && changed.size !in ImageGenerationOptions.STANDARD_SIZES) {
                            changed = changed.copy(size = "auto")
                            customSize = false
                        }
                        onChange(changed)
                    }
                }
                if (customModel) {
                    ImageOptionText("生图模型 ID", options.model, errors["model"]) {
                        update(options.copy(model = it.trim()))
                    }
                }
                ImageOptionHint("这里选择实际绘图的 GPT Image 模型；聊天模型保持当前选择。")
            }
            formItem {
                ImageOptionChoice("生图调用方式", options.executionMode.name, listOf(
                    "AUTO" to "自动 · 2.5 优先独立调用",
                    "INDEPENDENT" to "独立调用 · 保留聊天思考强度",
                    "HOSTED" to "服务端托管 · Responses 工具",
                )) { update(options.copy(executionMode = ImageGenerationExecutionMode.valueOf(it))) }
                ImageOptionHint(if (independent) {
                    "聊天模型按原来的思考强度规划，再由同一提供商的 Images API 生成或编辑图片。图像请求不继承聊天思考参数。"
                } else {
                    "独立调用需要启用 Responses API 的 OpenAI API Key 提供商，并支持 Images API；账号登录使用服务端托管。"
                })
            }
            formItem {
                ImageOptionChoice("生成模式", options.action, listOf(
                    "auto" to "自动判断", "generate" to "生成新图", "edit" to "编辑已有图片",
                )) { update(options.copy(action = it)) }
                if (options.action == "edit") ImageOptionHint("编辑模式需要在当前聊天中提供参考图片。")
            }
            formItem {
                ImageOptionChoice("规划复杂度（complexity）", if (independent25) "native" else options.complexity ?: "default",
                    if (independent25) listOf("native" to "2.5 模型默认 · 不发送") else
                        listOf("default" to if (independent) "生图模型默认" else "默认 · 跟随请求 / 模型") +
                            ImageGenerationOptions.EFFORTS.map { it to qualityLabel(it) },
                    enabled = !independent25,
                ) { update(options.copy(complexity = it.takeUnless { it == "default" })) }
                ImageOptionHint(if (independent25) {
                    "2.5 独立请求不发送 complexity，聊天模型的 high/max 保持不变。原来的手动值仍会保留。"
                } else if (independent) {
                    "只控制图像模型，不影响聊天思考强度；默认使用生图模型自身的设置。"
                } else {
                    "控制绘图前的规划投入。不发送时服务端可能继承聊天思考强度；可用档位由生图模型决定。"
                })
            }
            formItem {
                ImageOptionChoice("输入保真度（input_fidelity）",
                    if (independent && options.usesFixedInputFidelity) "default" else options.inputFidelity ?: "default",
                    listOf("default" to "模型默认 · 不发送", "low" to "低", "high" to "高"),
                    enabled = !(independent && options.usesFixedInputFidelity),
                ) { update(options.copy(inputFidelity = it.takeUnless { it == "default" })) }
                ImageOptionHint(when {
                    independent && options.usesFixedInputFidelity -> "独立请求使用该生图模型的默认输入保真度。"
                    options.model == "gpt-image-2" || options.model.startsWith("gpt-image-2-20") ->
                        "GPT Image 2 固定使用高保真，官方接口请保留默认。"
                    options.isMini -> "GPT Image 1 Mini 的官方接口仅支持低保真。"
                    else -> "控制参考图片的细节保留程度。默认不发送；可用档位由生图模型决定。"
                })
            }
            switchItem(
                checked = options.forceTool,
                onCheckedChange = { update(options.copy(forceTool = it)) },
                headlineContent = { Text("始终调用图像生成") },
                supportingContent = { Text("关闭时由聊天模型决定是否需要生成图片。") },
            )
        }
        CardGroup(title = { Text("图片输出") }) {
            formItem {
                ImageOptionChoice(
                    "图片尺寸", if (customSize && options.supportsFlexibleSize) "custom" else options.size,
                    listOf("auto" to "自动", "1024x1024" to "正方形 · 1024 × 1024",
                        "1536x1024" to "横向 · 1536 × 1024", "1024x1536" to "纵向 · 1024 × 1536") +
                        if (options.supportsFlexibleSize) listOf("custom" to "自定义尺寸") else emptyList(),
                ) { value ->
                    customSize = value == "custom"
                    update(options.copy(size = if (customSize) "1536x864" else value))
                }
                if (customSize && options.supportsFlexibleSize) {
                    ImageOptionText("宽 × 高（例如 1536x864）", options.size, errors["size"]) {
                        update(options.copy(size = it.trim().lowercase().replace("×", "x")))
                    }
                    ImageOptionHint("宽高须为 16 的倍数；超过 2560×1440 的分辨率属于实验性支持。")
                } else errors["size"]?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
            formItem {
                ImageOptionChoice("画质", options.quality, options.qualities.map { it to qualityLabel(it) }) {
                    update(options.copy(quality = it))
                }
                ImageOptionHint("较高画质通常需要更多时间和费用。")
            }
            formItem {
                ImageOptionChoice("输出格式", options.outputFormat, options.formats.map { it to it.uppercase() }) {
                    update(options.copy(outputFormat = it))
                }
            }
            formItem {
                ImageOptionChoice("背景", options.background,
                    listOf("auto" to "自动", "opaque" to "不透明") +
                        if (options.supportsTransparency) listOf("transparent" to "透明") else emptyList(),
                ) { update(options.copy(background = it)) }
                if (!options.supportsTransparency) ImageOptionHint("透明背景需要 PNG 或 WebP 格式。")
            }
            if (options.supportsCompression) {
                imageSlider("压缩设置", options.outputCompression, 0, 100, unit = "%") {
                    update(options.copy(outputCompression = it))
                }
            }
            formItem {
                ImageOptionChoice("生成过程预览", if (independent) "0" else options.partialImages.toString(), listOf(
                    "0" to "关闭 · 仅显示最终图片", "1" to "1 张预览图", "2" to "2 张预览图", "3" to "3 张预览图",
                ), enabled = !independent) { update(options.copy(partialImages = it.toInt())) }
                ImageOptionHint(if (independent) "独立调用会显示生成状态和最终图片，中途预览图仅在托管模式下提供。" else
                    "仅在聊天开启流式输出时生效；预览图会产生额外用量，实际返回数量可能更少。")
            }
        }

        TextButton(onClick = { advanced = !advanced }) {
            Text(if (advanced) "收起高级选项" else "展开高级选项 · 内容审核")
        }
        AnimatedVisibility(visible = advanced) {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                CardGroup(title = { Text("高级选项") }) {
                    formItem {
                        ImageOptionChoice("审核等级", options.moderation, listOf("auto" to "自动", "low" to "较宽松")) {
                            update(options.copy(moderation = it))
                        }
                        ImageOptionHint("较宽松不代表关闭内容审核。")
                    }
                }
            }
        }
        TextButton(onClick = {
            customModel = false
            customSize = false
            onChange(ImageGenerationOptions())
        }) { Text("恢复图像生成默认设置") }
    }
}

@Composable
private fun ImageOptionChoice(
    label: String,
    value: String,
    choices: List<Pair<String, String>>,
    enabled: Boolean = true,
    onChange: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded && enabled, onExpandedChange = { if (enabled) expanded = it }) {
        OutlinedTextField(
            value = choices.firstOrNull { it.first == value }?.second ?: value,
            onValueChange = {}, readOnly = true, singleLine = true,
            enabled = enabled,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled),
        )
        ExposedDropdownMenu(expanded = expanded && enabled, onDismissRequest = { expanded = false }) {
            choices.forEach { (key, text) ->
                DropdownMenuItem(text = { Text(text) }, onClick = { expanded = false; onChange(key) })
            }
        }
    }
}

@Composable
private fun ImageOptionText(label: String, value: String, error: String?, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label) },
        isError = error != null, supportingText = error?.let { { Text(it) } },
        singleLine = true, modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun ImageOptionHint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

private fun CardGroupScope.imageSlider(
    label: String, value: Int, min: Int, max: Int, unit: String = "", onChange: (Int) -> Unit,
) {
    formItem(headlineContent = { Text(label) }, trailingContent = { CardGroupValue("$value$unit") }) {
        Slider(
            value = value.toFloat().coerceIn(min.toFloat(), max.toFloat()),
            onValueChange = { onChange(it.roundToInt().coerceIn(min, max)) },
            valueRange = min.toFloat()..max.toFloat(),
            modifier = Modifier.semantics { contentDescription = label },
        )
    }
}

private fun qualityLabel(value: String): String = when (value) {
    "auto" -> "自动"
    "low" -> "低"
    "medium" -> "中"
    "high" -> "高"
    "xhigh" -> "超高"
    "max" -> "最高"
    else -> value
}
