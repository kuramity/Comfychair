package sh.hnet.comfychair.util

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class DebugLoggerTest {

    @After
    fun tearDown() {
        DebugLogger.setEnabled(false)
        DebugLogger.clear()
    }

    /** 建立可比较的版本基线，同时不改变 setEnabled 的既有启动日志语义。 */
    private fun resetLogger(): Int {
        DebugLogger.setEnabled(false)
        DebugLogger.clear()
        DebugLogger.setEnabled(true)
        DebugLogger.clear()
        return DebugLogger.currentVersion()
    }

    private fun assertConcurrentWritesStayBounded() {
        val versionBefore = resetLogger()
        val threadCount = 16
        val writesPerThread = 2_000
        val executor = Executors.newFixedThreadPool(threadCount)
        val ready = CountDownLatch(threadCount)
        val start = CountDownLatch(1)

        try {
            val futures = (0 until threadCount).map { worker ->
                executor.submit {
                    ready.countDown()
                    start.await()
                    repeat(writesPerThread) { sequence ->
                        DebugLogger.d("P7Concurrency", "$worker:$sequence")
                    }
                }
            }

            assertTrue("16 个工作线程未全部就绪", ready.await(10, TimeUnit.SECONDS))
            start.countDown()
            futures.forEach { it.get(30, TimeUnit.SECONDS) }
        } finally {
            start.countDown()
            executor.shutdownNow()
        }

        val totalWrites = threadCount * writesPerThread
        val entries = DebugLogger.getEntries()
        assertTrue("日志容量超限: ${entries.size}", entries.size <= 1_000)
        assertEquals("32,000 条写入后应批量裁剪并保留 845 条", 845, entries.size)
        assertEquals(
            "每次成功写入都必须增加版本号",
            versionBefore + totalWrites,
            DebugLogger.currentVersion()
        )
    }

    private fun assertTrimBoundaryKeepsNewestEntries() {
        val versionBefore = resetLogger()

        repeat(1_200) { index ->
            DebugLogger.d("P7Boundary", "boundary-$index")
        }
        assertEquals("首批写入跨过阈值后应保留 999 条", 999, DebugLogger.getEntries().size)

        repeat(1_200) { index ->
            DebugLogger.d("P7Boundary", "boundary-${index + 1_200}")
        }

        val entries = DebugLogger.getEntries()
        assertEquals("第二批写入后应批量裁剪到 993 条", 993, entries.size)
        assertEquals("boundary-1407", entries.first().message)
        assertEquals(
            "必须保留最新 800 条",
            (1_600..2_399).map { "boundary-$it" },
            entries.takeLast(800).map { it.message }
        )
        assertEquals(versionBefore + 2_400, DebugLogger.currentVersion())
    }

    private fun assertExistingLoggerContracts() {
        val versionBefore = resetLogger()
        DebugLogger.i("P7Regression", "export-me")
        DebugLogger.w("P7Regression", "also-export-me")

        val exported = DebugLogger.exportToString()
        assertTrue(exported.contains("Entries: 2"))
        assertTrue(exported.contains("export-me"))
        assertTrue(exported.contains("also-export-me"))
        assertEquals(versionBefore + 2, DebugLogger.currentVersion())

        DebugLogger.clear()
        assertTrue(DebugLogger.getEntries().isEmpty())
        assertEquals(versionBefore + 3, DebugLogger.currentVersion())

        DebugLogger.setEnabled(false)
        val disabledVersion = DebugLogger.currentVersion()
        repeat(10) { index ->
            DebugLogger.e("P7Regression", "disabled-$index")
        }
        assertTrue("enabled=false 时不得写入", DebugLogger.getEntries().isEmpty())
        assertFalse(DebugLogger.isEnabled())
        assertEquals(disabledVersion, DebugLogger.currentVersion())
    }

    @Test(timeout = 60_000)
    fun `concurrent writes stay bounded and preserve logger contracts`() {
        assertConcurrentWritesStayBounded()
        assertTrimBoundaryKeepsNewestEntries()
        assertExistingLoggerContracts()
    }
}
