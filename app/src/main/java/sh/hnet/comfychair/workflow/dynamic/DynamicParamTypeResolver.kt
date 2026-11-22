package sh.hnet.comfychair.workflow.dynamic

import sh.hnet.comfychair.workflow.InputDefinition
import sh.hnet.comfychair.workflow.NodeTypeRegistry

/**
 * 输入定义来源。生产实现委托 [NodeTypeRegistry]；单测注入假实现，
 * 让类型推导保持纯逻辑、可 JVM 单测。
 */
interface DynamicParamDefinitionSource {

    /** `(class_type, 输入名)` → 输入定义；查不到返回 null */
    fun inputDefinitionOf(classType: String, inputName: String): InputDefinition?

    /**
     * 取某个节点某个输入的**下拉选项**。设计约束 3：必须是 node-specific 的，
     * 绝不能用「把所有含同名字段的节点选项合并」那种全局取法 —— 本项目实测
     * `mode` 在服务器上有 144 个节点类在用，合并会串味（曾导致用户报的 400）。
     */
    fun optionsForNodeInput(classType: String, inputName: String): List<String>
}

/** 生产实现：直接委托 [NodeTypeRegistry]（选项走 node-specific 入口） */
class NodeTypeRegistryDefinitionSource(
    private val registry: NodeTypeRegistry
) : DynamicParamDefinitionSource {
    override fun inputDefinitionOf(classType: String, inputName: String): InputDefinition? =
        registry.getInputDefinition(classType, inputName)

    override fun optionsForNodeInput(classType: String, inputName: String): List<String> =
        registry.getOptionsForNodeInput(classType, inputName)
}

/**
 * 类型推导（纯逻辑，可 JVM 单测）。
 *
 * 由 `(class_type, 输入名)` 查 `/object_info` 的输入定义，归类成控件描述：
 *
 * | 服务器类型 | 控件 |
 * |---|---|
 * | `INT` / `FLOAT` | [DynamicParamControl.NumberField]（带 min / max / step） |
 * | `STRING`（multiline） | [DynamicParamControl.TextField] 多行 |
 * | `STRING` | [DynamicParamControl.TextField] 单行 |
 * | `BOOLEAN` | [DynamicParamControl.BoolField] |
 * | `COMBO` / `ENUM` | [DynamicParamControl.ComboField]（两种服务器格式都已在注册表里归一成 `ENUM`） |
 * | 取不到定义 | [DynamicParamControl.TextField] 单行（**不猜**） |
 *
 * 注意：`NodeTypeRegistry` 解析 `/object_info` 时已经把两种 COMBO 格式都归一成
 * `type = "ENUM"` + `options`（新格式 `["COMBO", {"options":[...]}]`、旧格式
 * `[["a","b"], {...}]`），所以这里以 `ENUM` 为准，同时兼容字面量 `"COMBO"`。
 */
object DynamicParamTypeResolver {

    /** `NodeTypeRegistry` 把两种 COMBO 格式归一后的类型名 */
    private const val TYPE_ENUM = "ENUM"

    /** 少数来源可能直接给字面量 COMBO，一并兼容 */
    private const val TYPE_COMBO = "COMBO"

    /**
     * App 端固定 COMBO 选项：占位符名 → 固定选项清单（模式枚举，与 SamplerStageRegistry
     * 的"登记即接管"先例同思路）。
     *
     * 这类占位符挂在通用输入上（如 CRZCompare 的 `a` 是 STRING），服务器定义推不出
     * "模式枚举"语义；不登记的话引擎只能渲染成一个文本框，让用户手抄标识符。
     * 命中本表的占位符**无视服务器定义**，直接渲染成选项固定的下拉。
     * 选项的中文显示名在 UI 侧（DynamicParamSection.fixedComboLabel）。
     */
    private val FIXED_COMBO_OPTIONS: Map<String, List<String>> = mapOf(
        // 出图模式占位符的固定选项（多档位采样模式，选项值由对应工作流的 defaults 提供）
        "moss_mode" to listOf("moss_25", "moss_spec25", "moss_50", "moss_c25", "moss_28")
    )

    /**
     * App 端固定控件类型：占位符名 → 控件描述（与 FIXED_COMBO_OPTIONS 同思路的"登记即接管"）。
     *
     * 用于「占位符落在连接类型字段下」的情形：rgthree Power Lora Loader 的 `lora_1` 在服务器
     * `/object_info` 里是连接类型而非 widget，查不到字段定义，类型推导退化成单行文本框，
     * 逼用户手打 `true` / `0.75`（移动端易错）。命中本表的占位符**无视服务器定义**，直接渲染成
     * 指定控件。与 FIXED_COMBO_OPTIONS 同时命中时**以下拉为准**（combo 检查在前；
     * 两表键集当前不相交，此规则只为未来重叠时行为确定）。
     *
     * default 与工作流 defaults 保持一致（取值顺序 defaults 优先于控件 default，两处一致
     * 才不会"面板初值"与"兜底语义"打架）：`lora*_on = false`；
     * `lora1/lora2_strength = 1.0`、`lora3_strength = 0.75`。
     * 强度范围按 rgthree LoRA 权重的合理区间取 `0..2`、步进 `0.05`（rgthree 支持负权重，
     * 但控件下限取 0 即可，不为负值放宽）。
     */
    private val FIXED_CONTROLS: Map<String, DynamicParamControl> = mapOf(
        // rgthree Power Lora Loader 三槽 LoRA 开关与强度（{{lora*_on}} / {{lora*_strength}}）
        "lora1_on" to DynamicParamControl.BoolField(default = false),
        "lora1_strength" to DynamicParamControl.NumberField(
            isInteger = false, min = 0.0, max = 2.0, step = 0.05, default = 1.0
        ),
        "lora2_on" to DynamicParamControl.BoolField(default = false),
        "lora2_strength" to DynamicParamControl.NumberField(
            isInteger = false, min = 0.0, max = 2.0, step = 0.05, default = 1.0
        ),
        "lora3_on" to DynamicParamControl.BoolField(default = false),
        "lora3_strength" to DynamicParamControl.NumberField(
            isInteger = false, min = 0.0, max = 2.0, step = 0.05, default = 0.75
        )
    )

    fun resolve(ref: DynamicParamRef, source: DynamicParamDefinitionSource): DynamicParam {
        val definition = source.inputDefinitionOf(ref.classType, ref.inputName)
        val control = controlFor(ref, definition, source)
        return DynamicParam(
            placeholder = ref.placeholder,
            nodeId = ref.nodeId,
            classType = ref.classType,
            inputName = ref.inputName,
            control = control
        )
    }

    private fun controlFor(
        ref: DynamicParamRef,
        definition: InputDefinition?,
        source: DynamicParamDefinitionSource
    ): DynamicParamControl {
        // App 端固定选项优先（见 FIXED_COMBO_OPTIONS）：服务器定义里的类型对模式枚举没有意义
        FIXED_COMBO_OPTIONS[ref.placeholder]?.let { options ->
            return DynamicParamControl.ComboField(options = options, default = null)
        }

        // App 端固定控件次之（见 FIXED_CONTROLS）：连接类型下的占位符查不到 widget 定义，
        // 不登记就会退化成文本框；命中即无视服务器定义
        FIXED_CONTROLS[ref.placeholder]?.let { return it }

        // 定义查不到 → 退化为单行文本框，不猜类型（设计约束 2）
        if (definition == null) {
            return DynamicParamControl.TextField(multiline = false, default = null)
        }

        return when (definition.type) {
            "INT" -> DynamicParamControl.NumberField(
                isInteger = true,
                min = definition.min?.toDouble(),
                max = definition.max?.toDouble(),
                step = definition.step?.toDouble(),
                default = definition.default
            )

            "FLOAT" -> DynamicParamControl.NumberField(
                isInteger = false,
                min = definition.min?.toDouble(),
                max = definition.max?.toDouble(),
                step = definition.step?.toDouble(),
                default = definition.default
            )

            "STRING" -> DynamicParamControl.TextField(
                multiline = definition.multiline,
                default = definition.default
            )

            "BOOLEAN" -> DynamicParamControl.BoolField(default = definition.default)

            TYPE_ENUM, TYPE_COMBO -> DynamicParamControl.ComboField(
                // 选项必须 node-specific 取（设计约束 3）；注册表返回空时退化到定义里自带的那份
                options = source.optionsForNodeInput(ref.classType, ref.inputName)
                    .ifEmpty { definition.options ?: emptyList() },
                default = definition.default
            )

            // 连接类型（MODEL / CLIP / LATENT …）等不可编辑输入：退化为文本框而不是猜
            else -> DynamicParamControl.TextField(multiline = false, default = null)
        }
    }
}
