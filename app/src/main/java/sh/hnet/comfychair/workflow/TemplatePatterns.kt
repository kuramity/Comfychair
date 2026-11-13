package sh.hnet.comfychair.workflow

/**
 * 占位符正则的统一定义。
 *
 * 这些正则原先在调用点现场构造，而 `Regex(...)` 每次执行都会重新编译 pattern：
 * - [sh.hnet.comfychair.ui.components.WorkflowGraphCanvas] 的 formatInputValue 在**绘制阶段**
 *   每帧、每个字面量输入调用一次（配合常驻动画，1 秒 60 次 × 节点数 × 输入数）；
 * - [WorkflowParser.parse] 在"每节点每输入"的循环里构造；
 * - [sh.hnet.comfychair.WorkflowManager] 的两处每次扫描工作流时构造。
 *
 * 提升为进程级常量后这些开销归零，且全项目只剩一处定义。
 */
object TemplatePatterns {

    /** 匹配 `{{placeholder_name}}`，捕获组 1 为占位符名（与 TemplateKeyRegistry 的键同构）。 */
    val PLACEHOLDER: Regex = Regex("""\{\{(\w+)\}\}""")
}
