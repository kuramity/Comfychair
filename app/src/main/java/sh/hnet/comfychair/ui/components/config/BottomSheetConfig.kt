package sh.hnet.comfychair.ui.components.config

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.runtime.Stable
import sh.hnet.comfychair.connection.CoreCategory
import sh.hnet.comfychair.model.LoraSelection
import sh.hnet.comfychair.model.SeedMode
import sh.hnet.comfychair.model.contract.WorkflowItemBase
import sh.hnet.comfychair.viewmodel.ImageToImageMode

/**
 * Unified configuration for the ConfigBottomSheetContent composable.
 * All fields are optional with visibility controlled by has* flags.
 */
@Stable
data class BottomSheetConfig(
    val workflow: WorkflowConfig,
    val prompts: PromptConfig,
    val itiConfig: ItiConfig? = null,
    val models: ModelConfig,
    val parameters: ParameterConfig,
    val lora: LoraConfig,
    /** 通用节点参数引擎的「节点参数」分组；null = 该屏幕不接（或工作流里没有动态参数） */
    val dynamic: DynamicParamConfig? = null,
    /**
     * 输出端格式开关（v4.6.1 模块 C）。
     * null = 该屏幕不展示（视频 / 3D / 不含 SaveImage 的工作流）。
     */
    val outputFormat: OutputFormatConfig? = null
)

/**
 * 输出端落盘格式配置（v4.6.1 模块 C）。
 *
 * 只在“输出为图片且工作流里有 SaveImage”时构造；视频与 3D 页面根本不传，
 * 因此开关不会出现在那些界面（需求硬性约束）。
 */
@Stable
data class OutputFormatConfig(
    val saveAsWebp: Boolean,
    val onSaveAsWebpChange: (Boolean) -> Unit
)

/**
 * Workflow dropdown configuration
 */
@Stable
data class WorkflowConfig(
    val selectedWorkflow: String,
    val availableWorkflows: List<WorkflowItemBase>,
    val onWorkflowChange: (String) -> Unit,
    val onViewWorkflow: () -> Unit
)

/**
 * Prompt fields configuration
 */
@Stable
data class PromptConfig(
    val negativePrompt: String,
    val onNegativePromptChange: (String) -> Unit,
    val hasNegativePrompt: Boolean = true
)

/**
 * ITI-specific configuration.
 *
 * 只有模式选择器：参考图已并入主区域的图位区（与图生视频同一套操作方式），
 * 底部面板不再承载任何图片入口。
 */
@Stable
data class ItiConfig(
    val mode: ImageToImageMode,
    val onModeChange: (ImageToImageMode) -> Unit
)

/**
 * Model selection configuration - all optional with visibility flags
 */
@Stable
data class ModelConfig(
    val checkpoint: ModelField? = null,
    val unet: ModelField? = null,
    val latentUpscaleModel: ModelField? = null,
    val upscale: ModelField? = null,
    val vae: ModelField? = null,
    val clip: ModelField? = null,
    val clip1: ModelField? = null,
    val clip2: ModelField? = null,
    val clip3: ModelField? = null,
    val clip4: ModelField? = null,
    val textEncoder: ModelField? = null,
    val highnoiseUnet: ModelField? = null,
    val lownoiseUnet: ModelField? = null,
    val highnoiseLora: ModelField? = null,
    val lownoiseLora: ModelField? = null
)

/**
 * Single model field configuration
 *
 * [strength] / [onStrengthChange] 是给"主 LoRA 下拉"用的可选权重滑块：
 * 两者都非空时在模型下拉下方渲染一条 0~2 的强度滑块，
 * 其余模型下拉不传（保持原样）。
 *
 * [category] 是 G8 指令 1 的接线：**UNET / VAE / CLIP 三类各传自己的类别**，
 * 下拉因此能读「这一类」的终态与失败原因，而不是整体 isLoading / lastError。
 * 于是「UNET 来源缺失、VAE/CLIP 正常」时，只有 UNET 行显示失败并可单独重试，
 * 另外两行照常出候选；UNET 也不会因为整体加载态没收敛而被拖成「正在加载」（E7）。
 * 其余下拉（LoRA / Checkpoint / 采样器 / 分辨率…）传 null = 沿用整体四态口径，行为不变。
 */
@Stable
data class ModelField(
    val label: Int,
    val selectedValue: String,
    val options: List<String>,
    val filteredOptions: List<String>? = null,
    val onValueChange: (String) -> Unit,
    val isVisible: Boolean = true,
    val strength: Float? = null,
    val onStrengthChange: ((Float) -> Unit)? = null,
    val category: CoreCategory? = null
)

/**
 * Generation parameter configuration
 */
@Stable
data class ParameterConfig(
    val width: NumericField? = null,
    val height: NumericField? = null,
    val steps: NumericField? = null,
    val cfg: NumericField? = null,
    val megapixels: NumericField? = null,
    val length: NumericField? = null,
    val fps: NumericField? = null,
    val sampler: DropdownField? = null,
    val scheduler: DropdownField? = null,
    // 视频分辨率选择器 / 加速模式
    val aspectRatio: DropdownField? = null,
    val speedMode: DropdownField? = null,
    val seed: SeedConfig? = null,
    val denoise: NumericField? = null,
    val batchSize: NumericField? = null,
    val upscaleMethod: DropdownField? = null,
    val scaleBy: NumericField? = null,
    val stopAtClipLayer: NumericField? = null
)

/**
 * Seed field configuration with mode selector and randomize controls
 */
@Stable
data class SeedConfig(
    /** 种子取值方式：固定 / 随机 / 每次+1 / 每次−1 */
    val seedMode: SeedMode,
    val onSeedModeChange: (SeedMode) -> Unit,
    val seed: String,
    val onSeedChange: (String) -> Unit,
    val onRandomizeSeed: () -> Unit,
    val seedError: String? = null,
    val isVisible: Boolean = true
)

/**
 * Numeric input field configuration
 */
@Stable
data class NumericField(
    val value: String,
    val onValueChange: (String) -> Unit,
    val error: String? = null,
    val isVisible: Boolean = true
)

/**
 * Dropdown field configuration (for sampler/scheduler)
 */
@Stable
data class DropdownField(
    val selectedValue: String,
    val options: List<String>,
    val onValueChange: (String) -> Unit,
    val isVisible: Boolean = true
)

/**
 * LoRA chain configuration
 */
@Stable
data class LoraConfig(
    val primaryChain: LoraChainField? = null,
    val loraName: ModelField? = null,  // Mandatory LoRA dropdown (from lora_name placeholder)
    val highnoiseChain: LoraChainField? = null,
    val lownoiseChain: LoraChainField? = null
)

/**
 * LoRA chain editor configuration
 */
@Stable
data class LoraChainField(
    val title: Int,
    val chain: List<LoraSelection>,
    val availableLoras: List<String>,
    val onAdd: () -> Unit,
    val onRemove: (Int) -> Unit,
    val onNameChange: (Int, String) -> Unit,
    val onStrengthChange: (Int, Float) -> Unit,
    val isVisible: Boolean = true
)
