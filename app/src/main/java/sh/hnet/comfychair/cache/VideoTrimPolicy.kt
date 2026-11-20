package sh.hnet.comfychair.cache

/**
 * 磁盘上一个视频文件的裁剪视图。
 *
 * 纯数据、零 Android 依赖：把 `File` 的三要素抽出来，裁剪策略才能在 JVM 上直接单测，
 * 不必拉起 Robolectric / 真机文件系统。
 */
data class VideoFileInfo(
    val name: String,
    val sizeBytes: Long,
    val lastModified: Long
)

/**
 * 单个服务器的视频文件裁剪策略（5.1：视频无上限累积）。
 *
 * 背景：每次生成的视频都写成一个新文件（`{serverId}_...{promptId}.mp4`），
 * 磁盘上没有任何删除点——内存里同类型只留最新一份，磁盘却越积越多，
 * 单个服务器攒到几个 GB 很常见，而旧视频几乎不会再被打开。
 *
 * 规则：
 * 1. 按修改时间倒序，最近的 [keepAtLeast] 个**一律保留**（用户最可能回看的窗口）；
 * 2. 其余文件在总量超过 [maxTotalBytes] 时从最旧的开始逐个删，直到总量不再超限；
 * 3. `protectedNames`（当前正在播放/展示的视频）永不删除，即使它落在候选里。
 *
 * 返回值只包含「要删的文件名」，删除动作交给调用方（纯策略不碰磁盘）。
 */
object VideoTrimPolicy {

    /**
     * 服务器维度的裁剪入口：把「受保护的 base 文件名」补上服务器前缀后交给 [plan]。
     *
     * 保护名单在调用方是按 `MediaKey.baseFilename`（不带服务器前缀）维护的 ——
     * 当前展示的 promptId、以及播放方登记的视频都只知道自己属于哪一类内容。
     * 前缀必须在这里统一补，否则换服务器后一份残留登记会保护到另一个服务器的同名文件。
     *
     * 启动期补裁（5.2）与写盘后裁剪（5.1）共用本函数：两条路径的策略与保护名单必须一致，
     * 否则「启动时不该删的」和「写完不该删的」会分叉。
     */
    fun planForServer(
        files: List<VideoFileInfo>,
        serverId: String,
        protectedBaseNames: Set<String>,
        maxTotalBytes: Long,
        keepAtLeast: Int
    ): List<String> {
        val prefix = "${serverId}_"
        val protectedNames = protectedBaseNames.mapTo(mutableSetOf()) { prefix + it }
        return plan(files, maxTotalBytes, keepAtLeast, protectedNames)
    }

    /**
     * 计算本次要删除的视频文件名。
     *
     * @param files 同一服务器下的全部视频文件（调用方先按 `{serverId}_` 前缀过滤）
     * @param maxTotalBytes 允许的总量上限
     * @param keepAtLeast 无条件保留的最近文件个数（按 [VideoFileInfo.lastModified] 倒序）
     * @param protectedNames 绝不删除的文件名（当前播放/展示中的视频）
     * @return 待删除的文件名，按「最旧优先」的顺序
     */
    fun plan(
        files: List<VideoFileInfo>,
        maxTotalBytes: Long,
        keepAtLeast: Int,
        protectedNames: Set<String> = emptySet()
    ): List<String> {
        if (files.isEmpty()) return emptyList()

        var total = files.sumOf { it.sizeBytes }
        if (total <= maxTotalBytes) return emptyList()

        // 时间戳相同时按文件名倒序兜底，保证裁剪结果确定（不依赖 listFiles 的顺序）
        val newestFirst = files.sortedWith(
            compareByDescending<VideoFileInfo> { it.lastModified }.thenByDescending { it.name }
        )
        // 去掉「受保护的最近 keepAtLeast 个」后，剩下的按**最旧优先**逐个当候选
        // （newestFirst 本身是降序，reversed 后才是从最旧开始）
        val candidates = newestFirst.drop(keepAtLeast.coerceAtLeast(0)).asReversed()

        val victims = mutableListOf<String>()
        for (info in candidates) {
            if (total <= maxTotalBytes) break
            if (info.name in protectedNames) continue
            victims += info.name
            total -= info.sizeBytes
        }
        return victims
    }
}
