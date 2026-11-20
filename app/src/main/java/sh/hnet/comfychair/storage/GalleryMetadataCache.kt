package sh.hnet.comfychair.storage

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import sh.hnet.comfychair.model.ThreeDResultHolder
import sh.hnet.comfychair.util.DebugLogger
import sh.hnet.comfychair.viewmodel.GalleryItem
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

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

    /** 原子写的临时文件后缀：先写 `<目标>.tmp`，fsync 后整体改名覆盖目标 */
    private const val TMP_SUFFIX = ".tmp"

    /**
     * 条目身份分隔符（NUL）。
     *
     * 用 NUL 而不是 `_`：文件名里 `_` 很常见（`ComfyUI_00001_.png`），
     * 用 `_` 拼键会让 `(a, b_c)` 与 `(a_b, c)` 撞成同一个键；NUL 不可能出现在
     * promptId / 文件名里，拼接结果天然无歧义。
     */
    private const val IDENTITY_SEPARATOR = '\u0000'

    /**
     * 条目级身份键：`promptId\0filename`（P1-19）。
     *
     * 同一个 promptId 会产出多张图（批量出图 4 张共用一次生成），
     * 所以"删掉其中一张"必须用**条目**身份，不能拿 promptId 当键整批删。
     */
    fun itemIdentityKey(promptId: String, filename: String): String =
        "$promptId$IDENTITY_SEPARATOR$filename"

    /**
     * 判断条目是否已被删除（P1-19）。
     *
     * 墓碑集合里可能同时存在两种粒度：
     * - **条目键**（含 NUL，新格式）：只删这一张；
     * - **裸 promptId**（不含 NUL，旧版本写下的格式）：整批删掉该 prompt 的所有图，
     *   保持向后兼容 —— 旧墓碑文件里的删除意图不能被新版本解释丢。
     */
    fun isDeleted(promptId: String, filename: String, tombstones: Set<String>): Boolean {
        if (tombstones.isEmpty()) return false
        return promptId in tombstones || itemIdentityKey(promptId, filename) in tombstones
    }

    /** [isDeleted] 的条目重载 */
    fun isDeleted(item: GalleryItem, tombstones: Set<String>): Boolean =
        isDeleted(item.promptId, item.filename, tombstones)

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
     * @param keys 删除身份键：条目级用 [itemIdentityKey] 拼（只删一张），
     *             整批删除时也可以直接传裸 promptId（旧语义，兼容保留）
     */
    fun addTombstones(context: Context, serverId: String, keys: Set<String>) {
        if (keys.isEmpty()) return
        try {
            val merged = loadTombstones(context, serverId) + keys
            val array = JSONArray()
            merged.forEach { array.put(it) }
            writeTextAtomically(getTombstoneFile(context, serverId), array.toString())
            DebugLogger.i(TAG, "Recorded ${keys.size} deletion tombstone(s) for server $serverId")
        } catch (e: Exception) {
            DebugLogger.e(TAG, "Failed to record deletion tombstones: ${e.message}")
        }
    }

    /**
     * 抹掉"已在手机上删掉"的墓碑记录（G4 回收站**恢复**用）。
     *
     * [loadTombstones] 是跨文件合并读的（删除意图跨网络成立），所以删除也必须
     * **遍历全部墓碑文件**逐个改写 —— 只删当前服务器那份的话，旧文件里的同键墓碑
     * 会把"已恢复"的图再次滤掉，恢复等于没发生。
     */
    fun removeTombstones(context: Context, serverId: String, keys: Set<String>) {
        if (keys.isEmpty()) return
        for (file in tombstoneFiles(context)) {
            try {
                val array = JSONArray(file.readText())
                val kept = JSONArray()
                for (i in 0 until array.length()) {
                    val key = array.optString(i)
                    if (key.isNotBlank() && key !in keys) kept.put(key)
                }
                writeTextAtomically(file, kept.toString())
            } catch (e: Exception) {
                DebugLogger.w(TAG, "Failed to remove tombstones from ${file.name}: ${e.message}")
            }
        }
        // serverId 只作日志口径：墓碑读取本来就是全文件合并的，删除同样全文件处理
        DebugLogger.i(TAG, "Removed ${keys.size} tombstone(s) for server $serverId (restore)")
    }

    fun loadTombstones(context: Context, serverId: String): Set<String> {
        val result = mutableSetOf<String>()
        // 当前服务器的 + 其它服务器的：删除意图跨网络、跨条目的都成立。
        // 读取的是**原始集合**：既可能是条目键（含 NUL），也可能是旧版本的裸 promptId，
        // 判定统一走 [isDeleted]（两种粒度都认），这里不做解释。
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

    /**
     * 从本地索引里**真正移除**这些条目（G4 阶段二"本地残留清理"）。
     *
     * 与墓碑的分工：墓碑是"显示层过滤"（可撤销——回收站恢复就是删墓碑）；
     * 服务器文件确认删除成功后，索引记录本身已经没有存在意义（文件与服务器历史
     * 都指向不了它），这时才走这里物理移除。墓碑**保留**：服务器 /history 里
     * prompt 记录可能还在（ComfyUI 只删文件不删历史），留着它才能防止刷新复活。
     */
    fun removeFromIndex(context: Context, serverId: String, keys: Set<String>) {
        if (keys.isEmpty()) return
        val existing = loadMetadata(context, serverId) ?: return
        val kept = existing.filterNot { isDeleted(it, keys) }
        if (kept.size == existing.size) return
        writeMetadata(context, serverId, kept.mapIndexed { position, item -> item.copy(index = position) })
        DebugLogger.i(TAG, "Removed ${existing.size - kept.size} record(s) from gallery index of server $serverId")
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
    private fun GalleryItem.identityKey(): String = itemIdentityKey(promptId, filename)

    /**
     * 把文本**原子地**写进文件（P1-20）。
     *
     * 修复前是 `File.writeText`：它先截断再写，进程在中间被杀（或写到一半 ENOSPC）
     * 就会留下半截 JSON —— 下次启动解析失败，图库索引整份作废。
     * 现在写 `.tmp` → flush + fsync → 原子改名覆盖目标：任何时刻目标文件要么是
     * 旧的完整内容、要么是新的完整内容，不存在"写了一半"的中间态。
     *
     * 抽成 internal 是为了 JVM 单测能直接钉住"覆盖写不残留 tmp、旧内容不被截断"。
     */
    internal fun writeTextAtomically(file: File, content: String) {
        val tmp = File(file.parentFile, file.name + TMP_SUFFIX)
        FileOutputStream(tmp).use { out ->
            out.write(content.toByteArray(Charsets.UTF_8))
            out.flush()
            // fsync：把页缓存刷到磁盘，否则"rename 成功但数据还在缓存里"同样会丢
            out.fd.sync()
        }
        try {
            Files.move(
                tmp.toPath(),
                file.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE
            )
        } catch (e: Exception) {
            // 个别文件系统不支持 ATOMIC_MOVE（或目标被占用）：退化为"先删目标再改名"，
            // 至少不会留下半截文件；仍失败则整份拷贝兜底
            DebugLogger.w(TAG, "Atomic move failed for ${file.name}, falling back: ${e.message}")
            if (!tmp.renameTo(file)) {
                file.delete()
                if (!tmp.renameTo(file)) {
                    tmp.copyTo(file, overwrite = true)
                    tmp.delete()
                }
            }
        }
    }

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
            writeTextAtomically(cacheFile, array.toString())
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
