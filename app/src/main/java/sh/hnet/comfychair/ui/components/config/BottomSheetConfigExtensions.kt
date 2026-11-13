package sh.hnet.comfychair.ui.components.config

import sh.hnet.comfychair.R
import sh.hnet.comfychair.WorkflowManager
import sh.hnet.comfychair.WorkflowType
import sh.hnet.comfychair.model.SamplerOptions
import sh.hnet.comfychair.model.SeedMode
import sh.hnet.comfychair.viewmodel.ImageToImageMode
import sh.hnet.comfychair.viewmodel.ImageToImageUiState
import sh.hnet.comfychair.viewmodel.ImageToVideoUiState
import sh.hnet.comfychair.viewmodel.TextToImageUiState
import sh.hnet.comfychair.viewmodel.TextToVideoUiState

/**
 * Extension functions to convert UiState classes to BottomSheetConfig.
 *
 * These functions use shared helpers from BottomSheetConfigHelpers.kt to reduce duplication.
 * All screens now use UnifiedCallbacks instead of screen-specific callback classes.
 */

// No-op callbacks for when a callback is not provided
private val noOpString: (String) -> Unit = {}
private val noOpUnit: () -> Unit = {}
private val noOpSeedMode: (SeedMode) -> Unit = {}
private val noOpStringPair: (String, String) -> Unit = { _, _ -> }
private val noOpBoolean: (Boolean) -> Unit = {}

/**
 * 只对"输出为图片 + 工作流里有 SaveImage"的流返回开关配置（v4.6.1 模块 C）。
 *
 * 视频（SaveVideo）、3D（Save3DAdvanced/SaveGLB）与没有保存节点的工作流一律返回 null，
 * 因此这些界面根本不会出现"保存为 WebP"的选项（硬性约束 1）。
 */
private fun webpOutputConfigIfSupported(
    workflowId: String,
    saveAsWebp: Boolean,
    onChange: ((Boolean) -> Unit)?
): OutputFormatConfig? {
    val callback = onChange ?: return null
    val workflow = WorkflowManager.getWorkflowById(workflowId) ?: return null
    val imageType = workflow.type == WorkflowType.TTI ||
        workflow.type == WorkflowType.ITI_INPAINTING ||
        workflow.type == WorkflowType.ITI_EDITING
    if (!imageType) return null
    // 用带引号的匹配，避免把 SaveImageWebsocket/SaveImageAdvanced 也算进来
    if (!workflow.jsonContent.contains("\"class_type\": \"SaveImage\"")) return null
    return OutputFormatConfig(saveAsWebp = saveAsWebp, onSaveAsWebpChange = callback)
}

/**
 * Convert TextToImageUiState to BottomSheetConfig.
 *
 * Uses unified fields - field visibility is controlled by capabilities (derived from placeholders).
 */
fun TextToImageUiState.toBottomSheetConfig(callbacks: UnifiedCallbacks): BottomSheetConfig {
    return BottomSheetConfig(
        workflow = WorkflowConfig(
            selectedWorkflow = selectedWorkflow,
            availableWorkflows = availableWorkflows,
            onWorkflowChange = callbacks.onWorkflowChange,
            onViewWorkflow = callbacks.onViewWorkflow
        ),
        prompts = PromptConfig(
            negativePrompt = negativePrompt,
            onNegativePromptChange = callbacks.onNegativePromptChange,
            hasNegativePrompt = capabilities.hasNegativePrompt
        ),
        itiConfig = null,
        models = buildCommonModelConfig(this, callbacks),
        parameters = buildCommonParameterConfig(
            state = this,
            callbacks = callbacks,
            widthField = NumericField(
                value = width,
                onValueChange = callbacks.onWidthChange ?: noOpString,
                error = widthError,
                isVisible = capabilities.hasWidth
            ),
            heightField = NumericField(
                value = height,
                onValueChange = callbacks.onHeightChange ?: noOpString,
                error = heightError,
                isVisible = capabilities.hasHeight
            ),
            // Qwen2.1 这类流的尺寸 = 宽高比 + 百万像素（节点是 ResolutionSelector），
            // 与 width/height 并存：哪个生效由工作流里的占位符决定
            megapixelsField = NumericField(
                value = megapixels,
                onValueChange = callbacks.onMegapixelsChange ?: noOpString,
                error = megapixelsError,
                isVisible = capabilities.hasMegapixels
            )
        ),
        lora = buildCommonLoraConfig(this, callbacks),
        // 通用节点参数引擎：工作流里写成 {{占位符名}} 的节点输入（工作流没写则分组自隐藏）
        dynamic = buildDynamicParamConfig(
            workflowId = selectedWorkflowId,
            params = dynamicParams,
            values = dynamicParamValues,
            registryReady = dynamicParamsRegistryReady,
            onValueChange = callbacks.onDynamicParamValueChange ?: noOpStringPair
        ),
        // 输出端格式（v4.6.1 模块 C）：3D 流与无 SaveImage 的流返回 null，不显示开关
        outputFormat = webpOutputConfigIfSupported(
            selectedWorkflowId,
            saveAsWebp,
            callbacks.onSaveAsWebpChange ?: noOpBoolean
        )
    )
}

/**
 * Convert TextToVideoUiState to BottomSheetConfig.
 *
 * Video screens have additional dual-model patterns (highnoise/lownoise).
 */
fun TextToVideoUiState.toBottomSheetConfig(callbacks: UnifiedCallbacks): BottomSheetConfig {
    // Start with common model config and add video-specific fields
    val commonModels = buildCommonModelConfig(this, callbacks)

    return BottomSheetConfig(
        workflow = WorkflowConfig(
            selectedWorkflow = selectedWorkflow,
            availableWorkflows = availableWorkflows,
            onWorkflowChange = callbacks.onWorkflowChange,
            onViewWorkflow = callbacks.onViewWorkflow
        ),
        prompts = PromptConfig(
            negativePrompt = negativePrompt,
            onNegativePromptChange = callbacks.onNegativePromptChange,
            hasNegativePrompt = capabilities.hasNegativePrompt
        ),
        itiConfig = null,
        models = commonModels.copy(
            // Add dual-model patterns (e.g., Wan 2.2)
            highnoiseUnet = if (capabilities.hasHighnoiseUnetName) ModelField(
                label = R.string.label_highnoise_unet,
                selectedValue = selectedHighnoiseUnet,
                options = availableUnets,
                filteredOptions = filteredUnets,
                onValueChange = callbacks.onHighnoiseUnetChange ?: noOpString,
                isVisible = true
            ) else null,
            lownoiseUnet = if (capabilities.hasLownoiseUnetName) ModelField(
                label = R.string.label_lownoise_unet,
                selectedValue = selectedLownoiseUnet,
                options = availableUnets,
                filteredOptions = filteredUnets,
                onValueChange = callbacks.onLownoiseUnetChange ?: noOpString,
                isVisible = true
            ) else null,
            highnoiseLora = if (capabilities.hasHighnoiseLoraName) ModelField(
                label = R.string.label_highnoise_lora,
                selectedValue = selectedHighnoiseLora,
                options = availableLoras,
                filteredOptions = filteredLoras,
                onValueChange = callbacks.onHighnoiseLoraChange ?: noOpString,
                isVisible = true
            ) else null,
            lownoiseLora = if (capabilities.hasLownoiseLoraName) ModelField(
                label = R.string.label_lownoise_lora,
                selectedValue = selectedLownoiseLora,
                options = availableLoras,
                filteredOptions = filteredLoras,
                onValueChange = callbacks.onLownoiseLoraChange ?: noOpString,
                isVisible = true
            ) else null
        ),
        parameters = buildCommonParameterConfig(
            state = this,
            callbacks = callbacks,
            widthField = NumericField(
                value = width,
                onValueChange = callbacks.onWidthChange ?: noOpString,
                error = widthError,
                isVisible = capabilities.hasWidth
            ),
            heightField = NumericField(
                value = height,
                onValueChange = callbacks.onHeightChange ?: noOpString,
                error = heightError,
                isVisible = capabilities.hasHeight
            ),
            megapixelsField = NumericField(
                value = megapixels,
                onValueChange = callbacks.onMegapixelsChange ?: noOpString,
                error = megapixelsError,
                isVisible = capabilities.hasMegapixels
            ),
            lengthField = NumericField(
                value = length,
                onValueChange = callbacks.onLengthChange ?: noOpString,
                error = lengthError,
                isVisible = capabilities.hasLength
            ),
            fpsField = NumericField(
                value = fps,
                onValueChange = callbacks.onFpsChange ?: noOpString,
                error = fpsError,
                isVisible = capabilities.hasFrameRate
            )
        ),
        lora = LoraConfig(
            loraName = if (capabilities.hasLoraName) ModelField(
                label = R.string.label_lora,
                // 空值显示为「不使用 LoRA」；选中它时值置空，提交阶段由
                // WorkflowManager.replaceLoraPlaceholder 移除 LoraLoader 节点并重连
                selectedValue = if (selectedLoraName.isEmpty()) {
                    LORA_NONE_LABEL
                } else {
                    selectedLoraName
                },
                options = listOf(LORA_NONE_LABEL) + availableLoras,
                filteredOptions = filteredLoras?.let { listOf(LORA_NONE_LABEL) + it },
                onValueChange = { value ->
                    val actual = if (value == LORA_NONE_LABEL) "" else value
                    (callbacks.onMandatoryLoraChange ?: noOpString).invoke(actual)
                },
                isVisible = true,
                // 权重滑块：只在该工作流确有 {{lora_strength}} 时出现（否则没有节点可写）
                strength = loraStrength.takeIf { capabilities.hasLoraStrength },
                onStrengthChange = callbacks.onMandatoryLoraStrengthChange
            ) else null,
            // Primary LoRA chain for single-model workflows (e.g., LTX 2.0)
            primaryChain = if (capabilities.hasLora) LoraChainField(
                title = R.string.title_lora_chain,
                chain = loraChain,
                availableLoras = availableLoras,
                onAdd = callbacks.onAddLora ?: {},
                onRemove = callbacks.onRemoveLora ?: {},
                onNameChange = callbacks.onLoraNameChange ?: { _, _ -> },
                onStrengthChange = callbacks.onLoraStrengthChange ?: { _, _ -> },
                isVisible = true
            ) else null,
            // Dual-model LoRA chains (for Wan 2.2 style workflows)
            highnoiseChain = buildVideoLoraChain(
                titleResId = R.string.title_highnoise_lora_chain,
                chain = highnoiseLoraChain,
                availableLoras = availableLoras,
                isVisible = capabilities.hasHighnoiseLora,
                onAdd = callbacks.onAddHighnoiseLora,
                onRemove = callbacks.onRemoveHighnoiseLora,
                onNameChange = callbacks.onHighnoiseLoraNameChange,
                onStrengthChange = callbacks.onHighnoiseLoraStrengthChange
            ),
            lownoiseChain = buildVideoLoraChain(
                titleResId = R.string.title_lownoise_lora_chain,
                chain = lownoiseLoraChain,
                availableLoras = availableLoras,
                isVisible = capabilities.hasLownoiseLora,
                onAdd = callbacks.onAddLownoiseLora,
                onRemove = callbacks.onRemoveLownoiseLora,
                onNameChange = callbacks.onLownoiseLoraNameChange,
                onStrengthChange = callbacks.onLownoiseLoraStrengthChange
            )
        ),
        // 通用节点参数引擎：工作流里写成 {{占位符名}} 的节点输入（工作流没写则分组自隐藏）
        dynamic = buildDynamicParamConfig(
            workflowId = selectedWorkflowId,
            params = dynamicParams,
            values = dynamicParamValues,
            registryReady = dynamicParamsRegistryReady,
            onValueChange = callbacks.onDynamicParamValueChange ?: noOpStringPair
        )
    )
}

/**
 * Convert ImageToVideoUiState to BottomSheetConfig.
 *
 * Very similar to TextToVideoUiState - shares dual-model patterns.
 */
fun ImageToVideoUiState.toBottomSheetConfig(callbacks: UnifiedCallbacks): BottomSheetConfig {
    // Start with common model config and add video-specific fields
    val commonModels = buildCommonModelConfig(this, callbacks)

    return BottomSheetConfig(
        workflow = WorkflowConfig(
            selectedWorkflow = selectedWorkflow,
            availableWorkflows = availableWorkflows,
            onWorkflowChange = callbacks.onWorkflowChange,
            onViewWorkflow = callbacks.onViewWorkflow
        ),
        prompts = PromptConfig(
            negativePrompt = negativePrompt,
            onNegativePromptChange = callbacks.onNegativePromptChange,
            hasNegativePrompt = capabilities.hasNegativePrompt
        ),
        itiConfig = null,
        models = commonModels.copy(
            // Add dual-model patterns (e.g., Wan 2.2)
            highnoiseUnet = if (capabilities.hasHighnoiseUnetName) ModelField(
                label = R.string.label_highnoise_unet,
                selectedValue = selectedHighnoiseUnet,
                options = availableUnets,
                filteredOptions = filteredUnets,
                onValueChange = callbacks.onHighnoiseUnetChange ?: noOpString,
                isVisible = true
            ) else null,
            lownoiseUnet = if (capabilities.hasLownoiseUnetName) ModelField(
                label = R.string.label_lownoise_unet,
                selectedValue = selectedLownoiseUnet,
                options = availableUnets,
                filteredOptions = filteredUnets,
                onValueChange = callbacks.onLownoiseUnetChange ?: noOpString,
                isVisible = true
            ) else null,
            highnoiseLora = if (capabilities.hasHighnoiseLoraName) ModelField(
                label = R.string.label_highnoise_lora,
                selectedValue = selectedHighnoiseLora,
                options = availableLoras,
                filteredOptions = filteredLoras,
                onValueChange = callbacks.onHighnoiseLoraChange ?: noOpString,
                isVisible = true
            ) else null,
            lownoiseLora = if (capabilities.hasLownoiseLoraName) ModelField(
                label = R.string.label_lownoise_lora,
                selectedValue = selectedLownoiseLora,
                options = availableLoras,
                filteredOptions = filteredLoras,
                onValueChange = callbacks.onLownoiseLoraChange ?: noOpString,
                isVisible = true
            ) else null
        ),
        parameters = buildCommonParameterConfig(
            state = this,
            callbacks = callbacks,
            widthField = NumericField(
                value = width,
                onValueChange = callbacks.onWidthChange ?: noOpString,
                error = widthError,
                isVisible = capabilities.hasWidth
            ),
            heightField = NumericField(
                value = height,
                onValueChange = callbacks.onHeightChange ?: noOpString,
                error = heightError,
                isVisible = capabilities.hasHeight
            ),
            megapixelsField = NumericField(
                value = megapixels,
                onValueChange = callbacks.onMegapixelsChange ?: noOpString,
                error = megapixelsError,
                isVisible = capabilities.hasMegapixels
            ),
            lengthField = NumericField(
                value = length,
                onValueChange = callbacks.onLengthChange ?: noOpString,
                error = lengthError,
                isVisible = capabilities.hasLength
            ),
            fpsField = NumericField(
                value = fps,
                onValueChange = callbacks.onFpsChange ?: noOpString,
                error = fpsError,
                isVisible = capabilities.hasFrameRate
            )
        ),
        lora = LoraConfig(
            loraName = if (capabilities.hasLoraName) ModelField(
                label = R.string.label_lora,
                // 空值显示为「不使用 LoRA」；选中它时值置空，提交阶段由
                // WorkflowManager.replaceLoraPlaceholder 移除 LoraLoader 节点并重连
                selectedValue = if (selectedLoraName.isEmpty()) {
                    LORA_NONE_LABEL
                } else {
                    selectedLoraName
                },
                options = listOf(LORA_NONE_LABEL) + availableLoras,
                filteredOptions = filteredLoras?.let { listOf(LORA_NONE_LABEL) + it },
                onValueChange = { value ->
                    val actual = if (value == LORA_NONE_LABEL) "" else value
                    (callbacks.onMandatoryLoraChange ?: noOpString).invoke(actual)
                },
                isVisible = true,
                // 权重滑块：只在该工作流确有 {{lora_strength}} 时出现（否则没有节点可写）
                strength = loraStrength.takeIf { capabilities.hasLoraStrength },
                onStrengthChange = callbacks.onMandatoryLoraStrengthChange
            ) else null,
            // Primary LoRA chain for single-model workflows (e.g., LTX 2.0)
            primaryChain = if (capabilities.hasLora) LoraChainField(
                title = R.string.title_lora_chain,
                chain = loraChain,
                availableLoras = availableLoras,
                onAdd = callbacks.onAddLora ?: {},
                onRemove = callbacks.onRemoveLora ?: {},
                onNameChange = callbacks.onLoraNameChange ?: { _, _ -> },
                onStrengthChange = callbacks.onLoraStrengthChange ?: { _, _ -> },
                isVisible = true
            ) else null,
            // Dual-model LoRA chains (for Wan 2.2 style workflows)
            highnoiseChain = buildVideoLoraChain(
                titleResId = R.string.title_highnoise_lora_chain,
                chain = highnoiseLoraChain,
                availableLoras = availableLoras,
                isVisible = capabilities.hasHighnoiseLora,
                onAdd = callbacks.onAddHighnoiseLora,
                onRemove = callbacks.onRemoveHighnoiseLora,
                onNameChange = callbacks.onHighnoiseLoraNameChange,
                onStrengthChange = callbacks.onHighnoiseLoraStrengthChange
            ),
            lownoiseChain = buildVideoLoraChain(
                titleResId = R.string.title_lownoise_lora_chain,
                chain = lownoiseLoraChain,
                availableLoras = availableLoras,
                isVisible = capabilities.hasLownoiseLora,
                onAdd = callbacks.onAddLownoiseLora,
                onRemove = callbacks.onRemoveLownoiseLora,
                onNameChange = callbacks.onLownoiseLoraNameChange,
                onStrengthChange = callbacks.onLownoiseLoraStrengthChange
            )
        ),
        // 通用节点参数引擎：工作流里写成 {{占位符名}} 的节点输入（工作流没写则分组自隐藏）
        dynamic = buildDynamicParamConfig(
            workflowId = selectedWorkflowId,
            params = dynamicParams,
            values = dynamicParamValues,
            registryReady = dynamicParamsRegistryReady,
            onValueChange = callbacks.onDynamicParamValueChange ?: noOpStringPair
        )
    )
}

/**
 * Convert ImageToImageUiState to BottomSheetConfig.
 *
 * Image-to-Image has two modes (Editing and Inpainting) with different workflows and parameters.
 * Note: Editing mode uses the same capabilities field as inpainting mode for field visibility.
 */
fun ImageToImageUiState.toBottomSheetConfig(callbacks: UnifiedCallbacks): BottomSheetConfig {
    val isEditing = mode == ImageToImageMode.EDITING

    return BottomSheetConfig(
        workflow = WorkflowConfig(
            selectedWorkflow = if (isEditing) selectedEditingWorkflow else selectedWorkflow,
            availableWorkflows = if (isEditing) editingWorkflows else availableWorkflows,
            onWorkflowChange = if (isEditing) {
                callbacks.onEditingWorkflowChange ?: noOpString
            } else callbacks.onWorkflowChange,
            onViewWorkflow = if (isEditing) {
                callbacks.onViewEditingWorkflow ?: noOpUnit
            } else callbacks.onViewWorkflow
        ),
        prompts = PromptConfig(
            negativePrompt = if (isEditing) editingNegativePrompt else negativePrompt,
            onNegativePromptChange = callbacks.onNegativePromptChange,
            hasNegativePrompt = capabilities.hasNegativePrompt
        ),
        itiConfig = ItiConfig(
            mode = mode,
            onModeChange = callbacks.onModeChange ?: {}
        ),
        models = if (isEditing) {
            // Editing mode models
            buildEditingModeModelConfig(this, capabilities, callbacks)
        } else {
            // Inpainting mode - use common helper
            buildCommonModelConfig(this, callbacks)
        },
        parameters = if (isEditing) {
            // Editing mode parameters
            buildEditingModeParameterConfig(this, capabilities, callbacks)
        } else {
            // Inpainting mode - use common helper
            buildCommonParameterConfig(
                state = this,
                callbacks = callbacks,
                megapixelsField = NumericField(
                    value = megapixels,
                    onValueChange = callbacks.onMegapixelsChange ?: noOpString,
                    error = megapixelsError,
                    isVisible = capabilities.hasMegapixels
                )
            )
        },
        lora = if (isEditing) {
            // Editing mode LoRA
            buildEditingModeLoraConfig(this, capabilities, callbacks)
        } else {
            // Inpainting mode - use common helper
            buildCommonLoraConfig(this, callbacks)
        },
        // 通用节点参数引擎：两个模式各有一套状态（图生图页 / 编辑页）
        dynamic = if (isEditing) {
            buildDynamicParamConfig(
                workflowId = selectedEditingWorkflowId,
                params = editingDynamicParams,
                values = editingDynamicParamValues,
                registryReady = editingDynamicParamsRegistryReady,
                onValueChange = callbacks.onEditingDynamicParamValueChange ?: noOpStringPair
            )
        } else {
            buildDynamicParamConfig(
                workflowId = selectedWorkflowId,
                params = dynamicParams,
                values = dynamicParamValues,
                registryReady = dynamicParamsRegistryReady,
                onValueChange = callbacks.onDynamicParamValueChange ?: noOpStringPair
            )
        },
        // 输出端格式（v4.6.1 模块 C）：编辑/修复两套工作流各按自己的 id 判断；
        // 3D 流（ITI_3D）与无 SaveImage 的流返回 null，不显示开关
        outputFormat = webpOutputConfigIfSupported(
            if (isEditing) selectedEditingWorkflowId else selectedWorkflowId,
            saveAsWebp,
            callbacks.onSaveAsWebpChange ?: noOpBoolean
        )
    )
}

// ========== ITI Editing Mode Helper Functions ==========

/**
 * Build ModelConfig for ITI editing mode.
 */
private fun buildEditingModeModelConfig(
    state: ImageToImageUiState,
    caps: sh.hnet.comfychair.model.WorkflowCapabilities,
    callbacks: UnifiedCallbacks
): ModelConfig {
    return ModelConfig(
        checkpoint = if (caps.hasCheckpointName) ModelField(
            label = R.string.label_checkpoint,
            selectedValue = state.selectedEditingCheckpoint,
            options = state.availableCheckpoints,
            filteredOptions = state.filteredCheckpoints,
            onValueChange = callbacks.onEditingCheckpointChange ?: noOpString,
            isVisible = true
        ) else null,
        unet = if (caps.hasUnetName) ModelField(
            label = R.string.label_unet,
            selectedValue = state.selectedEditingUnet,
            options = state.availableUnets,
            filteredOptions = state.filteredUnets,
            onValueChange = callbacks.onEditingUnetChange ?: noOpString,
            isVisible = true
        ) else null,
        vae = if (caps.hasVaeName) ModelField(
            label = R.string.label_vae,
            selectedValue = state.selectedEditingVae,
            options = state.availableVaes,
            filteredOptions = state.filteredVaes,
            onValueChange = callbacks.onEditingVaeChange ?: noOpString,
            isVisible = true
        ) else null,
        clip = if (caps.hasClipName) ModelField(
            label = R.string.label_clip,
            selectedValue = state.selectedEditingClip,
            options = state.availableClips,
            filteredOptions = state.filteredClips,
            onValueChange = callbacks.onEditingClipChange ?: noOpString,
            isVisible = true
        ) else null,
        clip1 = if (caps.hasClipName1) ModelField(
            label = R.string.label_clip1,
            selectedValue = state.selectedEditingClip1,
            options = state.availableClips,
            filteredOptions = state.filteredClips1,
            onValueChange = callbacks.onEditingClip1Change ?: noOpString,
            isVisible = true
        ) else null,
        clip2 = if (caps.hasClipName2) ModelField(
            label = R.string.label_clip2,
            selectedValue = state.selectedEditingClip2,
            options = state.availableClips,
            filteredOptions = state.filteredClips2,
            onValueChange = callbacks.onEditingClip2Change ?: noOpString,
            isVisible = true
        ) else null,
        clip3 = if (caps.hasClipName3) ModelField(
            label = R.string.label_clip3,
            selectedValue = state.selectedEditingClip3,
            options = state.availableClips,
            filteredOptions = state.filteredClips3,
            onValueChange = callbacks.onEditingClip3Change ?: noOpString,
            isVisible = true
        ) else null,
        clip4 = if (caps.hasClipName4) ModelField(
            label = R.string.label_clip4,
            selectedValue = state.selectedEditingClip4,
            options = state.availableClips,
            filteredOptions = state.filteredClips4,
            onValueChange = callbacks.onEditingClip4Change ?: noOpString,
            isVisible = true
        ) else null,
        textEncoder = if (caps.hasTextEncoderName) ModelField(
            label = R.string.label_text_encoder,
            selectedValue = state.selectedEditingTextEncoder,
            options = state.availableTextEncoders,
            filteredOptions = state.filteredTextEncoders,
            onValueChange = callbacks.onEditingTextEncoderChange ?: noOpString,
            isVisible = true
        ) else null,
        latentUpscaleModel = if (caps.hasLatentUpscaleModel) ModelField(
            label = R.string.label_latent_upscale_model,
            selectedValue = state.selectedEditingLatentUpscaleModel,
            options = state.availableLatentUpscaleModels,
            filteredOptions = state.filteredLatentUpscaleModels,
            onValueChange = callbacks.onEditingLatentUpscaleModelChange ?: noOpString,
            isVisible = true
        ) else null,
        upscale = if (caps.hasUpscaleModelName) ModelField(
            label = R.string.label_upscale_model,
            selectedValue = state.selectedEditingUpscale,
            options = state.availableUpscaleModels,
            filteredOptions = null,
            onValueChange = callbacks.onEditingUpscaleModelChange ?: noOpString,
            isVisible = true
        ) else null,
        // Dual-model patterns (for video-style workflows in editing mode)
        highnoiseUnet = if (caps.hasHighnoiseUnetName) ModelField(
            label = R.string.label_highnoise_unet,
            selectedValue = state.selectedEditingHighnoiseUnet,
            options = state.availableUnets,
            filteredOptions = null,
            onValueChange = callbacks.onEditingHighnoiseUnetChange ?: noOpString,
            isVisible = true
        ) else null,
        lownoiseUnet = if (caps.hasLownoiseUnetName) ModelField(
            label = R.string.label_lownoise_unet,
            selectedValue = state.selectedEditingLownoiseUnet,
            options = state.availableUnets,
            filteredOptions = null,
            onValueChange = callbacks.onEditingLownoiseUnetChange ?: noOpString,
            isVisible = true
        ) else null,
        highnoiseLora = if (caps.hasHighnoiseLoraName) ModelField(
            label = R.string.label_highnoise_lora,
            selectedValue = state.selectedEditingHighnoiseLora,
            options = state.availableLoras,
            filteredOptions = null,
            onValueChange = callbacks.onEditingHighnoiseLoraChange ?: noOpString,
            isVisible = true
        ) else null,
        lownoiseLora = if (caps.hasLownoiseLoraName) ModelField(
            label = R.string.label_lownoise_lora,
            selectedValue = state.selectedEditingLownoiseLora,
            options = state.availableLoras,
            filteredOptions = null,
            onValueChange = callbacks.onEditingLownoiseLoraChange ?: noOpString,
            isVisible = true
        ) else null
    )
}

/**
 * Build ParameterConfig for ITI editing mode.
 */
private fun buildEditingModeParameterConfig(
    state: ImageToImageUiState,
    caps: sh.hnet.comfychair.model.WorkflowCapabilities,
    callbacks: UnifiedCallbacks
): ParameterConfig {
    return ParameterConfig(
        megapixels = NumericField(
            value = state.editingMegapixels,
            onValueChange = callbacks.onEditingMegapixelsChange ?: noOpString,
            error = state.megapixelsError,
            isVisible = caps.hasMegapixels
        ),
        steps = NumericField(
            value = state.editingSteps,
            onValueChange = callbacks.onEditingStepsChange ?: noOpString,
            error = state.stepsError,
            isVisible = caps.hasSteps
        ),
        cfg = NumericField(
            value = state.editingCfg,
            onValueChange = callbacks.onEditingCfgChange ?: noOpString,
            error = state.cfgError,
            isVisible = caps.hasCfg
        ),
        sampler = DropdownField(
            selectedValue = state.editingSampler,
            options = state.availableSamplers.ifEmpty { SamplerOptions.SAMPLERS },
            onValueChange = callbacks.onEditingSamplerChange ?: noOpString,
            isVisible = caps.hasSamplerName
        ),
        scheduler = DropdownField(
            selectedValue = state.editingScheduler,
            options = state.availableSchedulers.ifEmpty { SamplerOptions.SCHEDULERS },
            onValueChange = callbacks.onEditingSchedulerChange ?: noOpString,
            isVisible = caps.hasScheduler
        ),
        seed = SeedConfig(
            seedMode = state.editingSeedMode,
            onSeedModeChange = callbacks.onEditingSeedModeChange ?: noOpSeedMode,
            seed = state.editingSeed,
            onSeedChange = callbacks.onEditingSeedChange ?: noOpString,
            onRandomizeSeed = callbacks.onEditingRandomizeSeed ?: noOpUnit,
            seedError = state.seedError,
            isVisible = caps.hasSeed
        ),
        denoise = NumericField(
            value = state.editingDenoise,
            onValueChange = callbacks.onEditingDenoiseChange ?: noOpString,
            error = state.denoiseError,
            isVisible = caps.hasDenoise
        ),
        batchSize = NumericField(
            value = state.editingBatchSize,
            onValueChange = callbacks.onEditingBatchSizeChange ?: noOpString,
            error = state.batchSizeError,
            isVisible = caps.hasBatchSize
        ),
        upscaleMethod = DropdownField(
            selectedValue = state.editingUpscaleMethod,
            options = state.availableUpscaleMethods,
            onValueChange = callbacks.onEditingUpscaleMethodChange ?: noOpString,
            isVisible = caps.hasUpscaleMethod
        ),
        scaleBy = NumericField(
            value = state.editingScaleBy,
            onValueChange = callbacks.onEditingScaleByChange ?: noOpString,
            error = state.scaleByError,
            isVisible = caps.hasScaleBy
        ),
        stopAtClipLayer = NumericField(
            value = state.editingStopAtClipLayer,
            onValueChange = callbacks.onEditingStopAtClipLayerChange ?: noOpString,
            error = state.stopAtClipLayerError,
            isVisible = caps.hasStopAtClipLayer
        )
    )
}

/**
 * Build LoraConfig for ITI editing mode.
 */
private fun buildEditingModeLoraConfig(
    state: ImageToImageUiState,
    caps: sh.hnet.comfychair.model.WorkflowCapabilities,
    callbacks: UnifiedCallbacks
): LoraConfig {
    // No-op callbacks
    val noOpInt: (Int) -> Unit = {}
    val noOpIntString: (Int, String) -> Unit = { _, _ -> }
    val noOpIntFloat: (Int, Float) -> Unit = { _, _ -> }

    return LoraConfig(
        loraName = if (caps.hasLoraName) ModelField(
            label = R.string.label_lora,
            // 空值显示为「不使用 LoRA」；用户选它时提交阶段会移除 LoraLoader 节点
            selectedValue = if (state.selectedEditingLora.isEmpty()) {
                LORA_NONE_LABEL
            } else {
                state.selectedEditingLora
            },
            options = listOf(LORA_NONE_LABEL) + state.availableLoras,
            filteredOptions = state.filteredLoras?.let { listOf(LORA_NONE_LABEL) + it },
            onValueChange = { value ->
                val actual = if (value == LORA_NONE_LABEL) "" else value
                (callbacks.onEditingLoraChange ?: noOpString).invoke(actual)
            },
            isVisible = true
        ) else null,
        primaryChain = if (caps.hasLora) LoraChainField(
            title = R.string.title_lora_chain,
            chain = state.editingLoraChain,
            availableLoras = state.availableLoras,
            onAdd = callbacks.onAddEditingLora ?: noOpUnit,
            onRemove = callbacks.onRemoveEditingLora ?: noOpInt,
            onNameChange = callbacks.onEditingLoraNameChange ?: noOpIntString,
            onStrengthChange = callbacks.onEditingLoraStrengthChange ?: noOpIntFloat,
            isVisible = true
        ) else null,
        // Dual LoRA chains (for video-style workflows in editing mode)
        highnoiseChain = if (caps.hasHighnoiseLora) LoraChainField(
            title = R.string.title_highnoise_lora_chain,
            chain = state.editingHighnoiseLoraChain,
            availableLoras = state.availableLoras,
            onAdd = callbacks.onAddEditingHighnoiseLora ?: noOpUnit,
            onRemove = callbacks.onRemoveEditingHighnoiseLora ?: noOpInt,
            onNameChange = callbacks.onEditingHighnoiseLoraNameChange ?: noOpIntString,
            onStrengthChange = callbacks.onEditingHighnoiseLoraStrengthChange ?: noOpIntFloat,
            isVisible = true
        ) else null,
        lownoiseChain = if (caps.hasLownoiseLora) LoraChainField(
            title = R.string.title_lownoise_lora_chain,
            chain = state.editingLownoiseLoraChain,
            availableLoras = state.availableLoras,
            onAdd = callbacks.onAddEditingLownoiseLora ?: noOpUnit,
            onRemove = callbacks.onRemoveEditingLownoiseLora ?: noOpInt,
            onNameChange = callbacks.onEditingLownoiseLoraNameChange ?: noOpIntString,
            onStrengthChange = callbacks.onEditingLownoiseLoraStrengthChange ?: noOpIntFloat,
            isVisible = true
        ) else null
    )
}
