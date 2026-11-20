package sh.hnet.comfychair.cache

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * P2-3：MediaCache 播放临时文件的清理边界。
 *
 * 关键约束是**跨模块前缀隔离**：MediaStateHolder 用 `state_playback_`，这里用
 * `playback_`，两边都不能删到对方正在用的文件。
 */
class MediaCachePlaybackTempPolicyTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `清理只删本模块前缀并保留本次要写的那份`() {
        val dir = tmp.newFolder("cache")
        File(dir, "playback_1.mp4").writeText("keep")
        File(dir, "playback_2.mp4").writeText("old")
        File(dir, "playback_3.mp4").writeText("old")
        // 其它模块/其它用途的文件：一个都不能动
        File(dir, "state_playback_1.mp4").writeText("MediaStateHolder")
        File(dir, "temp_meta_1.mp4").writeText("抽帧临时文件")
        File(dir, "share_video.mp4").writeText("分享文件")
        File(dir, "playback_note.txt").writeText("非 mp4")

        val deleted = MediaCachePlaybackTempPolicy.cleanupDirectory(
            cacheDir = dir,
            keepName = "playback_1.mp4"
        )

        assertEquals(listOf("playback_2.mp4", "playback_3.mp4"), deleted.sorted())
        assertTrue(File(dir, "playback_1.mp4").exists())
        assertTrue(File(dir, "state_playback_1.mp4").exists())
        assertTrue(File(dir, "temp_meta_1.mp4").exists())
        assertTrue(File(dir, "share_video.mp4").exists())
        assertTrue(File(dir, "playback_note.txt").exists())
    }

    @Test
    fun `正在播放的文件不会被清理`() {
        val dir = tmp.newFolder("cache")
        File(dir, "playback_a.mp4").writeText("playing")
        File(dir, "playback_b.mp4").writeText("playing")
        File(dir, "playback_c.mp4").writeText("old")

        val deleted = MediaCachePlaybackTempPolicy.cleanupDirectory(
            cacheDir = dir,
            keepName = "playback_a.mp4",
            protectedNames = setOf("playback_b.mp4")
        )

        assertEquals(listOf("playback_c.mp4"), deleted)
        assertTrue(File(dir, "playback_a.mp4").exists())
        assertTrue("F7：正在播放的视频必须保留", File(dir, "playback_b.mp4").exists())
    }

    @Test
    fun `shouldDelete 不认 state_playback_ 与 temp_meta_ 前缀`() {
        assertTrue(MediaCachePlaybackTempPolicy.shouldDelete("playback_123.mp4", "playback_999.mp4"))
        assertFalse(MediaCachePlaybackTempPolicy.shouldDelete("state_playback_123.mp4", ""))
        assertFalse(MediaCachePlaybackTempPolicy.shouldDelete("temp_meta_123.mp4", ""))
        assertFalse(MediaCachePlaybackTempPolicy.shouldDelete("playback_123.mp4", "playback_123.mp4"))
        assertFalse(
            MediaCachePlaybackTempPolicy.shouldDelete(
                "playback_123.mp4", "", protectedNames = setOf("playback_123.mp4")
            )
        )
        assertFalse(MediaCachePlaybackTempPolicy.shouldDelete("playback_123.mp4.part", ""))
        assertFalse(MediaCachePlaybackTempPolicy.shouldDelete("playback_123.png", ""))
    }

    @Test
    fun `fileNameFor 使用本模块前缀并稳定`() {
        val name = MediaCachePlaybackTempPolicy.fileNameFor("prompt_file.mp4")
        assertTrue(name.startsWith(MediaCachePlaybackTempPolicy.PREFIX))
        assertTrue(name.endsWith(MediaCachePlaybackTempPolicy.SUFFIX))
        assertEquals(name, MediaCachePlaybackTempPolicy.fileNameFor("prompt_file.mp4"))
        assertFalse("不能和 MediaStateHolder 的命名撞上", name.startsWith("state_playback_"))
    }

    @Test
    fun `目录不存在时返回空清单`() {
        val missing = File(tmp.root, "not-exists")
        assertTrue(MediaCachePlaybackTempPolicy.cleanupDirectory(missing, "").isEmpty())
    }
}