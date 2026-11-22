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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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

    /** 解析失败时保留证据的备份后缀前缀：`<原名>.corrupt-yyyyMMdd-HHmmss` */
    private const val CORRUPT_SUFFIX_PREFIX = ".corrupt-"

    /** `"[]"` 的字节长度：文件长度不超过它 = 原本就是空索引，允许被空表覆盖 */
    private const val EMPTY_JSON_ARRAY_LENGTH = 2L

    /** 写前内存备份上限：超过则不做备份（仍会做写后读回校验，只是失败时无法回滚） */
    private const val MAX_BACKUP_BYTES = 8L * 1024 * 1024

    /** 日志里回显读回内容的截断长度（本次 P0 排障要求带文件开头片段） */
    private const val LOG_PREVIEW_CHARS = 200

    /**
     * 条目身份分隔符（NUL）。
     *
     * 用 NUL 而不是 `_`：文件名里 `_` 很常见（`ComfyUI_00001_.png`），
     * 用 `_` 拼键会让 `(a, b_c)` 与 `(a_b, c)` 撞成同一个键；NUL 不可能出现在
     * promptId / 文件名里，拼接结果天然无歧义。
     */
    private const val IDENTITY_SEPARATOR = '\u0000'

    /**
     * 索引"读-改-写"的互斥锁。
     *
     * 刷新合并（mergeMetadata）与 404 标记写入（markSourceMissing）会来自不同协程：
     * 两边都是"读整份 → 改 → 写整份"，不加锁时后写的一方会覆盖先写的一方
     * —— 刚标记的 sourceMissing 可能在一次并发刷新后丢失（角标消失、重新白等超时）。
     * 锁只包住这些短小的读改写段，I/O 本身仍在调用方的 IO 线程上。
     */
    private val indexWriteLock = Any()

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
     * @return 合并结果；**本地索引读不出来（不存在/解析失败）且本次服务器也没有条目**时
     *         返回 null —— 调用方必须保留上一次的列表，绝不能把 null 当成"图库空了"。
     */
    fun mergeMetadata(
        context: Context,
        serverId: String,
        incoming: List<GalleryItem>
    ): List<GalleryItem>? = mergeMetadataInto(context.filesDir, serverId, incoming)

    /** [mergeMetadata] 的文件目录版本：JVM 单测可用临时目录直接钉住"拒绝写空"规则。 */
    internal fun mergeMetadataInto(
        filesDir: File,
        serverId: String,
        incoming: List<GalleryItem>
    ): List<GalleryItem>? = synchronized(indexWriteLock) {
        val existing = loadMetadataFromDir(filesDir, serverId)
        val merged = mergeOrNull(existing, incoming) ?: return@synchronized null
        // R10：落盘结果必须被检查。写被拒/回滚时磁盘仍是旧内容，这里明确记 error，
        // 返回的 merged 只作"本次展示"用（调用方不再把它当成已持久化的事实）。
        if (!writeMetadataToFile(getCacheFile(filesDir, serverId), merged)) {
            DebugLogger.e(
                TAG,
                "合并结果未能落盘（磁盘保留旧索引），本次仅用于展示: ${merged.size} 条"
            )
        }
        merged
    }

    /**
     * 合并决策（纯函数）。
     *
     * `existing == null` 表示索引**读不出来**（文件不存在或解析失败），而不是"索引是空表"。
     * 这种状态下如果服务器也没有任何条目，唯一合理的动作是**什么都不做**：
     * 旧实现把它当成空列表再写回磁盘，于是"服务器重启（/history 为空）+ 索引瞬间读失败"
     * 就把整份索引冲成 `[]` —— 这正是"图库一张都没有"的成因之一。
     */
    internal fun mergeOrNull(
        existing: List<GalleryItem>?,
        incoming: List<GalleryItem>
    ): List<GalleryItem>? {
        if (existing == null && incoming.isEmpty()) return null
        return mergeItems(existing.orEmpty(), incoming)
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
        // 「源已不存在」是**本地观测**（/view 404），服务器 /history 永远不会提供它
        // —— ComfyUI 只删文件不删历史记录。所以同一个 (promptId, filename) 上，
        // 本地旧值必须与服务器新值做逻辑或：本地为 true 就保持 true。
        // 直接拿服务器条目覆盖会把刚标记的角标冲掉，条目又变回"模糊 + 每次白等超时"。
        val localSourceMissing = existing
            .filter { it.sourceMissing }
            .mapTo(mutableSetOf()) { it.identityKey() }
        val seen = mutableSetOf<String>()
        val merged = mutableListOf<GalleryItem>()

        for (item in incoming) {
            val key = item.identityKey()
            if (seen.add(key)) {
                val keepMissing = key in localSourceMissing
                merged += if (keepMissing && !item.sourceMissing) {
                    item.copy(sourceMissing = true)
                } else {
                    item
                }
            }
        }
        for (item in existing) {
            if (seen.add(item.identityKey())) merged += item
        }

        // index 是解析阶段的临时序号，合并后重排一遍，免得两条记录带着同一个序号
        return renumberByPosition(merged)
    }

    /**
     * 把「源已不存在（/view 404）」标记写进本地索引（纯函数：JVM 单测可直接钉住）。
     *
     * 只把命中键的 `sourceMissing` 置 true，其余字段原样保留；已经为 true 的条目
     * 不产生差异（调用方据返回值决定是否需要落盘）。
     */
    internal fun applySourceMissing(
        items: List<GalleryItem>,
        keys: Set<String>
    ): List<GalleryItem> = items.map { item ->
        if (item.sourceMissing || itemIdentityKey(item.promptId, item.filename) !in keys) {
            item
        } else {
            item.copy(sourceMissing = true)
        }
    }

    /**
     * 持久化一组条目的「源已不存在」标记。
     *
     * 索引读不出来时返回 false（调用方只记日志，不谎报已持久化）；索引里没有该条目
     * 也返回 false —— 没有记录可标记，说明本地数据不同步，不能假装成功。
     *
     * @return true = 索引已包含全部标记（含"原本就是 true"的情况）
     */
    fun markSourceMissing(context: Context, serverId: String, keys: Set<String>): Boolean =
        synchronized(indexWriteLock) {
            if (keys.isEmpty()) return@synchronized true
            val existing = loadMetadata(context, serverId) ?: run {
                DebugLogger.w(TAG, "无法标记源已不存在：索引读取失败（server $serverId）")
                return@synchronized false
            }
            // 索引里没有这条记录（例如条目只从磁盘缓存恢复、从未写进索引）：没有东西可标记，
            // 不能返回 true 让调用方以为已经持久化。
            if (existing.none { itemIdentityKey(it.promptId, it.filename) in keys }) {
                DebugLogger.w(TAG, "无法标记源已不存在：索引里没有对应条目（server $serverId）")
                return@synchronized false
            }
            val updated = applySourceMissing(existing, keys)
            if (updated == existing) return@synchronized true
            writeMetadata(context, serverId, updated)
        }

    /**
     * 「带标记的最小条目」并入索引列表（纯函数，JVM 单测直接钉住；文件 IO 见
     * [markSourceMissingOrInsert]）。
     *
     * 条目已在索引里 → 只把标记置 true（[applySourceMissing] 原语义，无差异返回原表）；
     * 索引里没有（仅磁盘缓存恢复、从未写进索引的条目）→ 追加到末尾并强制带标记，
     * 顺序交给 [renumberByPosition] 统一重排。
     */
    internal fun mergeMissingRecord(
        items: List<GalleryItem>,
        record: GalleryItem
    ): List<GalleryItem> {
        val identity = itemIdentityKey(record.promptId, record.filename)
        return if (items.any { itemIdentityKey(it.promptId, it.filename) == identity }) {
            applySourceMissing(items, setOf(identity))
        } else {
            renumberByPosition(items + record.copy(sourceMissing = true))
        }
    }

    /**
     * 把「源已不存在（/view 404）」标记写进本地索引；索引里没有该条目时**补记一条最小记录**。
     *
     * 背景：条目只从磁盘缓存恢复（索引读不出来后由缓存键重建、或索引里从未写过）时，
     * [markSourceMissing] 会因「索引里没有对应条目」而放弃 —— 标记只留在内存列表，
     * 重启 App 后随内存列表一起消失，第三态丢失。这里改为把带标记的最小条目补进索引，
     * 让重启后的加载（索引合并对 sourceMissing 做逻辑或）重新认出第三态。
     * 索引读取失败仍然返回 false（与 [markSourceMissing] 同口径，不谎报已持久化）。
     *
     * @param record 索引缺记录时用于补记的最小条目（promptId/filename/type/isVideo 齐全，
     *               subfolder 按磁盘缓存恢复的口径为空串；是否已存在由本函数判断）
     * @return true = 标记已持久化（含补记、含原本就是 true 的无差异情形）
     */
    fun markSourceMissingOrInsert(context: Context, serverId: String, record: GalleryItem): Boolean =
        synchronized(indexWriteLock) {
            val existing = loadMetadata(context, serverId) ?: run {
                DebugLogger.w(TAG, "无法标记源已不存在：索引读取失败（server $serverId）")
                return@synchronized false
            }
            val updated = mergeMissingRecord(existing, record)
            if (updated == existing) return@synchronized true
            writeMetadata(context, serverId, updated)
        }

    /**
     * 抹掉「源已不存在」标记（用户在图库/查看器里显式重试时用）。
     *
     * 文件被放回服务器后，重试成功即可恢复成正常条目；重试再 404 会重新标记。
     */
    fun clearSourceMissing(context: Context, serverId: String, keys: Set<String>): Boolean =
        synchronized(indexWriteLock) {
            if (keys.isEmpty()) return@synchronized true
            val existing = loadMetadata(context, serverId) ?: return@synchronized false
            var changed = false
            val updated = existing.map { item ->
                if (item.sourceMissing && itemIdentityKey(item.promptId, item.filename) in keys) {
                    changed = true
                    item.copy(sourceMissing = false)
                } else {
                    item
                }
            }
            if (!changed) return@synchronized true
            writeMetadata(context, serverId, updated)
        }

    /**
     * 按列表位置重新编号 `index`（0..n-1，天然无重复、无负数）。
     *
     * 列表本身是"新的在前"的展示顺序，index 只用于恢复时找回原位置；
     * 任何"条目集合发生变化"的写盘（合并、物理移除）都必须重排，否则会出现
     * 两条记录共用一个序号、或旧实现留下的负序号。
     */
    internal fun renumberByPosition(items: List<GalleryItem>): List<GalleryItem> =
        items.mapIndexed { position, item -> item.copy(index = position) }

    /**
     * 读取本地索引；当前服务器没有索引时，回退到**最近更新的那一份**。
     *
     * 为什么要兜底：换网络（热点、不同网段）或改建服务器条目都会换 serverId，
     * 严格按 serverId 隔离会让之前攒下的图库凭空消失 —— 用户看到的就是"图全没了"。
     * 多台服务器各自攒的索引仍然各自保存，这里只在"当前这台一片空白"时才借用最近的一份。
     */
    fun loadMetadataWithFallback(context: Context, serverId: String): List<GalleryItem>? =
        loadMetadataWithFallbackFromDir(context.filesDir, serverId)

    /**
     * [loadMetadataWithFallback] 的文件目录版本（JVM 单测可直接钉住"迁移"行为）。
     *
     * 兜底只借**能解析成功**的索引：最新那份如果坏了（parseItems 已把它备份成
     * `.corrupt-*`），继续往后找 —— 否则"最新那份恰好损坏"会让兜底同样读不出东西，
     * 用户看到的就是"图库怎么也加载不出图片"。
     */
    internal fun loadMetadataWithFallbackFromDir(filesDir: File, serverId: String): List<GalleryItem>? {
        loadMetadataFromDir(filesDir, serverId)?.let { return it }

        val currentFile = getCacheFile(filesDir, serverId)
        var fallback: File? = null
        var fallbackItems: List<GalleryItem>? = null
        val candidates = cacheFiles(filesDir)
            .filterNot { it.name == currentFile.name }
            .sortedByDescending { it.lastModified() }
        for (candidate in candidates) {
            val parsed = parseItems(candidate) ?: continue
            fallback = candidate
            fallbackItems = parsed
            break
        }
        val source = fallback ?: return null
        val items = fallbackItems ?: return null

        DebugLogger.i(TAG, "No index for server $serverId, falling back to ${source.name}")
        migrateFallbackIfNeeded(currentFile, source, items)
        return items
    }

    /**
     * 借来的索引要在当前 serverId 下落一份（迁移）：否则每次冷启动都要重新借，
     * 而"最新那份"随时可能被别的服务器写掉。
     *
     * 当前 serverId 的文件**已存在**时只读不写：存在即说明它有自己的内容
     * （哪怕解析失败，坏文件也要留着当证据，由 `.corrupt-*` 备份 + 人工判断处理）。
     *
     * @return true = 真的写出了迁移副本
     */
    internal fun migrateFallbackIfNeeded(
        target: File,
        fallback: File,
        items: List<GalleryItem>
    ): Boolean {
        if (target.exists()) return false
        val content = try {
            fallback.readText()
        } catch (e: Exception) {
            DebugLogger.w(TAG, "Fallback index read failed during migration: ${e.message}")
            return false
        }
        val ok = writeTextAtomicallyVerified(
            file = target,
            content = content,
            label = "索引迁移",
            // 迁移内容必须是"读得出来且条目数一致"的：绝不把借来的内容原样写成新的坏文件
            verify = { text ->
                val parsed = parseItemsText(text)
                parsed != null && parsed.size == items.size
            }
        )
        if (ok) {
            DebugLogger.i(
                TAG,
                "Migrated fallback index ${fallback.name} -> ${target.name} (${items.size} items)"
            )
        }
        return ok
    }

    fun loadMetadata(context: Context, serverId: String): List<GalleryItem>? =
        loadMetadataFromDir(context.filesDir, serverId)

    internal fun loadMetadataFromDir(filesDir: File, serverId: String): List<GalleryItem>? {
        val cacheFile = getCacheFile(filesDir, serverId)
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
     * @return 是否**真的落盘并校验通过**。false 时调用方必须当作"删除未持久化"
     *         处理（内存里的隐藏状态与磁盘会不一致，重启后图片会复现），不能记成功。
     */
    fun addTombstones(context: Context, serverId: String, keys: Set<String>): Boolean {
        if (keys.isEmpty()) return true
        try {
            val target = getTombstoneFile(context, serverId)
            // R7：目标文件本身读不出来时，先备份成 .corrupt-* 再覆盖 —— 否则这次
            // "可读子集 + 新键"的整份重写会把旧的不可解析删除记录永久抹掉。
            backupTombstoneIfUnreadable(target)
            val merged = loadTombstones(context, serverId) + keys
            // JSONArray.toString() 会把 NUL 转义成 `\u0000` 六个字符；写出去的字节里
            // 不能出现真正的 0x00（真 NUL 会让部分解析器把字符串截断）。校验里一并钉住。
            // 写后读回：解析失败/条数不一致一律回滚，绝不留下"写成功但读不出来"的墓碑。
            val ok = writeTextAtomicallyVerified(
                file = target,
                content = tombstoneJson(merged),
                label = "删除墓碑",
                verify = { text -> isTombstoneDocumentConsistent(text, merged) }
            )
            if (ok) {
                DebugLogger.i(TAG, "Recorded ${keys.size} deletion tombstone(s) for server $serverId")
            } else {
                DebugLogger.e(
                    TAG,
                    "删除墓碑写入失败（已回滚），本次 ${keys.size} 条删除未持久化: $serverId"
                )
            }
            return ok
        } catch (e: Exception) {
            DebugLogger.e(TAG, "Failed to record deletion tombstones: ${e.message}")
            return false
        }
    }

    /**
     * 墓碑文件读不出来（截断/编码坏）时先备份成 `.corrupt-*`，**不删原文件**。
     *
     * @return true = 该文件存在但读不出来（已尝试备份）
     */
    internal fun backupTombstoneIfUnreadable(file: File): Boolean {
        if (!file.exists()) return false
        val readable = try {
            JSONArray(file.readText())
            true
        } catch (e: Exception) {
            DebugLogger.e(TAG, "墓碑文件读取失败（将先备份再重写）: ${file.name}: ${e.message}")
            false
        }
        if (!readable) backupCorruptFile(file)
        return !readable
    }

    /** 把身份键集合序列化成墓碑 JSON（纯函数，JVM 单测用它验证 NUL 转义）。 */
    internal fun tombstoneJson(keys: Collection<String>): String {
        val array = JSONArray()
        keys.forEach { array.put(it) }
        return array.toString()
    }

    /**
     * 墓碑文件读回校验：能解析、条数一致、内容集合一致，且**文本里没有裸 NUL**。
     *
     * 裸 NUL 检查是本次删除路径审查的专项：`\u0000` 是合法的 JSON 转义（6 个字符），
     * 而真正的 0x00 字节不是 —— 后者会让某些 JSON 解析器提前截断整份数组。
     */
    internal fun isTombstoneDocumentConsistent(
        text: String,
        expected: Collection<String>
    ): Boolean {
        if (text.indexOf(IDENTITY_SEPARATOR) >= 0) return false
        val array = try {
            JSONArray(text)
        } catch (e: Exception) {
            return false
        }
        if (array.length() != expected.size) return false
        val expectedSet = expected.toSet()
        for (i in 0 until array.length()) {
            if (array.optString(i) !in expectedSet) return false
        }
        return true
    }

    /**
     * 抹掉"已在手机上删掉"的墓碑记录（G4 回收站**恢复**用）。
     *
     * [loadTombstones] 是跨文件合并读的（删除意图跨网络成立），所以删除也必须
     * **遍历全部墓碑文件**逐个改写 —— 只删当前服务器那份的话，旧文件里的同键墓碑
     * 会把"已恢复"的图再次滤掉，恢复等于没发生。
     *
     * @return true = 所有墓碑文件都清理成功（恢复意图已持久化）；false = 至少一个
     *         文件没写成（调用方必须取消本次恢复，否则重启后图片仍处于"已删除"）。
     */
    fun removeTombstones(context: Context, serverId: String, keys: Set<String>): Boolean =
        removeTombstonesFromFiles(tombstoneFiles(context), keys, serverId)

    /**
     * [removeTombstones] 的文件级实现（JVM 单测可钉住"坏文件不阻塞恢复"）。
     *
     * **不可解析的墓碑文件不构成失败**（L2）：读路径 [loadTombstonesFromFiles] 对
     * 坏文件是整份跳过的，它的键根本不参与 [isDeleted] 过滤 —— 所以"改不动它"并不会
     * 让恢复失效。旧实现把这种情况判为 false，导致只要目录里有一个坏墓碑文件，
     * 之后每次恢复都会被永久取消（死胡同）。这里只备份留证，不阻塞恢复。
     *
     * 只有"可读文件写回清理版失败"才返回 false —— 那种情况下键仍是生效的，
     * 恢复确实不会生效，调用方必须取消。
     */
    internal fun removeTombstonesFromFiles(
        files: List<File>,
        keys: Set<String>,
        serverId: String = ""
    ): Boolean {
        if (keys.isEmpty()) return true
        var allOk = true
        for (file in files) {
            val text = try {
                file.readText()
            } catch (e: Exception) {
                DebugLogger.e(TAG, "墓碑文件读取失败（读路径同样跳过，不阻塞恢复）: ${file.name}: ${e.message}")
                backupCorruptFile(file)
                continue
            }
            val array = try {
                JSONArray(text)
            } catch (e: Exception) {
                DebugLogger.e(TAG, "墓碑文件解析失败（读路径同样跳过，不阻塞恢复）: ${file.name}: ${e.message}")
                backupCorruptFile(file)
                continue
            }
            val kept = mutableListOf<String>()
            for (i in 0 until array.length()) {
                val key = array.optString(i)
                if (key.isNotBlank() && key !in keys) kept += key
            }
            // 写后读回：失败则回滚（键仍生效 → 调用方必须取消恢复）
            val ok = writeTextAtomicallyVerified(
                file = file,
                content = tombstoneJson(kept),
                label = "墓碑恢复清理",
                verify = { written -> isTombstoneDocumentConsistent(written, kept) }
            )
            if (!ok) {
                allOk = false
                DebugLogger.e(TAG, "墓碑恢复清理写入失败（已回滚）: ${file.name}")
            }
        }
        // serverId 只作日志口径：墓碑读取本来就是全文件合并的，删除同样全文件处理
        if (allOk) {
            DebugLogger.i(TAG, "Removed ${keys.size} tombstone(s) for server $serverId (restore)")
        } else {
            DebugLogger.e(TAG, "墓碑清理未全部成功（恢复可能未生效），server $serverId")
        }
        return allOk
    }

    fun loadTombstones(context: Context, serverId: String): Set<String> =
        loadTombstonesFromFiles(tombstoneFiles(context))

    /**
     * 从一组墓碑文件合并读取（JVM 单测用：坏文件行为可钉住）。
     *
     * 删除意图跨网络、跨条目的都成立，所以读取的是**原始集合**：既可能是条目键
     * （含 NUL），也可能是旧版本的裸 promptId，判定统一走 [isDeleted]。
     *
     * 解析失败的文件：记 error 级日志 + 备份 `.corrupt-*`（R7：此前只记 warn，
     * 下一次整份重写会静默丢掉里面的删除记录，导致已删图片复活且无任何证据）。
     */
    internal fun loadTombstonesFromFiles(files: List<File>): Set<String> {
        val result = mutableSetOf<String>()
        for (file in files) {
            try {
                val array = JSONArray(file.readText())
                for (i in 0 until array.length()) {
                    array.optString(i).takeIf { it.isNotBlank() }?.let { result += it }
                }
            } catch (e: Exception) {
                DebugLogger.e(TAG, "Failed to read tombstones from ${file.name}: ${e.message}")
                backupCorruptFile(file)
            }
        }
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
    fun removeFromIndex(context: Context, serverId: String, keys: Set<String>) =
        synchronized(indexWriteLock) {
            if (keys.isEmpty()) return@synchronized
            val existing = loadMetadata(context, serverId) ?: return@synchronized
            val kept = existing.filterNot { isDeleted(it, keys) }
            if (kept.size == existing.size) return@synchronized
            // 删到一条不剩是**合法终态**：索引仍然是一份合法空表（"拒绝写空"的闸门
            // 只拦"本来非空、却因为读失败/服务器为空被冲成空"的意外，不拦用户的删除）。
            // index 一律按位置重排：0..n-1，不会有重复或负数。
            val ok = writeMetadata(context, serverId, renumberByPosition(kept), allowEmpty = true)
            if (ok) {
                DebugLogger.i(TAG, "Removed ${existing.size - kept.size} record(s) from gallery index of server $serverId")
            } else {
                // 失败已回滚（旧索引仍在），索引记录会在下次刷新时随墓碑重新过滤，不会复活
                DebugLogger.e(
                    TAG,
                    "索引移除未能落盘（已回滚），本次 ${existing.size - kept.size} 条仍留在索引文件: $serverId"
                )
            }
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

    /**
     * 原子写 + **写后立即读回校验**（本次 P0 的核心护栏）。
     *
     * 只保证"写成功"不够：能写出去但读不回来的文件，就是"下次进图库必崩"的定时炸弹。
     * 流程：写前把旧内容留在内存 → 原子写 → 读回并跑 [verify] → 失败则回滚旧内容
     * （原本没有文件就删掉）→ 记 error 日志（含读回内容前 200 字符）。
     *
     * @return true = 写入且校验通过；false = 写入/校验失败（文件已回滚到写前状态）
     */
    internal fun writeTextAtomicallyVerified(
        file: File,
        content: String,
        verify: (String) -> Boolean,
        label: String
    ): Boolean {
        val existedBefore = file.exists()
        val previous: String? = if (existedBefore && file.length() <= MAX_BACKUP_BYTES) {
            try {
                file.readText()
            } catch (e: Exception) {
                DebugLogger.w(TAG, "$label 写前备份失败（将无法回滚）: ${e.message}")
                null
            }
        } else {
            null
        }

        val ok = try {
            writeTextAtomically(file, content)
            val readBack = file.readText()
            val verified = verify(readBack)
            if (!verified) {
                DebugLogger.e(
                    TAG,
                    "$label 写后读回校验失败，准备回滚 ${file.name}: ${previewOf(readBack)}"
                )
            }
            verified
        } catch (e: Exception) {
            DebugLogger.e(TAG, "$label 写入/校验异常，准备回滚 ${file.name}: ${e.message}")
            false
        }

        if (!ok) restorePrevious(file, previous, existedBefore, label)
        return ok
    }

    /** 校验失败后的回滚：有旧内容就写回，原本没文件就删掉半成品，都做不到就记 error。 */
    private fun restorePrevious(
        file: File,
        previous: String?,
        existedBefore: Boolean,
        label: String
    ) {
        try {
            when {
                previous != null -> {
                    writeTextAtomically(file, previous)
                    DebugLogger.w(TAG, "$label 已回滚到写前内容（${previous.length} 字符）")
                }
                !existedBefore -> {
                    file.delete()
                    DebugLogger.w(TAG, "$label 原本无此文件，已删除写入失败的半成品")
                }
                else -> DebugLogger.e(
                    TAG,
                    "$label 旧内容过大无法回滚，${file.name} 保持新内容，需人工核对"
                )
            }
        } catch (e: Exception) {
            DebugLogger.e(TAG, "$label 回滚失败: ${e.message}")
        }
    }

    private fun previewOf(text: String): String =
        text.take(LOG_PREVIEW_CHARS).replace("\r", "\\r").replace("\n", "\\n")

    /**
     * 索引写盘（文件级入口：JVM 单测直接钉住"绝不写空"与"写后读回校验"）。
     *
     * @param allowEmpty 空表是否合法。默认 **false** —— 目标文件原本非空时拒绝把索引
     *   写成空表。历史教训：服务器 `/history` 是内存态，重启即空；任何一次"解析失败"
     *   或"服务器说没有"被当成空列表写回，索引就被冲成 `[]`，用户看到图库全没了。
     *   只有"用户删到空"这种真正的终态才显式传 true（见 [removeFromIndex]）。
     * @return 是否真的写入且校验通过
     */
    internal fun writeMetadataToFile(
        file: File,
        items: List<GalleryItem>,
        allowEmpty: Boolean = false
    ): Boolean {
        if (items.isEmpty() && !allowEmpty && file.exists() && file.length() > EMPTY_JSON_ARRAY_LENGTH) {
            DebugLogger.w(
                TAG,
                "拒绝把非空索引写成空表: ${file.name}（原有 ${file.length()} 字节；" +
                    "如确需清空必须显式 allowEmpty）"
            )
            return false
        }

        val payload = serializeItems(items)
        val expectedKeys = items.map { itemIdentityKey(it.promptId, it.filename) }
        val ok = writeTextAtomicallyVerified(
            file = file,
            content = payload,
            label = "图库索引",
            // 读回必须能解析，且条目顺序/身份与写入完全一致（截断、丢条、串条都会被拦下）
            verify = { text ->
                val parsed = parseItemsText(text)
                parsed != null &&
                    parsed.map { itemIdentityKey(it.promptId, it.filename) } == expectedKeys
            }
        )
        if (ok) DebugLogger.i(TAG, "Gallery index saved for ${file.name}: ${items.size} items")
        return ok
    }

    private fun writeMetadata(
        context: Context,
        serverId: String,
        items: List<GalleryItem>,
        allowEmpty: Boolean = false
    ): Boolean {
        // R2：返回值必须往上传 —— 调用方据此决定"删除没落盘"要不要按失败处理。
        return writeMetadataToFile(getCacheFile(context, serverId), items, allowEmpty)
    }

    /** 索引序列化（纯函数；字段与 [parseItemsText] 一一对应）。 */
    internal fun serializeItems(items: List<GalleryItem>): String {
        val array = JSONArray()
        for (item in items) {
            array.put(JSONObject().apply {
                put("promptId", item.promptId)
                put("filename", item.filename)
                put("subfolder", item.subfolder)
                put("type", item.type)
                put("isVideo", item.isVideo)
                put("index", item.index)
                // 「源已不存在」标记：只有 true 才写。旧索引没有这个键 → 解析侧按 false，
                // 老用户图库照常读出；新版本写下的索引也能被旧版本忽略（未知键）。
                if (item.sourceMissing) put("sourceMissing", true)
                // 3D 产物位置：字段可选（旧索引没有 → optString 拿到空 → threeD=null，向后兼容）。
                // filename 为空的 3D 三元组等同"没有 3D"：与解析侧口径一致，避免往返丢字段。
                item.threeD?.takeIf { it.filename.isNotBlank() }?.let {
                    put("threeDFilename", it.filename)
                    put("threeDSubfolder", it.subfolder)
                    put("threeDType", it.type)
                }
            })
        }
        return array.toString()
    }

    /**
     * 读索引文件。**解析失败不删原文件**：先复制成 `<原名>.corrupt-yyyyMMdd-HHmmss`
     * 留证据（复制失败只记日志），再返回 null 让调用方走"保留旧列表"的分支。
     */
    internal fun parseItems(file: File): List<GalleryItem>? {
        val text = try {
            file.readText()
        } catch (e: Exception) {
            DebugLogger.e(TAG, "Failed to read gallery metadata from ${file.name}: ${e.message}")
            backupCorruptFile(file)
            return null
        }
        val items = parseItemsText(text)
        if (items == null) {
            DebugLogger.e(TAG, "Failed to parse gallery metadata from ${file.name}（原文件保留不删）")
            backupCorruptFile(file)
        } else {
            DebugLogger.i(TAG, "Gallery index loaded from ${file.name}: ${items.size} items")
        }
        return items
    }

    /** 纯文本解析（不碰文件、不产生备份）：写后读回校验用它，避免校验动作污染磁盘。 */
    internal fun parseItemsText(text: String): List<GalleryItem>? {
        return try {
            val jsonArray = JSONArray(text)
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
                    threeD = threeD,
                    // 旧索引没有该字段：optBoolean 缺省 false —— 兼容老用户的图库，
                    // 绝不会因为新增字段让老索引判成"损坏"而整份作废。
                    sourceMissing = jsonItem.optBoolean("sourceMissing", false)
                ))
            }
            items
        } catch (e: Exception) {
            DebugLogger.e(TAG, "Gallery index JSON parse failed: ${e.message}")
            null
        }
    }

    /**
     * 解析失败时把原文件复制成 `<原名>.corrupt-yyyyMMdd-HHmmss`。
     *
     * **不删除原文件**（用户/排障还需要它）；复制失败只记日志，不能反过来打断加载流程。
     * 同一秒内重复解析同一坏文件时同名覆盖，不会堆出无数份。
     */
    internal fun backupCorruptFile(file: File): File? {
        if (!file.exists()) return null
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val backup = File(file.parentFile, file.name + CORRUPT_SUFFIX_PREFIX + stamp)
        return try {
            file.copyTo(backup, overwrite = true)
            DebugLogger.w(TAG, "Corrupt gallery file backed up to ${backup.name}")
            backup
        } catch (e: Exception) {
            DebugLogger.w(TAG, "Corrupt gallery file backup failed for ${file.name}: ${e.message}")
            null
        }
    }

    private fun getCacheFile(context: Context, serverId: String): File =
        getCacheFile(context.filesDir, serverId)

    private fun getCacheFile(filesDir: File, serverId: String): File =
        File(filesDir, "$CACHE_PREFIX$serverId$CACHE_SUFFIX")

    private fun getTombstoneFile(context: Context, serverId: String): File =
        File(context.filesDir, "$TOMBSTONE_PREFIX$serverId$TOMBSTONE_SUFFIX")

    private fun cacheFiles(context: Context): List<File> = cacheFiles(context.filesDir)

    private fun cacheFiles(filesDir: File): List<File> =
        filesDir.listFiles { file ->
            file.name.startsWith(CACHE_PREFIX) && file.name.endsWith(CACHE_SUFFIX)
        }?.toList().orEmpty()

    private fun tombstoneFiles(context: Context): List<File> =
        context.filesDir.listFiles { file ->
            file.name.startsWith(TOMBSTONE_PREFIX) && file.name.endsWith(TOMBSTONE_SUFFIX)
        }?.toList().orEmpty()
}
