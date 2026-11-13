package sh.hnet.comfychair.cache

/**
 * 一次缓存模式切换要清理的缓存层。
 *
 * @param memory 内存层（bitmap / video / uri / 尺寸 / 预取队列）
 * @param disk 磁盘层（cacheDir/gallery_cache）
 */
data class CacheLayers(
    val memory: Boolean,
    val disk: Boolean
)

/**
 * 「内存优先 ↔ 磁盘优先」切换时该清哪一层的判定。
 *
 * 纯逻辑、零 Android 依赖，可以在 JVM 上直接钉住——这条规则一旦退回旧行为，
 * 表现是"开了内存优先之后离线什么都读不到"，很难从界面上看出来。
 *
 * 语义分离（两层不要混为一谈）：
 * - **内存层**受「内存优先」开关控制：它是画廊的内存缓存策略；
 * - **磁盘层**（`cacheDir/gallery_cache`）是**离线读取唯一的数据源**，与内存优先无关。
 *
 * 所以切到内存优先时**不能**删磁盘层：删了，离线态就没有任何可读的数据了
 * （这正是旧实现 `deleteRecursively()` 造成的问题）。
 */
object CacheModePolicy {

    /**
     * 计算切到目标模式时要清理的缓存层。
     *
     * @param enteringMemoryFirst true = 正在切到内存优先；false = 正在切到磁盘优先
     */
    fun layersToClear(enteringMemoryFirst: Boolean): CacheLayers = CacheLayers(
        // 切到磁盘优先：内存里那份已是冗余副本（磁盘才是后续读取来源），清掉省内存。
        // 切到内存优先：保留内存层（不浪费，回切时还能直接命中）。
        memory = !enteringMemoryFirst,
        // 任何模式切换都不动磁盘层：它归离线读取用。
        disk = false
    )
}
