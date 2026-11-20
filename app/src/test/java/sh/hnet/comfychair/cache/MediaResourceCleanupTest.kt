package sh.hnet.comfychair.cache

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * 媒体缓存清理策略的边界测试：m8（内存态播放临时文件）与 m9（孤儿媒体文件）。
 *
 * 两个策略都抽成了不依赖 Android Context 的纯逻辑 + 目录操作，所以这里用
 * **临时目录** 跑真实的建/删文件流程，不碰设备的 filesDir / cacheDir。
 */
class MediaResourceCleanupTest {

    private lateinit var tempDir: File

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("comfychair-media-cleanup").toFile()
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    private fun touch(name: String): File = File(tempDir, name).apply { writeText("x") }

    // ============================================================
    // m9：孤儿媒体清理 —— 已知 serverId 白名单 + 已知文件名模式 双条件
    // ============================================================

    @Test
    fun `形状像 UUID 但不是已知 serverId 的非媒体文件不被删（m9 误删回归）`() {
        // 任务要求的回归用例：旧实现只看「下划线前 36 字符含 -」就删，
        // 这个文件会被直接删掉。它的 base 名 ttv_<id>.mp4 并不是本模块的媒体命名
        // （真实视频名是 last_generated_video_<promptId>.mp4），新策略必须保留它。
        val fakeServerId = "12345678-1234-1234-1234-123456789abc"
        assertEquals("构造的 serverId 必须是 36 字符", 36, fakeServerId.length)
        val name = "${fakeServerId}_ttv_prompt42.mp4"
        val file = touch(name)

        val deleted = MediaOrphanFilePolicy.cleanupDirectory(tempDir, validServerIds = setOf("srv-1"))

        assertTrue("这个文件不该被删：$name", file.exists())
        assertFalse("不该出现在删除清单里：$name", deleted.contains(name))
    }

    @Test
    fun `不在白名单的已知媒体文件被删，活服务器的同名文件保留`() {
        val deadServerId = "12345678-1234-1234-1234-123456789abc"
        val orphan = touch("${deadServerId}_tti_last_preview.png")
        val alive = touch("srv-1_tti_last_preview.png")

        val deleted = MediaOrphanFilePolicy.cleanupDirectory(tempDir, setOf("srv-1"))

        assertFalse("孤儿应被删", orphan.exists())
        assertTrue("活服务器的文件必须留", alive.exists())
        assertEquals(listOf("${deadServerId}_tti_last_preview.png"), deleted)
    }

    @Test
    fun `serverId 不是 UUID 形状的孤儿也能清掉（m9 漏清回归）`() {
        // 旧实现的 UUID 形状判断让这类孤儿永远留着
        val orphan = touch("old-server_tti_last_preview.png")

        MediaOrphanFilePolicy.cleanupDirectory(tempDir, setOf("srv-1"))

        assertFalse("非 UUID 形状的 serverId 孤儿也应被清", orphan.exists())
    }

    @Test
    fun `serverId 含下划线的活服务器文件不被误删`() {
        // serverId 自身可能含 `_`（自定义 ID），白名单按「完整前缀 + 下划线」精确比对
        val alive = touch("srv_a_tti_last_preview.png")

        MediaOrphanFilePolicy.cleanupDirectory(tempDir, setOf("srv_a"))

        assertTrue("含下划线的活 serverId 文件必须留", alive.exists())
    }

    @Test
    fun `编号参考图与两种视频命名都算已知媒体模式`() {
        val dead = "dead-server"
        val ref = touch("${dead}_itv_reference_3.png")
        val ttv = touch("${dead}_last_generated_video_abc123.mp4")
        val itv = touch("${dead}_image_to_video_last_generated_abc123.mp4")

        MediaOrphanFilePolicy.cleanupDirectory(tempDir, setOf("srv-1"))

        assertFalse("编号参考图应被删", ref.exists())
        assertFalse("文生视频文件应被删", ttv.exists())
        assertFalse("图生视频文件应被删", itv.exists())
    }

    @Test
    fun `未知形状的文件一律不删（fail-closed）`() {
        val unknown = listOf(
            "README.txt",
            "notes_tti.png",
            "dead-server_whatever.bin",
            "dead-server_itv_reference_abc.png",      // 编号不是整数
            "dead-server_last_generated_video_.mp4"   // promptId 为空
        ).map { touch(it) }

        MediaOrphanFilePolicy.cleanupDirectory(tempDir, setOf("srv-1"))

        unknown.forEach { assertTrue("不该删：${it.name}", it.exists()) }
    }

    @Test
    fun `没有 serverId 前缀的裸媒体名不删`() {
        val bare = touch("tti_last_preview.png")

        MediaOrphanFilePolicy.cleanupDirectory(tempDir, setOf("srv-1"))

        assertTrue("没有 serverId 前缀就无法归属，按 fail-closed 保留", bare.exists())
    }

    @Test
    fun `白名单 serverId 做前缀匹配时不会误伤更长的 serverId（保守保留）`() {
        // 白名单只有 srv，文件属于 srv_extra：按「srv_+下划线」前缀会判成活服务器。
        // 这是**刻意的保守行为** —— 宁可留一个不该留的文件，也不冒误删的风险。
        val sibling = touch("srv_extra_tti_last_preview.png")

        MediaOrphanFilePolicy.cleanupDirectory(tempDir, setOf("srv"))

        assertTrue("前缀命中的文件按 fail-closed 保留", sibling.exists())
    }

    // ============================================================
    // m8：内存态播放临时文件清理
    // ============================================================

    @Test
    fun `写新播放文件前清掉旧的，但保留本次与播放登记中的`() {
        // 生产端登记表存的是 **base 文件名**（MediaStateHolder.playingVideoBaseNames），
        // 这里必须按同样口径传入 —— 喂翻译好的磁盘名会掩盖命名空间错配（m8 复核 N2）
        val keepBase = "playing_target.mp4"
        val playingBase = "still_playing_a.mp4"
        val staleBase = "stale_b.mp4"
        val keep = MediaPlaybackTempPolicy.fileNameFor(keepBase)
        val playingA = MediaPlaybackTempPolicy.fileNameFor(playingBase)
        val stale = MediaPlaybackTempPolicy.fileNameFor(staleBase)
        assertEquals("三个不同 base 名不应撞 hash", 3, setOf(keep, playingA, stale).size)

        // 生产路径的清理扫 cacheDir 根目录，靠前缀把自己与 MediaCache 的文件区分开
        val keepFile = touch(keep)
        val playingFile = touch(playingA)
        val staleFile = touch(stale)
        // 其它用途的临时文件（MediaCache 的视频元数据 + MediaCache 的播放临时文件）不能被顺带清掉
        val metaTemp = touch("temp_meta_123_1.mp4")
        val mediaCacheTemp = touch("playback_${"gallery_video_key".hashCode()}.mp4")

        val deleted = MediaPlaybackTempPolicy.cleanupDirectory(
            cacheDir = tempDir,
            keepName = keep,
            playingBaseNames = setOf(playingBase)
        )

        assertTrue("本次要写的这份必须留", keepFile.exists())
        assertTrue("播放登记中的必须留（可能正被 ExoPlayer 读着）", playingFile.exists())
        assertFalse("过期的那份应被删", staleFile.exists())
        assertTrue("非本前缀的临时文件不能动", metaTemp.exists())
        assertTrue("MediaCache 的播放临时文件不能被本模块清理删掉（N3）", mediaCacheTemp.exists())
        assertEquals(listOf(stale), deleted)
    }

    @Test
    fun `播放登记表存 base 名，清理前必须翻译成临时文件名（N2 命名空间回归）`() {
        val base = "last_generated_video_abc.mp4"
        val translated = MediaPlaybackTempPolicy.playingTempNames(setOf(base))

        assertEquals(setOf(MediaPlaybackTempPolicy.fileNameFor(base)), translated)
        assertFalse("翻译结果不能等于 base 名本身（两者是不同命名空间）", translated.contains(base))
        assertTrue("空登记翻译成空集", MediaPlaybackTempPolicy.playingTempNames(emptySet()).isEmpty())
    }

    @Test
    fun `只传一个 base 名也能保住它的临时文件（N2 端到端回归）`() {
        val playingBase = "last_generated_video_abc.mp4"
        val playingFile = touch(MediaPlaybackTempPolicy.fileNameFor(playingBase))
        // 本次要写的是另一个视频
        val nextKeep = MediaPlaybackTempPolicy.fileNameFor("last_generated_video_next.mp4")

        MediaPlaybackTempPolicy.cleanupDirectory(tempDir, nextKeep, setOf(playingBase))

        assertTrue("登记表里的视频临时文件必须留", playingFile.exists())
    }

    @Test
    fun `播放临时文件换独立前缀，不再与 MediaCache 撞名（N3 隔离）`() {
        val base = "last_generated_video_abc.mp4"

        val name = MediaPlaybackTempPolicy.fileNameFor(base)
        assertEquals("state_playback_${base.hashCode()}.mp4", name)
        assertFalse(
            "不能再用裸 playback_ 前缀 —— MediaCache 的播放临时文件正是这个前缀（MediaCache.kt:1577）",
            name.startsWith("playback_")
        )
    }

    @Test
    fun `清理不碰 MediaCache 的 playback_ 临时文件（N3 跨模块删除回归）`() {
        // MediaCache 的真实写法：cacheDir/playback_<keyString hash>.mp4（MediaCache.kt:1577）。
        // 修复前本策略按「playback_ 前缀 + .mp4 后缀」扫描 cacheDir 根，会把这个文件一起删掉，
        // 而 MediaCache 的 videoUriCache 命中即返回、不校验文件是否还在 → 播放器拿到死链。
        val mediaCacheTemp = touch("playback_${"gallery_video_key".hashCode()}.mp4")
        val staleFile = touch(MediaPlaybackTempPolicy.fileNameFor("stale.mp4"))

        MediaPlaybackTempPolicy.cleanupDirectory(
            cacheDir = tempDir,
            keepName = MediaPlaybackTempPolicy.fileNameFor("next.mp4"),
            playingBaseNames = emptySet()
        )

        assertTrue("MediaCache 的播放临时文件不能被本模块清理删掉", mediaCacheTemp.exists())
        assertFalse("本模块自己的过期临时文件照常删", staleFile.exists())
    }

    @Test
    fun `目录不存在时清理不抛异常`() {
        val missing = File(tempDir, "not-created")

        val deleted = MediaPlaybackTempPolicy.cleanupDirectory(
            missing,
            "state_playback_1.mp4",
            emptySet()
        )

        assertTrue(deleted.isEmpty())
    }
}
