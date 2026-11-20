package sh.hnet.comfychair.cache

import java.io.File

/**
 * 磁盘缓存文件名的新旧兼容规则（F1）。
 *
 * 背景：P2-2 把缓存键从 `{promptId}_{filename}` 改成了
 * `v2_{serverId}_{type}_{promptId}_{filename}`，目录名却没变。如果读路径只认新名字，
 * 升级后既有全部磁盘缓存（cacheDir/gallery_cache、cacheDir/gallery_thumb、
 * filesDir/gallery_store、filesDir/gallery_thumbs）都会读不到：离线时缩略图/原图/
 * 视频首帧空白，联网后还得逐条重下；旧文件则长期占着 filesDir 配额。
 *
 * 这里集中两条规则：
 * 1. [legacyName]：旧版本文件名 `{promptId}_{filename}.{ext}`；
 * 2. [resolveReadable]：读取时优先新名字，未命中回退旧名字，命中后**尽力改名**
 *    成新名字（懒迁移）。改名失败时继续用旧路径读，不丢数据。
 *
 * 纯 JVM（只用 java.io.File），便于单测钉住命名与迁移行为。
 */
internal object MediaCacheFileNames {

    /** 旧版本的文件名：键里还没有 type/serverId 时的命名。 */
    fun legacyName(promptId: String, filename: String, ext: String): String =
        "${promptId}_${filename}.$ext"

    /**
     * 读路径的文件解析：
     * - 新名字存在 → 直接返回；
     * - 否则旧名字存在 → 尝试 rename 成新名字并返回新文件；
     * - rename 失败 → 重新检查新名字（并发的另一个读线程可能已经改名成功），
     *   存在就返回新文件；否则返回仍在原位的旧文件；
     * - 两者都不存在 → null。
     *
     * 只用于**读**路径，写入一律用新名字。
     */
    fun resolveReadable(target: File, legacyName: String): File? {
        if (target.exists()) return target
        val dir = target.parentFile ?: return null
        val legacy = File(dir, legacyName)
        if (!legacy.isFile) return null
        if (legacy.renameTo(target)) return target
        // R2-N2：两个读线程同时迁移时，胜者已把旧文件移走；败者的 renameTo 失败后
        // 不能再返回已不存在的 legacy 路径（会被当成 MISS），要先看 target 是否已就位。
        return pickAfterFailedRename(target, legacy)
    }

    /**
     * rename 失败后的选择（抽出来便于单测）：
     * 新名字已存在（并发的另一个线程迁移成功）→ 新文件；
     * 否则旧文件仍在 → 旧文件；两者都没有 → null。
     */
    internal fun pickAfterFailedRename(target: File, legacy: File): File? = when {
        target.exists() -> target
        legacy.isFile -> legacy
        else -> null
    }

    /** 媒体条目身份：与 GalleryMetadataCache.itemIdentityKey 同一口径（promptId + NUL + filename）。 */
    private const val IDENTITY_SEPARATOR = '\u0000'

    fun identityKey(promptId: String, filename: String): String =
        "$promptId$IDENTITY_SEPARATOR$filename"

    /**
     * 按 [identityKey] 去重缓存键（R2-N1）。
     *
     * 同一媒体条目在磁盘上可能同时存在：
     * - 旧名文件（serverId 为空、type 固定 output），例如 gallery_thumb 里从未被迁移的缩略图；
     * - 新名文件（v2_…，带 serverId/type），例如已被懒迁移或新写入的统一档。
     *
     * 两者 keyString 不同，若都返回，GalleryRepository 会把同一 (promptId, filename)
     * 生成两条 GalleryItem，而图库 LazyGrid 的 key 正是 `${promptId}_${filename}`，
     * 重复 key 会直接抛 IllegalArgumentException。这里按条目身份只保留一条：
     * **优先带 serverId 的新格式键**（类型信息准确），否则保留先遇到的。
     *
     * 注意：output 与 temp 同名同 prompt 也属于同一身份 —— 图库列表本来就用
     * (promptId, filename) 识别条目（见 GalleryMetadataCache.mergeItems），这里保持同一口径。
     */
    fun dedupeByIdentity(keys: List<MediaCacheKey>): List<MediaCacheKey> {
        val byIdentity = LinkedHashMap<String, MediaCacheKey>()
        for (key in keys) {
            val identity = identityKey(key.promptId, key.filename)
            val existing = byIdentity[identity]
            if (existing == null || (existing.serverId.isEmpty() && key.serverId.isNotEmpty())) {
                byIdentity[identity] = key
            }
        }
        return byIdentity.values.toList()
    }
}