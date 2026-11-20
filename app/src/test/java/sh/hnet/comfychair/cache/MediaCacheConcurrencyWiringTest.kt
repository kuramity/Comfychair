package sh.hnet.comfychair.cache

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [MediaCache] 跨线程字段的源码级钉子（m6 / N4）。
 *
 * 为什么读源码：`MediaCache` 是 Android object，字段的 `@Volatile` 与 CAS 用法
 * 在 JVM 单测里既读不到也测不出（可见性缺陷不会稳定复现）；而删掉一个 `@Volatile`
 * 或把 CAS 改回 check-then-act，现有行为测试仍会全绿。
 * 口径与 [MediaStateHolderWiringTest] 一致：单测工作目录是模块根（`app/`）。
 */
class MediaCacheConcurrencyWiringTest {

    private val cachePath = "src/main/java/sh/hnet/comfychair/cache/MediaCache.kt"

    private fun source(): String {
        val file = File(cachePath)
        assertTrue(
            "源文件不存在：${file.absolutePath} —— 单测工作目录应为 app/ 模块根",
            file.exists()
        )
        return file.readText()
    }

    /** 去掉 `//` 行注释与 `/* */` 块注释：注释里提到 @Volatile 不算加了注解。 */
    private fun codeOnly(text: String): String =
        text.replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), " ")
            .replace(Regex("""//[^\n]*"""), " ")

    @Test
    fun `isMemoryFirstMode 带 @Volatile（主线程写、预取 worker 读）`() {
        val code = codeOnly(source())
        assertTrue(
            "isMemoryFirstMode 缺 @Volatile：主线程切模式后，Dispatchers.Default 上的预取 worker " +
                "可能长时间按旧模式分流",
            Regex("""@Volatile\s+private\s+var\s+isMemoryFirstMode\b""").containsMatchIn(code)
        )
    }

    @Test
    fun `activeView 带 @Volatile（写方界面、读取走公开访问器）`() {
        val code = codeOnly(source())
        assertTrue(
            "activeView 缺 @Volatile：isActiveView 是公开访问器，任何线程都可能读",
            Regex("""@Volatile\s+private\s+var\s+activeView\b""").containsMatchIn(code)
        )
    }

    @Test
    fun `applicationContext 带 @Volatile`() {
        val code = codeOnly(source())
        assertTrue(
            "applicationContext 缺 @Volatile：多个入口跨线程首调 ensureInitialized，读侧可能长期读到 null",
            Regex("""@Volatile\s+private\s+var\s+applicationContext\b""").containsMatchIn(code)
        )
    }

    @Test
    fun `预取 worker 用 AtomicBoolean 且以 CAS 保证只启动一次（m6）`() {
        val code = codeOnly(source())

        assertTrue(
            "isPrefetchWorkersStarted 必须是 AtomicBoolean",
            Regex("""private\s+val\s+isPrefetchWorkersStarted\s*=\s*AtomicBoolean\(""").containsMatchIn(code)
        )
        assertTrue(
            "启动判据必须是 compareAndSet(false, true)：check-then-act 会起两组 worker",
            Regex("""isPrefetchWorkersStarted\.compareAndSet\(false,\s*true\)""").containsMatchIn(code)
        )
        assertFalse(
            "不能退回普通 var 的 check-then-act 写法",
            Regex("""private\s+var\s+isPrefetchWorkersStarted\s*=""").containsMatchIn(code)
        )
        assertFalse(
            "不能残留 `if (!isPrefetchWorkersStarted)` 的旧写法",
            Regex("""if\s*\(\s*!\s*isPrefetchWorkersStarted\s*\)""").containsMatchIn(code)
        )
    }

    /**
     * P2-5 磁盘优先链路的源码级钉子。
     *
     * 远程抽帧依赖 MediaMetadataRetriever 与真实网络，JVM 单测无法执行；
     * 但"磁盘优先分支必须先尝试 extractVideoDataFromUrl、再考虑 client.fetchVideo"
     * 是可以在源码层钉住的（删掉或调换顺序在运行时表现为整段下载，单测不会红了）。
     */
    @Test
    fun `磁盘优先的视频首帧也先尝试远程抽帧（P2-5）`() {
        val code = codeOnly(source())
        val start = code.indexOf("private suspend fun fetchVideoBitmapDiskFirst")
        assertTrue("找不到 fetchVideoBitmapDiskFirst", start >= 0)
        val end = code.indexOf("// Full-size image operations", start)
            .let { if (it > start) it else code.length }
        val body = code.substring(start, end)

        val remote = body.indexOf("extractVideoDataFromUrl(")
        val download = body.indexOf("client.fetchVideo(")
        assertTrue("磁盘优先链路必须调用 extractVideoDataFromUrl（远程抽帧）", remote >= 0)
        assertTrue(
            "远程抽帧必须排在整段下载之前：remote=$remote download=$download",
            download < 0 || remote < download
        )
    }
}