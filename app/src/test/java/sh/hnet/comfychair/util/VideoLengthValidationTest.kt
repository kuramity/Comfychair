package sh.hnet.comfychair.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * `ValidationUtils.validateVideoLength` 的行为钉子。
 *
 * 这个方法原先在两个视频 ViewModel 里各有一份逐字节相同的私有拷贝（文生视频 / 图生视频），
 * 合并后语义必须逐字不变：
 * - 空串 → `error_required`（注意：与 `validateLength` 不同，空串是错误而不是"未填写"）
 * - 非数字 → `error_invalid_number`
 * - 不在 1..129 → `error_length_range`
 * - 不满足 `(n - 1) % 4 == 0` → `error_length_step`
 *
 * 单测不提供 Context（JVM 测试没有资源），断言的正是无 Context 时的回退文案，
 * 这同时钉住了"先取本地化串、取不到再回退"的分支顺序。
 */
class VideoLengthValidationTest {

    @Test
    fun `空串是必填错误`() {
        assertEquals("Required", ValidationUtils.validateVideoLength("", null))
        assertEquals("Required", ValidationUtils.validateVideoLength(""))
    }

    @Test
    fun `0 超出下限是区间错误`() {
        assertEquals("Must be 1-129", ValidationUtils.validateVideoLength("0", null))
    }

    @Test
    fun `1 是合法起点`() {
        assertNull(ValidationUtils.validateVideoLength("1", null))
    }

    @Test
    fun `129 是合法上限`() {
        assertNull(ValidationUtils.validateVideoLength("129", null))
    }

    @Test
    fun `130 超出上限是区间错误`() {
        assertEquals("Must be 1-129", ValidationUtils.validateVideoLength("130", null))
    }

    @Test
    fun `非 4n+1 是步长错误`() {
        val expected = "Must be 1, 5, 9, 13... (steps of 4)"
        assertEquals(expected, ValidationUtils.validateVideoLength("2", null))
        assertEquals(expected, ValidationUtils.validateVideoLength("3", null))
        assertEquals(expected, ValidationUtils.validateVideoLength("4", null))
        assertEquals(expected, ValidationUtils.validateVideoLength("126", null))
    }

    @Test
    fun `4n+1 在区间内合法`() {
        listOf("5", "9", "13", "125", "129").forEach { value ->
            assertNull("期望 $value 合法", ValidationUtils.validateVideoLength(value, null))
        }
    }

    @Test
    fun `非数字是数字格式错误`() {
        assertEquals("Invalid number", ValidationUtils.validateVideoLength("abc", null))
        assertEquals("Invalid number", ValidationUtils.validateVideoLength("1.5", null))
        assertEquals("Invalid number", ValidationUtils.validateVideoLength(" ", null))
    }

    @Test
    fun `负数先落区间错误而不是步长错误`() {
        // -3：-3-1=-4 能被 4 整除，但区间检查在前，必须先报区间错误
        assertEquals("Must be 1-129", ValidationUtils.validateVideoLength("-3", null))
        assertNotNull(ValidationUtils.validateVideoLength("-3", null))
    }

    /**
     * 四个错误分支必须各自绑定正确的字符串资源。
     *
     * 上面的用例都传 null Context，只能验证"回退文案"；如果实现里把
     * `R.string.error_length_step` 换成 `R.string.error_length_range`，那些用例仍然全绿。
     * 因此按项目既有范式（TrashDeleteWiringTest / ViewerDismissWiringTest）直接读源码，
     * 把「分支条件 → 资源 id → 回退文案」三者的绑定与判定顺序钉死。
     */
    @Test
    fun `四个错误分支必须绑定各自的字符串资源与回退文案`() {
        val block = blockOf(
            source("src/main/java/sh/hnet/comfychair/util/ValidationUtils.kt"),
            "fun validateVideoLength(value: String, context: Context? = null): String? {"
        )
        val branches = listOf(
            Triple("value.isEmpty()", "R.string.error_required", "\"Required\""),
            Triple("num == null", "R.string.error_invalid_number", "\"Invalid number\""),
            Triple("num !in 1..129", "R.string.error_length_range", "\"Must be 1-129\""),
            Triple(
                "(num - 1) % 4 != 0",
                "R.string.error_length_step",
                "\"Must be 1, 5, 9, 13... (steps of 4)\""
            )
        )

        val starts = branches.map { (condition, _, _) ->
            val index = block.indexOf(condition)
            assertTrue("源码里找不到分支条件：$condition", index >= 0)
            index
        }
        // 判定顺序必须与抽取前一致（空串 → 非数字 → 区间 → 步长）
        assertEquals("分支顺序被改动", starts.sorted(), starts)

        branches.forEachIndexed { i, (condition, resId, fallback) ->
            val end = if (i + 1 < starts.size) starts[i + 1] else block.length
            val slice = block.substring(starts[i], end)
            assertTrue("分支「$condition」必须使用 $resId，实际片段：$slice", slice.contains(resId))
            assertTrue("分支「$condition」的回退文案不能被改动", slice.contains(fallback))
            // 不得串用其他分支的资源 id
            branches.filter { it.second != resId }.forEach { (_, otherId, _) ->
                assertFalse("分支「$condition」不得使用 $otherId", slice.contains(otherId))
            }
        }
    }

    /** 单测工作目录是模块根（app/），与 TrashDeleteWiringTest 同口径。 */
    private fun source(relative: String): String {
        val candidates = listOf(File(relative), File("app/$relative"))
        val file = candidates.firstOrNull { it.exists() }
            ?: error("找不到源文件 $relative，尝试过：${candidates.map { it.absolutePath }}")
        return file.readText()
    }

    /** 取函数正文：从签名行到与签名同缩进的单独一行 `}`（按缩进配对）。 */
    private fun blockOf(source: String, signature: String): String {
        val lines = source.lines()
        val startIdx = lines.indexOfFirst { it.trim() == signature }
        assertTrue("源码里找不到签名行：$signature", startIdx >= 0)
        val indent = lines[startIdx].takeWhile { it == ' ' }.count()
        val closer = " ".repeat(indent) + "}"
        val builder = StringBuilder()
        for (i in startIdx until lines.size) {
            builder.appendLine(lines[i])
            if (i > startIdx && lines[i] == closer) return builder.toString()
        }
        return builder.toString()
    }
}