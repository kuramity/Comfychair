package sh.hnet.comfychair.taglibrary.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 方案 A 的「接线还在不在」源码级钉子。
 *
 * 引用计数的互斥只有落到 DAO / Repository 的调用点上才算数：计数机本身由
 * [DeferredCloseCounterTest] 在 JVM 里跑，但 `TagDao` 依赖 `SQLiteDatabase`，
 * 纯 JVM 单测够不着。沿用 [sh.hnet.comfychair.P2LongTailWiringTest] 的做法，
 * 读源码文本把最容易「顺手改回去」的几处钉住：
 *
 * 1. DAO 里不允许出现裸 `db.rawQuery`（必须全部套在引用计数里）；
 * 2. 计数的出口必须在 `finally` 里；
 * 3. `open` 的禁入门必须排在「复用已有句柄」之前；
 * 4. `close` 只能请求关闭，物理关闭只由计数门回调；
 * 5. `searcher` 赋值前必须过 `isClosePending`，且句柄关闭要作废缓存的 searcher。
 */
class TagCloseGuardWiringTest {

    /** 单测工作目录是模块根（app/），与 P2LongTailWiringTest 同口径。 */
    private fun source(path: String): String {
        val file = File(path)
        assertTrue(
            "源文件不存在：${file.absolutePath} —— 单测工作目录应为 app/ 模块根",
            file.exists()
        )
        return file.readText()
    }

    /** 取一个成员的正文：从签名起，到下一处 4 空格缩进的右花括号为止。 */
    private fun memberBody(source: String, signature: String): String {
        val start = source.indexOf(signature)
        assertTrue("源码里找不到 $signature", start >= 0)
        val rest = source.substring(start)
        val end = rest.indexOf("\n    }")
        return if (end >= 0) rest.substring(0, end) else rest
    }

    private val daoSource by lazy {
        source("src/main/java/sh/hnet/comfychair/taglibrary/data/TagDao.kt")
    }
    private val databaseSource by lazy {
        source("src/main/java/sh/hnet/comfychair/taglibrary/data/TagDatabase.kt")
    }
    private val repositorySource by lazy {
        source("src/main/java/sh/hnet/comfychair/taglibrary/data/TagRepository.kt")
    }
    private val counterSource by lazy {
        source("src/main/java/sh/hnet/comfychair/taglibrary/data/DeferredCloseCounter.kt")
    }

    // ------------------------------------------------------------ DAO：9 处查询点全部套计数

    @Test
    fun `DAO 不允许出现绕过引用计数的裸 rawQuery`() {
        assertFalse(
            "TagDao 里不能再有 db.rawQuery —— 查询必须走 guardedQuery 拿到句柄",
            daoSource.contains("db.rawQuery")
        )
        // 诊断报告点名的 9 个 rawQuery 调用点（TagDao.kt 原 53/73/95/106/120/129/135/146/162 行）
        val rawQueryLines = daoSource.lines().filter { it.contains("database.rawQuery(") }
        assertEquals(
            "DAO 打到数据库的调用点应当仍是 9 处（新增查询点请一并补单测）",
            9,
            rawQueryLines.size
        )
    }

    @Test
    fun `每个 rawQuery 调用点都处在 guardedQuery 块内`() {
        val lines = daoSource.lines()
        val methodStart = Regex("""^    (?:private |internal |@Synchronized *)*fun \w+""")
        var currentMethod = "<file 头>"
        var guardedForCurrentMethod = false
        var checked = 0
        for (line in lines) {
            if (methodStart.matches(line) || line.startsWith("    fun ") || line.startsWith("    private fun ")) {
                currentMethod = line.trim()
                guardedForCurrentMethod = false
            }
            if (line.contains("guardedQuery(")) guardedForCurrentMethod = true
            if (line.contains("database.rawQuery(")) {
                checked++
                assertTrue(
                    "rawQuery 未受引用计数保护，方法：$currentMethod",
                    guardedForCurrentMethod
                )
            }
        }
        assertEquals(9, checked)
    }

    @Test
    fun `guardedQuery 的计数出口必须写在 finally 里`() {
        val body = memberBody(daoSource, "private inline fun <R> guardedQuery(")
        val acquireIndex = body.indexOf("TagDatabase.acquire()")
        val tryIndex = body.indexOf("try {")
        val finallyIndex = body.indexOf("finally {")
        val releaseIndex = body.indexOf("TagDatabase.release()")
        assertTrue("guardedQuery 必须先 TagDatabase.acquire()", acquireIndex >= 0)
        assertTrue("acquire 被拒时必须先返回兜底值再谈查询", body.contains("return onClosed()"))
        assertTrue("查询本体必须在 try 块内", tryIndex > acquireIndex)
        assertTrue("release 必须在 finally 块内（异常路径也要减计数）", finallyIndex > tryIndex)
        assertTrue("finally 里减的就是 TagDatabase.release()", releaseIndex > finallyIndex)
        assertTrue("release 必须在 guardedQuery 内部，不能散落到各个方法", releaseIndex < body.length)
    }

    // ------------------------------------------------------------ TagDatabase：关闭走计数门

    @Test
    fun `close 只请求关闭不直接关句柄`() {
        val body = memberBody(databaseSource, "fun close()")
        assertTrue("close 必须走引用计数门", body.contains("closeGate.requestClose()"))
        assertFalse("close 不能再直接 close 句柄", body.contains(".close()"))
        assertFalse("close 不能再直接置空句柄", body.contains("database = null"))
    }

    @Test
    fun `物理关闭只能由计数门回调并作废句柄`() {
        assertTrue(
            "计数门的回调必须是 closeHandleNow",
            databaseSource.contains("DeferredCloseCounter { closeHandleNow() }")
        )
        val body = memberBody(databaseSource, "private fun closeHandleNow()")
        assertTrue("物理关闭要真的关掉 SQLiteDatabase", body.contains("db.close()"))
        assertTrue("关闭后必须把句柄置空", body.contains("database = null"))
        assertTrue(
            "物理关闭完成必须通知上层作废缓存的 searcher（防毒化单例）",
            body.contains("onHandleClosed?.invoke()")
        )
    }

    @Test
    fun `open 的禁入门排在复用已有句柄之前`() {
        val body = memberBody(databaseSource, "fun open(context: Context): Result<SQLiteDatabase>")
        val gateIndex = body.indexOf("isClosePending")
        val reuseIndex = body.indexOf("database?.let { if (it.isOpen) return Result.success(it) }")
        assertTrue("open 必须检查关闭门（排空期间不得交出即将关闭的句柄）", gateIndex >= 0)
        assertTrue("找不到复用句柄的快路径", reuseIndex >= 0)
        assertTrue("禁入门必须排在复用句柄之前", gateIndex < reuseIndex)
        assertTrue("关闭排空期间 open 必须失败而不是硬开", body.contains("Result.failure("))
    }

    @Test
    fun `acquire 在被拒时不得留下引用`() {
        val body = memberBody(databaseSource, "fun acquire(): Boolean")
        val gateIndex = body.indexOf("closeGate.acquire()")
        val nullCheckIndex = body.indexOf("database ?: return false")
        assertTrue("acquire 必须先确认句柄存活（陈旧句柄直接拒绝，不抛异常）", nullCheckIndex >= 0)
        assertTrue("存活检查必须排在计数递增之前", nullCheckIndex < gateIndex)
        assertFalse("acquire 的拒绝分支不许调用 release", body.contains("release()"))
    }

    // ------------------------------------------------------------ TagRepository：初始化事务与单例赋值

    @Test
    fun `建索引期间必须持有引用并在 finally 里归还`() {
        val body = memberBody(repositorySource, "suspend fun ensureReady(")
        val acquireIndex = body.indexOf("TagDatabase.acquire()")
        val buildIndex = body.indexOf("TagMemoryIndex.build(dao)")
        val finallyIndex = body.indexOf("finally {")
        val releaseIndex = body.indexOf("TagDatabase.release()")
        assertTrue("建索引前必须先 acquire（长查询期间把 close 挡在门外）", acquireIndex >= 0)
        assertTrue("acquire 必须排在索引构建之前", acquireIndex < buildIndex)
        assertTrue("release 必须写在 finally 里", finallyIndex > buildIndex)
        assertTrue("finally 里归还引用", releaseIndex > finallyIndex)
    }

    @Test
    fun `searcher 赋值必须被关闭门保护`() {
        val body = memberBody(repositorySource, "suspend fun ensureReady(")
        val gateIndex = body.indexOf("TagDatabase.isClosePending")
        val assignIndex = body.indexOf("searcher = candidate")
        assertTrue("写单例前必须检查关闭门", gateIndex >= 0)
        assertTrue("检查必须排在赋值之前", gateIndex < assignIndex)
    }

    @Test
    fun `句柄关闭时必须作废缓存的 searcher`() {
        val initBlock = memberBody(repositorySource, "init {")
        assertTrue(
            "Repository 必须注册物理关闭回调，把 searcher 生命周期收进句柄生命周期",
            initBlock.contains("TagDatabase.onHandleClosed = { searcher = null }")
        )
    }

    @Test
    fun `release 仍然是非挂起的关闭请求`() {
        val body = memberBody(repositorySource, "fun release()")
        assertTrue("release 依旧通过 TagDatabase.close() 请求关闭", body.contains("TagDatabase.close()"))
        assertTrue("release 必须同步清掉 searcher（retryInitialize 流程不变）", body.contains("searcher = null"))
        assertFalse("方案 A 不做阻塞：release 不许变成 suspend", body.contains("suspend"))
    }

    // ------------------------------------------------------------ 计数机保持纯 JVM 可测

    @Test
    fun `计数机不得依赖 Android`() {
        val importLines = counterSource.lines().filter { it.startsWith("import ") }
        assertTrue(
            "DeferredCloseCounter 一旦引入 android.* 就没法在 JVM 单测里跑：$importLines",
            importLines.none { it.contains("android.") }
        )
        assertTrue("计数状态必须在同一把锁里转移", counterSource.contains("synchronized(lock)"))
        assertTrue(
            "物理关闭回调要在锁外执行（与 TagDatabase 的 monitor 构成反向加锁会死锁）",
            !counterSource.contains("synchronized(lock) { physicalClose()")
        )
    }
}
