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

        /**
         * 对标签名做提示词语法转义。
         *
         * **只转义圆括号**：ComfyUI 的提示词解析（`comfy/sd1_clip.py` 的
         * `escape_important` / `unescape_important`）只把 `\(` `\)` 还原成字面括号，
         * 冒号、方括号、反斜杠都不做还原。若给 `:` `[` `]` `\` 也加反斜杠，这些反斜杠
         * 会原样进入 CLIP tokenizer，反而让提示词文本与标签名不一致
         * （内置库里含冒号的标签有 1.3 万余个，如 `honkai:_star_rail`）。
         *
         * 括号本身必须转义：不转义时 `papyrus_(salon_pixiv)` 会被
         * `parse_parentheses` 当成权重分组拆开，标签直接变形。
         *
         * 权重括号与权重冒号由 [toPromptToken] 在转义后拼接，不会被误伤。
         */
        fun escapeForPrompt(raw: String): String {
            if (raw.isEmpty()) return raw
            val sb = StringBuilder(raw.length + 4)
            for (ch in raw) {
                when (ch) {
                    '(', ')' -> sb.append('\\').append(ch)
                    else -> sb.append(ch)
                }
            }
            return sb.toString()
        }
    }

    /**
     * 提示词中的写法：1.0 → `tag`；其他 → `(tag:1.2)`。
     *
     * 标签名先做圆括号转义：Danbooru 的标签名本身可能含圆括号
     * （如 `papyrus_(salon_pixiv)`），不转义会被 ComfyUI 的提示词解析当成权重分组拆开。
     * 所有插入路径（追加、指定位置插入、改权重）都经过本方法，因此转义只在这里做一次。
     */
    fun toPromptToken(): String {
        val escapedName = escapeForPrompt(name)
        return if (isNeutral(weight)) escapedName else "($escapedName:${formatWeight(weight)})"
    }

    private fun formatWeight(w: Float): String {
        // 去掉多余的 0：1.20 → 1.2，1.00 → 1
        val rounded = kotlin.math.round(w * 100f) / 100f
        return if (rounded == rounded.toInt().toFloat()) rounded.toInt().toString()
        else rounded.toString()
    }
}
