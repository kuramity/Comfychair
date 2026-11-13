package sh.hnet.comfychair.ui.components.config

import org.json.JSONObject
import sh.hnet.comfychair.workflow.TemplateKeyRegistry
import sh.hnet.comfychair.workflow.dynamic.DynamicParamScanner

/**
 * 「节点参数」分组需要向用户点明的两类**静默失效**。
 *
 * 通用节点参数引擎（`workflow/dynamic/`）的跳过规则本身是有意的（设计约束 1），
 * 但跳过时不留任何痕迹：用户改了工作流却什么都没发生，App 端毫无线索。
 * 这里在 UI 层把两种情况显式找出来，供分组渲染成提示条。
 */
data class DynamicParamIssues(
    /**
     * 名字不合法的占位符（不是 `[A-Za-z0-9_]+`，如 `my-steps`、`我的参数`、空名）。
     * 引擎既不索引也不替换，`{{...}}` 会被原样提交给服务器 —— 用户只看到服务器的校验错。
     */
    val invalidNames: List<String> = emptyList(),
    /**
     * 名字被系统内置参数占用、且放在了内置参数**不会填进去**的节点输入上的占位符
     * （如把 `{{steps}}` 写到插件节点的自定义输入上）。这类名字引擎会跳过，不会生成控件，
     * 用户需要知道「这些名字由内置参数处理」，免得以为是 App 漏了。
     *
     * 注意：**正常使用**内置占位符（`{{positive_prompt}}` 在 CLIPTextEncode.text /
     * MiniMaxH3ImageToVideo.prompt / PrimitiveStringMultiline.value、`{{steps}}` 在 KSampler.steps 等）
     * 不算问题，不会出现在这里 —— 否则任何工作流都会常驻一行提示。
     */
    val takenNames: List<String> = emptyList()
) {
    val hasInvalidNames: Boolean get() = invalidNames.isNotEmpty()
    val hasTakenNames: Boolean get() = takenNames.isNotEmpty()
}

/**
 * 扫描工作流 JSON，找出上述两类问题（纯逻辑，可 JVM 单测）。
 *
 * **只认「节点输入值位置」上的 token**：`description` / `name` / `defaults` 以及提示词文本里的
 * `{{...}}` 都不是节点输入，引擎不会（也不该）把它当参数，扫它们只会制造误报。
 * 节点表形态与引擎一致（`{"nodes":{...}}` 或裸节点表），复用 [DynamicParamScanner.nodesOf]。
 *
 * 与引擎的判定同源：名字规则 `\w`、已识别清单 [TemplateKeyRegistry.isRecognizedPlaceholder]。
 */
object DynamicParamIssueScanner {

    /** 合法占位符名：字母 / 数字 / 下划线（与引擎扫描正则里的 `\w` 一致） */
    private val VALID_NAME = Regex("""^[A-Za-z0-9_]+$""")

    /**
     * 任意 `{{...}}`，**不要求名字合法** —— 正是要把引擎忽略的形态也捞出来。
     * 名字部分不允许含花括号，避免把 `{{a}}` 之外的杂音吞进来。
     * 只用节点输入的**字符串值**做匹配，所以嵌入在更大文本里的 token 也会被看到。
     */
    private val ANY_TOKEN = Regex("""\{\{([^{}]*)\}\}""")

    /**
     * 服务器上**真实承载提示词文本**的节点输入名。
     *
     * 为什么提示词要单列一张表：正/负向提示词在 5 条 `prepare*WorkflowById` 里是**全文替换**
     * （`json.replace("{{positive_prompt}}", …)`，见 `WorkflowManager.kt` 的 5 处），
     * **不限定输入名** —— 只要某个节点输入的字符串值里写了这个 token 就会被替换。
     * 因此 [sh.hnet.comfychair.workflow.TemplateKeyRegistry.doesInputKeyMatchField] 那套
     * 「字段名 → 目标键 + 别名键」的判定对提示词是不完整的：真正的承载输入是
     * `CLIPTextEncode.text`、`MiniMaxH3ImageToVideo.prompt`、`PrimitiveStringMultiline.value` 等，
     * 必须逐个登记，否则 prebuilt_08/09/13/14/15/16 这些**正常用法**都会被误判成「争抢」。
     *
     * 清单来源：
     * - 16 个预置工作流扫描出的三种形态（`text` / `prompt` / `value`）；
     * - 本服务器 `/object_info` 上 CLIP 文本编码器的标准提示词输入（`text_g` / `text_l`，`CLIPTextEncodeSDXL`）；
     * - 通用字符串节点的输入名（`String`，如 `String` / `KepStringLiteral` 节点）。
     */
    private val PROMPT_TEXT_INPUTS: Set<String> = setOf(
        // CLIPTextEncode：正/负向提示词走图追踪注入，落在 text（不是映射表里的 positive_text）
        "text",
        // CLIPTextEncodeSDXL：SDXL 双编码器
        "text_g",
        "text_l",
        // MiniMaxH3ImageToVideo / MiniMaxH3ReferenceToVideo / Krea2EditGroundedEncode 等
        "prompt",
        // PrimitiveStringMultiline 等字符串常量节点
        "value",
        // String / KepStringLiteral 等自定义字符串节点
        "String"
    )

    /**
     * 已识别占位符的「内置目标输入名」补充别名（映射表之外的额外承载输入）。
     */
    private val CANONICAL_INPUT_ALIASES: Map<String, Set<String>> = mapOf(
        "positive_prompt" to PROMPT_TEXT_INPUTS,
        "negative_prompt" to PROMPT_TEXT_INPUTS,
        // 内置的「放大倍数」字段（`scale_by`，由 WorkflowManager.replaceCommonPlaceholders 全文替换）
        // 在两个节点家族上语义相同、输入名不同：ImageScaleBy 的输入就叫 `scale_by`，
        // UltimateSDUpscale 的等价输入叫 `upscale_by`。语义经服务器 `/object_info` +
        // 既有资产核实（prebuilt_19 的 USD 靠它、prebuilt_05/06 的 USD 用公式链喂它）。
        // 不登记的话 prebuilt_19 会弹一条「有占位符名由内置参数处理」的中性提示 —— 那是误报：
        // 这个位置正是内置字段要填的地方（Fix Round 1 / I-5）。
        // 这里刻意【不】改 `TemplateKeyRegistry.ALTERNATIVE_INPUT_KEYS`：那份表同时驱动字段映射引擎的
        // 候选选择（`FieldMappingAnalyzer.findCandidatesForField`，保存时 `applyFieldMappings` 会往
        // 选中的输入写 `{{占位符}}`），改它会让**存量用户工作流**在编辑器保存时被悄悄改接，
        // 超出这条缺陷的范围（缺陷只出在提示判定这一层）。
        "scale_by" to setOf("upscale_by")
    )

    /**
     * @param workflowJson 工作流文档（本项目封装格式或裸节点表都行）
     */
    fun scan(workflowJson: String): DynamicParamIssues {
        val root = try {
            JSONObject(workflowJson)
        } catch (e: Exception) {
            return DynamicParamIssues()
        }

        // 去重但保持首次出现顺序，提示条里读起来与工作流一致
        val invalid = LinkedHashSet<String>()
        val taken = LinkedHashSet<String>()

        // 节点按引擎同一套规则排序（数字感知）、输入名按字典序：org.json 的 JSONObject 是哈希表，
        // 不排序的话提示条里"前 3 个"每次都可能不同
        val nodes = DynamicParamScanner.nodesOf(root)
        val nodeIds = nodes.keys().asSequence().toList().sortedWith(DynamicParamScanner::compareNodeIds)
        for (nodeId in nodeIds) {
            val node = nodes.optJSONObject(nodeId) ?: continue
            if (node.optString("class_type", "").isEmpty()) continue
            val inputs = node.optJSONObject("inputs") ?: continue

            for (inputName in inputs.keys().asSequence().toList().sorted()) {
                // 连线是 JSONArray、数值/布尔也不是字符串；只有字符串值可能承载 token
                val value = inputs.opt(inputName) as? String ?: continue
                ANY_TOKEN.findAll(value).forEach { match ->
                    val name = match.groupValues[1]
                    when {
                        !VALID_NAME.matches(name) -> invalid.add(name)
                        TemplateKeyRegistry.isRecognizedPlaceholder(name) &&
                            !isCanonicalPlacement(name, inputName) -> taken.add(name)
                    }
                }
            }
        }

        return DynamicParamIssues(invalidNames = invalid.toList(), takenNames = taken.toList())
    }

    /**
     * 判断某个已识别占位符是不是落在**内置参数本来管辖（= 会被内置值填进去）的**那个输入上。
     * 是 → 内置参数处理它，不会在「节点参数」里再出现一个控件（**不是问题**，不提示）；
     * 否 → 该名字确实被内置参数占用，可以中性说明一句。
     */
    private fun isCanonicalPlacement(name: String, inputName: String): Boolean {
        if (CANONICAL_INPUT_ALIASES[name]?.contains(inputName) == true) return true
        return TemplateKeyRegistry.doesInputKeyMatchField(name, inputName)
    }
}
