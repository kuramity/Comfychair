package sh.hnet.comfychair.cache

/**
 * filesDir 下「允许删 / 禁止碰」的显式白名单（v4.16.4 起）。
 *
 * 背景：v4.16.3 及之前「清空缓存」会连 `user_workflows`（用户工作流 + 提示词）一起删掉。
 * 这里把数据边界固化成纯 Kotlin 清单，让清理链路"先过清单再动手"：
 * 任何清理逻辑在删 filesDir 下的目录/文件前，必须先用本对象检查；
 * 未出现在两张清单里的名字一律**默认不删**（fail-closed），防止未来有人往清理链路里塞越界删除。
 *
 * 纯 Kotlin object、纯函数，不依赖 Context / Android API，可在 JVM 单测里直接跑。
 */
object MediaCacheCleanupPolicy {

    /** filesDir 下允许整体删除的媒体缓存目录名（图库统一档图片 + 视频首帧缩略图） */
    val deletableDataDirs: Set<String> = setOf(
        "gallery_store",
        "gallery_thumbs"
    )

    /** filesDir 下的业务数据目录，任何清理都不得触碰 */
    val protectedDataDirs: Set<String> = setOf(
        "user_workflows", // 用户上传/复制的工作流文件 + metadata（删了提示词会孤儿化）
        "model3d"         // 3D 生成结果（保守保留，可重下但不是纯图片缓存）
    )

    /** 前缀匹配的业务数据文件（图库索引/删除墓碑按 serverId 拼文件名，节点描述缓存同样） */
    private val PROTECTED_DATA_FILE_PREFIXES: Set<String> = setOf(
        "gallery_metadata_", // 图库本地持久索引（图库的底座，删了图库就空了）
        "gallery_deleted_",  // 图库删除墓碑（删了删掉的图会复活）
        "object_info_"       // /object_info 节点描述缓存
    )

    /** 精确名匹配的业务数据文件 */
    private val PROTECTED_DATA_FILE_NAMES: Set<String> = setOf(
        "model_intros.json",    // 模型简介缓存
        "lora_effects.json",    // LoRA 效果数据
        "tag_favorites.json",   // 标签收藏
        "login_background.jpg"  // 登录背景
    )

    /**
     * filesDir 下的业务数据文件：以 `_` 结尾的项按**前缀**匹配（文件名里拼着 serverId，
     * 只能按前缀认），其余项按**精确名**匹配。
     */
    val protectedDataFiles: Set<String> = PROTECTED_DATA_FILE_PREFIXES + PROTECTED_DATA_FILE_NAMES

    /** 目录名是否允许整体删除：只认白名单，未知名返回 false（默认不删） */
    fun isDeletableDataDir(name: String): Boolean = name in deletableDataDirs

    /** 目录名是否受保护：命中即跳过并记日志，不得删除 */
    fun isProtectedDataDir(name: String): Boolean = name in protectedDataDirs

    /** 文件名是否受保护：前缀项按前缀命中，其余按精确名命中 */
    fun isProtectedDataFile(name: String): Boolean =
        name in PROTECTED_DATA_FILE_NAMES || PROTECTED_DATA_FILE_PREFIXES.any { name.startsWith(it) }
}
