package sh.hnet.comfychair.model

/**
 * 介绍库编辑器的**内置模板**（纯数据 + 纯函数，不依赖 Android / Context / 资源）。
 *
 * 为什么需要它：用户要求「自定义字段可增可删、字段名可改」，但一个空表格没人填得动——
 * 模板给的是「常见三类模型各该填哪几行」，选中即预填好字段名，值留空让用户自己敲。
 * 预填的是**空值**而不是示例值，避免用户忘记改就把假信息当真介绍显示出来。
 *
 * 标签词汇与展示层共用同一批常量（[ModelIntroPresentation.LABEL_NAME] 等）：
 * 元信息头叫「名字 / 底模 / 版本」，模板预填就必须叫同一个名字，否则同一条目里
 * 会出现两个写法不同的同类字段。
 *
 * 纯函数所以 JVM 单测直接钉住（见 CustomFieldTest）。
 */

/** 模板图标语义（界面按它挑图标；数据层不引用任何 Android 资源，避免把 UI 依赖拖进来）。 */
enum class TemplateIcon { LORA, CHECKPOINT, GENERIC }

/**
 * 一套可选项模板。
 *
 * @param id 稳定标识（存给用户选择、单测断言用；**不要用显示名当标识**，改名会连带改逻辑）
 * @param displayName 界面显示名
 * @param icon 图标语义
 * @param hint 一句话说明这套模板适合什么（界面做副标题，可为空）
 * @param customFields 预填字段：label 已填好、value 一律为空串（[blankFields]）。
 *        **空白值不落盘**（[ModelIntroTable.introToJson] 会剔除空白项）——选完模板一个字没填
 *        就保存，得到的是「没有自定义字段」而不是三行空白，这是有意的
 */
data class Template(
    val id: String,
    val displayName: String,
    val icon: TemplateIcon,
    val hint: String? = null,
    val customFields: List<CustomField>,
)

object ModelIntroTemplates {

    // 模板专用标签（与展示层常量不重叠的几只）。触发词 / 推荐权重 / 推荐参数是用户口语说法，
    // 与 params 里的预设键（"触发词" / "权重"）字面一致，界面才不会出现两个同义不同名的行。
    const val LABEL_TRIGGER_WORDS = "触发词"
    const val LABEL_RECOMMENDED_WEIGHT = "推荐权重"
    const val LABEL_RECOMMENDED_PARAMS = "推荐参数"
    const val LABEL_DESCRIPTION = "说明"
    const val LABEL_EFFECT = "效果"

    /** LoRA 模板：名字 / 底模 / 版本 / LoRA 效果 / 触发词 / 推荐权重。 */
    val LORA = Template(
        id = "lora",
        displayName = "LoRA",
        icon = TemplateIcon.LORA,
        hint = "触发词与权重是 LoRA 最容易忘的两项",
        customFields = blankFields(
            ModelIntroPresentation.LABEL_NAME,
            ModelIntroPresentation.LABEL_BASE,
            ModelIntroPresentation.LABEL_VERSION,
            ModelIntroPresentation.LABEL_LORA_EFFECT,
            LABEL_TRIGGER_WORDS,
            LABEL_RECOMMENDED_WEIGHT,
        ),
    )

    /** 大模型模板：名字 / 底模 / 版本 / 模型效果 / 推荐参数。 */
    val CHECKPOINT = Template(
        id = "checkpoint",
        displayName = "大模型",
        icon = TemplateIcon.CHECKPOINT,
        hint = "适合 Checkpoint / 扩散模型",
        customFields = blankFields(
            ModelIntroPresentation.LABEL_NAME,
            ModelIntroPresentation.LABEL_BASE,
            ModelIntroPresentation.LABEL_VERSION,
            ModelIntroPresentation.LABEL_MODEL_EFFECT,
            LABEL_RECOMMENDED_PARAMS,
        ),
    )

    /** 通用模板：名字 / 说明 / 效果（VAE、编码器、放大模型这类不想套固定表的都用它）。 */
    val GENERIC = Template(
        id = "generic",
        displayName = "通用",
        icon = TemplateIcon.GENERIC,
        hint = "只要三行，其余自己加",
        customFields = blankFields(
            ModelIntroPresentation.LABEL_NAME,
            LABEL_DESCRIPTION,
            LABEL_EFFECT,
        ),
    )

    /**
     * 全部内置模板，**界面显示顺序即返回顺序**（LoRA → 大模型 → 通用）。
     *
     * 每次调用都新建列表，调用方改了顺序不会污染下一次（模板内容是数据，改 label 不用改引擎）。
     */
    fun builtInTemplates(): List<Template> = listOf(LORA, CHECKPOINT, GENERIC)

    /** 按 id 取模板；id 为空或未知返回 null（界面不该把「没选模板」当错误）。 */
    fun findById(id: String?): Template? {
        if (id.isNullOrBlank()) return null
        return builtInTemplates().firstOrNull { it.id == id }
    }

    /** 按 labels 造一组空值字段（模板预填唯一入口，保证「值一律为空」这条规则只写一遍）。 */
    private fun blankFields(vararg labels: String): List<CustomField> =
        labels.map { CustomField(label = it, value = "") }
}
