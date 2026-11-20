package sh.hnet.comfychair.ui.components.config

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import sh.hnet.comfychair.R
import sh.hnet.comfychair.WorkflowManager
import sh.hnet.comfychair.model.SamplerOptions
import sh.hnet.comfychair.ui.components.shared.NoOverscrollContainer
import sh.hnet.comfychair.ui.components.shared.rememberSpellCheckVisualTransformation
import sh.hnet.comfychair.ui.components.LoraChainEditor
import sh.hnet.comfychair.ui.components.shared.DimensionStepperRow
import sh.hnet.comfychair.ui.components.shared.GenericWorkflowDropdown
import sh.hnet.comfychair.ui.components.shared.LengthFpsRow
import sh.hnet.comfychair.ui.components.shared.LoraStrengthRow
import sh.hnet.comfychair.ui.components.shared.MegapixelsField
import sh.hnet.comfychair.ui.components.shared.ClipLayerField
import sh.hnet.comfychair.ui.components.shared.DenoiseBatchRow
import sh.hnet.comfychair.ui.components.shared.ModelDropdown
import sh.hnet.comfychair.ui.components.shared.ScaleByField
import sh.hnet.comfychair.ui.components.shared.SeedRow
import sh.hnet.comfychair.ui.components.shared.StepsCfgRow
import sh.hnet.comfychair.viewmodel.ImageToImageMode

/**
 * Unified configuration bottom sheet content.
 * All fields are driven by the BottomSheetConfig data class.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfigBottomSheetContent(
    config: BottomSheetConfig,
    workflowName: String,
    spellCheckEnabled: Boolean = false,
    modifier: Modifier = Modifier
) {
    NoOverscrollContainer {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp)
                .verticalScroll(rememberScrollState())
        ) {
            // 1. Negative Prompt (at top)
            if (config.prompts.hasNegativePrompt) {
                NegativePromptSection(config.prompts, spellCheckEnabled)
                Spacer(modifier = Modifier.height(16.dp))
            }

            // 2. ITI Mode Selector (if applicable)
            // 参考图不在这里：图生图的参考图已并入主区域的图位区（与图生视频同一套操作方式）
            config.itiConfig?.let { itiConfig ->
                ModeSelector(itiConfig)
                Spacer(modifier = Modifier.height(16.dp))
            }

            // 3. Workflow Dropdown
            WorkflowSection(config.workflow)
            Spacer(modifier = Modifier.height(16.dp))

            // 4. Model Selection Section
            ModelSelectionSection(config.models)

            // 5. Generation Parameters Section
            ParametersSection(config.parameters, workflowName)

            // 5a. 输出端格式开关（v4.6.1 模块 C）：只对"输出为图片且有 SaveImage"的工作流装配
            config.outputFormat?.let {
                Spacer(modifier = Modifier.height(16.dp))
                OutputFormatSection(it)
            }

            // 5b. Dynamic Node Parameters Section（通用节点参数引擎；无动态参数时自隐藏）
            config.dynamic?.let { DynamicParamSection(it) }

            // 5c. 技能提示词（仅技能流显示 —— 工作流里没有 {{skill_file_name}} 时整块自隐藏）
            SkillPromptSection(workflowName)

            // 6. LoRA Section (including single LoRA dropdowns at the bottom)
            LoraSection(config.lora, config.models)
        }
    }
}

@Composable
private fun NegativePromptSection(prompts: PromptConfig, spellCheckEnabled: Boolean) {
    val transformation = rememberSpellCheckVisualTransformation(
        text = prompts.negativePrompt,
        enabled = spellCheckEnabled
    )
    OutlinedTextField(
        value = prompts.negativePrompt,
        onValueChange = prompts.onNegativePromptChange,
        label = { Text(stringResource(R.string.hint_negative_prompt)) },
        modifier = Modifier.fillMaxWidth(),
        minLines = 2,
        maxLines = 4,
        keyboardOptions = KeyboardOptions(autoCorrectEnabled = spellCheckEnabled),
        visualTransformation = transformation,
        // 与正面提示词框同款的一键清空：只在有内容时出现，位置同在右下角。
        // 清空复用同一个 onNegativePromptChange 回调 —— 图生图那层已经按模式把负面分发到
        // editingNegativePrompt / negativePrompt，所以编辑与修复两种模式都对。
        trailingIcon = {
            if (prompts.negativePrompt.isNotEmpty()) {
                IconButton(onClick = { prompts.onNegativePromptChange("") }) {
                    Icon(
                        Icons.Default.Clear,
                        contentDescription = stringResource(R.string.content_description_clear)
                    )
                }
            }
        }
    )
}

@Composable
private fun ModeSelector(itiConfig: ItiConfig) {
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        SegmentedButton(
            selected = itiConfig.mode == ImageToImageMode.EDITING,
            onClick = { itiConfig.onModeChange(ImageToImageMode.EDITING) },
            shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2)
        ) { Text(stringResource(R.string.option_mode_editing)) }
        SegmentedButton(
            selected = itiConfig.mode == ImageToImageMode.INPAINTING,
            onClick = { itiConfig.onModeChange(ImageToImageMode.INPAINTING) },
            shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2)
        ) { Text(stringResource(R.string.option_mode_inpainting)) }
    }
}

@Composable
private fun WorkflowSection(workflow: WorkflowConfig) {
    GenericWorkflowDropdown(
        label = stringResource(R.string.label_workflow),
        selectedWorkflow = workflow.selectedWorkflow,
        workflows = workflow.availableWorkflows,
        onWorkflowChange = workflow.onWorkflowChange,
        onViewWorkflow = workflow.onViewWorkflow
    )
    WorkflowDescriptionHint(workflow.selectedWorkflow)
}

/**
 * 工作流说明：转换脚本把作者写在画布 Note 里的使用介绍提取进了 `description`
 * （尺寸切换说明、提示词模板、加速经验等）。收起成一行入口，点开看全文 ——
 * 提示词模板动辄上千字，常驻展开会把整个配置面板撑得很长。
 */
@Composable
private fun WorkflowDescriptionHint(workflowName: String) {
    val description = remember(workflowName) {
        WorkflowManager.getWorkflowByName(workflowName)?.description.orEmpty()
    }
    if (description.isBlank()) return
    var showDialog by remember { mutableStateOf(false) }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = "工作流说明",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.width(8.dp))
        TextButton(onClick = { showDialog = true }) { Text("查看") }
    }

    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text("工作流说明") },
            text = {
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .heightIn(max = 360.dp)
                        .verticalScroll(rememberScrollState())
                )
            },
            confirmButton = {
                TextButton(onClick = { showDialog = false }) { Text("关闭") }
            }
        )
    }
}

@Composable
private fun ModelSelectionSection(models: ModelConfig) {
    // Note: Single LoRA dropdowns (highnoiseLora, lownoiseLora) are rendered in LoraSection
    val visibleModels = remember(models) {
        listOfNotNull(
            models.checkpoint?.takeIf { it.isVisible },
            models.unet?.takeIf { it.isVisible },
            models.latentUpscaleModel?.takeIf { it.isVisible },
            models.upscale?.takeIf { it.isVisible },
            models.highnoiseUnet?.takeIf { it.isVisible },
            models.lownoiseUnet?.takeIf { it.isVisible },
            models.vae?.takeIf { it.isVisible },
            models.clip?.takeIf { it.isVisible },
            models.clip1?.takeIf { it.isVisible },
            models.clip2?.takeIf { it.isVisible },
            models.clip3?.takeIf { it.isVisible },
            models.clip4?.takeIf { it.isVisible },
            models.textEncoder?.takeIf { it.isVisible }
        )
    }

    if (visibleModels.isEmpty()) return

    // Section title
    Text(
        text = stringResource(R.string.title_model_selection),
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier.padding(vertical = 8.dp)
    )

    // Render each visible model field in order
    models.checkpoint?.takeIf { it.isVisible }?.let { RenderModelField(it) }
    models.unet?.takeIf { it.isVisible }?.let { RenderModelField(it) }
    models.latentUpscaleModel?.takeIf { it.isVisible }?.let { RenderModelField(it) }
    models.upscale?.takeIf { it.isVisible }?.let { RenderModelField(it) }
    models.highnoiseUnet?.takeIf { it.isVisible }?.let { RenderModelField(it) }
    models.lownoiseUnet?.takeIf { it.isVisible }?.let { RenderModelField(it) }
    models.vae?.takeIf { it.isVisible }?.let { RenderModelField(it) }
    models.clip?.takeIf { it.isVisible }?.let { RenderModelField(it) }
    models.clip1?.takeIf { it.isVisible }?.let { RenderModelField(it) }
    models.clip2?.takeIf { it.isVisible }?.let { RenderModelField(it) }
    models.clip3?.takeIf { it.isVisible }?.let { RenderModelField(it) }
    models.clip4?.takeIf { it.isVisible }?.let { RenderModelField(it) }
    models.textEncoder?.takeIf { it.isVisible }?.let { RenderModelField(it) }
}

@Composable
private fun RenderModelField(field: ModelField) {
    Spacer(modifier = Modifier.height(12.dp))
    ModelDropdown(
        label = stringResource(field.label),
        selectedValue = field.selectedValue,
        options = field.filteredOptions ?: field.options,
        onValueChange = field.onValueChange,
        // G8 指令 1：UNET / VAE / CLIP 把类别透传给下拉，四态按这一类判定
        category = field.category
    )
    // 主 LoRA 下拉的权重滑块（仅该字段带 strength/onStrengthChange 时出现）
    RenderFieldStrength(field)
}

/**
 * 渲染模型下拉下方的可选权重滑块；两个字段都为 null 时不渲染任何内容。
 */
@Composable
private fun RenderFieldStrength(field: ModelField) {
    val strength = field.strength
    val onStrengthChange = field.onStrengthChange
    if (strength != null && onStrengthChange != null) {
        LoraStrengthRow(value = strength, onValueChange = onStrengthChange)
    }
}

@Composable
private fun ParametersSection(params: ParameterConfig, workflowName: String) {
    val hasAnyParams = remember(params) {
        listOfNotNull(
            params.width?.takeIf { it.isVisible },
            params.height?.takeIf { it.isVisible },
            params.megapixels?.takeIf { it.isVisible },
            params.aspectRatio?.takeIf { it.isVisible },
            params.steps?.takeIf { it.isVisible },
            params.cfg?.takeIf { it.isVisible },
            params.length?.takeIf { it.isVisible },
            params.fps?.takeIf { it.isVisible },
            params.sampler?.takeIf { it.isVisible },
            params.scheduler?.takeIf { it.isVisible },
            params.speedMode?.takeIf { it.isVisible },
            params.seed?.takeIf { it.isVisible },
            params.denoise?.takeIf { it.isVisible },
            params.batchSize?.takeIf { it.isVisible },
            params.upscaleMethod?.takeIf { it.isVisible },
            params.scaleBy?.takeIf { it.isVisible },
            params.stopAtClipLayer?.takeIf { it.isVisible }
        ).isNotEmpty()
    }

    if (!hasAnyParams) return

    Spacer(modifier = Modifier.height(16.dp))

    // Dimensions row (width/height)
    val showWidth = params.width?.isVisible == true
    val showHeight = params.height?.isVisible == true
    if (showWidth || showHeight) {
        DimensionStepperRow(
            workflowName = workflowName,
            width = params.width?.value ?: "",
            onWidthChange = params.width?.onValueChange ?: {},
            widthError = params.width?.error,
            height = params.height?.value ?: "",
            onHeightChange = params.height?.onValueChange ?: {},
            heightError = params.height?.error,
            showWidth = showWidth,
            showHeight = showHeight
        )
        Spacer(modifier = Modifier.height(12.dp))
    }

    // Megapixels
    params.megapixels?.takeIf { it.isVisible }?.let { field ->
        MegapixelsField(
            workflowName = workflowName,
            value = field.value,
            onValueChange = field.onValueChange,
            error = field.error,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(12.dp))
    }

    // Aspect ratio dropdown (ResolutionSelector.aspect_ratio)
    params.aspectRatio?.takeIf { it.isVisible }?.let { field ->
        ModelDropdown(
            label = stringResource(R.string.label_aspect_ratio),
            selectedValue = field.selectedValue,
            options = field.options,
            onValueChange = field.onValueChange
        )
        Spacer(modifier = Modifier.height(12.dp))
    }

    // Steps/CFG row
    val showSteps = params.steps?.isVisible == true
    val showCfg = params.cfg?.isVisible == true
    if (showSteps || showCfg) {
        StepsCfgRow(
            workflowName = workflowName,
            steps = params.steps?.value ?: "",
            onStepsChange = params.steps?.onValueChange ?: {},
            stepsError = params.steps?.error,
            showSteps = showSteps,
            cfg = params.cfg?.value ?: "",
            onCfgChange = params.cfg?.onValueChange ?: {},
            cfgError = params.cfg?.error,
            showCfg = showCfg
        )
        Spacer(modifier = Modifier.height(12.dp))
    }

    // Length/FPS row
    val showLength = params.length?.isVisible == true
    val showFps = params.fps?.isVisible == true
    if (showLength || showFps) {
        LengthFpsRow(
            workflowName = workflowName,
            length = params.length?.value ?: "",
            onLengthChange = params.length?.onValueChange ?: {},
            lengthError = params.length?.error,
            showLength = showLength,
            fps = params.fps?.value ?: "",
            onFpsChange = params.fps?.onValueChange ?: {},
            fpsError = params.fps?.error,
            showFps = showFps
        )
        Spacer(modifier = Modifier.height(12.dp))
    }

    // Sampler dropdown
    params.sampler?.takeIf { it.isVisible }?.let { field ->
        ModelDropdown(
            label = stringResource(R.string.label_sampler),
            selectedValue = field.selectedValue,
            options = field.options,
            onValueChange = field.onValueChange
        )
        Spacer(modifier = Modifier.height(12.dp))
    }

    // Scheduler dropdown
    params.scheduler?.takeIf { it.isVisible }?.let { field ->
        ModelDropdown(
            label = stringResource(R.string.label_scheduler),
            selectedValue = field.selectedValue,
            options = field.options,
            onValueChange = field.onValueChange
        )
        Spacer(modifier = Modifier.height(12.dp))
    }

    // Speed mode dropdown (TESpeedMiniMaxH3.mode)
    params.speedMode?.takeIf { it.isVisible }?.let { field ->
        ModelDropdown(
            label = stringResource(R.string.label_speed_mode),
            selectedValue = field.selectedValue,
            options = field.options,
            onValueChange = field.onValueChange
        )
        Spacer(modifier = Modifier.height(12.dp))
    }

    // Seed row (mode selector + field + randomize button)
    params.seed?.takeIf { it.isVisible }?.let { seedConfig ->
        SeedRow(
            workflowName = workflowName,
            seedMode = seedConfig.seedMode,
            onSeedModeChange = seedConfig.onSeedModeChange,
            seed = seedConfig.seed,
            onSeedChange = seedConfig.onSeedChange,
            onRandomizeSeed = seedConfig.onRandomizeSeed,
            seedError = seedConfig.seedError
        )
        Spacer(modifier = Modifier.height(12.dp))
    }

    // Denoise / Batch size row
    val showDenoise = params.denoise?.isVisible == true
    val showBatchSize = params.batchSize?.isVisible == true
    if (showDenoise || showBatchSize) {
        DenoiseBatchRow(
            workflowName = workflowName,
            denoise = params.denoise?.value ?: "",
            onDenoiseChange = params.denoise?.onValueChange ?: {},
            denoiseError = params.denoise?.error,
            showDenoise = showDenoise,
            batchSize = params.batchSize?.value ?: "",
            onBatchSizeChange = params.batchSize?.onValueChange ?: {},
            batchSizeError = params.batchSize?.error,
            showBatchSize = showBatchSize
        )
        Spacer(modifier = Modifier.height(12.dp))
    }

    // Upscale method / Scale by row
    val showUpscaleMethod = params.upscaleMethod?.isVisible == true
    val showScaleBy = params.scaleBy?.isVisible == true
    if (showUpscaleMethod || showScaleBy) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (showUpscaleMethod) {
                params.upscaleMethod?.let { field ->
                    ModelDropdown(
                        label = stringResource(R.string.label_upscale_method),
                        selectedValue = field.selectedValue,
                        options = field.options,
                        onValueChange = field.onValueChange,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            if (showScaleBy) {
                params.scaleBy?.let { field ->
                    ScaleByField(
                        workflowName = workflowName,
                        value = field.value,
                        onValueChange = field.onValueChange,
                        error = field.error,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
    }

    // CLIP layer
    params.stopAtClipLayer?.takeIf { it.isVisible }?.let { field ->
        ClipLayerField(
            workflowName = workflowName,
            value = field.value,
            onValueChange = field.onValueChange,
            error = field.error,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun LoraSection(lora: LoraConfig, models: ModelConfig) {
    // Single LoRA dropdowns (in order: LoRA, High noise LoRA, Low noise LoRA)
    lora.loraName?.takeIf { it.isVisible }?.let { field ->
        Spacer(modifier = Modifier.height(16.dp))
        ModelDropdown(
            label = stringResource(field.label),
            selectedValue = field.selectedValue,
            options = field.filteredOptions ?: field.options,
            onValueChange = field.onValueChange
        )
        // 主 LoRA 权重滑块，紧挨着 LoRA 下拉
        RenderFieldStrength(field)
    }

    models.highnoiseLora?.takeIf { it.isVisible }?.let { field ->
        Spacer(modifier = Modifier.height(16.dp))
        ModelDropdown(
            label = stringResource(field.label),
            selectedValue = field.selectedValue,
            options = field.filteredOptions ?: field.options,
            onValueChange = field.onValueChange
        )
    }

    models.lownoiseLora?.takeIf { it.isVisible }?.let { field ->
        Spacer(modifier = Modifier.height(16.dp))
        ModelDropdown(
            label = stringResource(field.label),
            selectedValue = field.selectedValue,
            options = field.filteredOptions ?: field.options,
            onValueChange = field.onValueChange
        )
    }

    // LoRA chain editors
    lora.primaryChain?.takeIf { it.isVisible }?.let { chain ->
        RenderLoraChain(chain)
    }

    lora.highnoiseChain?.takeIf { it.isVisible }?.let { chain ->
        RenderLoraChain(chain)
    }

    lora.lownoiseChain?.takeIf { it.isVisible }?.let { chain ->
        RenderLoraChain(chain)
    }
}

@Composable
private fun RenderLoraChain(chain: LoraChainField) {
    Spacer(modifier = Modifier.height(16.dp))
    LoraChainEditor(
        title = stringResource(chain.title),
        loraChain = chain.chain,
        availableLoras = chain.availableLoras,
        onAddLora = chain.onAdd,
        onRemoveLora = chain.onRemove,
        onLoraNameChange = chain.onNameChange,
        onLoraStrengthChange = chain.onStrengthChange
    )
}
