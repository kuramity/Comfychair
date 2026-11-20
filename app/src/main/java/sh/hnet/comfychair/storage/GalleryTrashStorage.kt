package sh.hnet.comfychair.storage

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import sh.hnet.comfychair.model.ThreeDResultHolder
import sh.hnet.comfychair.util.DebugLogger
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
    fun loadAll(context: Context): List<GalleryTrashEntry> {
        val file = File(context.filesDir, TRASH_FILE_NAME)
        if (!file.exists()) return emptyList()
        return try {
            parse(file.readText())
        } catch (e: Exception) {
            DebugLogger.e(TAG, "回收站读取失败: ${e.message}")
            emptyList()
        }
    }

    /** 全量落盘（原子写）。条目数量是个位数~几十级，全量重写最简单也最不容易漏状态。 */
    fun saveAll(context: Context, entries: List<GalleryTrashEntry>) {
        try {
            GalleryMetadataCache.writeTextAtomically(
                File(context.filesDir, TRASH_FILE_NAME),
                toJson(entries)
            )
        } catch (e: Exception) {
            DebugLogger.e(TAG, "回收站保存失败: ${e.message}")
        }
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
                entry.item.threeD?.let {
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
                if (filename.isBlank() || promptId.isBlank()) continue
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
