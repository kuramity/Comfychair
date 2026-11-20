package sh.hnet.comfychair.cache

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 钉住 5.1「视频无上限累积」的裁剪策略。
 *
 * 每次生成的视频都是一个新文件（`{serverId}_..._{promptId}.mp4`），磁盘侧原先没有任何删除点；
 * 规则：最近 KEEP_AT_LEAST 个一律保留，其余在总量超过上限时从最旧的开始删，
 * 且当前播放/展示的视频永不删除。
 */
class VideoTrimPolicyTest {

    private val mb = 1024L * 1024L

    private fun file(name: String, sizeMb: Long, modified: Long) =
        VideoFileInfo(name = name, sizeBytes = sizeMb * mb, lastModified = modified)

    @Test
    fun `总量未超上限时不删任何文件`() {
        val files = (1..5).map { file("v$it.mp4", 100, it.toLong()) }

        val plan = VideoTrimPolicy.plan(files, maxTotalBytes = 512 * mb, keepAtLeast = 3)

        assertTrue("500MB < 512MB，一个都不用删", plan.isEmpty())
    }

    @Test
    fun `超上限时从最旧的开始删直到回到上限内`() {
        // 6 × 100MB = 600MB，上限 512MB，最近 3 个受保护
        val files = (1..6).map { file("v$it.mp4", 100, it.toLong()) }

        val plan = VideoTrimPolicy.plan(files, maxTotalBytes = 512 * mb, keepAtLeast = 3)

        assertEquals("只需删掉最旧的 1 个（600 → 500 ≤ 512）", listOf("v1.mp4"), plan)
    }

    @Test
    fun `最近三个视频无论多大都保留`() {
        // 4 × 300MB = 1200MB，上限 512MB：候选只有最旧那个，删完仍超限也只能停手
        val files = (1..4).map { file("v$it.mp4", 300, it.toLong()) }

        val plan = VideoTrimPolicy.plan(files, maxTotalBytes = 512 * mb, keepAtLeast = 3)

        assertEquals(listOf("v1.mp4"), plan)
        assertFalse("最近一个不能进裁剪名单", plan.contains("v4.mp4"))
        assertFalse("倒数第二个不能进裁剪名单", plan.contains("v3.mp4"))
        assertFalse("倒数第三个不能进裁剪名单", plan.contains("v2.mp4"))
    }

    @Test
    fun `当前播放的视频即使最旧也不删`() {
        // 6 × 200MB = 1200MB，上限 512MB，最近 3 个受保护；
        // 最旧的 v1 正在展示 → 跳过它，继续删 v2、v3
        val files = (1..6).map { file("v$it.mp4", 200, it.toLong()) }

        val plan = VideoTrimPolicy.plan(
            files = files,
            maxTotalBytes = 512 * mb,
            keepAtLeast = 3,
            protectedNames = setOf("v1.mp4")
        )

        assertEquals("按最旧优先删，且跳过受保护的 v1", listOf("v2.mp4", "v3.mp4"), plan)
        assertFalse("正在播放的视频不能出现在删除名单里", plan.contains("v1.mp4"))
    }

    @Test
    fun `文件数不足 KEEP_AT_LEAST 时一个都不删`() {
        val files = (1..3).map { file("v$it.mp4", 400, it.toLong()) }

        val plan = VideoTrimPolicy.plan(files, maxTotalBytes = 512 * mb, keepAtLeast = 3)

        assertTrue(plan.isEmpty())
    }

    @Test
    fun `空列表直接返回空计划`() {
        assertTrue(VideoTrimPolicy.plan(emptyList(), 512 * mb, 3).isEmpty())
    }

    @Test
    fun `时间戳相同时按文件名倒序兜底，结果稳定`() {
        // lastModified 全部相同：不能让裁剪结果随 listFiles 顺序漂移
        val files = listOf(
            file("a.mp4", 300, 1000),
            file("b.mp4", 300, 1000),
            file("c.mp4", 300, 1000),
            file("d.mp4", 300, 1000)
        )

        val plan = VideoTrimPolicy.plan(files, maxTotalBytes = 512 * mb, keepAtLeast = 3)

        // 倒序后保留 d/c/b，只有 a 是候选
        assertEquals(listOf("a.mp4"), plan)
    }

    // ---- 启动期补裁（5.2）：没有任何新视频写入，也要能裁历史文件 ----

    private fun serverFile(serverId: String, promptId: String, sizeMb: Long, modified: Long) =
        VideoFileInfo(
            name = "${serverId}_ttv_$promptId.mp4",
            sizeBytes = sizeMb * mb,
            lastModified = modified
        )

    @Test
    fun `启动期补裁：此后再没生成新视频，历史视频同样会被裁`() {
        // 「已有大量历史视频、此后再不生成」的设备：磁盘上只有历史文件，没有任何新写入。
        // 启动路径（loadFromDisk / discoverVideoPromptIds）必须按同一策略裁一次，
        // 否则总量只会在 512MB 上限之上原地不动。
        val serverId = "srv-a"
        val files = (1..6).map { serverFile(serverId, "p$it", 200, it.toLong()) }

        val plan = VideoTrimPolicy.planForServer(
            files = files,
            serverId = serverId,
            protectedBaseNames = emptySet(),
            maxTotalBytes = 512 * mb,
            keepAtLeast = 3
        )

        // 6 × 200MB = 1200MB；最近 3 个（p4..p6）无条件保留，从最旧的 p1 开始删
        // 1200 → 1000 → 800 → 600，候选用尽仍未回到 512MB 以内
        assertEquals(
            listOf("srv-a_ttv_p1.mp4", "srv-a_ttv_p2.mp4", "srv-a_ttv_p3.mp4"),
            plan
        )
    }

    @Test
    fun `播放方登记的正在播放视频在启动裁剪里同样受保护`() {
        // D6：保护名单按 base 文件名（不带服务器前缀）登记，前缀由 planForServer 统一补。
        // 正在播放的 p1 最旧，但不能删；继续删 p2、p3。
        val serverId = "srv-b"
        val files = (1..6).map { serverFile(serverId, "p$it", 200, it.toLong()) }

        val plan = VideoTrimPolicy.planForServer(
            files = files,
            serverId = serverId,
            protectedBaseNames = setOf("ttv_p1.mp4"),
            maxTotalBytes = 512 * mb,
            keepAtLeast = 3
        )

        assertEquals(listOf("srv-b_ttv_p2.mp4", "srv-b_ttv_p3.mp4"), plan)
        assertFalse("正在播放的视频不能出现在删除名单里", plan.contains("srv-b_ttv_p1.mp4"))
    }

    @Test
    fun `别的服务器残留的播放登记不会保护本服务器的同名文件`() {
        // 登记的是 base 名，planForServer 按传入的 serverId 补前缀：
        // 归属于另一个服务器作用域的残留登记不会意外保住本服务器的文件。
        val files = listOf(
            VideoFileInfo("srv-y_ttv_p1.mp4", 400 * mb, 1),
            VideoFileInfo("srv-y_ttv_p2.mp4", 400 * mb, 2),
            VideoFileInfo("srv-y_ttv_p3.mp4", 400 * mb, 3),
            VideoFileInfo("srv-y_ttv_p4.mp4", 400 * mb, 4)
        )

        val plan = VideoTrimPolicy.planForServer(
            files = files,
            serverId = "srv-x",           // 与文件前缀不同：保护前缀补成 srv-x_...
            protectedBaseNames = setOf("ttv_p1.mp4"),
            maxTotalBytes = 512 * mb,
            keepAtLeast = 3
        )

        assertEquals("前缀对不上就不该受保护", listOf("srv-y_ttv_p1.mp4"), plan)
    }
}
