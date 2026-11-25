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
 *    folder_type) 三元组：folder_type 由 [toBrowserFolderType] 解析，**发出值恒在**
 *    outputs/temp/input 三个根内（未知 type 兜底 outputs 并置告警标记，原值永不透传）；
 *    子目录先经 [normalizeFolderPath] 把 Windows 反斜杠统一成正斜杠，再由
 *    [isLocatorSafe] 把含 `..`、绝对前缀（含归一化后的盘符形态）、反斜杠残值的
 *    定位符挡在请求之前 —— 客户端拼不出、也发不出绝对路径。
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

/** folder_type 解析结果：[value] 恒为白名单值，[fellBackToOutputs] 提示调用方记日志。 */
data class BrowserFolderTypeResolution(
    /** 实际发给接口的 folder_type：只会是 outputs / temp / input 三者之一 */
    val value: String,
    /** 原始 type 为 null / 空白 / 白名单之外，已兜底到 outputs 根 —— 调用方须记告警并带原值 */
    val fellBackToOutputs: Boolean
)

/**
 * ComfyUI 历史里的 type → 插件接口的 folder_type（白名单映射 + 显式兜底）。
 *
 * 接口只认三个根（folder_type ∈ outputs/input/temp，服务端 get_parent_path 解析），
 * 本函数的**发出值**恒在这三根之内 —— 原值任何情况下都不透传（那等于把任意字符串
 * 送进服务端的路径拼接）。
 * null / 空白 / 白名单外的值兜底按 "outputs" 处理：图库只展示 outputs 目录的产出，
 * 这是最合理的默认；兜底不静默 —— [BrowserFolderTypeResolution.fellBackToOutputs]
 * 为 true，调用方据此带原始值记调试日志。
 */
fun toBrowserFolderType(type: String?): BrowserFolderTypeResolution =
    when (type?.trim()?.lowercase()) {
        "output", "outputs" -> BrowserFolderTypeResolution("outputs", fellBackToOutputs = false)
        "temp" -> BrowserFolderTypeResolution("temp", fellBackToOutputs = false)
        "input" -> BrowserFolderTypeResolution("input", fellBackToOutputs = false)
        else -> BrowserFolderTypeResolution("outputs", fellBackToOutputs = true)
    }

/**
 * folder_path 归一化：Windows 下的 ComfyUI 会在 /history 里返回反斜杠子目录
 * （`anima\hires`），原值会被 [isLocatorSafe] 判非法、删除请求永远发不出去。
 * 校验与发送**同一个归一化值**（调用侧唯一入口），杜绝"校验过却发原值"的分裂。
 *
 * 只做分隔符统一，不做任何安全裁决：`..` 段、绝对前缀归一化后依旧由
 * [isLocatorSafe] 拦截（含反斜杠形态的 Windows 绝对路径 —— 归一化出的盘符段
 * `C:` 同样被拒）。
 */
fun normalizeFolderPath(folderPath: String): String = folderPath.replace('\\', '/')

/**
 * 定位符守卫：filename / folder_path 必须是纯名字，不得携带任何路径语义。
 *
 * filename 允许子目录吗？不允许 —— 插件语义是 path.join(root, folder_path, filename)，
 * filename 里塞 `../` 或绝对路径就把"只能删输出目录"的边界打穿。folder_path 允许
 * 正常的多级子目录，但同样拒绝 `..` 段与绝对前缀 —— 包括 [normalizeFolderPath]
 * 之后呈现 `C:/x` 形态的 Windows 绝对路径（盘符段视同绝对前缀）。空 filename 直接拒。
 *
 * **本函数是最后一道安全闸，只收紧不放宽**：上游归一化解决的是分隔符形态问题，
 * 任何穿越/绝对语义到这里仍然一律 false。
 */
fun isLocatorSafe(filename: String, folderPath: String): Boolean {
    if (filename.isBlank()) return false
    if (filename.contains('/') || filename.contains('\\')) return false
    if (filename.contains("..")) return false
    if (folderPath.isBlank()) return true
    if (folderPath.startsWith("/")) return false
    if (folderPath.contains("\\")) return false
    val segments = folderPath.split('/')
    // "C:\Windows" 归一化成 "C:/Windows"：首段是盘符（单字母+冒号）即绝对路径，拒
    if (segments.any { it.matches(Regex("[A-Za-z]:")) }) return false
    // 任何一段是 .. 都拒（正常输出目录没有这种段）
    return segments.all { it != ".." }
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
