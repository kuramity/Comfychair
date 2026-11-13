package sh.hnet.comfychair.taglibrary.model

/**
 * 标签数据模型。
 *
 * @param name      标签名（Danbooru 标准写法，如 1girl / long_hair）
 * @param category  分类
 * @param count     热度（Danbooru 图量），用于排序
 * @param zhName    中文释义（优先纯中文别名，无则空串）
 * @param aliases   全部别名（已按逗号/换行拉平，保持原始顺序）
 */
data class Tag(
    val name: String,
    val category: TagCategory,
    val count: Long,
    val zhName: String,
    val aliases: List<String>
) {
    /** 列表副标题：中文释义 + 别名（超长由 UI 省略）。 */
    fun subtitle(): String {
        val aliasPart = aliases.joinToString(", ")
        return when {
            zhName.isNotEmpty() && aliasPart.isNotEmpty() -> "$zhName · $aliasPart"
            zhName.isNotEmpty() -> zhName
            else -> aliasPart
        }
    }
}

/**
 * 已添加到构建器的标签（搜索界面维护的独立记录）。
 *
 * 与提示词框的关系见需求 5.2：
 * - 添加：同时写入本记录与提示词框
 * - 改权重：更新本记录，并同步更新提示词框中的对应标签
 * - 删除：仅从本记录移除（提示词框不动，单向删除）
 *
 * @param name   标签名
 * @param weight 权重（默认 1.0，等于 1.0 时提示词中不写括号）
 */
data class AppliedTag(
    val name: String,
    val weight: Float = DEFAULT_WEIGHT
) {
    companion object {
        const val DEFAULT_WEIGHT = 1.0f

        /** 浮点比较容差：权重可视作 1.0 的边界。 */
        private const val EPS = 0.0001f

        /** 权重是否等于 1.0（等于时不加括号）。 */
        fun isNeutral(weight: Float): Boolean = kotlin.math.abs(weight - DEFAULT_WEIGHT) < EPS
    }

    /** 提示词中的写法：1.0 → `tag`；其他 → `(tag:1.2)`。 */
    fun toPromptToken(): String =
        if (isNeutral(weight)) name else "($name:${formatWeight(weight)})"

    private fun formatWeight(w: Float): String {
        // 去掉多余的 0：1.20 → 1.2，1.00 → 1
        val rounded = kotlin.math.round(w * 100f) / 100f
        return if (rounded == rounded.toInt().toFloat()) rounded.toInt().toString()
        else rounded.toString()
    }
}
