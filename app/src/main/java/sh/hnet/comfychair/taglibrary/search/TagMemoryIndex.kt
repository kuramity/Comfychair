package sh.hnet.comfychair.taglibrary.search

import android.util.Log
import sh.hnet.comfychair.taglibrary.data.TagDao
import sh.hnet.comfychair.taglibrary.model.Tag

/**
 * 内存搜索索引。
 *
 * 启动时（标签库页面首次打开）在 IO 线程构建，供中文/日文/英文的高速子串检索：
 * - `hotTags`：count > 100 的高频标签（约 7.1 万条），用于别名命中后回填与子串检索
 * - `aliasExact`：全部别名的精确索引（约 3.5 万键），中文/英文/日文均可精确命中
 * - `cjkAliasEntries`：含中日韩字符的别名条目，**按标签热度降序**（`loadAliasRows`
 *   已 `ORDER BY count DESC`），子串检索命中上限即 break，等价于「最热命中优先」
 * - `nameEntries`：高频标签名条目，用于英文子串匹配（避免 170 万行 SQL LIKE 全扫）
 *
 * 内存开销估算：别名条目约 10 万条 × 约 100 字节 ≈ 10MB，对现代手机可接受；
 * 若后续需要进一步压缩，可只保留 count > 500 的标签名条目。
 */
class TagMemoryIndex private constructor(
    private val hotTags: Map<String, Tag>,
    /** 高频标签**按热度降序**（建索引时的查询本身就带 ORDER BY count DESC，直接留用）。 */
    private val hotTagsByCount: List<Tag>,
    private val aliasExact: Map<String, List<String>>,
    private val cjkAliasEntries: List<Entry>,
    private val nameEntries: List<Entry>
) {

    /** 子串检索条目（text 已小写，tag 为原始标签名）。 */
    class Entry(val text: String, val tag: String)

    val hotTagCount: Int get() = hotTags.size
    val aliasCount: Int get() = aliasExact.size

    /** 从高频缓存取标签详情（无则返回 null）。 */
    fun getHotTag(name: String): Tag? = hotTags[name]

    /** 批量取高频标签详情（别名命中回填用，避免逐条查库）。 */
    fun getHotTags(names: Collection<String>): List<Tag> =
        names.mapNotNull { hotTags[it] }

    /**
     * 按热度降序取前 [limit] 个高频标签（"未输入关键词"时的默认列表）。
     *
     * 等价于一次 `WHERE count > 100 ORDER BY count DESC LIMIT n`，但那条 SQL 在 170 万行的
     * 表上没有可用索引（只有 (category, count) 复合索引，而排序键是 count 单列）→ 全表扫描 +
     * 临时排序。索引里本来就有一份按热度排好的列表，直接切片即可。
     */
    fun topTags(limit: Int): List<Tag> =
        if (limit >= hotTagsByCount.size) hotTagsByCount else hotTagsByCount.subList(0, limit)

    /** 别名精确匹配（大小写不敏感），返回命中的标签名。 */
    fun findTagsByExactAlias(keyword: String): List<String> =
        aliasExact[keyword.lowercase()] ?: emptyList()

    /**
     * 中日韩别名子串匹配（中文/日文搜索主力，约 10 万条扫描，5-15ms）。
     *
     * [cjkAliasEntries] 按热度降序灌入，因此达到 [limit] 提前退出时留下的
     * 是「最热的命中」，不会因为库文件物理行序不同而给出另一批结果。
     *
     * @param limit 命中上限，避免极端输入拖慢
     */
    fun findTagsByAliasSubstring(keyword: String, limit: Int): List<String> {
        val key = keyword.lowercase()
        val out = ArrayList<String>(limit)
        for (entry in cjkAliasEntries) {
            if (entry.text.contains(key)) {
                out.add(entry.tag)
                if (out.size >= limit) break
            }
        }
        return out
    }

    /**
     * 标签名子串匹配（英文搜索补充，仅扫高频标签名）。
     */
    fun findTagsByNameSubstring(keyword: String, limit: Int): List<String> {
        val key = keyword.lowercase()
        val out = ArrayList<String>(limit)
        for (entry in nameEntries) {
            if (entry.text.contains(key)) {
                out.add(entry.tag)
                if (out.size >= limit) break
            }
        }
        return out
    }

    /** 标签名前缀匹配（高频区间，用于英文联想）。 */
    fun findTagsByNamePrefix(keyword: String, limit: Int): List<String> {
        val key = keyword.lowercase()
        val out = ArrayList<String>(limit)
        for (entry in nameEntries) {
            if (entry.text.startsWith(key)) {
                out.add(entry.tag)
                if (out.size >= limit) break
            }
        }
        return out
    }

    companion object {
        private const val TAG = "TagMemoryIndex"

        /**
         * 构建索引（IO 线程调用）。
         *
         * @param dao 已打开的标签库 DAO
         * @return 构建完成的索引；数据为空时仍返回可用实例（只是命中少）
         */
        fun build(dao: TagDao): TagMemoryIndex {
            val start = System.currentTimeMillis()

            // 1) 高频标签（count > 100）
            val hotList = dao.loadTagsForMemoryIndex()
            val hotMap = HashMap<String, Tag>(hotList.size * 4 / 3 + 1)
            for (tag in hotList) hotMap[tag.name] = tag

            // 2) 全部别名展开 → 精确索引 + 中日韩子串条目
            val exact = HashMap<String, MutableList<String>>()
            val cjkEntries = ArrayList<Entry>()
            val nameEntries = ArrayList<Entry>(hotList.size)
            for (tag in hotList) {
                val lower = tag.name.lowercase()
                nameEntries.add(Entry(if (lower == tag.name) tag.name else lower, tag.name))
            }
            val aliasRows = dao.loadAliasRows()
            for ((tagName, aliasRaw) in aliasRows) {
                for (alias in AliasParser.parse(aliasRaw)) {
                    // 繁简归一化：别名里的「長乳首」「髪」先压成简体再进索引，
                    // 这样用户输简体「长乳首／长发」就能命中繁体/日文写法的别名
                    val normalized = HanVariantTable.simplify(alias)
                    val key = normalized.lowercase()
                    exact.getOrPut(key) { ArrayList(2) }.let { list ->
                        if (!list.contains(tagName)) list.add(tagName)
                    }
                    if (AliasParser.containsCjk(normalized)) {
                        cjkEntries.add(Entry(key, tagName))
                    }
                }
            }

            val cost = System.currentTimeMillis() - start
            Log.i(
                TAG,
                "内存索引构建完成：高频标签 ${hotMap.size}，别名键 ${exact.size}，" +
                        "中文子串条目 ${cjkEntries.size}，标签名条目 ${nameEntries.size}，耗时 ${cost}ms"
            )
            return TagMemoryIndex(hotMap, hotList, exact, cjkEntries, nameEntries)
        }
    }
}
