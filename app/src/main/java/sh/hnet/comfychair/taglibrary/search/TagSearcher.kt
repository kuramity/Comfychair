package sh.hnet.comfychair.taglibrary.search

import sh.hnet.comfychair.taglibrary.data.TagDao
import sh.hnet.comfychair.taglibrary.model.Tag
import sh.hnet.comfychair.taglibrary.model.TagCategory

/**
 * 标签搜索编排器。
 *
 * 搜索策略（与电脑端 danbooru_app.py 的 Api.search 保持一致，并针对移动端做了收敛）：
 *
 * **中文/日文输入**（含 CJK 字符）：
 * 1. 同义词表精确词条（「巨乳」→ huge_breasts）——精确优先，避免误命中
 * 2. 同义词表包含匹配（补充）
 * 3. 内存别名精确索引
 * 4. 内存别名子串匹配（5-15ms）
 * 5. SQLite 别名包含匹配（低频兜底）
 *
 * **英文输入**：
 * 1. SQLite 标签名精确匹配（主键索引）
 * 2. SQLite 标签名前缀匹配（主键索引范围扫描）
 * 3. 内存别名精确索引（覆盖 like / source 等别名）
 * 4. 内存标签名子串匹配（高频区间，替代昂贵的全表 LIKE）
 *
 * 最终结果：精确命中置顶 → 热度降序 → 截断到 limit 条。
 */
class TagSearcher(private val dao: TagDao) {

    /** 内存索引（可能尚未构建完成，为 null 时自动退化为纯 SQLite 查询）。 */
    @Volatile
    var memoryIndex: TagMemoryIndex? = null

    /** 是否已具备内存索引（UI 可用于提示"索引加载中"）。 */
    val isIndexReady: Boolean get() = memoryIndex != null

    /** 搜索结果条数上限（需求 6.3）。 */
    private val maxResults = TagDao.DEFAULT_LIMIT

    /** 单路检索的候选上限，避免中间集合过大。 */
    private val candidateLimit = 120

    /**
     * 执行搜索。
     *
     * @param keyword  用户输入（已 trim）
     * @param category 可选分类过滤
     * @return 按「精确命中 → 热度」排序的标签列表
     */
    fun search(keyword: String, category: TagCategory? = null): List<Tag> {
        if (keyword.isBlank()) return emptyList()
        val trimmed = keyword.trim()
        val index = memoryIndex

        val ordered = LinkedHashSet<String>()
        val exactHits = HashSet<String>()

        if (AliasParser.containsCjk(trimmed)) {
            searchChinese(trimmed, index, ordered, exactHits)
        } else {
            searchLatin(trimmed, index, ordered, exactHits)
        }

        // 回填完整标签对象：优先内存缓存，其余批量查库
        val fromCache: Map<String, Tag> = index?.getHotTags(ordered)?.associateBy { it.name } ?: emptyMap()
        val missing = ordered.filter { it !in fromCache }
        val fromDb: Map<String, Tag> =
            if (missing.isEmpty()) emptyMap() else dao.getByNames(missing).associateBy { it.name }

        return ordered
            .mapNotNull { fromCache[it] ?: fromDb[it] }
            .filter { category == null || it.category == category }
            .sortedWith(compareByDescending<Tag> { it.name in exactHits }.thenByDescending { it.count })
            .take(maxResults)
    }

    /**
     * 中文/日文检索路径。
     *
     * 归一化说明：内存索引里的别名已压成简体（见 [HanVariantTable]），
     * 因此查询词也先压一遍——「長乳首」与「长乳首」都能命中 huge_nipples 那条别名；
     * SQLite 兜底路径则同时用**原文与简体**查两次（库里的别名是原始写法，
     * 用户输繁体时原文能命中，用户输简体时简体形态能命中本就是简体的那些行）。
     */
    private fun searchChinese(
        keyword: String,
        index: TagMemoryIndex?,
        ordered: MutableSet<String>,
        exactHits: MutableSet<String>
    ) {
        val normalized = HanVariantTable.simplify(keyword)

        // 1) 同义词表：精确词条优先（关键词已归一化，走 lookupNormalized 免去重复 simplify）
        val exactSyn = TagSynonymTable.lookupNormalized(normalized, exactOnly = true)
        ordered.addAll(exactSyn)
        exactHits.addAll(exactSyn)

        // 2) 同义词表：包含匹配补充
        if (ordered.size < candidateLimit) {
            ordered.addAll(TagSynonymTable.lookupNormalized(normalized, exactOnly = false))
        }

        // 3) 内存别名精确 + 子串
        if (index != null) {
            val exact = index.findTagsByExactAlias(normalized)
            ordered.addAll(exact)
            exactHits.addAll(exact)
            if (ordered.size < candidateLimit) {
                ordered.addAll(
                    index.findTagsByAliasSubstring(normalized, candidateLimit - ordered.size)
                )
            }
        }

        // 4) SQLite 别名包含（低频标签兜底）
        // 传"剩余额度"而不是全额，与其它分支口径一致（原先会多塞候选）；
        // 关键词本身就是简体时 normalized == keyword，第二段自然不执行（不再把同一条全表 LIKE 查两遍）。
        if (ordered.size < candidateLimit) {
            ordered.addAll(
                dao.searchByAliasContains(keyword, candidateLimit - ordered.size).map { it.name }
            )
        }
        if (normalized != keyword && ordered.size < candidateLimit) {
            ordered.addAll(
                dao.searchByAliasContains(normalized, candidateLimit - ordered.size).map { it.name }
            )
        }
    }

    /** 英文（拉丁）检索路径。 */
    private fun searchLatin(
        keyword: String,
        index: TagMemoryIndex?,
        ordered: MutableSet<String>,
        exactHits: MutableSet<String>
    ) {
        // 1) 精确匹配（主键索引）
        val exact = dao.searchByExactName(keyword)
        exact.forEach { ordered.add(it.name) }
        exactHits.addAll(exact.map { it.name })

        // 2) 前缀匹配（主键索引范围扫描）
        if (ordered.size < candidateLimit) {
            ordered.addAll(dao.searchByNamePrefix(keyword, candidateLimit).map { it.name })
        }

        // 3) 内存别名精确（如输入 "source" 命中别名含 source 的标签）
        if (index != null && ordered.size < candidateLimit) {
            val aliasExact = index.findTagsByExactAlias(keyword)
            ordered.addAll(aliasExact)
            exactHits.addAll(aliasExact)
        }

        // 4) 内存子串（高频区间，替代全表 LIKE）
        if (index != null && ordered.size < candidateLimit) {
            ordered.addAll(
                index.findTagsByNameSubstring(keyword, candidateLimit - ordered.size)
            )
        }

        // 5) SQLite 子串兜底（低频标签；由 limit 保护，代价可控）
        if (ordered.size < candidateLimit) {
            ordered.addAll(
                dao.searchByNameContains(keyword, candidateLimit - ordered.size).map { it.name }
            )
        }
    }

    /**
     * 加载"未输入关键词"时的默认列表（高频标签，可按分类过滤）。
     */
    fun loadDefault(category: TagCategory? = null, limit: Int = maxResults): List<Tag> {
        // 不带分类筛选时优先用内存索引里已按热度排好的那份：对应的 SQL 在 170 万行上
        // 没有可用索引（ORDER BY count DESC 用不上 (category, count) 复合索引），
        // 每次进标签库 / 清空搜索框都要全表扫描 + 临时排序。
        // 带分类筛选的查询能命中复合索引，仍走库。
        if (category == null) {
            val fromIndex = memoryIndex?.topTags(limit)
            if (!fromIndex.isNullOrEmpty()) return fromIndex
        }
        return dao.loadHotTags(category, limit)
    }

    /**
     * 按标签名精确取详情（详情页使用，保证别名等字段完整）。
     */
    fun loadByNames(names: List<String>): List<Tag> = dao.getByNames(names)
}
