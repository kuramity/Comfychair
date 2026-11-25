package sh.hnet.comfychair.model

/**
 * 介绍条目的**展示层**纯逻辑：元信息头 + 摘要清洗。
 *
 * 为什么单独一个文件：详情页与导出的 `.md` 都必须显示同一套元信息头
 * （名字/底模/版本/类型/效果），而头部内容又要从「脚本拼出来的散文摘要」里剔除与下方
 * 区块重复的段落。这份判定一旦在各处各写一遍，改文案时必然漂移——
 * 所以实现集中在这里，[ModelIntroMarkdown.headerLines] 只是把结果拼成 `"键：值"` 行。
 *
 * 两条用户决策（2026-11-23 拍板）：
 * 1. **元信息头不显示作者**（`author` 保留在数据里，界面不渲染）；
 * 2. 摘要里与下方独立区块重复的模板段（推荐参数 / 作者推荐 tag / 搭配 VAE /
 *    在用工作流 / 风格）全部剥掉，避免同一信息在页面上出现两次。
 *
 * 全部是纯函数（无 Context、无 Android API），JVM 单测直接钉住。
 */
object ModelIntroPresentation {

    /** 元信息头的键名（界面与导出共用；顺序即 [buildMetaHeader] 的输出顺序）。 */
    const val LABEL_NAME = "名字"
    const val LABEL_BASE = "底模"
    const val LABEL_VERSION = "版本"
    const val LABEL_KIND = "类型"

    /** 效果行的键名：LoRA 条目叫「LoRA 效果」，其余叫「模型效果」（用户要求区分）。 */
    const val LABEL_MODEL_EFFECT = "模型效果"
    const val LABEL_LORA_EFFECT = "LoRA 效果"

    // ---------------- 摘要清洗用的正则 ----------------

    /**
     * a. 开头模板句：`「XXX」是 YYY 底模的 ZZZ`。
     *
     * `[^。，,]*?` 同时容纳「是 SD 1.5 底模的…」与「是 通用 底模的…」（底模名前后带空格），
     * 尾部停在第一个逗号/句号——后面通常是「，作者 …」。
     */
    private val TEMPLATE_OPEN_RE = Regex("""「[^」]*」\s*是\s*[^。，,]*?底模的[^。，,]*""")

    /**
     * a'. 同一条模板句的**无底模变体**：`「XXX」是大模型。` / `「XXX」是LoRA。`
     *
     * 真实数据里有 17 条（脚本没写底模名）。刻意只认已知 kind 且**必须紧跟标点**：
     * 放开成 `[^。，,]*` 会把「「X」是 LoRA 效果很好…」这类真实描述一起吞掉。
     * 不消费标点（改用前瞻），后面的「，作者 …」仍能被 [AUTHOR_RE] 正常剥掉。
     */
    private val TEMPLATE_OPEN_SHORT_RE = Regex(
        """^\s*「[^」]*」\s*是\s*(?:大模型|扩散模型|LoRA|VAE|放大模型|图像编码器|背景去除|几何估计)(?=[。，,.])""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * b. 作者与版本：`，作者 AAA（版本 BBB）。`
     *
     * `(?!推荐)` 是关键：样本里还有「作者推荐 tag：…」「作者推荐提示词：…」两个
     * **重复段标记**，它们同样以「作者」开头，少了这道负向断言会被当成作者段连标记词
     * 一起剥掉（值留下来反而更乱）。括号全角半角都认，句号可有可无。
     *
     * `[^，。]*` 必须**贪婪**：后面的括号组是可选的，用惰性量词会让正则引擎选最短匹配
     * （只删掉「，作者 」三个字，作者名留在摘要里）。
     */
    private val AUTHOR_RE = Regex("""[，,]\s*作者(?!推荐)\s*[^，。]*(?:[（(][^）)]*[）)])?\s*[。.]?""")

    /** c. 占位：`（暂无更多介绍信息）`（全角半角括号都认）。 */
    private val PLACEHOLDER_RE = Regex("""[（(]\s*暂无更多介绍信息\s*[）)]""")

    /**
     * d–i. 与下方独立区块重复的模板段：`标记词：…`（到句末为止）。
     *
     * 标记词覆盖：推荐参数（参数卡）/ 作者推荐 tag（触发词区块）/ 作者推荐提示词
     * （提示词区块）/ 搭配 VAE（搭配区块）/ 在你的工作流里用过（在用工作流区块）/
     * 风格（风格标签）。`[^。]*` 不跨句号，所以「推荐参数：… 搭配 VAE：…」这种
     * 无句号连写的长段会被一次性吞掉（结果正确：整段都是重复内容）。
     */
    private val REPEATED_SEGMENT_RE = Regex(
        """(?:推荐参数|作者推荐\s*tag|作者推荐提示词|搭配\s*VAE|在你的工作流里用过|风格)\s*[:：][^。]*[。.]?""",
        RegexOption.IGNORE_CASE,
    )

    /** 连续句号压缩（清洗后可能出现「。。」）。 */
    private val REPEATED_PERIOD_RE = Regex("""([。.])\1+""")

    /** 空白压缩（含全角空格；summary 是散文，换行一律抹平成空格）。 */
    private val BLANKS_RE = Regex("""[\s\u3000]+""")

    /** 收尾要剪掉的垃圾标点（模板段剥完常在首尾留一串标点）。 */
    private val TRIM_CHARS = charArrayOf(
        ' ', '\u3000', '\t', '\n', '\r',
        '。', '.', '，', ',', '、', '；', ';', '：', ':',
    )

    // ---------------- 元信息头 ----------------

    /**
     * 元信息头：**分行**的键值对，顺序固定为 名字 / 底模 / 版本 / 类型 / 效果。
     *
     * 值为空（null 或纯空白）的项整项跳过——绝不输出「版本：null」。
     * 类型优先取 `type`（数据侧写的「大模型（Checkpoint）」这种带后缀的完整说法），
     * `type` 缺失时退回 `kind`（「大模型」/「LoRA」）。
     *
     * @param effectFallback 条目自身没有 `effect` 时的兜底（默认取 [LoraEffectTable]，
     *        与界面/导出既有兜底链一致；单测显式传 null 以隔离全局表）
     */
    fun buildMetaHeader(
        intro: ModelIntro,
        effectFallback: String? = LoraEffectTable.lookup(intro.file),
    ): List<Pair<String, String>> = buildList(5) {
        val name = intro.name?.takeIf { it.isNotBlank() }
            ?: intro.file.takeIf { it.isNotBlank() }
        name?.let { add(LABEL_NAME to it.trim()) }
        intro.base?.takeIf { it.isNotBlank() }?.let { add(LABEL_BASE to it.trim()) }
        intro.version?.takeIf { it.isNotBlank() }?.let { add(LABEL_VERSION to it.trim()) }
        (intro.type?.takeIf { it.isNotBlank() } ?: intro.kindLabel())
            ?.let { add(LABEL_KIND to it.trim()) }
        effectEntry(intro, effectFallback)?.let { add(it) }
    }

    /**
     * 效果行的取值（不含键名）：`effect` → 效果档案兜底 → 清洗后的 `summary`，都没有返回 null。
     *
     * 与 [buildMetaHeader] 同一套链，供 [ModelIntroMarkdown.effectOrSummary] 转接，
     * 避免「头部行」与「单纯取效果文本」两处判定漂移。
     */
    fun effectText(intro: ModelIntro, effectFallback: String?): String? =
        effectEntry(intro, effectFallback)?.second

    /**
     * 这条条目是不是 LoRA（决定效果行叫「LoRA 效果」还是「模型效果」）。
     *
     * 三个信号任一成立即可，宁可判宽：`loraWeight` 有权重建议的必然是 LoRA；
     * `kind` / `type` 里含 `LoRA`（大小写不敏感）也算——数据侧 `kind` 是中文枚举
     * （`LoRA` 本身不是中文，但它是固定枚举值），`type` 常写成「LoRA（…）」。
     */
    fun isLora(intro: ModelIntro): Boolean {
        if (intro.loraWeight != null) return true
        return listOfNotNull(intro.kind, intro.type)
            .any { it.contains("lora", ignoreCase = true) }
    }

    /**
     * 剥掉效果文本自带的「效果：」前缀（数据侧 `effect` 普遍自带）。
     *
     * 半角冒号一并兼容；本函数是**唯一实现**——[ModelIntroMarkdown.stripEffectLabel]
     * 与详情页的 `stripEffectPrefix` 都是转接，既有单测继续钉住这条规则。
     */
    fun stripEffectLabel(text: String): String =
        text.trim().removePrefix("效果：").removePrefix("效果:").trim()

    private fun effectEntry(intro: ModelIntro, effectFallback: String?): Pair<String, String>? {
        val label = if (isLora(intro)) LABEL_LORA_EFFECT else LABEL_MODEL_EFFECT
        // effect 与档案兜底是同一层（数据构建链已把档案合进 effect，兜底只服务没合并上的条目）
        val direct = sequenceOf(intro.effect, effectFallback)
            .mapNotNull { it }
            .map(::stripEffectLabel)
            .firstOrNull(::isRealContent)
        if (direct != null) return label to direct
        // summary 是脚本拼的散文：剥掉与下方区块重复的模板段，剥空了就当没有
        val cleaned = cleanIntroSummary(intro.summary.orEmpty())
        return cleaned.takeIf { it.isNotEmpty() }?.let { label to it }
    }

    /** 剥掉占位括号后还剩内容才算「真的有效果描述」。 */
    private fun isRealContent(text: String): Boolean =
        text.isNotBlank() && PLACEHOLDER_RE.replace(text, "").isNotBlank()

    /**
     * 元信息头**下方**要单独显示的剩余简介；不该显示时返回 null。
     *
     * 两个条件必须同时成立：
     * 1. `cleanIntroSummary` 剥完还有内容（剥空了就是纯套话，整块不渲染）；
     * 2. 这段内容**没有被效果行取用**——`effect` 与效果档案都为空时，效果行取的就是
     *    清洗后的 summary，再在下面渲染一遍就是同一段文字出现两次。
     *
     * @param effectFallback 与 [buildMetaHeader] 同一个兜底来源（默认取 [LoraEffectTable]）
     */
    fun summaryRestForDisplay(
        intro: ModelIntro,
        effectFallback: String? = LoraEffectTable.lookup(intro.file),
    ): String? {
        val cleaned = cleanIntroSummary(intro.summary.orEmpty())
        if (cleaned.isEmpty()) return null
        return cleaned.takeIf { it != effectText(intro, effectFallback) }
    }

    // ---------------- 摘要清洗 ----------------

    /**
     * 清洗脚本预生成的散文摘要：剥掉下面这些**与页面独立区块重复**的模板段。
     *
     * ```
     * 「AWPainting」是 SD 1.5 底模的大模型（Checkpoint），作者 DynamicWang（版本 v1.2）。
     * 推荐参数：步数 20 步、CFG 5.7… 搭配 VAE：… 在你的工作流里用过：…
     * ```
     * 清洗后为空串——这些信息在元信息头与下方参数/搭配/在用工作流区块里都有，
     * 留在摘要里只会让同一句话出现两次，还额外把作者名暴露出来。
     *
     * 处理顺序（顺序有意义：先剥能吃掉大段的模板句，再处理标记段）：
     * a 开头模板句（含「底模的」的完整式与「是大模型。」这种无底模变体）→
     * b 作者与版本 → c 占位 → d–i 重复段 → 压缩空白/句号 → 剪首尾标点。
     *
     * 不是「一律清空」：夹在模板段之外的真实描述会保留
     * （如「适合赛博朋克夜景」）。全空输入返回空串，不抛异常。
     */
    fun cleanIntroSummary(raw: String): String {
        if (raw.isBlank()) return ""
        var text = raw
        text = TEMPLATE_OPEN_RE.replace(text, " ")
        text = TEMPLATE_OPEN_SHORT_RE.replace(text, " ")
        text = AUTHOR_RE.replace(text, " ")
        text = PLACEHOLDER_RE.replace(text, " ")
        text = REPEATED_SEGMENT_RE.replace(text, " ")
        text = BLANKS_RE.replace(text, " ")
        text = REPEATED_PERIOD_RE.replace(text) { it.groupValues[1] }
        return text.trim(*TRIM_CHARS)
    }
}