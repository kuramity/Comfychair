package sh.hnet.comfychair.util

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * CrashLogger 可独立单测的纯逻辑：文件名生成 + 只保留最近 N 份的裁剪集合。
 *
 * **不在这里 install handler** —— 那会污染整个测试进程的未捕获异常行为，
 * 一旦有测试真的抛出就会被吞掉/改道。
 */
class CrashLoggerTest {

    private val tempDirs = mutableListOf<File>()

    @After
    fun cleanup() {
        for (dir in tempDirs) {
            dir.deleteRecursively()
        }
    }

    /**
     * 临时目录放在项目的 `build/tmp` 下（E 盘），不往系统临时目录（通常在 C 盘）写文件。
     * 用 `Files.createTempDirectory` 生成唯一子目录，避免并行用例互相干扰。
     */
    private fun newTempDir(): File {
        val root = File(System.getProperty("user.dir"), "build/tmp/crash-logger-test")
        root.mkdirs()
        return Files.createTempDirectory(root.toPath(), "case-").toFile()
            .also { tempDirs.add(it) }
    }

    private fun createFile(dir: File, name: String, lastModified: Long): File =
        File(dir, name).apply {
            writeText("测试内容")
            setLastModified(lastModified)
        }

    // ============================================================
    // 文件名生成
    // ============================================================

    @Test
    fun `文件名符合 crash-yyyyMMdd-HHmmss_txt 格式`() {
        val name = CrashLogger.crashFileName(1_600_000_000_000L)
        assertTrue("实际文件名: $name", Regex("^crash-\\d{8}-\\d{6}\\.txt$").matches(name))
    }

    @Test
    fun `同一秒碰撞序号追加在扩展名前`() {
        val base = 1_600_000_000_000L
        val first = CrashLogger.crashFileName(base, 0)
        val second = CrashLogger.crashFileName(base, 1)
        val third = CrashLogger.crashFileName(base, 2)

        assertEquals(first.removeSuffix(".txt") + "-1.txt", second)
        assertEquals(first.removeSuffix(".txt") + "-2.txt", third)
        assertTrue("序号不同的文件名必须不同", first != second && second != third)
    }

    @Test
    fun `负数碰撞序号按无后缀处理`() {
        val base = 1_600_000_000_000L
        assertEquals(CrashLogger.crashFileName(base, 0), CrashLogger.crashFileName(base, -1))
    }

    // ============================================================
    // 只保留最近 N 份
    // ============================================================

    @Test
    fun `空输入不产生删除项`() {
        assertTrue(CrashLogger.crashFilesToDelete(emptyList()).isEmpty())
    }

    @Test
    fun `未超过上限时一个都不删`() {
        val dir = newTempDir()
        val files = (1..10).map { i ->
            createFile(dir, "crash-20260101-00000$i.txt", 1_000_000L + i * 1000L)
        }
        assertTrue(CrashLogger.crashFilesToDelete(files).isEmpty())
    }

    @Test
    fun `超过上限时按修改时间删最旧的 3 份`() {
        val dir = newTempDir()
        // 故意按"新→旧"的倒序输入，验证排序键是 lastModified 而不是输入顺序
        val files = (1..13).map { i ->
            createFile(dir, "crash-20260101-0000$i.txt", 1_000_000L + i * 1000L)
        }.reversed()

        val toDelete = CrashLogger.crashFilesToDelete(files)

        assertEquals("超限 3 份就删 3 份", 3, toDelete.size)
        val deletedNames = toDelete.map { it.name }.toSet()
        assertEquals(
            setOf(
                "crash-20260101-00001.txt",
                "crash-20260101-00002.txt",
                "crash-20260101-00003.txt"
            ),
            deletedNames
        )
        // 保留的必须是最新的 10 份
        val remaining = files.map { it.name }.toSet() - deletedNames
        assertEquals(10, remaining.size)
        assertEquals(
            setOf(
                "crash-20260101-00004.txt", "crash-20260101-00005.txt",
                "crash-20260101-00006.txt", "crash-20260101-00007.txt",
                "crash-20260101-00008.txt", "crash-20260101-00009.txt",
                "crash-20260101-000010.txt", "crash-20260101-000011.txt",
                "crash-20260101-000012.txt", "crash-20260101-000013.txt"
            ),
            remaining
        )
    }

    @Test
    fun `上限为 0 时全部删除`() {
        val dir = newTempDir()
        val files = (1..3).map { i ->
            createFile(dir, "crash-20260101-0000$i.txt", 1_000_000L + i * 1000L)
        }
        assertEquals(3, CrashLogger.crashFilesToDelete(files, maxFiles = 0).size)
    }

    @Test
    fun `负数上限按 0 处理`() {
        val dir = newTempDir()
        val files = (1..2).map { i ->
            createFile(dir, "crash-20260101-0000$i.txt", 1_000_000L + i * 1000L)
        }
        assertEquals(2, CrashLogger.crashFilesToDelete(files, maxFiles = -5).size)
    }

    @Test
    fun `默认上限就是 10 份`() {
        assertEquals(10, CrashLogger.MAX_CRASH_FILES)
        val dir = newTempDir()
        val files = (1..11).map { i ->
            createFile(dir, "crash-20260101-0000$i.txt", 1_000_000L + i * 1000L)
        }
        assertEquals(1, CrashLogger.crashFilesToDelete(files).size)
        assertEquals("crash-20260101-00001.txt", CrashLogger.crashFilesToDelete(files).first().name)
    }
}