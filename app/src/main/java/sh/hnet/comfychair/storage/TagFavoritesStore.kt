package sh.hnet.comfychair.storage

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 标签库的「收藏」与「最近使用」（本地持久化）。
 *
 * 条目量很小（几十到几百），存成一份 JSON 放私有目录即可，不需要数据库。
 *
 * **整份数据读一次后常驻内存**：收藏与最近使用共用同一个文件，而这两个方法会在点击标签 /
 * 点收藏时被直接调用，原先每次都是"读两次文件、写一次文件"（pushRecent 读 2 次、初始化读 2 次）。
 * 常驻之后，写盘仍然只写一次，读盘只在首次发生。
 */
class TagFavoritesStore(context: Context) {

    private val appContext = context.applicationContext

    /** 整份 JSON 的常驻副本（首次 [root] 时读盘，之后一律走内存）。 */
    private var cachedRoot: JSONObject? = null

    private val file: File
        get() = File(appContext.filesDir, FILE_NAME)

    /** 取整份 JSON（首次读盘，之后走内存）。 */
    private fun root(): JSONObject {
        cachedRoot?.let { return it }
        val loaded = try {
            val f = file
            if (f.exists()) JSONObject(f.readText()) else JSONObject()
        } catch (e: Exception) {
            Log.w(TAG, "读取标签收藏失败，按空处理", e)
            JSONObject()
        }
        cachedRoot = loaded
        return loaded
    }

    /** 收藏的标签名（顺序无意义，用 Set 语义）。 */
    fun loadFavorites(): Set<String> {
        val array = root().optJSONArray(KEY_FAVORITES) ?: return emptySet()
        val out = LinkedHashSet<String>()
        for (i in 0 until array.length()) {
            array.optString(i).takeIf { it.isNotEmpty() }?.let { out.add(it) }
        }
        return out
    }

    fun saveFavorites(tags: Set<String>) {
        writeArray(KEY_FAVORITES, JSONArray(tags.toList()))
    }

    /** 最近使用的标签，最新的在前。 */
    fun loadRecents(): List<String> {
        val array = root().optJSONArray(KEY_RECENTS) ?: return emptyList()
        val out = ArrayList<String>(array.length())
        for (i in 0 until array.length()) {
            array.optString(i).takeIf { it.isNotEmpty() }?.let { out.add(it) }
        }
        return out
    }

    /**
     * 记一次使用：已存在则提到最前，超出 [MAX_RECENTS] 截断。
     */
    fun pushRecent(tag: String): List<String> {
        if (tag.isBlank()) return loadRecents()
        val list = LinkedHashSet<String>()
        list.add(tag)
        list.addAll(loadRecents())
        val trimmed = list.toList().take(MAX_RECENTS)
        writeArray(KEY_RECENTS, JSONArray(trimmed))
        return trimmed
    }

    private fun writeArray(key: String, array: JSONArray) {
        try {
            // 走常驻副本：避免"写之前再读一次文件"（原先 writeArray 每次都重新 readText）
            val root = root()
            root.put(key, array)
            file.writeText(root.toString())
        } catch (e: Exception) {
            Log.w(TAG, "写入标签收藏失败", e)
        }
    }

    companion object {
        private const val TAG = "TagFavorites"
        private const val FILE_NAME = "tag_favorites.json"
        private const val KEY_FAVORITES = "favorites"
        private const val KEY_RECENTS = "recents"
        private const val MAX_RECENTS = 30
    }
}
