package sh.hnet.comfychair.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 超长作者原文切分（[splitIntroText]）的测试。
 *
 * 核心硬约束：**所有片段按顺序拼接必须与原文完全相等**——不丢字符、不丢换行、
 * 不改任何标点（"完整不截断"是 UI 呈现的既定约定）。
 *
 * 用构造的超长文本（多段 + 超长单段混合）覆盖同一批约束，不依赖任何外部数据文件。
 */
class IntroTextSplitTest {

    @Test
    fun `超长混合文本_切分后拼接与原文完全相等`() {
        // 构造一段 5 万字符的混合原文：多段正文 + 一个 3000 字符的无换行长段
        val paragraphs = (1..50).joinToString("\n") { "第 ${it} 段：" + "文".repeat(900) }
        val original = paragraphs + "\n" + "长".repeat(3000)

        val chunks = splitIntroText(original)

        // 硬约束：拼接恒等（不丢字符、不丢换行、不改标点）
        assertEquals(original, chunks.joinToString(""))
        // 每个片段都不超过单片段上限（段边界优先，超长单段才定长切）。
        // 320 = 现默认值：详情页原文卡 300dp 高、行高 21sp，约 13 行 ≈ 260 字可见，
        // 片段只留一点超量（旧默认 1200 时单段约 4 倍可见量，是滑动卡顿候选主因）。
        chunks.forEach { chunk ->
            assertTrue("片段超长：${chunk.length} 字符", chunk.length <= 320)
        }
        // 真的切成了多段（LazyColumn 逐段渲染才有意义）
        assertTrue("应切成多个片段，实际 ${chunks.size}", chunks.size > 1)
        // 换行一个不丢
        assertEquals(
            original.count { it == '\n' },
            chunks.sumOf { chunk -> chunk.count { it == '\n' } }
        )
    }

    @Test
    fun `短文本整体一个片段`() {
        assertEquals(listOf("短原文"), splitIntroText("短原文"))
    }

    @Test
    fun `空串返回单个空片段_拼接仍为空`() {
        // 调用方无须为空串分支（拼接回来还是原文）
        assertEquals(listOf(""), splitIntroText(""))
    }

    @Test
    fun `多段文本优先在段边界切分_换行归前一段`() {
        // 5 段各 300 字符（总 1504），单段含换行 301 ≤ 320 都不超限，但总长超过默认值 320 → 按段边界切
        val para = "甲".repeat(300)
        val text = listOf(para, para, para, para, para).joinToString("\n")
        val chunks = splitIntroText(text)
        assertEquals(text, chunks.joinToString(""))
        assertTrue(chunks.size > 1)
        // 切口落在换行之后：前一片段以 \n 结尾（换行符不丢）
        assertTrue(chunks.first().endsWith("\n"))
    }

    @Test
    fun `超长单段按定长硬切`() {
        // 没有任何换行的 3000 字符单段，按默认 320 定长硬切：9 × 320 + 120 = 3000，共 10 段
        val text = "乙".repeat(3000)
        val chunks = splitIntroText(text)
        assertEquals(text, chunks.joinToString(""))
        assertEquals(10, chunks.size)
        assertEquals(320, chunks[0].length)
        assertEquals(320, chunks[1].length)
        assertEquals(120, chunks[9].length)
    }

    @Test
    fun `多段超限文本_拼接恒等且不丢换行`() {
        // 7 个「100 字符 + 换行」单元（总 707）。单元长 101，默认 320 装得下 3 个单元
        //（3×101=303 ≤ 320 < 4×101）但装不下 4 个——刀口仍全部落在段边界，一个单元被
        // 硬切到片段中间的情况不该出现。旧默认 1200 时这里用 501 字符单元演示同样的
        // 「一段片段打包多个单元」；标定改为 320 后单元必须缩到 320 以内，否则超长单元
        // 会触发段内硬切，片尾不再是换行（那是下一个用例「定长硬切」管的事）。
        val text = ("段".repeat(100) + "\n").repeat(7)
        val chunks = splitIntroText(text)
        assertEquals(text, chunks.joinToString(""))
        // 换行一个不丢（7 个换行分散在各片段里）
        assertEquals(7, chunks.sumOf { chunk -> chunk.count { it == '\n' } })
        // 刀口都落在段边界：每个片段都以换行结尾（换行归属前一段）
        assertEquals(chunks.size, chunks.count { it.endsWith("\n") })
    }

    @Test
    fun `自定义maxCharsPerChunk生效`() {
        val text = "丙".repeat(50)
        val chunks = splitIntroText(text, maxCharsPerChunk = 10)
        assertEquals(text, chunks.joinToString(""))
        assertEquals(5, chunks.size)
        chunks.forEach { assertTrue(it.length <= 10) }
    }

    // ---------------- 入口钳制：maxCharsPerChunk <= 0 不死循环 ----------------

    @Test
    fun `maxCharsPerChunk为0不死循环且拼接恒等`() {
        // 防御性加固：0 在旧实现里会死循环；钳到 1 后退化为逐字符切分，必须正常返回
        val chunks = splitIntroText("abc", 0)
        assertTrue("应返回非空列表", chunks.isNotEmpty())
        assertEquals("abc", chunks.joinToString(""))
    }

    @Test
    fun `maxCharsPerChunk为负数不死循环且拼接恒等`() {
        val chunks = splitIntroText("abc", -5)
        assertTrue("应返回非空列表", chunks.isNotEmpty())
        assertEquals("abc", chunks.joinToString(""))
    }

    @Test
    fun `钳到1时逐字符切分拼接仍恒等`() {
        // 钳制后的实际行为：每片段 1 字符，拼接回来还是原文
        val chunks = splitIntroText("abc", 0)
        assertEquals(listOf("a", "b", "c"), chunks)
    }
}
