package sh.hnet.comfychair.storage

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import sh.hnet.comfychair.model.ThreeDResultHolder
import sh.hnet.comfychair.util.DebugLogger
import sh.hnet.comfychair.viewmodel.GalleryItem
import java.io.File

/**
 * 图库的**本地持久索引**（每台服务器一个文件：`gallery_metadata_{serverId}.json`）。
 *
 * 它不只是"离线时用的缓存"，而是图库的底座。原因：服务器历史（ComfyUI 的 `/history`）
 * 是**内存态**的，ComfyUI 一重启就清空，而图片文件其实一直躺在 output 目录里。
 * 只要本地记得住 `(promptId, filename)`，换任何网络、重启多少次都能按当前地址重新下载。
 *
 * 所以写入语义是**并集**而不是覆盖 —— 覆盖式写法会在服务器历史被清空时把整份索引
 * 冲成空，那正是"图库突然一张都没有、只有新生成的图能看到"的直接原因。
 *
 * 另有 `gallery_deleted_{serverId}.json` 记录"已在手机上删掉"的条目：
 * 删除只影响手机端（服务器的记录与文件都不动），所以必须有这份墓碑，
 * 否则下次刷新服务器历史时，删掉的图会原样复活。
 */
object GalleryMetadataCache {
    private const val TAG = "GalleryMetadataCache"
    private const val CACHE_PREFIX = "gallery_metadata_"
    private const val CACHE_SUFFIX = ".json"
    private const val TOMBSTONE_PREFIX = "gallery_deleted_"
    private const val TOMBSTONE_SUFFIX = ".json"

    // ============================================================
    // 本地索引
    // ============================================================

    /**
     * 把服务器这次拉到的条目并进本地索引，返回**合并后的全量列表**。
     *
     * 调用方拿这个返回值直接当图库数据用：服务器认得的（新）+ 本地记得的（服务器已不认得）。
     *
     * @param incoming 服务器本次返回的条目（按"新的在前"排好序）
     */
    fun mergeMetadata(
        context: Context,
        serverId: String,
        incoming: List<GalleryItem>
    ): List<GalleryItem> {
        val existing = loadMetadata(context, serverId).orEmpty()
        val merged = mergeItems(existing, incoming)
        writeMetadata(context, serverId, merged)
        return merged
    }

    /**
     * 合并两组条目：按 `(promptId, filename)` 去重，**服务器条目在前、本地独有的在后**。
     *
     * 顺序不是随手定的：服务器返回的是"现在还在的"（较新），本地独有的那些是
     * "服务器已经不认识"的（较旧），图库按"新的在前"展示，所以先放服务器那批。
     *
     * 抽成 internal 纯函数是为了能在 JVM 单测里钉住这两条规则（去重 + 顺序），
     * 它们正是"图库不再丢图"的核心。
     */
    internal fun mergeItems(
        existing: List<GalleryItem>,
        incoming: List<GalleryItem>
    ): List<GalleryItem> {
        val seen = mutableSetOf<String>()
        val merged = mutableListOf<GalleryItem>()

        for (item in incoming) {
            if (seen.add(item.identityKey())) merged += item
        }
        for (item in existing) {
            if (seen.add(item.identityKey())) merged += item
        }

        // index 是解析阶段的临时序号，合并后重排一遍，免得两条记录带着同一个序号
        return merged.mapIndexed { position, item -> item.copy(index = position) }
    }

    /**
     * 读取本地索引；当前服务器没有索引时，回退到**最近更新的那一份**。
     *
     * 为什么要兜底：换网络（热点、不同网段）或改建服务器条目都会换 serverId，
     * 严格按 serverId 隔离会让之前攒下的图库凭空消失 —— 用户看到的就是"图全没了"。
     * 多台服务器各自攒的索引仍然各自保存，这里只在"当前这台一片空白"时才借用最近的一份。
     */
    fun loadMetadataWithFallback(context: Context, serverId: String): List<GalleryItem>? {
        loadMetadata(context, serverId)?.let { return it }

        val fallback = cacheFiles(context).maxByOrNull { it.lastModified() } ?: return null
        DebugLogger.i(TAG, "No index for server $serverId, falling back to ${fallback.name}")
        return parseItems(fallback)
    }

    fun loadMetadata(context: Context, serverId: String): List<GalleryItem>? {
        val cacheFile = getCacheFile(context, serverId)
        if (!cacheFile.exists()) {
            DebugLogger.d(TAG, "No gallery metadata cache found for server $serverId")
            return null
        }
        return parseItems(cacheFile)
    }

    fun hasCache(context: Context, serverId: String): Boolean =
        getCacheFile(context, serverId).exists()

    /** 本地索引的最近更新时间（毫秒），没有索引时为 0 */
    fun getCacheTimestamp(context: Context, serverId: String): Long {
        val cacheFile = getCacheFile(context, serverId)
        return if (cacheFile.exists()) cacheFile.lastModified() else 0L
    }

    // ============================================================
    // 删除墓碑
    // ============================================================

    /**
     * 记住"这些条目已经在手机上删掉"，避免服务器历史刷新时把它们又带回来。
     *
     * 读的时候会把**所有服务器**的墓碑合并起来：删除是用户的明确意图，
     * 换个网络、改个地址都不该让删掉的图复活。
     */
    fun addTombstones(context: Context, serverId: String, promptIds: Set<String>) {
        if (promptIds.isEmpty()) return
        try {
            val merged = loadTombstones(context, serverId) + promptIds
            val array = JSONArray()
            merged.forEach { array.put(it) }
            getTombstoneFile(context, serverId).writeText(array.toString())
            DebugLogger.i(TAG, "Recorded ${promptIds.size} deletion tombstone(s) for server $serverId")
        } catch (e: Exception) {
            DebugLogger.e(TAG, "Failed to record deletion tombstones: ${e.message}")
        }
    }

    fun loadTombstones(context: Context, serverId: String): Set<String> {
        val result = mutableSetOf<String>()
        // 当前服务器的 + 其它服务器的：删除意图跨网络、跨条目的都成立
        val files = tombstoneFiles(context)
        for (file in files) {
            try {
                val array = JSONArray(file.readText())
                for (i in 0 until array.length()) {
                    array.optString(i).takeIf { it.isNotBlank() }?.let { result += it }
                }
            } catch (e: Exception) {
                DebugLogger.w(TAG, "Failed to read tombstones from ${file.name}: ${e.message}")
            }
        }
        // serverId 形参保留在签名里：将来若要改回"按服务器隔离"，调用方不用动
        @Suppress("UNUSED_EXPRESSION") serverId
        return result
    }

    // ============================================================
    // 清理
    // ============================================================

    /** 清掉某台服务器的本地索引与墓碑（设置里的「清除缓存」走这里）。 */
    fun clearCache(context: Context, serverId: String) {
        try {
            val cacheFile = getCacheFile(context, serverId)
            if (cacheFile.exists()) {
                cacheFile.delete()
                DebugLogger.d(TAG, "Gallery metadata cache cleared for server $serverId")
            }
            val tombstoneFile = getTombstoneFile(context, serverId)
            if (tombstoneFile.exists()) {
                tombstoneFile.delete()
                DebugLogger.d(TAG, "Gallery deletion tombstones cleared for server $serverId")
            }
        } catch (e: Exception) {
            DebugLogger.e(TAG, "Failed to clear gallery metadata cache for server $serverId: ${e.message}")
        }
    }

    // ============================================================
    // 内部
    // ============================================================

    /** 条目身份：同一个 prompt 可能产出多张图，所以要用 (promptId, filename) 作键 */
    private fun GalleryItem.identityKey(): String = "$promptId\u0000$filename"

    private fun writeMetadata(context: Context, serverId: String, items: List<GalleryItem>) {
        try {
            val array = JSONArray()
            for (item in items) {
                array.put(JSONObject().apply {
                    put("promptId", item.promptId)
                    put("filename", item.filename)
                    put("subfolder", item.subfolder)
                    put("type", item.type)
                    put("isVideo", item.isVideo)
                    put("index", item.index)
                    // 3D 产物位置：字段可选（旧索引没有 → optString 拿到空 → threeD=null，向后兼容）
                    item.threeD?.let {
                        put("threeDFilename", it.filename)
                        put("threeDSubfolder", it.subfolder)
                        put("threeDType", it.type)
                    }
                })
            }
            val cacheFile = getCacheFile(context, serverId)
            cacheFile.writeText(array.toString())
            DebugLogger.i(TAG, "Gallery index saved for server $serverId: ${items.size} items")
        } catch (e: Exception) {
            DebugLogger.e(TAG, "Failed to save gallery metadata for server $serverId: ${e.message}")
        }
    }

    private fun parseItems(file: File): List<GalleryItem>? {
        return try {
            val jsonArray = JSONArray(file.readText())
            val items = mutableListOf<GalleryItem>()
            for (i in 0 until jsonArray.length()) {
                val jsonItem = jsonArray.getJSONObject(i)
                val threeD = jsonItem.optString("threeDFilename", "")
                    .takeIf { it.isNotBlank() }
                    ?.let {
                        ThreeDResultHolder.Result(
                            filename = it,
                            subfolder = jsonItem.optString("threeDSubfolder", ""),
                            type = jsonItem.optString("threeDType", "output")
                        )
                    }
                items.add(GalleryItem(
                    promptId = jsonItem.getString("promptId"),
                    filename = jsonItem.getString("filename"),
                    subfolder = jsonItem.optString("subfolder", ""),
                    type = jsonItem.optString("type", "output"),
                    isVideo = jsonItem.optBoolean("isVideo", false),
                    index = jsonItem.optInt("index", i),
                    threeD = threeD
                ))
            }
            DebugLogger.i(TAG, "Gallery index loaded from ${file.name}: ${items.size} items")
            items
        } catch (e: Exception) {
            DebugLogger.e(TAG, "Failed to load gallery metadata from ${file.name}: ${e.message}")
            null
        }
    }

    private fun getCacheFile(context: Context, serverId: String): File =
        File(context.filesDir, "$CACHE_PREFIX$serverId$CACHE_SUFFIX")

    private fun getTombstoneFile(context: Context, serverId: String): File =
        File(context.filesDir, "$TOMBSTONE_PREFIX$serverId$TOMBSTONE_SUFFIX")

    private fun cacheFiles(context: Context): List<File> =
        context.filesDir.listFiles { file ->
            file.name.startsWith(CACHE_PREFIX) && file.name.endsWith(CACHE_SUFFIX)
        }?.toList().orEmpty()

    private fun tombstoneFiles(context: Context): List<File> =
        context.filesDir.listFiles { file ->
            file.name.startsWith(TOMBSTONE_PREFIX) && file.name.endsWith(TOMBSTONE_SUFFIX)
        }?.toList().orEmpty()
}
