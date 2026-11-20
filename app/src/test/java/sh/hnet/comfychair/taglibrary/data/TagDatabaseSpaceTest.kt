package sh.hnet.comfychair.taglibrary.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P2-21 的纯逻辑钉子：解压空间预检的算法。
 *
 * 只测算式本身（Long 进、Boolean 出），不碰 StatFs / 文件系统 ——
 * 真正的 StatFs 调用在 Android 运行时才可用，JVM 单测里是桩。
 */
class TagDatabaseSpaceTest {

    private val oneMb = 1024L * 1024L

    @Test
    fun `所需空间是目标大小的二点二倍`() {
        val expected = 100L * oneMb
        assertEquals((expected * 2.2).toLong(), TagDatabase.requiredSpaceBytes(expected))
    }

    @Test
    fun `默认按解压后约103MB计算`() {
        assertEquals(103L * oneMb, TagDatabase.EXPECTED_DB_BYTES)
        val required = TagDatabase.requiredSpaceBytes()
        assertTrue("预检阈值必须大于目标大小（解压期间 .tmp 与旧库并存）", required > 103L * oneMb)
        assertTrue("2.2 倍是上限口径，不应再翻倍", required < 103L * oneMb * 3)
    }

    @Test
    fun `可用空间恰好等于需求算够`() {
        assertTrue(TagDatabase.hasEnoughSpace(availableBytes = 100L * oneMb, requiredBytes = 100L * oneMb))
    }

    @Test
    fun `可用空间多一字节算够`() {
        assertTrue(TagDatabase.hasEnoughSpace(availableBytes = 101L, requiredBytes = 100L))
    }

    @Test
    fun `可用空间差一字节算不够`() {
        assertFalse(TagDatabase.hasEnoughSpace(availableBytes = 99L, requiredBytes = 100L))
    }
}