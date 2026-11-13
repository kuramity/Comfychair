package sh.hnet.comfychair.connection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ModelIntroSyncRunner] 的行为测试 —— 也就是「热更新接线」在 JVM 里能测的那一半。
 *
 * 守的是两条承诺（终审 I-1 / I-2）：
 * 1. **闸门在每一条路径上复位**：成功、拉取失败、回调里抛出、发起阶段同步抛出。
 *    漏掉任何一条，本进程内的热更新就永久失效 —— 界面上看不出任何异常，数据只是再也不更新；
 * 2. **异常绝不外泄**：调用方（`ConnectionManager.fetchServerData` 的 `/object_info` 回调）
 *    在这之后还要装配模型列表；让异常冒泡的后果是"模型下拉为空"，比"没有介绍"严重得多。
 *
 * 为什么不能只测 `SingleFlight` 自己（见 `ModelIntroFetchTest` 里那几条）：
 * 那测的是"这个工具类能不能用"，与本对象**有没有把它用对**是两件事 ——
 * 终审反证实验证明，把生产接线整个删掉，既有全量测试仍然 473 全绿。
 */
class ModelIntroSyncRunnerTest {

    /** 记录 runner 打出来的日志（同时用于确认失败是"记了日志"而不是"静默吞掉"）。 */
    private val logs = mutableListOf<String>()

    private fun newRunner() = ModelIntroSyncRunner(
        logInfo = { logs.add("I:$it") },
        logWarn = { logs.add("W:$it") },
    )

    // ---------------- ① 正常路径 ----------------

    @Test
    fun `拉取成功后应用数据并释放闸门`() {
        var applied: String? = null
        var fetchCount = 0
        val runner = newRunner()

        runner.run(
            fetch = { callback -> fetchCount++; callback("""{"version":1,"models":{"a":{}}}""") },
            apply = { applied = it },
        )

        assertEquals("""{"version":1,"models":{"a":{}}}""", applied)
        // 闸门必须已复位：再触发一次仍应真的发起拉取（若卡死，这里 fetchCount 会停在 1）
        runner.run(fetch = { callback -> fetchCount++; callback(null) }, apply = {})
        assertEquals("闸门未复位：第二次触发没有真正发起拉取", 2, fetchCount)
    }

    @Test
    fun `拉取失败时不应用数据但仍释放闸门`() {
        var applyCalls = 0
        var fetchCount = 0
        val runner = newRunner()

        runner.run(fetch = { callback -> fetchCount++; callback(null) }, apply = { applyCalls++ })

        assertEquals("拉取失败不得动本地表", 0, applyCalls)
        assertTrue("失败要留下日志，不能静默", logs.any { it.startsWith("W:") })
        runner.run(fetch = { callback -> fetchCount++; callback(null) }, apply = {})
        assertEquals("闸门未复位：第二次触发没有真正发起拉取", 2, fetchCount)
    }

    // ---------------- ② 异常路径（I-1 的核心） ----------------

    @Test
    fun `发起阶段同步抛出时异常不冒泡且闸门复位`() {
        var fetchCount = 0
        var applyCalls = 0
        val runner = newRunner()

        // 模拟 getBaseUrl / 地址拼装 / Request 构造 / enqueue 同步抛出。
        // 这一行只要把异常抛出来，测试就红 —— 那正是"异常穿过 /object_info 回调、
        // 后面的 extractModelLists() 与模型列表装配全部被跳过"的生产事故。
        runner.run(
            fetch = { fetchCount++; throw IllegalStateException("模拟发起阶段同步抛出") },
            apply = { applyCalls++ },
        )

        assertEquals(0, applyCalls)
        assertTrue("发起阶段抛出要留下日志", logs.any { it.startsWith("W:") && it.contains("发起阶段") })
        // 闸门必须复位：否则本进程内后续所有同步都被永久跳过（且界面无任何异常）
        runner.run(fetch = { callback -> fetchCount++; callback(null) }, apply = {})
        assertEquals("闸门卡死：第二次触发没有真正发起拉取", 2, fetchCount)
    }

    @Test
    fun `回调内部抛出时异常不外泄且闸门复位`() {
        var fetchCount = 0
        val runner = newRunner()

        // apply（落盘 + 重载内存表）内部任何异常都不许影响调用方
        runner.run(
            fetch = { callback -> fetchCount++; callback("""{"models":{"a":{}}}""") },
            apply = { throw IllegalStateException("模拟落盘异常") },
        )

        assertTrue("回调内异常要留下日志", logs.any { it.startsWith("W:") && it.contains("同步异常") })
        runner.run(fetch = { callback -> fetchCount++; callback(null) }, apply = {})
        assertEquals("闸门卡死：第二次触发没有真正发起拉取", 2, fetchCount)
    }

    @Test
    fun `同步回调之后才抛出时不会误放别人刚拿到的执行权`() {
        // 组合场景：fetch 同步回调（闸门已正常释放）→ 另一条链路触发并拿到执行权 →
        // fetch 才抛出。兜底释放必须是幂等的，否则会把**别人**刚拿到的执行权放掉，
        // 于是"同一时刻至多一个在飞"失效（两个拉取同时在飞、两次落盘）。
        var nestedFetchCount = 0
        var thirdFetchCount = 0
        var pendingNested: ((String?) -> Unit)? = null
        val runner = newRunner()

        runner.run(
            fetch = { callback ->
                callback("first")
                runner.run(
                    fetch = { nested -> nestedFetchCount++; pendingNested = nested },
                    apply = { },
                )
                throw IllegalStateException("模拟同步回调之后的抛出")
            },
            apply = { },
        )

        assertEquals(1, nestedFetchCount)
        // 嵌套那次仍在飞：第三次触发必须被跳过
        runner.run(fetch = { callback -> thirdFetchCount++; callback(null) }, apply = {})
        assertEquals("兜底释放误放了嵌套任务的执行权", 0, thirdFetchCount)

        // 嵌套任务收尾后才轮得到下一次
        pendingNested?.invoke(null)
        runner.run(fetch = { callback -> thirdFetchCount++; callback(null) }, apply = {})
        assertEquals(1, thirdFetchCount)
    }

    // ---------------- ③ 跳过式并发保护 ----------------

    @Test
    fun `在飞期间重复触发被跳过且不重开拉取`() {
        var fetchCount = 0
        var secondApplied = false
        var pendingFirst: ((String?) -> Unit)? = null
        val runner = newRunner()

        runner.run(fetch = { callback -> fetchCount++; pendingFirst = callback }, apply = { })
        assertEquals(1, fetchCount)

        // 第一个还没回来：第二次触发（比如用户连点刷新）必须直接跳过
        runner.run(fetch = { callback -> fetchCount++; callback("second") }, apply = { secondApplied = true })
        assertEquals("在飞期间不得重开拉取", 1, fetchCount)
        assertFalse(secondApplied)
        assertTrue("跳过要留下日志", logs.any { it.contains("跳过本次") })

        // 第一个回来后闸门释放 → 下一次能进
        pendingFirst?.invoke(null)
        runner.run(fetch = { callback -> fetchCount++; callback(null) }, apply = { })
        assertEquals(2, fetchCount)
    }

    @Test
    fun `回调尚未返回时不应用任何数据`() {
        var applied: String? = null
        val runner = newRunner()
        runner.run(fetch = { /* 什么都不回调：模拟请求悬在空中 */ }, apply = { applied = it })
        assertNull("回调没来之前不得应用数据", applied)
    }
}
