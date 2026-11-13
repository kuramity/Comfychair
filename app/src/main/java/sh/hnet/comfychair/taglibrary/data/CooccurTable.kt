package sh.hnet.comfychair.taglibrary.data

import android.content.Context
import android.util.Log
import org.json.JSONObject

/**
 * 「常见搭配」查询表（懒加载 `assets/databases/tag_cooccur.json`）。
 *
 * 数据由 `tools/comfyui-scripts/pack_tag_cooccur.py` 从电脑端的共现库提取：
 * 只保留双方热度都够高的标签对（456KB / 1774 个标签 / 2.2 万条），
 * 每个标签最多 20 条、按共现次数降序。
 *
 * 用途：在标签详情里提示"用这个标签的人通常还会加什么"，点一下即可加入提示词。
 */
object CooccurTable {

    private const val TAG = "CooccurTable"
    private const val ASSET_PATH = "databases/tag_cooccur.json"

    @Volatile
    private var table: Map<String, List<Pair<String, Long>>>? = null

    val isLoaded: Boolean get() = table != null

    /** 懒加载（线程安全；失败时退化为空表，不影响标签库其它功能）。 */
    fun ensureLoaded(context: Context) {
        if (table != null) return
        synchronized(this) {
            if (table != null) return
            table = try {
                val json = context.assets.open(ASSET_PATH).use { it.readBytes().decodeToString() }
                val root = JSONObject(json)
                val map = HashMap<String, List<Pair<String, Long>>>(root.length() * 4 / 3 + 1)
                val keys = root.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val array = root.optJSONArray(key) ?: continue
                    val items = ArrayList<Pair<String, Long>>(array.length())
                    for (i in 0 until array.length()) {
                        val pair = array.optJSONArray(i) ?: continue
                        val name = pair.optString(0)
                        val count = pair.optLong(1)
                        if (name.isNotEmpty()) items.add(name to count)
                    }
                    if (items.isNotEmpty()) map[key] = items
                }
                map
            } catch (e: Exception) {
                Log.w(TAG, "常见搭配表加载失败，该功能将不显示", e)
                emptyMap()
            }
        }
    }

    /** 测试注入（JVM 单测没有 assets）。 */
    internal fun setTableForTest(value: Map<String, List<Pair<String, Long>>>?) {
        table = value
    }

    /**
     * 取某标签的常见搭配（按共现次数降序）。
     *
     * @param limit 最多返回多少条（详情页展示 12 条足够）
     */
    fun partners(tag: String, limit: Int = 12): List<Pair<String, Long>> {
        val items = table?.get(tag) ?: return emptyList()
        return if (items.size <= limit) items else items.subList(0, limit)
    }
}
