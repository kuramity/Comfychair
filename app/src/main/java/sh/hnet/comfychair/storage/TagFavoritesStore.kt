package sh.hnet.comfychair.storage

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val TAG = "TagFavorites"

/** 收藏文件的最小 IO 接口（便于 JVM 单测替换真实文件）。 */
internal interface TagFavoritesIo {
    fun exists(): Boolean
    fun readText(): String
    fun writeText(text: String)

    /** 把损坏的原文件另存为 `<原名>.corrupt-<时间戳>`；返回备份文件名，失败返回 null。 */
    fun backupCorrupt(): String?
}

/**
 * 标签库的「收藏」与「最近使用」（本地持久化）。
 *
 * 条目量很小（几十到几百），存成一份 JSON 放私有目录即可，不需要数据库。
 *
 * **整份数据读一次后常驻内存**：收藏与最近使用共用同一个文件，而这两个方法会在点击标签 /
 * 点收藏时被直接调用，原先每次都是"读两次文件、写一次文件"（pushRecent 读 2 次、初始化读 2 次）。
 * 常驻之后，写盘仍然只写一次，读盘只在首次发生。
 *
 * **损坏保护**：JSON 解析失败时先把原文件备份成 `.corrupt-<时间戳>`，本次运行内拒绝
 * 任何写入 —— 否则「读到空表 → 加一条 → 整份写回」会把用户原有收藏直接覆盖掉。
 */
class TagFavoritesStore internal constructor(
    private val io: TagFavoritesIo,
    private val logWarn: (String, Throwable?) -> Unit = { message, error ->
        Log.w(TAG, message, error)
    }
) {

    constructor(context: Context) : this(
        FileTagFavoritesIo(File(context.applicationContext.filesDir, FILE_NAME))
    )

    /** 最近一次读取是否解析失败；失败后拒绝写入，避免用空表覆盖原文件。 */
    @Volatile
    private var lastParseFailed = false

    /** 整份 JSON 的常驻副本（首次 [root] 时读盘，之后一律走内存）。 */
    private var cachedRoot: JSONObject? = null

    /** 最近一次读取是否解析失败（调用方可据此给出可见提示）。 */
    fun didLastParseFail(): Boolean = lastParseFailed

    /** 取整份 JSON（首次读盘，之后走内存）。解析失败时备份原文件并标记拒绝写入。 */
    private fun root(): JSONObject {
        cachedRoot?.let { return it }
        val loaded = try {
            if (io.exists()) JSONObject(io.readText()) else JSONObject()
        } catch (e: Exception) {
            lastParseFailed = true
            logWarn("读取标签收藏失败，本次运行拒绝写入以免覆盖原文件", e)
            io.backupCorrupt()?.let { name ->
                logWarn("已备份损坏的标签收藏：$name", null)
            }
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

    /**
     * 保存收藏。
     *
     * @return false 表示存储此前解析失败（已备份原文件），本次写入被拒绝
     */
    fun saveFavorites(tags: Set<String>): Boolean =
        writeArray(KEY_FAVORITES, JSONArray(tags.toList()))

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
     *
     * 解析失败时原样返回已有列表，不假装写入成功。
     */
    fun pushRecent(tag: String): List<String> {
        if (tag.isBlank()) return loadRecents()
        val current = loadRecents()  // 触发读取与损坏检测
        if (lastParseFailed) return current
        val list = LinkedHashSet<String>()
        list.add(tag)
        list.addAll(current)
        val trimmed = list.toList().take(MAX_RECENTS)
        writeArray(KEY_RECENTS, JSONArray(trimmed))
        return trimmed
    }

    private fun writeArray(key: String, array: JSONArray): Boolean {
        // 必须先读一次：解析失败会在 root() 里置位，此时绝不能把「空 + 新数据」写回去
        val root = root()
        if (lastParseFailed) {
            logWarn("标签收藏数据此前解析失败，本次写入已拒绝（原文件保留）", null)
            return false
        }
        return try {
            // 走常驻副本：避免"写之前再读一次文件"（原先 writeArray 每次都重新 readText）
            root.put(key, array)
            io.writeText(root.toString())
            true
        } catch (e: Exception) {
            logWarn("写入标签收藏失败", e)
            false
        }
    }

    companion object {
        private const val FILE_NAME = "tag_favorites.json"
        private const val KEY_FAVORITES = "favorites"
        private const val KEY_RECENTS = "recents"
        private const val MAX_RECENTS = 30
    }
}

/** 真实文件 IO：备份时把原文件复制成同目录下的 `.corrupt-<时间戳>`。 */
private class FileTagFavoritesIo(private val file: File) : TagFavoritesIo {
    override fun exists(): Boolean = file.exists()

    override fun readText(): String = file.readText()

    override fun writeText(text: String) {
        file.writeText(text)
    }

    override fun backupCorrupt(): String? {
        return try {
            if (!file.exists()) return null
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            val backup = File(file.parentFile, "${file.name}.corrupt-$stamp")
            file.copyTo(backup, overwrite = true)
            backup.name
        } catch (e: Exception) {
            Log.e(TAG, "备份损坏的标签收藏失败", e)
            null
        }
    }
}