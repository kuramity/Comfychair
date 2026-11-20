package sh.hnet.comfychair.cache

import java.io.File

/**
 * [MediaCache] 内存态播放临时文件的清理判据（P2-3）。
 *
 * 背景：内存优先模式下 [MediaCache.getVideoUri] 每次播放都会在 `cacheDir` 根目录写一份
 * `playback_<hash>.mp4`，而修复前**没有任何删除点** —— 看得越多，cacheDir 里攒得越多，
 * 只能等系统回收。这里提供：
 * - [fileNameFor]：与写入端共用同一命名规则（单一事实来源）；
 * - [cleanupDirectory]：写新文件前清掉同前缀的旧文件，只保留本次要写的那份。
 *
 * ⚠️ 跨模块隔离（必须保持）：[MediaStateHolder] 的播放临时文件前缀是
 * `state_playback_`（见 MediaPlaybackTempPolicy）。本策略的前缀是 `playback_`，
 * 两者**不互为前缀**：`state_playback_xxx.mp4` 不以 `playback_` 开头，因此这里
 * 永远不会删到 MediaStateHolder 的文件；反过来它的策略也以完整前缀 `state_playback_`
 * 匹配，碰不到这里的文件。修改任一侧前缀前先确认这条对称关系仍然成立。
 *
 * 另：`temp_meta_*` 是抽帧用的临时文件，由 extractVideoData 的 finally 自删，不归这里管。
 */
internal object MediaCachePlaybackTempPolicy {

    /** 仅属于 [MediaCache] 的播放临时文件前缀（刻意与 `state_playback_` 错开）。 */
    const val PREFIX = "playback_"

    const val SUFFIX = ".mp4"

    /** `playback_<hash>.mp4`：命名规则与写入端历史版本保持一致，避免旧文件变成"认不出"的垃圾。 */
    fun fileNameFor(keyString: String): String = "$PREFIX${keyString.hashCode()}$SUFFIX"

    /** 是否是本模块产出的播放临时文件（前缀 + 后缀都要精确匹配）。 */
    fun isManagedName(name: String): Boolean =
        name.startsWith(PREFIX) && name.endsWith(SUFFIX)

    /**
     * 这个文件是否该删：属于本模块、不是本次要写的那份、也不在**正在播放**的保护集合里。
     *
     * [keepName] 必须传磁盘文件名（`playback_<hash>.mp4`），不要传 base 名。
     * [protectedNames] 同样是**磁盘文件名**集合（调用方用 [fileNameFor] 把正在播放的
     * keyString 翻译过来）：F7 的时序漏洞——视频 A 已 prepare、ExoPlayer 还没打开文件时
     * 为视频 B 生成 URI——靠它避免把 A 的临时文件删掉导致播放失败。
     */
    fun shouldDelete(
        name: String,
        keepName: String,
        protectedNames: Set<String> = emptySet()
    ): Boolean =
        isManagedName(name) && name != keepName && name !in protectedNames

    /**
     * 在 [cacheDir] 上执行一次清理，返回实际删掉的文件名。
     *
     * 生产路径传 `context.cacheDir`；单测传临时目录。
     * [protectedNames] 见 [shouldDelete]；显式"清缓存"时传空集合（全部清掉）。
     * 目录不存在或不可读时返回空清单，不抛异常。
     */
    fun cleanupDirectory(
        cacheDir: File,
        keepName: String,
        protectedNames: Set<String> = emptySet()
    ): List<String> {
        val deleted = mutableListOf<String>()
        val files = try {
            cacheDir.listFiles()
        } catch (_: Exception) {
            null
        } ?: return deleted
        for (file in files) {
            if (!file.isFile) continue
            if (!shouldDelete(file.name, keepName, protectedNames)) continue
            if (file.delete()) deleted += file.name
        }
        return deleted
    }
}