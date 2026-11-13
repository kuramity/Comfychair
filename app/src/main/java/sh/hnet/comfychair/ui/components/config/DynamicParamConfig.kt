package sh.hnet.comfychair.ui.components.config

import androidx.compose.runtime.Immutable
import sh.hnet.comfychair.workflow.dynamic.DynamicParam
import sh.hnet.comfychair.workflow.dynamic.DynamicParamControl

/**
 * 「节点参数」分组的渲染数据（UI 侧模型）。
 *
 * 与逻辑层的 [DynamicParamControl] 一一对应但**只保留 UI 需要的东西**：
 * 类型、范围提示、选项。默认值不在其中 —— 初值已由
 * `WorkflowManager.resolveDynamicParamValues` 按「保存值 → defaults → 节点默认值」解析好，
 * 直接取 [DynamicParamField.value] 即可。
 */
@Immutable
data class DynamicParamField(
    /** 工作流里的占位符名，如 `test_param`；控件上以 `{{test_param}}` 显示 */
    val placeholder: String,
    /** 归属节点，形如 `PathchSageAttentionKJ.allow_compile`（逻辑层没保留 tooltip，用这个当标签） */
    val nodeLabel: String,
    /** 当前值（已解析默认值链） */
    val value: String,
    val control: Control
) {
    @Immutable
    sealed interface Control {
        /** `INT` / `FLOAT` → 数字输入；[min] / [max] / [step] 只做提示，没给就不显示 */
        data class Number(
            val isInteger: Boolean,
            val min: Double?,
            val max: Double?,
            val step: Double?
        ) : Control

        /** `STRING` → [multiline] 为 true 用多行框 */
        data class Text(val multiline: Boolean) : Control

        /** `BOOLEAN` → 开关；[checked] 由已解析的值得到 */
        data class Bool(val checked: Boolean) : Control

        /**
         * `COMBO` → 下拉。
         * [options] 为空 = 节点定义还没拿到（注册表未就绪 / 该输入无选项），
         * UI 要显示「选项未就绪」而不是给一个空框让用户瞎填。
         */
        data class Combo(val options: List<String>) : Control
    }
}

/**
 * 配置面板「节点参数」分组的入参。
 *
 * [workflowId] 供分组在渲染时扫工作流 JSON 找出静默失效项（见 [DynamicParamIssueScanner]）；
 * 扫描只在分组真正渲染时做一次，按 workflowId 缓存。
 */
@Immutable
data class DynamicParamConfig(
    val workflowId: String,
    val fields: List<DynamicParamField>,
    /**
     * [fields] 是**在哪一次快照下推导出来的**：true = 推导时 `/object_info` 已就绪，
     * 控件类型可信；false = 类型可能已退化（INT 变文本框等）。
     *
     * 必须由 UiState 与 [fields] 一起传进来 —— 不能由 UI 直接读
     * `ConnectionManager.nodeTypeRegistry`：那是普通 map，读取不建立订阅，而本类是
     * `@Immutable` 的 Composable 参数，注册表就绪后不一定会重组，提示条会挂死不消。
     */
    val registryReady: Boolean = false,
    val onValueChange: (String, String) -> Unit
)

/**
 * 把逻辑层的 [DynamicParam] 清单 + 已解析的值，映射成 UI 渲染数据。
 *
 * 控件清单为空时仍返回配置（[DynamicParamConfig.fields] 为空）—— 分组还要靠它渲染
 * 「占位符名不合法被跳过」这类提示（那种工作流恰恰一个控件都没有）。
 */
internal fun buildDynamicParamConfig(
    workflowId: String,
    params: List<DynamicParam>,
    values: Map<String, String>,
    registryReady: Boolean = false,
    onValueChange: (String, String) -> Unit
): DynamicParamConfig = DynamicParamConfig(
    workflowId = workflowId,
    fields = params.map { it.toUiField(values[it.placeholder].orEmpty()) },
    registryReady = registryReady,
    onValueChange = onValueChange
)

private fun DynamicParam.toUiField(value: String): DynamicParamField = DynamicParamField(
    placeholder = placeholder,
    nodeLabel = if (classType.isEmpty()) inputName else "$classType.$inputName",
    value = value,
    control = when (val c = control) {
        is DynamicParamControl.NumberField ->
            DynamicParamField.Control.Number(c.isInteger, c.min, c.max, c.step)

        is DynamicParamControl.TextField ->
            DynamicParamField.Control.Text(c.multiline)

        is DynamicParamControl.BoolField ->
            DynamicParamField.Control.Bool(value.equals("true", ignoreCase = true))

        is DynamicParamControl.ComboField ->
            DynamicParamField.Control.Combo(c.options)
    }
)
