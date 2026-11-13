package sh.hnet.comfychair.taglibrary.search

import android.content.Context
import android.util.Log
import org.json.JSONObject

/**
 * 中文同义词表（移植自电脑端 danbooru-gui 的 synonyms_data.py，767 个词条）。
 *
 * 作用：中文搜索的第一优先级。例如输入「巨乳」可直接命中 `huge_breasts`，
 * 即使该标签的别名里没有「巨乳」二字也能搜到。
 *
 * 数据随 APK 打包在 `assets/databases/tag_synonyms.json`，首次使用时懒加载。
 * 匹配策略与电脑端一致：**精确词条优先**（避免「弓背」命中含「弓」的道具组），
 * 再退化为包含匹配。
 */
object TagSynonymTable {

    private const val TAG = "TagSynonymTable"
    private const val ASSET_PATH = "databases/tag_synonyms.json"

    /** 中文词条 → 对应英文标签列表（保留分组信息以便后续扩展）。 */
    @Volatile
    private var termToTags: Map<String, List<String>>? = null

    /** 懒加载同义词表（线程安全，失败时返回空表，不影响主流程）。 */
    fun ensureLoaded(context: Context) {
        if (termToTags != null) return
        synchronized(this) {
            if (termToTags != null) return
            termToTags = try {
                val json = context.assets.open(ASSET_PATH).use { input ->
                    JSONObject(input.readBytes().decodeToString())
                }
                val map = HashMap<String, MutableList<String>>()
                val groups = json.keys()
                while (groups.hasNext()) {
                    val group = json.optJSONObject(groups.next()) ?: continue
                    val terms = group.keys()
                    while (terms.hasNext()) {
                        val term = terms.next()
                        val tags = group.optJSONArray(term) ?: continue
                        val list = map.getOrPut(term) { ArrayList() }
                        for (i in 0 until tags.length()) {
                            val t = tags.optString(i)
                            if (t.isNotEmpty() && !list.contains(t)) list.add(t)
                        }
                    }
                }
                map
            } catch (e: Exception) {
                Log.w(TAG, "同义词表加载失败，中文搜索将退化为别名匹配", e)
                emptyMap()
            }
        }
    }

    /**
     * 按关键词查同义词命中的标签名。
     *
     * 关键词先过一遍繁简归一化：词条表用简体书写，用户输「長乳首」也要能查到「长乳首」的条目。
     *
     * @param keyword 用户输入（中文）
     * @param exactOnly true 时只做精确词条匹配（第一轮），false 时允许包含匹配（第二轮）
     */
    fun lookup(keyword: String, exactOnly: Boolean): List<String> =
        lookupNormalized(HanVariantTable.simplify(keyword), exactOnly)

    /**
     * 与 [lookup] 相同，但调用方**保证 key 已经过繁简归一化**。
     *
     * [TagSearcher.searchChinese] 一次检索内先归一化再分别做精确/包含两轮查表，
     * 走这个入口可以少两次 `simplify`。
     */
    fun lookupNormalized(normalizedKey: String, exactOnly: Boolean): List<String> {
        val table = termToTags ?: return emptyList()
        val key = normalizedKey
        val direct = table[key]
        if (direct != null && direct.isNotEmpty()) return direct
        if (exactOnly) return emptyList()
        val merged = LinkedHashSet<String>()
        for ((term, tags) in table) {
            if (merged.size >= MAX_SUGGEST) break
            if (key in term || term in key) merged.addAll(tags)
        }
        return merged.toList()
    }

    /** 包含匹配最多返回的标签数（与电脑端 keys[:3] × 多词条的量级相当）。 */
    private const val MAX_SUGGEST = 30
}
