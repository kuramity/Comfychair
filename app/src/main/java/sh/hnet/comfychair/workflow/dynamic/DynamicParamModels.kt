package sh.hnet.comfychair.workflow.dynamic

/**
 * 通用节点参数引擎 —— 数据模型（纯逻辑，无 Android 依赖，可 JVM 单测）。
 *
 * 机制：在工作流 JSON 里把某个节点输入的值写成 `{{占位符名}}`，App 扫描后查该输入的
 * `/object_info` 定义，按真实类型渲染控件。这样暴露插件节点的参数无需改 App 代码。
 *
 * 设计依据：`docs/superpowers/specs/2026-09-13-dynamic-node-params-design.md`
 */

/**
 * 控件描述：由 `/object_info` 的输入定义推导，UI 依此渲染。
 * 四类与设计文档的 `NumberField / TextField / BoolField / ComboField` 一一对应。
 */
sealed interface DynamicParamControl {

    /** 服务器输入定义里的 `default`（null = 定义里没给，不要猜） */
    val default: Any?

    /** `INT` / `FLOAT` → 数字输入框（带 min / max / step） */
    data class NumberField(
        /** true = INT（提交时必须是整数），false = FLOAT */
        val isInteger: Boolean,
        val min: Double?,
        val max: Double?,
        val step: Double?,
        override val default: Any?
    ) : DynamicParamControl

    /** `STRING` → 单行 / 多行文本框 */
    data class TextField(
        val multiline: Boolean,
        override val default: Any?
    ) : DynamicParamControl

    /** `BOOLEAN` → 开关 */
    data class BoolField(
        override val default: Any?
    ) : DynamicParamControl

    /**
     * `COMBO` → 下拉。两种服务器格式都会归一到这里：
     * - 新格式 `["COMBO", {"options": [...]}]`
     * - 旧格式 `[["a","b"], {...}]`
     */
    data class ComboField(
        val options: List<String>,
        override val default: Any?
    ) : DynamicParamControl
}

/**
 * 工作流里一个「未识别」占位符的定位信息（扫描产物）。
 * 同一个占位符只保留一条（首个命中节点），因为替换是全局文本替换、值由占位符名唯一决定。
 */
data class DynamicParamRef(
    val placeholder: String,
    /** 命中的节点 id（工作流 JSON 里 `nodes` 的键） */
    val nodeId: String,
    val classType: String,
    val inputName: String
) {
    /** 节点输入上的字面量形态，如 `{{my_steps}}` */
    val token: String get() = "{{$placeholder}}"
}

/** 定位 + 控件描述（类型推导产物），UI 直接消费 */
data class DynamicParam(
    val placeholder: String,
    val nodeId: String,
    val classType: String,
    val inputName: String,
    val control: DynamicParamControl
)

/** 一个占位符的最终取值（替换产物） */
data class DynamicParamValue(
    val param: DynamicParam,
    val value: String
)
