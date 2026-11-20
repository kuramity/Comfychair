package sh.hnet.comfychair.repository

import org.junit.Assert.assertEquals
import org.junit.Test
import sh.hnet.comfychair.storage.GalleryTrashEntry
import sh.hnet.comfychair.viewmodel.GalleryItem

/**
 * G4（任务书 11.4）两阶段删除状态机的纯函数测试。
 *
 * 这里钉死的是破坏性操作的四条红线在"判定层"的实现：
 * 1. 探测失败 ≠ 不支持（UNKNOWN 可重试）；删除请求失败 ≠ 成功（RETRYABLE）；
 * 2. folder_type 只有 outputs/temp/input 三个白名单值能进请求体，绝对路径拼不进去；
 * 3. filename / folder_path 携带路径语义（分隔符、`..`、绝对前缀）即被守卫拒绝；
 * 4. 回收站去重幂等（同一文件删两次只留最早一条，恢复/清空不会被双份记录绊倒）。
 */
class GalleryTrashLogicTest {

    // ============================================================
    // classifyProbe：能力探测
    // ============================================================

    @Test
    fun `探测 - 2xx 与 3xx 判 SUPPORTED`() {
        assertEquals(ServerDeleteCapability.SUPPORTED, classifyProbe(200, false))
        assertEquals(ServerDeleteCapability.SUPPORTED, classifyProbe(204, false))
        assertEquals(ServerDeleteCapability.SUPPORTED, classifyProbe(304, false))
    }

    @Test
    fun `探测 - 404 与 405 判插件未安装（UNSUPPORTED）`() {
        // 派工单口径：404/405 都视为"服务器未安装该插件"
        assertEquals(ServerDeleteCapability.UNSUPPORTED, classifyProbe(404, false))
        assertEquals(ServerDeleteCapability.UNSUPPORTED, classifyProbe(405, false))
    }

    @Test
    fun `探测 - 连不上与不确定响应保持 UNKNOWN（可重试，不判死）`() {
        assertEquals(ServerDeleteCapability.UNKNOWN, classifyProbe(null, true))
        assertEquals(ServerDeleteCapability.UNKNOWN, classifyProbe(null, false))
        assertEquals(ServerDeleteCapability.UNKNOWN, classifyProbe(500, false))
        assertEquals(ServerDeleteCapability.UNKNOWN, classifyProbe(401, false))
        assertEquals(ServerDeleteCapability.UNKNOWN, classifyProbe(403, false))
    }

    @Test
    fun `探测 - 网络失败优先于状态码（半截响应不可信）`() {
        assertEquals(ServerDeleteCapability.UNKNOWN, classifyProbe(200, true))
    }

    // ============================================================
    // classifyDeleteAttempt：删除请求结局
    // ============================================================

    @Test
    fun `删除 - 2xx 才算 REMOVED（插件成功返回 201）`() {
        assertEquals(ServerDeleteAttempt.REMOVED, classifyDeleteAttempt(201, false))
        assertEquals(ServerDeleteAttempt.REMOVED, classifyDeleteAttempt(200, false))
        assertEquals(ServerDeleteAttempt.REMOVED, classifyDeleteAttempt(204, false))
    }

    @Test
    fun `删除 - 404 是文件已不在（幂等收敛）不是失败`() {
        // 能力已由探测确认在位，此时 404 来自插件的"未找到文件"：
        // 与删除成功同等处置（本地状态可以前进），但归类上区别于 REMOVED
        assertEquals(ServerDeleteAttempt.ALREADY_GONE, classifyDeleteAttempt(404, false))
    }

    @Test
    fun `删除 - 405 判能力过期降级，其余一律 RETRYABLE`() {
        assertEquals(ServerDeleteAttempt.UNSUPPORTED_FALLBACK, classifyDeleteAttempt(405, false))
        assertEquals(ServerDeleteAttempt.RETRYABLE, classifyDeleteAttempt(null, true))
        assertEquals(ServerDeleteAttempt.RETRYABLE, classifyDeleteAttempt(500, false))
        assertEquals(ServerDeleteAttempt.RETRYABLE, classifyDeleteAttempt(403, false))
        assertEquals(ServerDeleteAttempt.RETRYABLE, classifyDeleteAttempt(302, false))
    }

    // ============================================================
    // toBrowserFolderType：folder_type 白名单（硬约束 1 的核心）
    // ============================================================

    @Test
    fun `folder_type 只认三个根，其它值一律 null`() {
        assertEquals("outputs", toBrowserFolderType("output"))
        assertEquals("outputs", toBrowserFolderType("outputs"))
        assertEquals("outputs", toBrowserFolderType(""))     // 空按 ComfyUI 默认 output
        assertEquals("outputs", toBrowserFolderType(" Output "))
        assertEquals("temp", toBrowserFolderType("temp"))
        assertEquals("input", toBrowserFolderType("input"))
        // 白名单外：绝不透传给服务端的路径拼接
        assertEquals(null, toBrowserFolderType("../input"))
        assertEquals(null, toBrowserFolderType("models"))
        assertEquals(null, toBrowserFolderType("C:"))
    }

    // ============================================================
    // isLocatorSafe：定位符守卫（filename 不得携带任何路径语义）
    // ============================================================

    @Test
    fun `filename 含路径分隔或相对段一律拒绝`() {
        assertEquals(true, isLocatorSafe("ComfyUI_00001_.png", ""))
        assertEquals(false, isLocatorSafe("sub/a.png", ""))
        assertEquals(false, isLocatorSafe("a\\b.png", ""))
        assertEquals(false, isLocatorSafe("../secret.txt", ""))
        assertEquals(false, isLocatorSafe("", ""))
        assertEquals(false, isLocatorSafe("   ", ""))
    }

    @Test
    fun `folder_path 允许正常多级子目录，拒绝穿越与绝对前缀`() {
        assertEquals(true, isLocatorSafe("a.png", "batch/run1"))
        assertEquals(true, isLocatorSafe("a.png", ""))
        assertEquals(false, isLocatorSafe("a.png", "/etc"))
        assertEquals(false, isLocatorSafe("a.png", "batch/../../models"))
        assertEquals(false, isLocatorSafe("a.png", "batch\\run1"))
    }

    // ============================================================
    // planPurgeRoute：单条目的彻底删除路由
    // ============================================================

    @Test
    fun `路由矩阵 - 只有 SUPPORTED + 当前服务器 + 有客户端才走服务器删除`() {
        assertEquals(
            PurgeRoute.SERVER_DELETE,
            planPurgeRoute(ServerDeleteCapability.SUPPORTED, entryOnCurrentServer = true, clientAvailable = true)
        )
        assertEquals(
            PurgeRoute.LOCAL_ONLY,
            planPurgeRoute(ServerDeleteCapability.UNSUPPORTED, entryOnCurrentServer = true, clientAvailable = true)
        )
        // UNKNOWN（探测失败）：不动任何东西，保留可重试 —— 既不谎报也不误判不支持
        assertEquals(
            PurgeRoute.KEEP_FOR_RETRY,
            planPurgeRoute(ServerDeleteCapability.UNKNOWN, entryOnCurrentServer = true, clientAvailable = true)
        )
        // 离线（无客户端）：同 KEEP_FOR_RETRY
        assertEquals(
            PurgeRoute.KEEP_FOR_RETRY,
            planPurgeRoute(ServerDeleteCapability.SUPPORTED, entryOnCurrentServer = true, clientAvailable = false)
        )
        // 别家服务器的条目：先按 serverId 分流，能力判定根本到不了
        assertEquals(
            PurgeRoute.KEEP_OTHER_SERVER,
            planPurgeRoute(ServerDeleteCapability.SUPPORTED, entryOnCurrentServer = false, clientAvailable = true)
        )
        assertEquals(
            PurgeRoute.KEEP_OTHER_SERVER,
            planPurgeRoute(ServerDeleteCapability.UNSUPPORTED, entryOnCurrentServer = false, clientAvailable = false)
        )
    }

    // ============================================================
    // dedupeTrash：回收站去重幂等
    // ============================================================

    private fun entry(
        server: String,
        filename: String,
        deletedAt: Long,
        id: String = "$server-$filename"
    ) = GalleryTrashEntry(
        id = id,
        serverId = server,
        item = GalleryItem(
            promptId = "prompt-$filename",
            filename = filename,
            subfolder = "",
            type = "output",
            isVideo = false
        ),
        deletedAt = deletedAt
    )

    @Test
    fun `同一文件删两次只留最早一条（恢复与清空不被双份绊倒）`() {
        val existing = entry("srvA", "a.png", deletedAt = 1000L, id = "old")
        val incoming = entry("srvA", "a.png", deletedAt = 2000L, id = "new")
        val merged = dedupeTrash(listOf(existing), listOf(incoming)) { it.identityKey }
        assertEquals(1, merged.size)
        assertEquals("old", merged.first().id)
        assertEquals(1000L, merged.first().deletedAt)
    }

    @Test
    fun `同文件名不同服务器是两条独立记录（serverId 在身份里）`() {
        val merged = dedupeTrash(
            listOf(entry("srvA", "a.png", 1L)),
            listOf(entry("srvB", "a.png", 2L))
        ) { it.identityKey }
        assertEquals(2, merged.size)
    }

    @Test
    fun `同 prompt 不同文件各算一条（P1-19 条目粒度在回收站里同样成立）`() {
        val batch1 = GalleryTrashEntry(
            id = "1", serverId = "srvA",
            item = GalleryItem("p1", "ComfyUI_00001_.png", "", "output", false),
            deletedAt = 1L
        )
        val batch2 = GalleryTrashEntry(
            id = "2", serverId = "srvA",
            item = GalleryItem("p1", "ComfyUI_00002_.png", "", "output", false),
            deletedAt = 1L
        )
        val merged = dedupeTrash(listOf(batch1), listOf(batch2)) { it.identityKey }
        assertEquals(2, merged.size)
    }
}
