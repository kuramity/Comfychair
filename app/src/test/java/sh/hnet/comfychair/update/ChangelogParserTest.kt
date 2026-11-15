package sh.hnet.comfychair.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import java.io.File

/**
 * [ChangelogParser] 的单元测试（T2 更新日志入口）。
 *
 * 覆盖七类场景（全部纯 JVM，不依赖 Android 框架）：
 * 1. 正常两版本解析
 * 2. 版本头缺 code 段（versionCode 记 null、条目不丢）
 * 3. 没有条目的版本保留（items 为空）
 * 4. 空字符串 → 空列表不抛异常
 * 5. 只有垃圾行（无 `##`）→ 空列表
 * 6. `* ` 前缀条目也能解析
 * 7. 真实 assets 文件解析（文件缺失时跳过）
 */
class ChangelogParserTest {

    // ---------- 1. 正常两版本解析 ----------

    @Test
    fun `parses two well-formed versions`() {
        val markdown = """
            # ComfyChair 更新日志

            ## v4.14.0 | 2026-11-15 | code122
            - 条目一
            - 条目二

            ## v4.13.0 | 2026-11-14 | code121
            - 旧条目
        """.trimIndent()

        val entries = ChangelogParser.parse(markdown)
        assertEquals(2, entries.size)

        val first = entries[0]
        assertEquals("v4.14.0", first.version)
        assertEquals("2026-11-15", first.date)
        assertEquals(122, first.versionCode)
        assertEquals(listOf("条目一", "条目二"), first.items)

        val second = entries[1]
        assertEquals("v4.13.0", second.version)
        assertEquals(121, second.versionCode)
        assertEquals(1, second.items.size)
        // 顺序保持文件中的顺序（新的在前）
        assertTrue(entries.indexOfFirst { it.version == "v4.14.0" } < entries.indexOfFirst { it.version == "v4.13.0" })
    }

    // ---------- 2. 版本头缺 code 段 ----------

    @Test
    fun `header without code segment keeps entry with null versionCode`() {
        val markdown = """
            ## v4.14.0 | 2026-11-15
            - 无 code 段的版本头

            ## v4.13.0 | 2026-11-14 | code121
            - 正常版本头
        """.trimIndent()

        val entries = ChangelogParser.parse(markdown)
        assertEquals(2, entries.size)
        assertEquals("v4.14.0", entries[0].version)
        assertNull(entries[0].versionCode)
        assertEquals(listOf("无 code 段的版本头"), entries[0].items)
        assertEquals(121, entries[1].versionCode)
    }

    @Test
    fun `header with non-numeric code segment yields null versionCode`() {
        val markdown = "## v1.0.0 | 2026-01-01 | code\n- 条目仍在\n"
        val entries = ChangelogParser.parse(markdown)
        assertEquals(1, entries.size)
        assertNull(entries[0].versionCode)
        assertEquals(1, entries[0].items.size)
    }

    // ---------- 3. 没有条目的版本 ----------

    @Test
    fun `version without items is kept with empty items`() {
        val markdown = """
            ## v4.14.0 | 2026-11-15 | code122

            ## v4.13.0 | 2026-11-14 | code121
            - 只有旧版有内容
        """.trimIndent()

        val entries = ChangelogParser.parse(markdown)
        assertEquals(2, entries.size)
        assertTrue(entries[0].items.isEmpty())
        assertEquals(1, entries[1].items.size)
    }

    // ---------- 4. 空字符串 ----------

    @Test
    fun `empty string yields empty list without exception`() {
        assertEquals(emptyList<ChangelogEntry>(), ChangelogParser.parse(""))
        assertEquals(emptyList<ChangelogEntry>(), ChangelogParser.parse("   \n  "))
    }

    // ---------- 5. 只有垃圾行 ----------

    @Test
    fun `garbage-only input yields empty list`() {
        val garbage = """
            这不是日志
            ## 也是垃圾
            - 孤立的条目
            #### 更多的井号
        """.trimIndent()
        assertEquals(emptyList<ChangelogEntry>(), ChangelogParser.parse(garbage))
    }

    // ---------- 6. `* ` 前缀条目 ----------

    @Test
    fun `star-prefixed items are parsed`() {
        val markdown = """
            ## v4.15.0 | 2026-11-15 | code123
            * 星号条目一
            *   星号条目二（多空格）
            - 连字符条目
        """.trimIndent()

        val entries = ChangelogParser.parse(markdown)
        assertEquals(1, entries.size)
        assertEquals(listOf("星号条目一", "星号条目二（多空格）", "连字符条目"), entries[0].items)
    }

    // ---------- 7. 真实 assets 文件 ----------

    @Test
    fun `parses the bundled assets changelog`() {
        // 单测工作目录是 app/ 模块目录；文件缺失（如干净 clone 后未同步）时跳过而非失败
        val asset = File("src/main/assets/CHANGELOG.md")
        Assume.assumeTrue(asset.exists())

        val entries = ChangelogParser.parse(asset.readText())
        assertTrue("内置日志至少要收录 6 个版本，实际 ${entries.size}", entries.size >= 6)
        assertTrue(
            "第一项的版本号应以 v 开头，实际 ${entries.firstOrNull()?.version}",
            (entries.firstOrNull()?.version ?: "").startsWith("v")
        )
    }
}
