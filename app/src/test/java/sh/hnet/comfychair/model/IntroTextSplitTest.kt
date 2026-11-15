package sh.hnet.comfychair.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * 超长作者原文切分（[splitIntroText]）的测试。
 *
 * 核心硬约束：**所有片段按顺序拼接必须与原文完全相等**——不丢字符、不丢换行、
 * 不改任何标点（"完整不截断"是 UI 呈现的既定约定）。
 *
 * 用数据包里真实那条 51864 字符的原文验证（从 model_intros.json 的
 * `intro_original` 字段读取）；文件不在（换机/离线）时跳过而不是误报失败。
 */
class IntroTextSplitTest {

    /** 数据包副本（只读，不修改）。测试默认工作目录是模块目录（app/），用绝对路径最稳。 */
    private val packFile = File(
        "E:\\Zcode\\ZcodeProject\\output\\comfychair-intro-pack\\model_intros.json"
    )

    /** 取数据包里最长的那条原文（krea2-moody-mix-premium，51864 字符）。 */
    private fun loadLongestOriginal(): String? {
        if (!packFile.exists()) return null
        return try {
            val root = org.json.JSONObject(packFile.readText())
            val models = root.getJSONObject("models")
            val target = models.optJSONObject("krea2-moody-mix-premium_int8_convrot.safetensors")
                ?: return null
            val original = target.optString("intro_original", "")
            // 长度下限防呆：数据换版后这条不再是超长条目时，别拿短文本冒充超长用例
            original.takeIf { it.length > 10000 }
        } catch (_: Exception) {
            null
        }
    }

    @Test
    fun `真实51864字符原文_切分后拼接与原文完全相等`() {
        val original = loadLongestOriginal()
        assumeTrue("数据包文件不可用时跳过（换机/离线环境）", original != null)
        val chunks = splitIntroText(original!!)
        // 硬约束：拼接恒等（不丢字符、不丢换行、不改标点）
        assertEquals(original, chunks.joinToString(""))
        // 每个片段都不超过单片段上限（段边界优先，超长单段才定长切）
        chunks.forEach { chunk ->
            assertTrue("片段超长：${chunk.length} 字符", chunk.length <= 1200)
        }
        // 真的切成了多段（LazyColumn 逐段渲染才有意义）
        assertTrue("应切成多个片段，实际 ${chunks.size}", chunks.size > 1)
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
        // 5 段各 300 字符（总 1504），单段都不超限，但总长超过 1200 → 按段边界切
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
        // 没有任何换行的 3000 字符单段 → 1200 + 1200 + 600
        val text = "乙".repeat(3000)
        val chunks = splitIntroText(text)
        assertEquals(text, chunks.joinToString(""))
        assertEquals(3, chunks.size)
        assertEquals(1200, chunks[0].length)
        assertEquals(1200, chunks[1].length)
        assertEquals(600, chunks[2].length)
    }

    @Test
    fun `多段超限文本_拼接恒等且不丢换行`() {
        // 7 个「500 字符 + 换行」单元（总 3507），刀口全部落在段边界
        val text = ("段".repeat(500) + "\n").repeat(7)
        val chunks = splitIntroText(text)
        assertEquals(text, chunks.joinToString(""))
        // 换行一个不丢（7 个换行分散在 4 个片段里）
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
