package sh.hnet.comfychair.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * R5：重试上限降到 2，失败即停，回退开关也必须有上限（v4.18.0）。
 *
 * 用户实测：「通知显示已重试 5 次仍失败，烧掉大量移动流量」。每轮重试都要重新发一次
 * Range 请求，源站不支持 Range 时还要从 0 重下 —— 每多一轮就是几十到上百 MB。
 *
 * 行为断言在 [DownloadPolicyTest]（`shouldRetryAutomatically(2) == false` 等）；
 * 这里钉住「接线」层面不会偷偷绕过上限：
 * 1. Worker 里每一处 `Result.retry()` 都必须先过重试判定；
 * 2. 回退分支必须走 `shouldRetryLegacy`（不允许无上限分支）；
 * 3. 达到上限的通知文案明确告诉用户下一步；
 * 4. 退避基数 ≥ 30 秒且为指数退避。
 */
class UpdateRetryCapWiringTest {

    private fun readSource(relative: String): String {
        val candidates = listOf(File(relative), File("app/$relative"))
        val file = candidates.firstOrNull { it.exists() }
            ?: error("找不到源文件 $relative，尝试过：${candidates.map { it.absolutePath }}")
        return file.readText()
    }

    /** 去掉 Kotlin 注释：KDoc 里会正面提到 `Result.retry()`，不能算成代码里的调用 */
    private fun codeOnly(source: String): String =
        source.replace(Regex("/\\*[\\s\\S]*?\\*/"), "")
            .replace(Regex("//[^\\n]*"), "")

    private val workerPath = "src/main/java/sh/hnet/comfychair/update/UpdateDownloadWorker.kt"
    private val repoPath = "src/main/java/sh/hnet/comfychair/update/UpdateRepository.kt"

    @Test
    fun `Worker 里只有两处 retry 且都在上限判定之后`() {
        val code = codeOnly(readSource(workerPath))
        val retryCount = Regex("Result\\.retry\\(\\)").findAll(code).count()
        assertEquals(
            "Worker 只允许『分型分支』与『回退分支』两处 Result.retry()；" +
                "新增一处不受上限约束的 retry 就是流量黑洞（R5）",
            2,
            retryCount
        )

        // 回退分支：retry 必须排在 shouldRetryLegacy 之后
        val legacyStart = code.indexOf("if (legacy) {")
        assertTrue("找不到回退分支", legacyStart >= 0)
        val legacyEnd = code.indexOf("private fun handleDownloadFailure", legacyStart)
        assertTrue("找不到 handleDownloadFailure（回退分支的边界）", legacyEnd > legacyStart)
        val legacyBlock = code.substring(legacyStart, legacyEnd)

        val guard = legacyBlock.indexOf("DownloadPolicy.shouldRetryLegacy(")
        val retry = legacyBlock.indexOf("Result.retry()")
        assertTrue(
            "回退开关也必须先过 shouldRetryLegacy（R5 第 3 条：不允许无次数上限）",
            guard >= 0
        )
        assertTrue("回退分支的 retry（$retry）必须排在上限判定（$guard）之后", retry > guard)
        assertEquals(
            "回退分支只允许一处 retry",
            1,
            Regex("Result\\.retry\\(\\)").findAll(legacyBlock).count()
        )
    }

    @Test
    fun `达到上限后给出可执行的下一步文案`() {
        val code = codeOnly(readSource(workerPath))
        assertTrue(
            "重试耗尽的通知必须告诉用户点『检查更新』可重试",
            code.contains("R.string.update_failed_retry_hint")
        )
        assertTrue(
            "正常路径耗尽也必须走 update_error_retry_exhausted 文案",
            code.contains("R.string.update_error_retry_exhausted")
        )
    }

    @Test
    fun `退避基数不小于 30 秒且为指数退避`() {
        val body = readSource(repoPath)
        assertTrue(
            "必须使用指数退避",
            body.contains("setBackoffCriteria(BackoffPolicy.EXPONENTIAL")
        )
        assertTrue(
            "退避基数必须是 30 秒（R5 第 4 条：避免短时间内连续重试）",
            body.contains("private const val BACKOFF_SECONDS = 30L")
        )
    }

    @Test
    fun `常量上限被钉在 2 与 3`() {
        // 行为断言在 DownloadPolicyTest；这里额外把「有人偷偷改大」的成本抬高
        assertEquals(2, DownloadPolicy.MAX_AUTO_RETRIES)
        assertEquals(3, DownloadPolicy.LEGACY_MAX_AUTO_RETRIES)
        assertFalse(DownloadPolicy.shouldRetry(DownloadPolicy.MAX_AUTO_RETRIES, legacyMode = false))
        assertFalse(DownloadPolicy.shouldRetry(DownloadPolicy.LEGACY_MAX_AUTO_RETRIES, legacyMode = true))
    }
}