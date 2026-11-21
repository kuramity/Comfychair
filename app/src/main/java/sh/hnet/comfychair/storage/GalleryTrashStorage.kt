package sh.hnet.comfychair.storage

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import sh.hnet.comfychair.model.ThreeDResultHolder
import sh.hnet.comfychair.util.DebugLogger
import sh.hnet.comfychair.util.Obfuscator
import sh.hnet.comfychair.viewmodel.GalleryItem
import java.io.File
import java.util.UUID

/**
 * 图库一级回收站的持久条目（G4，任务书 11.4）。
 *
 * 每条记录 = 被删项的**完整定位**：
 * - [item]：本地资源定位（promptId / filename / subfolder / type / isVideo / index）——
 *   缓存文件按它重建 MediaCacheKey 就能找到本地图；恢复回列表时连 index（原位置）
 *   一起带回，保证"列表与元数据一致"；
 * - [serverId]：所属服务器 —— 换服务器连接时其它条目原样留着，不误删别家的文件；
 * - [deletedAt]：删除时间戳（排序与展示用）；
 * - 两阶段状态：
 *   · 阶段一（[stage] = [TrashStage.IN_TRASH]）= 在回收站里，本地资源保留、可恢复；
 *   · 阶段二失败时 [pendingRetry] = true（[TrashStage.PENDING_RETRY]），条目**留在**
 *     回收站等重试，服务器删除成功后条目连同本地残留一起消失。
 */
data class GalleryTrashEntry(
    val id: String,
    val serverId: String,
    val item: GalleryItem,
    val deletedAt: Long,
    val pendingRetry: Boolean = false,
    val lastError: String? = null
) {
    /** 条目身份：与图库列表、墓碑同一口径（P1-19：条目粒度，不是 promptId） */
    val identityKey: String get() = "$serverId\u0000${item.promptId}\u0000${item.filename}"
}

/** 回收站条目的可观测阶段（两阶段删除状态机的"分别可观测、可区分"要求）。 */
enum class TrashStage {
    /** 阶段一：已移入回收站，本地资源保留，可恢复 */
    IN_TRASH,

    /** 阶段二在途失败：服务器删除失败待重试（条目仍在回收站，本地残留**未**清理） */
    PENDING_RETRY
}

/** [GalleryTrashEntry] 当前处于哪个阶段（纯派生，不落第二份状态字段，避免不一致）。 */
fun trashStageOf(entry: GalleryTrashEntry): TrashStage =
    if (entry.pendingRetry) TrashStage.PENDING_RETRY else TrashStage.IN_TRASH

/**
 * 回收站的磁盘载体：`filesDir/gallery_trash.json`（全服务器共用一份，条目自带 serverId）。
 *
 * 写入沿用 [GalleryMetadataCache.writeTextAtomically]（P1-20 的原子写：tmp → fsync →
 * 改名覆盖）—— 回收站记录是"恢复的唯一依据"，半截 JSON = 条目永久失联，绝不允许。
 *
 * **回收站默认不自动清空**（硬约束 4）：本类不提供任何按时间/容量的自动清理，
 * 条目只在用户显式"恢复 / 彻底删除 / 清空"时移除。
 */
object GalleryTrashStorage {
    private const val TAG = "GalleryTrashStorage"
    private const val TRASH_FILE_NAME = "gallery_trash.json"

    /** 全量读入；文件缺失或整份损坏 → 空表（宁可显示空回收站，也不猜内容）。 */
    fun loadAll(context: Context): List<GalleryTrashEntry> =
        loadAllFromFile(File(context.filesDir, TRASH_FILE_NAME))

    /**
     * 文件级读取（JVM 单测用临时文件走完整 save → load 往返）。
     *
     * 整份解析失败**不崩**：记 error 日志 + 把坏文件备份成 `.corrupt-*`（不删原文件），
     * 返回空表。单条损坏由 [parse] 自己跳过并记日志。
     */
    internal fun loadAllFromFile(file: File): List<GalleryTrashEntry> {
        if (!file.exists()) return emptyList()
        return try {
            val raw = file.readText()
            val parsed = parse(raw)
            if (parsed.size < countTopLevelItems(raw)) {
                // 坏记录被跳过：这次读取可能少几条（可恢复的仍会显示）。
                // 下次任何一次 saveAll 都会按内存里的干净条目整份重写，坏记录不会永远留着。
                DebugLogger.w(
                    TAG,
                    "回收站有 ${countTopLevelItems(raw) - parsed.size} 条记录解析失败被跳过（文件: ${file.name}）"
                )
            }
            parsed
        } catch (e: Exception) {
            DebugLogger.e(TAG, "回收站读取失败（整份损坏，已保留原文件）: ${e.message}")
            GalleryMetadataCache.backupCorruptFile(file)
            emptyList()
        }
    }

    /**
     * 全量落盘（原子写 + 写后读回校验）。
     *
     * @return 是否真的落盘并校验通过。false 时调用方必须当作"本次删除未持久化"
     *         处理（内存状态不能领先于磁盘），不能记成功日志。
     */
    fun saveAll(context: Context, entries: List<GalleryTrashEntry>): Boolean =
        saveAllToFile(File(context.filesDir, TRASH_FILE_NAME), entries)

    /**
     * 文件级落盘（JVM 单测直接钉住"写后读回校验 + 失败回滚"）。
     *
     * 回收站记录是"恢复的唯一依据"：写成功但读不回来的文件 = 所有条目永久失联。
     * 因此写完立刻用 [parse] 读回，条数/身份集合不一致（或解析抛异常）就回滚写前内容。
     *
     * @return true = 写入且校验通过
     */
    internal fun saveAllToFile(file: File, entries: List<GalleryTrashEntry>): Boolean {
        val payload = toJson(entries)
        val expectedIdentities = entries.map { it.identityKey }
        val ok = GalleryMetadataCache.writeTextAtomicallyVerified(
            file = file,
            content = payload,
            label = "回收站",
            verify = { text ->
                val parsed = try {
                    parse(text)
                } catch (e: Exception) {
                    null
                }
                parsed != null &&
                    parsed.size == entries.size &&
                    parsed.map { it.identityKey } == expectedIdentities
            }
        )
        if (!ok) {
            // 失败详情（含读回内容前 200 字符）由 writeTextAtomicallyVerified 记 error 日志；
            // 这里只补一条条数口径，避免把文件名原样重复写进日志。
            DebugLogger.e(TAG, "回收站保存失败已回滚（期望 ${entries.size} 条）")
        }
        return ok
    }

    /** 数一下 JSON 数组的顶层条目数（只用于"跳过几条"的日志口径，解析失败返回 0）。 */
    private fun countTopLevelItems(json: String): Int = try {
        JSONArray(json).length()
    } catch (e: Exception) {
        0
    }

    // ============================================================
    // JSON 编解码（internal 纯函数：不碰文件，JVM 单测直接钉往返与坏数据行为）
    // ============================================================

    internal fun toJson(entries: List<GalleryTrashEntry>): String {
        val array = JSONArray()
        for (entry in entries) {
            array.put(JSONObject().apply {
                put("id", entry.id)
                put("serverId", entry.serverId)
                put("deletedAt", entry.deletedAt)
                put("pendingRetry", entry.pendingRetry)
                entry.lastError?.let { put("lastError", it) }
                put("promptId", entry.item.promptId)
                put("filename", entry.item.filename)
                put("subfolder", entry.item.subfolder)
                put("type", entry.item.type)
                put("isVideo", entry.item.isVideo)
                put("index", entry.item.index)
                // 只有 filename 非空的 3D 三元组才写出：解析侧（optString + isNotBlank）
                // 会把空 filename 视为"没有 3D"，两侧口径必须一致，否则往返会悄悄变 null。
                entry.item.threeD?.takeIf { it.filename.isNotBlank() }?.let {
                    put("threeDFilename", it.filename)
                    put("threeDSubfolder", it.subfolder)
                    put("threeDType", it.type)
                }
            })
        }
        return array.toString()
    }

    /**
     * 解析整份回收站 JSON。单条损坏（缺 filename / 类型不对）跳过该条、保留其余 ——
     * 一条坏记录不该把整个回收站清空（那等于把"还能恢复"的图全判死）。
     */
    internal fun parse(json: String): List<GalleryTrashEntry> {
        val array = JSONArray(json)
        val result = mutableListOf<GalleryTrashEntry>()
        for (i in 0 until array.length()) {
            try {
                val o = array.optJSONObject(i) ?: continue
                val filename = o.optString("filename")
                val promptId = o.optString("promptId")
                // 只有 filename 是硬性必需（没有它任何一条都无法定位/恢复）。
                // promptId 为空时**保留**而不是丢：直接丢 = 这条图从回收站里蒸发、
                // 用户再也没机会恢复；保留下来至少还能显示、还能手动处理。
                if (filename.isBlank()) {
                    DebugLogger.w(TAG, "跳过缺 filename 的回收站记录（第 $i 条）")
                    continue
                }
                if (promptId.isBlank()) {
                    DebugLogger.w(
                        TAG,
                        "回收站记录 promptId 为空（第 $i 条，filename=${Obfuscator.filename(filename)}）：保留"
                    )
                }
                val threeD = o.optString("threeDFilename", "")
                    .takeIf { it.isNotBlank() }
                    ?.let {
                        ThreeDResultHolder.Result(
                            filename = it,
                            subfolder = o.optString("threeDSubfolder", ""),
                            type = o.optString("threeDType", "output")
                        )
                    }
                result += GalleryTrashEntry(
                    id = o.optString("id").takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString(),
                    serverId = o.optString("serverId"),
                    item = GalleryItem(
                        promptId = promptId,
                        filename = filename,
                        subfolder = o.optString("subfolder", ""),
                        type = o.optString("type", "output"),
                        isVideo = o.optBoolean("isVideo", false),
                        index = o.optInt("index", 0),
                        threeD = threeD
                    ),
                    deletedAt = o.optLong("deletedAt", 0L),
                    pendingRetry = o.optBoolean("pendingRetry", false),
                    lastError = o.optString("lastError", "").takeIf { it.isNotBlank() }
                )
            } catch (e: Exception) {
                DebugLogger.w(TAG, "跳过损坏的回收站记录（第 $i 条）: ${e.message}")
            }
        }
        return result
    }
}
