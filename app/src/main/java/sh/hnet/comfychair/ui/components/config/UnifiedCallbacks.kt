package sh.hnet.comfychair.ui.components.config

import androidx.compose.runtime.Stable
import sh.hnet.comfychair.model.SeedMode
import sh.hnet.comfychair.viewmodel.ImageToImageMode

/**
 * Unified callback interface for all generation screen types.
 *
 * Core callbacks (workflow, view, negative prompt) are required.
 * All other callbacks are nullable - screens only provide the callbacks they need.
 *
 * 参考图不在这里：图生图的参考图已并入主区域的图位区（与图生视频同一套操作方式），
 * 配置面板里不再有参考图入口。
 *
 * This consolidates the previous 4 separate callback classes:
 * - TextToImageCallbacks
 * - TextToVideoCallbacks
 * - ImageToVideoCallbacks
 * - ImageToImageCallbacks
 */
@Stable
data class UnifiedCallbacks(
    // === Core (required for ALL screens) ===
    val onWorkflowChange: (String) -> Unit,
    val onViewWorkflow: () -> Unit,
    val onNegativePromptChange: (String) -> Unit,

    // === Model Callbacks (common to all) ===
    val onCheckpointChange: ((String) -> Unit)? = null,
    val onUnetChange: ((String) -> Unit)? = null,
    val onVaeChange: ((String) -> Unit)? = null,
    val onClipChange: ((String) -> Unit)? = null,
    val onClip1Change: ((String) -> Unit)? = null,
    val onClip2Change: ((String) -> Unit)? = null,
    val onClip3Change: ((String) -> Unit)? = null,
    val onClip4Change: ((String) -> Unit)? = null,
    val onTextEncoderChange: ((String) -> Unit)? = null,
    val onLatentUpscaleModelChange: ((String) -> Unit)? = null,
    val onMandatoryLoraChange: ((String) -> Unit)? = null,
    // 主 LoRA 权重（LoraLoaderModelOnly.strength_model，占位符 {{lora_strength}}）
    val onMandatoryLoraStrengthChange: ((Float) -> Unit)? = null,

    // === Dual-Model Patterns (TTV/ITV only) ===
    val onHighnoiseUnetChange: ((String) -> Unit)? = null,
    val onLownoiseUnetChange: ((String) -> Unit)? = null,
    val onHighnoiseLoraChange: ((String) -> Unit)? = null,
    val onLownoiseLoraChange: ((String) -> Unit)? = null,

    // === Generation Parameters ===
    val onWidthChange: ((String) -> Unit)? = null,
    val onHeightChange: ((String) -> Unit)? = null,
    val onMegapixelsChange: ((String) -> Unit)? = null,
    val onLengthChange: ((String) -> Unit)? = null,
    val onFpsChange: ((String) -> Unit)? = null,
    val onStepsChange: ((String) -> Unit)? = null,
    val onCfgChange: ((String) -> Unit)? = null,
    val onSamplerChange: ((String) -> Unit)? = null,
    val onSchedulerChange: ((String) -> Unit)? = null,
    // 视频分辨率选择器 / 加速模式
    val onAspectRatioChange: ((String) -> Unit)? = null,
    val onSpeedModeChange: ((String) -> Unit)? = null,
    /** 种子取值方式变更（固定/随机/每次+1/每次−1）；取代原来的 onRandomSeedToggle 二态 */
    val onSeedModeChange: ((SeedMode) -> Unit)? = null,
    val onSeedChange: ((String) -> Unit)? = null,
    val onRandomizeSeed: (() -> Unit)? = null,
    val onDenoiseChange: ((String) -> Unit)? = null,
    val onBatchSizeChange: ((String) -> Unit)? = null,
    val onUpscaleMethodChange: ((String) -> Unit)? = null,
    val onScaleByChange: ((String) -> Unit)? = null,
    val onStopAtClipLayerChange: ((String) -> Unit)? = null,

    // === Primary LoRA Chain ===
    val onAddLora: (() -> Unit)? = null,
    val onRemoveLora: ((Int) -> Unit)? = null,
    val onLoraNameChange: ((Int, String) -> Unit)? = null,
    val onLoraStrengthChange: ((Int, Float) -> Unit)? = null,

    // === Dual-Model LoRA Chains (TTV/ITV) ===
    val onAddHighnoiseLora: (() -> Unit)? = null,
    val onRemoveHighnoiseLora: ((Int) -> Unit)? = null,
    val onHighnoiseLoraNameChange: ((Int, String) -> Unit)? = null,
    val onHighnoiseLoraStrengthChange: ((Int, Float) -> Unit)? = null,
    val onAddLownoiseLora: (() -> Unit)? = null,
    val onRemoveLownoiseLora: ((Int) -> Unit)? = null,
    val onLownoiseLoraNameChange: ((Int, String) -> Unit)? = null,
    val onLownoiseLoraStrengthChange: ((Int, Float) -> Unit)? = null,

    // === ITI-Specific: Mode Switching ===
    val onModeChange: ((ImageToImageMode) -> Unit)? = null,

    // === ITI-Specific: Editing Workflow ===
    val onEditingWorkflowChange: ((String) -> Unit)? = null,
    val onViewEditingWorkflow: (() -> Unit)? = null,

    // === ITI-Specific: Editing Mode Models ===
    val onEditingUnetChange: ((String) -> Unit)? = null,
    val onEditingLoraChange: ((String) -> Unit)? = null,
    val onEditingVaeChange: ((String) -> Unit)? = null,
    val onEditingClipChange: ((String) -> Unit)? = null,
    val onEditingClip1Change: ((String) -> Unit)? = null,
    val onEditingClip2Change: ((String) -> Unit)? = null,
    val onEditingClip3Change: ((String) -> Unit)? = null,
    val onEditingClip4Change: ((String) -> Unit)? = null,
    val onEditingTextEncoderChange: ((String) -> Unit)? = null,
    val onEditingLatentUpscaleModelChange: ((String) -> Unit)? = null,
    val onEditingUpscaleModelChange: ((String) -> Unit)? = null,

    // === ITI-Specific: Editing Mode Checkpoint ===
    val onEditingCheckpointChange: ((String) -> Unit)? = null,

    // === ITI-Specific: Editing Mode Dual-Model Patterns ===
    val onEditingHighnoiseUnetChange: ((String) -> Unit)? = null,
    val onEditingLownoiseUnetChange: ((String) -> Unit)? = null,
    val onEditingHighnoiseLoraChange: ((String) -> Unit)? = null,
    val onEditingLownoiseLoraChange: ((String) -> Unit)? = null,

    // === ITI-Specific: Editing Mode Parameters ===
    val onEditingMegapixelsChange: ((String) -> Unit)? = null,
    val onEditingStepsChange: ((String) -> Unit)? = null,
    val onEditingCfgChange: ((String) -> Unit)? = null,
    val onEditingSamplerChange: ((String) -> Unit)? = null,
    val onEditingSchedulerChange: ((String) -> Unit)? = null,
    /** 编辑模式的种子取值方式变更 */
    val onEditingSeedModeChange: ((SeedMode) -> Unit)? = null,
    val onEditingSeedChange: ((String) -> Unit)? = null,
    val onEditingRandomizeSeed: (() -> Unit)? = null,
    val onEditingDenoiseChange: ((String) -> Unit)? = null,
    val onEditingBatchSizeChange: ((String) -> Unit)? = null,
    val onEditingUpscaleMethodChange: ((String) -> Unit)? = null,
    val onEditingScaleByChange: ((String) -> Unit)? = null,
    val onEditingStopAtClipLayerChange: ((String) -> Unit)? = null,

    // === ITI-Specific: Editing Mode LoRA Chain ===
    val onAddEditingLora: (() -> Unit)? = null,
    val onRemoveEditingLora: ((Int) -> Unit)? = null,
    val onEditingLoraNameChange: ((Int, String) -> Unit)? = null,
    val onEditingLoraStrengthChange: ((Int, Float) -> Unit)? = null,

    // === ITI-Specific: Editing Mode Dual LoRA Chains ===
    val onAddEditingHighnoiseLora: (() -> Unit)? = null,
    val onRemoveEditingHighnoiseLora: ((Int) -> Unit)? = null,
    val onEditingHighnoiseLoraNameChange: ((Int, String) -> Unit)? = null,
    val onEditingHighnoiseLoraStrengthChange: ((Int, Float) -> Unit)? = null,
    val onAddEditingLownoiseLora: (() -> Unit)? = null,
    val onRemoveEditingLownoiseLora: ((Int) -> Unit)? = null,
    val onEditingLownoiseLoraNameChange: ((Int, String) -> Unit)? = null,
    val onEditingLownoiseLoraStrengthChange: ((Int, Float) -> Unit)? = null,

    // === 通用节点参数引擎（「节点参数」分组；改值即持久化，提交时由 ViewModel 统一替换） ===
    val onDynamicParamValueChange: ((String, String) -> Unit)? = null,
    // ITI 的图生图页与编辑页各有一套动态参数状态，编辑页走这个回调
    val onEditingDynamicParamValueChange: ((String, String) -> Unit)? = null,

    // === 输出端格式（v4.6.1 模块 C）：true = 保存为 WebP ===
    val onSaveAsWebpChange: ((Boolean) -> Unit)? = null
)
