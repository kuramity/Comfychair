package sh.hnet.comfychair.taglibrary.search

/**
 * 别名字段解析器。
 *
 * 数据库中 alias 字段存在两种历史格式（均需兼容）：
 * 1. 单行逗号分隔：`女の子,女性,少女,girl,女孩`
 * 2. 多行带类别名：`中文: 女孩, 少女\n日文: 女の子\n英文: girl`
 *
 * 解析规则与电脑端 danbooru_app.py 的 extract_aliases 保持一致：
 * - 按换行拆行；每行若有「:」或「：」，只取冒号后的值（类别名不参与索引）
 * - 值再按中英文逗号、顿号拆分
 * - 斜杠不拆分（保护 fate/zero 这类真实别名）
 */
object AliasParser {

    /** 日文假名（平假名 + 片假名）。 */
    private val JA_REGEX = Regex("[\\u3040-\\u30ff]")

    /** 汉字（中日共用区）。 */
    private val ZH_REGEX = Regex("[\\u4e00-\\u9fff]")

    /**
     * 拉平别名列表。
     *
     * @param aliasText 数据库 alias 原始文本（可能为 null）
     * @return 别名列表（去空白、去空项，保持原始顺序）
     */
    fun parse(aliasText: String?): List<String> {
        if (aliasText.isNullOrEmpty()) return emptyList()
        val result = ArrayList<String>()
        for (rawLine in aliasText.replace("\r", "").split("\n")) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            // 去掉「类别名:」前缀（中英文冒号都支持）
            val values = when {
                line.contains(':') -> line.substringAfter(':')
                line.contains('：') -> line.substringAfter('：')
                else -> line
            }
            for (piece in values.replace('，', ',').replace('、', ',').split(',')) {
                val alias = piece.trim()
                if (alias.isNotEmpty()) result.add(alias)
            }
        }
        return result
    }

    /**
     * 提取中文释义。
     *
     * 优先级与电脑端 zh_name 一致：纯中文别名（含汉字且不含假名）优先，
     * 否则退而取含汉字的别名（如「女の子」这类日文汉字混合写法）。
     */
    fun pickChineseName(aliases: List<String>): String {
        for (alias in aliases) {
            if (ZH_REGEX.containsMatchIn(alias) && !JA_REGEX.containsMatchIn(alias)) {
                return alias
            }
        }
        for (alias in aliases) {
            if (ZH_REGEX.containsMatchIn(alias)) return alias
        }
        return ""
    }

    /**
     * 三语分类（详情页分组展示用）。
     *
     * @return Triple(中文别名, 日文别名, 英文别名)
     */
    fun splitByLanguage(aliases: List<String>): Triple<List<String>, List<String>, List<String>> {
        val zh = ArrayList<String>()
        val ja = ArrayList<String>()
        val en = ArrayList<String>()
        for (alias in aliases) {
            when {
                JA_REGEX.containsMatchIn(alias) -> ja.add(alias)
                ZH_REGEX.containsMatchIn(alias) -> zh.add(alias)
                else -> en.add(alias)
            }
        }
        return Triple(zh, ja, en)
    }

    /** 是否为中日韩字符（中文搜索判定用，与电脑端 CJK_RE 一致）。 */
    fun containsCjk(text: String): Boolean =
        ZH_REGEX.containsMatchIn(text) || JA_REGEX.containsMatchIn(text) ||
                text.any { it.code in 0xAC00..0xD7AF }  // 韩文音节
}
