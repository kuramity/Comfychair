package sh.hnet.comfychair.viewmodel.base

import android.content.Context
import androidx.compose.runtime.Immutable
import sh.hnet.comfychair.lora.LoraChainManager
import sh.hnet.comfychair.model.LoraSelection
import sh.hnet.comfychair.model.SeedMode
import sh.hnet.comfychair.util.ValidationUtils
import sh.hnet.comfychair.workflow.dynamic.DynamicParam

/**
 * 四个生成页（文生图 / 图生图 / 文生视频 / 图生视频）**共享的生成参数集合**，
 * 以及每个字段的纯校验函数 [withXxx]。
 *
 * 背景（任务书 6.1 第一步）：`BaseGenerationViewModel` 只抽了 `_uiState` / `_events` /
 * `initialize`，真正的「参数集合 + 校验」一直被逐字节复制在四个子类里
 * （同名函数 51 个、函数体完全相同 58 个），改一条校验规则要改 4 处。
 * 本类把「字段 + 校验」收敛成单一来源，校验逻辑**全部委托**
 * [ValidationUtils]（四处现状的同一委托目标），规则不可能漂移。
 *
 * 设计约束：
 * - 纯函数：不读文件 / 网络 / 系统属性；`ctx` 只用来 `getString(R.string.xxx)`
 *   取本地化错误消息，传 null 时落回 [ValidationUtils] 内置的英文兜底文案
 *   （与现有 JVM 单测 `ValidationUtils.validateXxx(value, null)` 的用法一致）。
 * - 错误表达与现状一致：`String?`（已本地化的消息文本），不是错误码。
 * - 带 min/max 参数：签名与 [ValidationUtils] 对齐，调用方可传各自的范围；
 *   默认值就是现状的默认阈值，**不得**借迁移之机改阈值。
 * - 模式差异留在各自 ViewModel：例如图生图编辑页 `editingSteps` 不做校验
 *   （`ImageToImageViewModel.onEditingStepsChange`）、`clip_gguf` 门控联动、
 *   视频长度校验 `ValidationUtils.validateVideoLength`（1..129 且步长 4）都不在这里。
 *
 * 默认值取文生图页（`TextToImageUiState`）的口径；视频两页的 width/height
 * 默认是 "848"/"480"，由各 ViewModel 构造时显式传入，不在本类里二选一。
 */
@Immutable
data class GenerationParams(
    // Prompts（现状无校验，直接赋值）
    val positivePrompt: String = "",
    val negativePrompt: String = "",

    // Model selections（现状无输入校验；候选合法性由 modelCache 收集链上的
    // validateModelSelection 把关，那需要候选列表，不是本类字段的职责）
    val selectedCheckpoint: String = "",
    val selectedUnet: String = "",
    val selectedVae: String = "",
    val selectedClip: String = "",   // 单 CLIP（clip_name 占位符）
    val selectedClip1: String = "",  // 多 CLIP 槽 1（clip_name1）
    val selectedClip2: String = "",  // 多 CLIP 槽 2（clip_name2）
    val selectedClip3: String = "",  // 多 CLIP 槽 3（clip_name3）
    val selectedClip4: String = "",  // 多 CLIP 槽 4（clip_name4）

    // Unified LoRA chain（增删改委托 LoraChainManager，四处现状同一委托目标）
    val loraChain: List<LoraSelection> = emptyList(),

    // 动态节点参数（通用节点参数引擎）：控件清单 + 用户当前值。
    // clip_gguf 门控对 CLIP 候选的联动是模式差异，留在各 ViewModel。
    val dynamicParams: List<DynamicParam> = emptyList(),
    val dynamicParamValues: Map<String, String> = emptyMap(),

    // Unified generation parameters（与四个 UiState 同名字段同口径）
    val width: String = "1024",
    val height: String = "1024",
    val steps: String = "20",
    val cfg: String = "7.0",
    val sampler: String = "euler",
    val scheduler: String = "normal",
    val seedMode: SeedMode = SeedMode.RANDOM,
    val seed: String = "0",
    val denoise: String = "1.0",
    val batchSize: String = "1",

    // Validation errors（现状只有这 7 个数值字段有 error；表达方式 String? 同 UiState）
    val widthError: String? = null,
    val heightError: String? = null,
    val stepsError: String? = null,
    val cfgError: String? = null,
    val seedError: String? = null,
    val denoiseError: String? = null,
    val batchSizeError: String? = null,
) {
    // ===========================================
    // 提示词（现状无校验）
    // ===========================================

    /** 正面提示词：四处现状都是直接赋值，无校验。 */
    fun withPositivePrompt(raw: String): GenerationParams =
        copy(positivePrompt = raw)

    /** 负面提示词：四处现状都是直接赋值，无校验。 */
    fun withNegativePrompt(raw: String): GenerationParams =
        copy(negativePrompt = raw)

    // ===========================================
    // 模型选择（现状无输入校验，直接赋值）
    // ===========================================

    fun withCheckpoint(raw: String): GenerationParams = copy(selectedCheckpoint = raw)

    fun withUnet(raw: String): GenerationParams = copy(selectedUnet = raw)

    fun withVae(raw: String): GenerationParams = copy(selectedVae = raw)

    fun withClip(raw: String): GenerationParams = copy(selectedClip = raw)

    fun withClip1(raw: String): GenerationParams = copy(selectedClip1 = raw)

    fun withClip2(raw: String): GenerationParams = copy(selectedClip2 = raw)

    fun withClip3(raw: String): GenerationParams = copy(selectedClip3 = raw)

    fun withClip4(raw: String): GenerationParams = copy(selectedClip4 = raw)

    fun withSampler(raw: String): GenerationParams = copy(sampler = raw)

    fun withScheduler(raw: String): GenerationParams = copy(scheduler = raw)

    fun withSeedMode(raw: SeedMode): GenerationParams = copy(seedMode = raw)

    // ===========================================
    // 数值参数（校验委托 ValidationUtils，规则单一来源）
    // ===========================================

    /**
     * 宽度：整数 1..4096（`validateDimension` 默认阈值，四处现状一致）。
     * 空串视为「尚未输入」，不报错（现状口径）。
     */
    fun withWidth(
        raw: String,
        ctx: Context? = null,
        min: Int = 1,
        max: Int = 4096,
    ): GenerationParams = copy(
        width = raw,
        widthError = ValidationUtils.validateDimension(raw, ctx, min, max)
    )

    /** 高度：整数 1..4096，规则同 [withWidth]。 */
    fun withHeight(
        raw: String,
        ctx: Context? = null,
        min: Int = 1,
        max: Int = 4096,
    ): GenerationParams = copy(
        height = raw,
        heightError = ValidationUtils.validateDimension(raw, ctx, min, max)
    )

    /**
     * 步数：整数 1..255（四处现状一致）。
     *
     * 注意模式差异：图生图**编辑页**的 `editingSteps` 现状不校验
     * （`ImageToImageViewModel.onEditingStepsChange` 直接赋值），
     * 那是编辑页特有字段，不进本类；迁移时调用方按现状决定调不调本函数。
     */
    fun withSteps(
        raw: String,
        ctx: Context? = null,
        min: Int = 1,
        max: Int = 255,
    ): GenerationParams = copy(
        steps = raw,
        stepsError = ValidationUtils.validateSteps(raw, ctx, min, max)
    )

    /** CFG：浮点 0.0..100.0（四处现状一致）。 */
    fun withCfg(
        raw: String,
        ctx: Context? = null,
        min: Float = 0.0f,
        max: Float = 100.0f,
    ): GenerationParams = copy(
        cfg = raw,
        cfgError = ValidationUtils.validateCfg(raw, ctx, min, max)
    )

    /**
     * 种子：整数 ≥ 0（只查下限，四处现状一致——上限由工作流节点的
     * `WorkflowConstraintsProvider` 约束管，不进输入校验）。
     */
    fun withSeed(
        raw: String,
        ctx: Context? = null,
        min: Long = 0,
    ): GenerationParams = copy(
        seed = raw,
        seedError = ValidationUtils.validateSeed(raw, ctx, min)
    )

    /** 去噪强度：浮点 0.0..1.0（四处现状一致）。 */
    fun withDenoise(
        raw: String,
        ctx: Context? = null,
        min: Float = 0.0f,
        max: Float = 1.0f,
    ): GenerationParams = copy(
        denoise = raw,
        denoiseError = ValidationUtils.validateDenoise(raw, ctx, min, max)
    )

    /** 单批数量：整数 1..64（四处现状一致）。 */
    fun withBatchSize(
        raw: String,
        ctx: Context? = null,
        min: Int = 1,
        max: Int = 64,
    ): GenerationParams = copy(
        batchSize = raw,
        batchSizeError = ValidationUtils.validateBatchSize(raw, ctx, min, max)
    )

    // ===========================================
    // LoRA 链（委托 LoraChainManager，保留「无变化返回原对象」语义）
    // ===========================================

    /**
     * 整条替换 LoRA 链（载入工作流保存值用）。
     * 各 ViewModel 载入时的 `fromJsonString` 解析留在外面——它依赖存储层。
     */
    fun withLoraChain(raw: List<LoraSelection>): GenerationParams =
        copy(loraChain = raw)

    /**
     * 添加一个空名 LoRA（下拉里的「（不使用 LoRA）」）。
     * 链满或无候选时 [LoraChainManager.addLora] 原样返回旧链，本函数随之返回
     * **this**（引用相等），调用方可以照现状用 `===` 判断跳过写状态与落盘。
     */
    fun addLora(availableLoras: List<String>): GenerationParams {
        val newChain = LoraChainManager.addLora(loraChain, availableLoras)
        if (newChain === loraChain) return this
        return copy(loraChain = newChain)
    }

    /** 移除指定位置的 LoRA；索引无效时返回 this（理由同 [addLora]）。 */
    fun removeLora(index: Int): GenerationParams {
        val newChain = LoraChainManager.removeLora(loraChain, index)
        if (newChain === loraChain) return this
        return copy(loraChain = newChain)
    }

    /**
     * 改指定位置 LoRA 的名字（经 [LoraSelection.normalizeName] 归一化，
     * "None" 等历史遗留写法落成空串）；索引无效时返回 this。
     */
    fun withLoraName(index: Int, name: String): GenerationParams {
        val newChain = LoraChainManager.updateLoraName(loraChain, index, name)
        if (newChain === loraChain) return this
        return copy(loraChain = newChain)
    }

    /** 改指定位置 LoRA 的强度（clamp 到合法区间）；索引无效时返回 this。 */
    fun withLoraStrength(index: Int, strength: Float): GenerationParams {
        val newChain = LoraChainManager.updateLoraStrength(loraChain, index, strength)
        if (newChain === loraChain) return this
        return copy(loraChain = newChain)
    }

    /** LoRA 链按可用候选过滤（模型缓存刷新链上用）。 */
    fun filterLoraChain(availableLoras: List<String>): GenerationParams {
        val newChain = LoraChainManager.filterUnavailable(loraChain, availableLoras)
        if (newChain === loraChain) return this
        return copy(loraChain = newChain)
    }

    // ===========================================
    // 动态节点参数（通用节点参数引擎）
    // ===========================================

    /** 整份替换控件清单（工作流切换 / 注册表就绪重算用）。 */
    fun withDynamicParams(raw: List<DynamicParam>): GenerationParams =
        copy(dynamicParams = raw)

    /**
     * 更新某个占位符的值（基础更新，四处现状一致）。
     *
     * 模式差异留在各 ViewModel：文生图 / 图生图在 `clip_gguf` 变化时还要联动
     * 重过滤 CLIP 候选并重挑选中值（`pickClipsAfterGate`），那需要 modelCache
     * 与选中值上下文，不在本纯函数里。
     */
    fun withDynamicParamValue(placeholder: String, value: String): GenerationParams =
        copy(dynamicParamValues = dynamicParamValues + (placeholder to value))
}
