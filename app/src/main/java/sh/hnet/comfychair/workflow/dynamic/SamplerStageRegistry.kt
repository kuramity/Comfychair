package sh.hnet.comfychair.workflow.dynamic

/**
 * 采样阶段识别（纯逻辑，可 JVM 单测）。
 *
 * 命名约定来自 `tools/comfyui-scripts/inject_stage_placeholders.py`：
 * - **竖叉流（illustrious-Noob AI）** 的三阶段沿用原字段前缀 `comp_` / `struct_` / `detail_`
 *   （IllustriousTriplePassSampler 一张节点统合三阶段采样，共 15 个字段）
 * - **没有前缀可依的第二个采样器**（Krea2 二采/高清重绘）用 `stage2_`
 * - **放大后的补采**（极清放大阶段）用 `hires_`（Krea2 极清流的 SeedVR2 前预放大倍数、
 *   竖叉流的 1~3 次可开关补采都归到这一段；补采的轮次写成后缀，如 `hires_steps2`）
 *
 * 识别结果供 UI 把控件归入「阶段卡片」并换成中文标签；
 * 认不出的占位符返回 null，仍按普通「节点参数」平铺渲染，不受影响。
 */
object SamplerStageRegistry {

    /**
     * 一个采样阶段。
     *
     * @param index 决定卡片顺序（1 在前）
     * @param key   供 UI 取标题与用途名（"comp" → 「构图」）
     */
    data class Stage(val index: Int, val key: String)

    /**
     * 阶段参数的解析结果。
     *
     * @param paramKey 去掉阶段前缀后的参数名（"comp_steps" → "steps"），供 UI 取中文标签
     */
    data class StageParam(val stage: Stage, val paramKey: String)

    /** 已知前缀 → 阶段。新增多阶段工作流时在这里登记即可。 */
    private val KNOWN: List<Pair<String, Stage>> = listOf(
        "comp_" to Stage(1, "comp"),
        "struct_" to Stage(2, "struct"),
        "detail_" to Stage(3, "detail"),
        "stage2_" to Stage(2, "stage2"),
        // 极清放大：放大之后的补采/预放大倍数（prebuilt_11/19/20）
        "hires_" to Stage(4, "hires"),
        // CLIP 加载器格式门控（prebuilt_01/21，2026-09-23）：`clip_gguf` 一个开关。
        // 它严格说不是"采样阶段"，但要的是同一套能力（卡片分组 + 中文标签 + 说明行），
        // 而 UI 的中文标签入口就挂在本表上 —— 不登记的话界面上只有一串 `{{clip_gguf}}`。
        "clip_" to Stage(5, "clip"),
        // VOSR2 开关式放大（prebuilt_22/23，2026-09-24）：`vosr2_on` 一个开关，
        // 决定出图后要不要再过一遍 VOSR2。同样借这套分组能力拿中文标题与说明行。
        "vosr2_" to Stage(6, "vosr2"),
        // PE 提示词强化（prebuilt_22/23，2026-09-25）：`pe_on` 开关 + `pe_model` /
        // `pe_mmproj` 两个模型下拉，决定出图前要不要先把提示词交给本地模型改写一遍
        "pe_" to Stage(7, "pe"),
    )

    /**
     * 分次补采的参数名（竖叉流 `prebuilt_11` 的 1~3 次可开关补采，2026-09-22 Fix Round 2）。
     *
     * 每轮三个：`on<轮次>`（开关）、`steps<轮次>`（步数）、`denoise<轮次>`（强度）；
     * 采样器/调度器/CFG 三轮共用（`hires_sampler` / `hires_scheduler` / `hires_cfg`，已在 [PARAM_KEYS] 里）。
     *
     * 轮次后缀只到 3：资产里就是三个 KSampler（85/87/89），多认会让界面出现没有落点的"幽灵参数"。
     * 前导的 `hires_` 由 [KNOWN] 统一剥掉，所以这里只写后缀部分。
     *
     * 声明在 [PARAM_KEYS] **之前**：object 的属性初始化按书写顺序执行，反过来引用会读到未初始化值。
     */
    private val REFINE_ROUND_KEYS: Set<String> = (1..3).flatMap { round ->
        listOf("on$round", "steps$round", "denoise$round")
    }.toSet()

    /** 认得的采样参数名（去掉前缀后的部分）。不在其中一律不接管，避免误伤同前缀的其它参数。 */
    private val PARAM_KEYS: Set<String> = setOf(
        "steps", "cfg", "denoise", "sampler", "sampler_name", "scheduler",
        // 预放大倍数（ImageScaleBy.scale_by），不是采样参数但同属「极清放大」这一段
        "scale",
        // CLIP 加载器格式门控（`clip_gguf` → CRZSwitch.value，BOOLEAN）：
        // 它不是采样参数，但是 prebuilt_01/21 上"要不要走 GGUF 加载器"的唯一开关
        "gguf",
        // VOSR2 开关式放大（`vosr2_on` → CRZSwitch.value，BOOLEAN，prebuilt_22/23）：
        // 拨开则出图后过一遍 VOSR2（惰性开关，关掉时那半边真的不执行）
        "on",
        // PE 提示词强化（`pe_model` / `pe_mmproj`，prebuilt_22/23）：
        // 强化用的本地 gguf 与它的视觉投影文件。`pe_on` 复用上面的 "on"。
        "model",
        "mmproj"
    ) + REFINE_ROUND_KEYS


    /**
     * 解析一个占位符。只认「已知阶段前缀 + 已知参数名」的组合。
     *
     * @return 属于采样阶段时返回阶段与参数名；否则 null
     */
    fun resolve(placeholder: String): StageParam? {
        for ((prefix, stage) in KNOWN) {
            if (!placeholder.startsWith(prefix)) continue
            val paramKey = placeholder.removePrefix(prefix)
            return if (paramKey in PARAM_KEYS) StageParam(stage, paramKey) else null
        }
        return null
    }

    /** 该字段是否属于某个采样阶段（UI 用来决定分组与排序）。 */
    fun isStageParam(placeholder: String): Boolean = resolve(placeholder) != null
}
