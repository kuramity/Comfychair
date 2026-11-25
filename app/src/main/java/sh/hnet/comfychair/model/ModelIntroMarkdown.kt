package sh.hnet.comfychair.model

import sh.hnet.comfychair.util.SafeFileName

/**
 * 介绍条目的 Markdown 模板——**界面渲染与导出共用的唯一一处定义**。
 *
 * 模板形态（字段为空时整行/整节不渲染，沿用项目「宁可少一行」原则）：
 * ```
 * # {name}
 *
 * 名字：{name}
 * 底模：{base}
 * 版本：{version}
 * 类型：{type 或 kind}
 * 模型效果：{effect 或清洗后的 summary}   ← LoRA 条目这一行叫「LoRA 效果」
 *
 * {自定义字段行 "键：值"，编辑器写入；没有就整块不出现}
 *
 * ## 推荐参数
 * | 步数 | CFG | 采样器 | 调度器 |
 * | --- | --- | --- | --- |
 * | 20 步 | 5.7 | dpmpp_2m | karras |
 *
 * ## 触发词
 * `word1`、`word2`
 *
 * ## 推荐提示词
 * ...
 *
 * ## 社区常用
 * - 采样器：Euler a（17 张）
 *
 * ## 作者原文
 * ...
 * ```
 *
 * 三条设计约定：
 * 1. **不输出作者**（用户拍板：author 字段保留在数据里，元信息头不显示）；
 * 2. 标签是中文且硬编码：介绍数据本身就是预生成的中文（见 [ModelIntro] KDoc），
 *    导出的是数据文档、不是界面文案，所以不随界面语言变化（与
 *    `ModelIntroDetailScreen.communityKeyLabel` / `sourceLabel` 同一口径）；
 * 3. [buildMarkdown] 是导出/存档用的完整文档；[buildHeaderMarkdown] 是同一批元信息行拼出的
 *    界面头部块（供界面用 [sh.hnet.comfychair.workflow.MarkdownParser] 渲染），**不带
 *    `# 名字` 大标题**——界面开头就是「名字：…」，标题会让模型名重复两次。两者内容同源，
 *    只差标题行与段落分隔——commonmark 把**单个换行**当软换行、渲染时并成一个空格，
 *    界面要分行必须用空行分段；导出文件保持模板的紧凑排布。
 */
object ModelIntroMarkdown {

    /** 推荐参数表头/列的顺序（与数据侧 PARAM_ORDER 同一语义顺序：先采样强度再搭配类）。 */
    private val PARAM_ORDER = listOf("步数", "CFG", "采样器", "调度器", "降噪", "推荐分辨率", "权重", "触发词")

    /** 参数表「两个来源都有」时的来源行标题（与详情页两张参数卡的标题同义，不合并数值）。 */
    private const val SOURCE_AUTHOR = "作者推荐"
    private const val SOURCE_WORKFLOW = "你的工作流"

    /**
     * 「社区常用」里样本数达到该值才展示数字。
     *
     * 与详情页社区卡同一门槛（两三张图撑不起「常用」二字），界面从这里取同一个常量，
     * 不在两处各写一个 3。
     */
    const val COMMUNITY_COUNT_MIN_DISPLAY = 3

    /** 滑块型 LoRA（有权重建议、没有触发词）的触发词节文案。 */
    const val SLIDER_TRIGGER_HINT = "滑块调节，无需触发词"

    const val SECTION_PARAMS = "## 推荐参数"
    const val SECTION_TRIGGERS = "## 触发词"
    const val SECTION_PROMPT = "## 推荐提示词"
    const val SECTION_COMMUNITY = "## 社区常用"
    const val SECTION_ORIGINAL = "## 作者原文"

    /** 社区参数键 → 中文标签（数据键是英文枚举；未知键原样显示）。 */
    internal fun communityParamLabel(key: String): String = when (key) {
        "sampler" -> "采样器"
        "cfg" -> "CFG"
        "steps" -> "步数"
        "scheduler" -> "调度器"
        else -> key
    }

    /**
     * 剥掉效果文本自带的「效果：」前缀。
     *
     * 数据侧 `effect` 普遍自带这个前缀（如「效果：皮肤更细腻」），而模板行本身已经写成
     * 「模型效果：{...}」——不剥就会输出「模型效果：效果：……」。半角冒号一并兼容。
     *
     * 实现唯一一份在 [ModelIntroPresentation.stripEffectLabel]（详情页
     * `stripEffectPrefix` 也转接到这里），本函数只是同签名转接，既有单测继续钉住规则。
     */
    internal fun stripEffectLabel(text: String): String =
        ModelIntroPresentation.stripEffectLabel(text)

    /** 标题行（`# {name}`；无名时退回文件名）。 */
    internal fun titleLine(intro: ModelIntro): String {
        val name = intro.name?.takeIf { it.isNotBlank() }
            ?: intro.file.takeIf { it.isNotBlank() }
            ?: ""
        return "# $name".trimEnd()
    }

    /**
     * 元信息行（顺序固定：名字 / 底模 / 版本 / 类型 / 模型效果）。
     *
     * **刻意没有作者行**（用户决策 1）。空值整行不渲染。
     *
     * 键值由 [ModelIntroPresentation.buildMetaHeader] 产出（详情页与导出两处共用
     * 同一份判定）；这里只负责拼成 Markdown 的 `"键：值"` 行。效果行的键名按条目类型
     * 区分——LoRA 叫「LoRA 效果」，其余叫「模型效果」。
     *
     * @param effectFallback 条目自身没有 `effect` 时的兜底文本（现有链路的第二级：
     *        独立维护的效果档案 [LoraEffectTable]；调用方默认就传它）
     */
    internal fun headerLines(intro: ModelIntro, effectFallback: String?): List<String> =
        ModelIntroPresentation.buildMetaHeader(intro, effectFallback)
            .map { (label, value) -> "$label：$value" }

    /**
     * 「效果」行的内容：`effect` → 效果档案兜底 → 清洗后的 `summary`（都没有返回 null）。
     *
     * 兜底顺序与详情页既有链一致（数据构建链已把档案合进 effect，兜底只服务
     * 没合并上的条目），模板因此只有一行效果而不是并列两段。摘要会先过
     * [ModelIntroPresentation.cleanIntroSummary]——脚本拼的散文里与下方区块重复的
     * 模板段不进入效果行。
     */
    internal fun effectOrSummary(intro: ModelIntro, effectFallback: String?): String? =
        ModelIntroPresentation.effectText(intro, effectFallback)

    /**
     * 自定义字段行（编辑器写入的 `customFields`，v4.21）。
     *
     * 与详情页头部块同位置（元信息头之后）、同措辞（`"键：值"`），保证导出与界面一致。
     * 空白项（label 或 value 为空白）整行剔除——与 [ModelIntroTable.introToJson] 落盘
     * 侧同一判定，导出不可能比磁盘上多出一行。空列表返回空表（调用方据此省掉整个块）。
     */
    internal fun customFieldLines(intro: ModelIntro): List<String> =
        intro.customFields
            .filter { it.label.isNotBlank() && it.value.isNotBlank() }
            .map { "${it.label.trim()}：${it.value.trim()}" }

    /**
     * 完整文档（导出 `.md` 用）。
     *
     * 头部与各节之间空一行；头部内部的元信息行按模板紧凑排布（单换行）。
     * 用户自定义字段紧跟元信息头之后（单独一块、块内逐行），没有就整块不出现。
     *
     * @param effectFallback 条目无 `effect` 时的兜底文本，默认取 [LoraEffectTable]
     *        （与界面同一兜底链；单测可显式传 null 隔离全局表）
     */
    fun buildMarkdown(
        intro: ModelIntro,
        effectFallback: String? = LoraEffectTable.lookup(intro.file),
    ): String {
        val blocks = ArrayList<String>(9)
        blocks += titleLine(intro)
        headerLines(intro, effectFallback).takeIf { it.isNotEmpty() }?.let { blocks += it.joinToString("\n") }
        customFieldLines(intro).takeIf { it.isNotEmpty() }?.let { blocks += it.joinToString("\n") }
        blocks += buildSections(intro)
        return blocks.joinToString("\n\n") + "\n"
    }

    /**
     * 头部块（界面 Markdown 渲染用）：**只有元信息行**，不带 `# 名字` 标题。
     *
     * 用户要求「详情页/抽屉的开头必须是分行元信息头，不再是一大段散文」，示例的第一行就是
     * 「名字：…」——界面若再叠一个 `# 名字` 大标题，模型名会在首屏出现两次。
     * 所以界面变体只输出 [headerLines]；**导出的 `.md` 仍保留标题**（[buildMarkdown]），
     * 文档需要一级标题，两者内容同源、只差这一行。
     *
     * 元信息行之间用空行分段——commonmark 的软换行会被渲染成空格，空行才是真正的换行。
     */
    fun buildHeaderMarkdown(
        intro: ModelIntro,
        effectFallback: String? = LoraEffectTable.lookup(intro.file),
    ): String = headerLines(intro, effectFallback).joinToString("\n\n")

    /** 各节（顺序固定；空节不出现）。 */
    internal fun buildSections(intro: ModelIntro): List<String> = buildList {
        paramsSection(intro)?.let { add(it) }
        triggersSection(intro)?.let { add(it) }
        promptSection(intro)?.let { add(it) }
        communitySection(intro)?.let { add(it) }
        originalSection(intro)?.let { add(it) }
    }

    /**
     * 「推荐参数」表。
     *
     * 只有一种来源时，表头就是参数名、下面一行是值（模板形态）；两种来源都有时多一列
     * 「来源」并分两行——**并列不合并**是详情页的既有语义（作者建议 ≠ 你工作流的实际值，
     * 冲突时让用户自己判断信哪个），导出文档同样不替用户做选择。
     */
    private fun paramsSection(intro: ModelIntro): String? {
        val author = intro.authorParams
        val workflow = intro.params
        if (author.isEmpty() && workflow.isEmpty()) return null
        val keys = orderedParamKeys(author, workflow)
        if (keys.isEmpty()) return null
        val withSourceColumn = author.isNotEmpty() && workflow.isNotEmpty()

        val table = buildString {
            append("| ")
            if (withSourceColumn) append("来源 | ")
            append(keys.joinToString(" | ") { escapeCell(it) })
            append(" |\n| ")
            if (withSourceColumn) append("--- | ")
            append(keys.joinToString(" | ") { "---" })
            append(" |")
            fun row(label: String?, params: Map<String, String>) {
                append("\n| ")
                if (label != null) append(escapeCell(label)).append(" | ")
                append(keys.joinToString(" | ") { escapeCell(params[it] ?: "") })
                append(" |")
            }
            if (author.isNotEmpty()) row(if (withSourceColumn) SOURCE_AUTHOR else null, author)
            if (workflow.isNotEmpty()) row(if (withSourceColumn) SOURCE_WORKFLOW else null, workflow)
        }
        return "$SECTION_PARAMS\n\n$table"
    }

    /** 参数列顺序：预设键在前（按 [PARAM_ORDER]），其余键按数据顺序接在后面。 */
    private fun orderedParamKeys(
        first: Map<String, String>,
        second: Map<String, String>,
    ): List<String> {
        val out = LinkedHashSet<String>()
        for (key in PARAM_ORDER) {
            if (first.containsKey(key) || second.containsKey(key)) out += key
        }
        for (key in first.keys + second.keys) {
            if (key.isNotBlank()) out += key
        }
        return out.toList()
    }

    /**
     * 「触发词」节。
     *
     * 数据模型没有独立的 `triggers` 字段：`tags` 就是作者推荐触发词（见 [ModelIntro.tags]）。
     * 没有 tags 但有权重建议的条目是**滑块型 LoRA**（调权重而不是打字），按用户给的模板
     * 显示「滑块调节，无需触发词」；两者都没有就整节不渲染。
     */
    private fun triggersSection(intro: ModelIntro): String? {
        val tags = intro.tags.filter { it.isNotBlank() }
        return when {
            tags.isNotEmpty() -> "$SECTION_TRIGGERS\n\n" + tags.joinToString("、") { "`${it.trim()}`" }
            intro.loraWeight != null -> "$SECTION_TRIGGERS\n\n$SLIDER_TRIGGER_HINT"
            else -> null
        }
    }

    /** 「推荐提示词」节：社区正向提示词（模板只取 positive）。 */
    private fun promptSection(intro: ModelIntro): String? {
        val positive = intro.communityPrompt?.positive?.takeIf { it.isNotBlank() } ?: return null
        return "$SECTION_PROMPT\n\n" + positive.trim()
    }

    /**
     * 「社区常用」节：社区众数 + 样本数。
     *
     * 样本数不到 [COMMUNITY_COUNT_MIN_DISPLAY] 时不写数字（与详情页社区卡同一口径：
     * 两三张图撑不起「常用」二字），值本身照常列出。
     */
    private fun communitySection(intro: ModelIntro): String? {
        if (intro.communityParams.isEmpty()) return null
        val lines = intro.communityParams.map { (key, item) ->
            val label = communityParamLabel(key)
            val count = if (item.count >= COMMUNITY_COUNT_MIN_DISPLAY) "（${item.count} 张）" else ""
            "- $label：${item.value}$count"
        }
        return "$SECTION_COMMUNITY\n\n" + lines.joinToString("\n")
    }

    /**
     * 「作者原文」节。
     *
     * 「折叠」是界面行为（详情页的 300dp 内滚框 + 展开收起），文档里给纯文本——
     * 这样导出的 .md 能被普通编辑器正确读回（`<details>` 之类 HTML 块
     * 本项目使用的 commonmark 不渲染，会连同内容一起丢掉）。
     */
    private fun originalSection(intro: ModelIntro): String? {
        val text = intro.introOriginal?.takeIf { it.isNotBlank() } ?: return null
        return "$SECTION_ORIGINAL\n\n" + text.trim()
    }

    /** 表格单元格转义：竖线会把表格拆坏，换行同理（压成空格）。 */
    internal fun escapeCell(value: String): String =
        value.replace("\r\n", " ").replace('\n', ' ').replace('\r', ' ').replace("|", "\\|")

    /**
     * 导出文件名：`{模型名}.md`。
     *
     * 名称来自数据（可能带斜杠、冒号、`..`），直接复用 [SafeFileName]——与 3D 产物下载
     * 同一套白名单 + 路径穿越防护（取最后一段、非法字符换 `_`、拒绝 `..`/隐藏名），
     * 再补 `.md` 扩展名。净化不出来时退回文件名，仍不行就用 `model_intro`。
     */
    fun fileNameFor(intro: ModelIntro): String {
        val raw = intro.name?.takeIf { it.isNotBlank() }
            ?: intro.file.substringBeforeLast('.')
        val sanitized = SafeFileName.sanitize(raw)
            ?: SafeFileName.sanitize(intro.file)
            ?: "model_intro"
        return if (sanitized.endsWith(".md", ignoreCase = true)) sanitized else "$sanitized.md"
    }
}