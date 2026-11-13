package sh.hnet.comfychair.workflow.dynamic

import java.math.BigDecimal

/**
 * 动态参数的**数值范围约束**（纯逻辑，可 JVM 单测）。
 *
 * ## 为什么需要这一层
 *
 * 服务器 `validate_inputs()`（ComfyUI 的 `execution.py`）**沿全部连线递归校验**，
 * `lazy` 只影响 `comfy_execution/graph.py` 的**执行表**、不影响校验 —— 于是
 * 「关掉补采」并不会让那个越界值消失：`hires_steps1 = 0`（节点 85 的 `steps` 最小 1）
 * 会让整单 400 `value_smaller_than_min`，而报错点名的却是一个"已经关掉"的节点，
 * 用户完全看不懂。所以界面上既要说清"关掉≠不校验"，也不能让越界值真的发出去。
 *
 * 范围本身**不需要额外数据源**：`/object_info` 的 `min` / `max` 已经被
 * [NodeTypeRegistryDefinitionSource] 取出，经 [DynamicParamTypeResolver] 变成
 * [DynamicParamControl.NumberField.min] / [DynamicParamControl.NumberField.max]，
 * 再经 UI 侧 `DynamicParamField.Control.Number` 一路带到控件。本对象只做两件事：
 *
 * 1. [violationOf]：当前输入是否越界（UI 据此立即标红并写明"会被取成哪个值"）
 * 2. [clampForSubmit]：提交前把越界值夹到最近的合法值（其余值**原样返回**）
 *
 * ## 能力边界（刻意不做的事，改本文件前先读）
 *
 * - **非数字控件 / 空串 / 解析不出数字 / 整型控件拿到小数** → 一律不处理。
 *   这些不是"范围"问题，且既有链路（[DynamicParamValues] 保留占位符让服务器报错）
 *   已经覆盖，本层不改变那条行为。
 * - **不做步长对齐**：`step` 只控制滑块粒度，夹到上下界得到的本身就是合法值；
 *   按 step 取整反而可能（浮点误差下）夹出界。
 * - **不做"输入即夹紧"**：用户的文本框内容会被逐键写回 UiState，若边打边夹，
 *   打 `0.` 这类中间态会被吃掉后续输入（想输 0.5，`0` 就被改成 `0.01`）。所以
 *   UI 只提示不夹，夹紧只发生在提交那一刻。
 * - 服务器对定义了 `VALIDATE_INPUTS` 的节点**不查 min/max**，那种节点的越界值本来
 *   能提交成功，本层会把它夹掉。已核对：本项目全部预置资产里带范围约束的动态参数
 *   落在 `KSampler` / `KSamplerAdvanced` / `ImageScaleBy` / `UltimateSDUpscale` /
 *   `ResolutionSelector` / `ImageScaleToTotalPixels` / `EmptyLatentImage` /
 *   `BasicScheduler` / `LoraLoaderModelOnly` / `IllustriousTriplePassSampler` 上，
 *   这些类**都没有** `VALIDATE_INPUTS`，故不受影响。
 */
object DynamicParamRange {

    /** 越界方向 */
    enum class Violation { BELOW_MIN, ABOVE_MAX }

    /**
     * 一次越界判定。
     *
     * @param violation 越界方向（[Violation.BELOW_MIN] / [Violation.ABOVE_MAX]）
     * @param clamped 会被实际发出去的值（已按控件类型规范化，可直接当提示文案）
     */
    data class OutOfRange(val violation: Violation, val clamped: String)

    /**
     * 越界判定。不越界、或无法判定（缺 min/max、非数字控件、非数值输入）时返回 null。
     *
     * @param isInteger 控件是否为 INT（整型控件拿到小数视为"不是范围问题"，返回 null）
     */
    fun violationOf(value: String, isInteger: Boolean, min: Double?, max: Double?): OutOfRange? {
        if (min == null && max == null) return null
        val parsed = parseNumber(value, isInteger) ?: return null
        if (min != null && parsed < min) {
            return OutOfRange(Violation.BELOW_MIN, formatBound(min, isInteger, isMin = true))
        }
        if (max != null && parsed > max) {
            return OutOfRange(Violation.ABOVE_MAX, formatBound(max, isInteger, isMin = false))
        }
        return null
    }

    /** [violationOf] 的控件重载（非数字控件恒为 null）。 */
    fun violationOf(value: String, control: DynamicParamControl): OutOfRange? =
        when (control) {
            is DynamicParamControl.NumberField -> violationOf(value, control.isInteger, control.min, control.max)
            else -> null
        }

    /**
     * 提交前夹紧：越界 → 最近的合法值；**其余情况一律原样返回**
     * （含非数字控件、空串、解析不出数字、整型控件的小数 —— 这些交给既有行为）。
     *
     * 夹紧结果一定是能通过服务器 `min <= v <= max` 校验的数字字面量：
     * 上界用 `floor` / 下界用 `ceil`（整数控件），且字符串形态与数值严格可逆。
     */
    fun clampForSubmit(value: String, control: DynamicParamControl): String =
        violationOf(value, control)?.clamped ?: value

    /**
     * 一批取值里的越界项（占位符 → 判定结果）。供提交路径打一条日志用：
     * 「用户看到的输入」与「实际发出去的值」不一致时，日志必须留痕，否则出图结果对不上时无从追起。
     */
    fun outOfRangeIn(values: List<DynamicParamValue>): List<Pair<String, OutOfRange>> =
        values.mapNotNull { entry ->
            violationOf(entry.value, entry.param.control)?.let { entry.param.placeholder to it }
        }

    /**
     * 解析成数值。**不接受的输入一律返回 null**（不猜、不四舍五入）：
     * 空串、非数字、Infinity/NaN、以及整型控件里的小数。
     */
    private fun parseNumber(value: String, isInteger: Boolean): Double? {
        val text = value.trim()
        if (text.isEmpty()) return null
        val parsed = text.toDoubleOrNull() ?: return null
        if (!parsed.isFinite()) return null
        if (isInteger && parsed != Math.floor(parsed)) return null
        return parsed
    }

    /**
     * 上下界的字符串形态。
     *
     * 整型控件先取 `ceil(min)` / `floor(max)`：服务器偶有非整数界的 INT 输入，
     * 直接发 `0.5` 会被 [DynamicParamValues] 的整型判定挡下（占位符不替换），反而回到 400。
     */
    private fun formatBound(bound: Double, isInteger: Boolean, isMin: Boolean): String {
        val normalized = if (!isInteger) {
            bound
        } else if (isMin) {
            Math.ceil(bound)
        } else {
            Math.floor(bound)
        }
        return plainString(normalized)
    }

    /** 去掉科学计数法与多余的 `.0`：`1.0` → `1`，`1.0E-5` → `0.00001` */
    private fun plainString(value: Double): String =
        BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()
}
