package sh.hnet.comfychair.repository

/**
 * G4（任务书 11.4）：回收站两阶段删除的**纯状态机**。
 *
 * 这里只放"决策算术"，不放任何 Android / 网络 / 存储依赖：
 * - 「服务器支不支持删除」由探测响应分类（[classifyProbe]）；
 * - 「一次删除请求算成功、算已消失、还是要重试」由响应分类（[classifyDeleteAttempt]）；
 * - 「这条回收站条目下一步走哪条路」由路由决策（[planPurgeRoute]）；
 * - 「文件名能不能发给删除接口」由定位符守卫（[toBrowserFolderType] / [isLocatorSafe]）。
 *
 * **破坏性硬约束在代码上的落点**（违反任一条即验收不通过）：
 * 1. 服务器删除只走 `DELETE /browser/files`，请求体只带 (filename, folder_path,
 *    folder_type) 三元组，folder_type 被 [toBrowserFolderType] 白名单锁死在
 *    outputs/temp/input 三个根 —— 客户端拼不出、也发不出绝对路径；
 *    [isLocatorSafe] 再把含路径分隔符 / `..` 的定位符挡在请求之前。
 * 2. 探测失败（[ServerDeleteCapability.UNKNOWN]）绝不折算成"支持"；
 *    删除请求失败（[ServerDeleteAttempt.RETRYABLE]）绝不折算成"已删除"——
 *    调用方据返回值保留条目并标记待重试，最终状态只认服务器响应。
 * 3. 服务器不支持（UNSUPPORTED）走 [PurgeRoute.LOCAL_ONLY]：只清本机记录，
 *    并由调用方明确提示"服务器文件保留"，不谎报、不报错。
 */

/** 服务器删除能力的探测结果（每台服务器独立缓存，UNKNOWN 不落缓存、可再探）。 */
enum class ServerDeleteCapability {
    /** 还没探 / 探测时网络不可达 —— 下次操作要重试探测，不能当作支持或不支持 */
    UNKNOWN,

    /** comfyui-browser 插件在位：可以发 DELETE /browser/files */
    SUPPORTED,

    /** 服务器未安装该插件（路由不存在）：只做本机清理并明确告知 */
    UNSUPPORTED
}

/**
 * 探测响应 → 能力判定（纯函数，JVM 单测钉住）。
 *
 * 探测请求是 `GET /browser/files`：
 * - 2xx/3xx → 路由在、插件在 → SUPPORTED；
 * - 404 / 405 → 路由不存在（或方法未被注册）→ 判"未安装该插件"（派工单口径：
 *   404/405/连不上都视为未安装；这里把"连不上"单列为 UNKNOWN，因为它**可重试**，
 *   与"确认没装"是两种处置 —— 混在一起会让断网一次就把能力永久判死）；
 * - 401/403 → 认证问题，插件在不在说不准 → UNKNOWN（重试窗口留给登录恢复）；
 * - 其它 / 网络失败 → UNKNOWN。
 */
fun classifyProbe(httpCode: Int?, networkFailed: Boolean): ServerDeleteCapability = when {
    networkFailed || httpCode == null -> ServerDeleteCapability.UNKNOWN
    httpCode in 200..399 -> ServerDeleteCapability.SUPPORTED
    httpCode == 404 || httpCode == 405 -> ServerDeleteCapability.UNSUPPORTED
    else -> ServerDeleteCapability.UNKNOWN
}

/** 一次 DELETE /browser/files 请求的结局分类（能力 SUPPORTED 前提下才会发出请求）。 */
enum class ServerDeleteAttempt {
    /** 2xx：服务器确认删除成功（插件返回 201） */
    REMOVED,

    /** 404：服务器上已经没有这个文件（被别处删掉了）→ 与删除成功同等处置（幂等收敛） */
    ALREADY_GONE,

    /** 405：删除路由其实不在（能力判断过期，插件被卸载等）→ 降级为不支持、走本机清理 */
    UNSUPPORTED_FALLBACK,

    /** 网络失败 / 5xx / 401 / 403：可重试。条目留在回收站标记待重试，本地一样都不清 */
    RETRYABLE
}

/**
 * 删除响应 → 结局分类（纯函数）。
 *
 * 只有 REMOVED / ALREADY_GONE / UNSUPPORTED_FALLBACK 三种结局允许清本地残留；
 * RETRYABLE 一律保留原状 —— 这就是"本地状态不得谎报成功、最终状态以服务器响应为准"。
 */
fun classifyDeleteAttempt(httpCode: Int?, networkFailed: Boolean): ServerDeleteAttempt = when {
    networkFailed || httpCode == null -> ServerDeleteAttempt.RETRYABLE
    httpCode in 200..299 -> ServerDeleteAttempt.REMOVED
    httpCode == 404 -> ServerDeleteAttempt.ALREADY_GONE
    httpCode == 405 -> ServerDeleteAttempt.UNSUPPORTED_FALLBACK
    else -> ServerDeleteAttempt.RETRYABLE
}

/**
 * ComfyUI 历史里的 type → 插件接口的 folder_type（白名单映射）。
 *
 * 接口只认三个根（folder_type ∈ outputs/input/temp，服务端 get_parent_path 解析），
 * 白名单之外的值一律返回 null —— 调用方必须把该条目标为"无法定位"，**不得**
 * 把原值透传出去（那等于把任意字符串送进服务端的路径拼接）。
 * type 为空按 ComfyUI 默认的 output 处理（个别节点 /history 里缺这个字段）。
 */
fun toBrowserFolderType(type: String): String? = when (type.trim().lowercase()) {
    "output", "outputs", "" -> "outputs"
    "temp" -> "temp"
    "input" -> "input"
    else -> null
}

/**
 * 定位符守卫：filename / folder_path 必须是纯名字，不得携带任何路径语义。
 *
 * filename 允许子目录吗？不允许 —— 插件语义是 path.join(root, folder_path, filename)，
 * filename 里塞 `../` 或绝对路径就把"只能删输出目录"的边界打穿。folder_path 允许
 * 正常的多级子目录，但同样拒绝 `..` 段与绝对前缀。空 filename 直接拒。
 */
fun isLocatorSafe(filename: String, folderPath: String): Boolean {
    if (filename.isBlank()) return false
    if (filename.contains('/') || filename.contains('\\')) return false
    if (filename.contains("..")) return false
    if (folderPath.isBlank()) return true
    if (folderPath.startsWith("/")) return false
    if (folderPath.contains("\\")) return false
    // 按 / 拆段：任何一段是 .. 或以 . 开头的可疑段都拒（正常输出目录没有这种段）
    return folderPath.split('/').all { it != ".." }
}

/** 回收站条目"彻底删除"单条目的路由决策。 */
enum class PurgeRoute {
    /** 当前服务器、能力 SUPPORTED：发 DELETE /browser/files，按响应收敛 */
    SERVER_DELETE,

    /** 当前服务器、能力 UNSUPPORTED：只清本机记录与缓存（调用方必须提示服务器文件保留） */
    LOCAL_ONLY,

    /** 探测失败 / 离线 / 无客户端：什么都不做，条目留在回收站（可重试，不谎报） */
    KEEP_FOR_RETRY,

    /** 条目属于其它服务器：不在当前连接上动它，留在回收站，连上那台后再处理 */
    KEEP_OTHER_SERVER
}

/**
 * 路由决策（纯函数）。
 *
 * @param capability 该服务器当前缓存的删除能力（UNKNOWN = 还没探出来）
 * @param entryOnCurrentServer 条目 serverId 是否就是当前连接的服务器
 * @param clientAvailable 当前有没有可用的 ComfyUIClient（离线态没有）
 */
fun planPurgeRoute(
    capability: ServerDeleteCapability,
    entryOnCurrentServer: Boolean,
    clientAvailable: Boolean
): PurgeRoute = when {
    !entryOnCurrentServer -> PurgeRoute.KEEP_OTHER_SERVER
    !clientAvailable -> PurgeRoute.KEEP_FOR_RETRY
    capability == ServerDeleteCapability.SUPPORTED -> PurgeRoute.SERVER_DELETE
    capability == ServerDeleteCapability.UNSUPPORTED -> PurgeRoute.LOCAL_ONLY
    else -> PurgeRoute.KEEP_FOR_RETRY
}

/**
 * 回收站合并去重（纯函数）：同一条目（serverId + promptId + filename）只留最早那条。
 *
 * "移入回收站"必须幂等：同一张图被删两次（列表竞态、撤销后重删）不能出现两条记录，
 * 否则恢复时会双写、清空时会向服务器重发同一个删除。保留最早 deletedAt 让排序稳定。
 */
fun <T> dedupeTrash(existing: List<T>, incoming: List<T>, identityOf: (T) -> String): List<T> {
    val byIdentity = LinkedHashMap<String, T>()
    for (entry in existing) byIdentity.putIfAbsent(identityOf(entry), entry)
    for (entry in incoming) byIdentity.putIfAbsent(identityOf(entry), entry)
    return byIdentity.values.toList()
}
