package sh.hnet.comfychair.queue

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 钉住 P1-1（execution_start 归属）与 P1-5（/queue 陈旧快照误删）的判定契约。
 *
 * 真实的状态写入发生在 [JobRegistry] 的 [Synchronized] 方法里（含 SharedPreferences /
 * GalleryRepository 等 Android 依赖），JVM 单测够不着；这里只测被抽出来的两个纯函数 ——
 * 它们正是"外部任务被误认成本机任务"和"刚登记的任务被旧快照删掉"唯一的判定点。
 */
class JobRegistryAttributionTest {

    // ── P1-1：execution_start 归属 ─────────────────────────────────

    @Test
    fun `注册表命中的任务归属注册表里的 owner`() {
        assertEquals(
            "提交成功时 registerJob 已经记下了归属，必须照它走",
            "TEXT_TO_IMAGE",
            resolveExecutionStartOwner(
                eventPromptId = "p-1",
                registeredOwnerId = "TEXT_TO_IMAGE",
                submittedOwnerId = null,
            ),
        )
    }

    @Test
    fun `注册表查不到且本机没提交过时判为外部任务`() {
        // 这是本次修复的核心：旧实现只要 generationOwnerId != null 就认领，
        // 于是别的客户端一提交，本机进度条/预览就跟过去了。
        assertNull(
            "外部客户端的 promptId 不能被归属到本机任何页面",
            resolveExecutionStartOwner(
                eventPromptId = "external-prompt",
                registeredOwnerId = null,
                submittedOwnerId = null,
            ),
        )
    }

    @Test
    fun `注册表条目已被对账删除但本机提交过时仍按提交记录归属`() {
        // 场景：任务完成后 ownJobs 条目已被移除，WS 重连补发同一条 execution_start。
        // 只要 promptId 是本机提交返回过的，就仍认得出是自己的（不会挂到别的页面）。
        assertEquals(
            "本机提交记录是第二条合法证据",
            "IMAGE_TO_VIDEO",
            resolveExecutionStartOwner(
                eventPromptId = "p-2",
                registeredOwnerId = null,
                submittedOwnerId = "IMAGE_TO_VIDEO",
            ),
        )
    }

    @Test
    fun `提交记录里的 owner 与事件 promptId 不一致时不能错配`() {
        // submittedOwnerId 是按 promptId 查出来的（findSubmittedPrompt），
        // 这里验证纯函数的语义：拿到谁就是谁，不会退回到"当前在途提交"的 owner。
        val ownerForPromptA = resolveExecutionStartOwner("p-a", null, "TEXT_TO_IMAGE")
        val ownerForExternal = resolveExecutionStartOwner("p-other", null, null)

        assertEquals("TEXT_TO_IMAGE", ownerForPromptA)
        assertNull("另一个 promptId 不能借用别人的归属", ownerForExternal)
    }

    @Test
    fun `空 promptId 一律不归属`() {
        assertNull(
            "空 id 没有可信归属，注册表/提交记录再怎么写都不该认领",
            resolveExecutionStartOwner("", "TEXT_TO_IMAGE", "TEXT_TO_IMAGE"),
        )
    }

    @Test
    fun `注册表归属优先于提交记录`() {
        // 正常路径下两者一致；不一致时以注册表为准（它带的是任务当前状态）。
        assertEquals(
            "registeredOwnerId 命中时不再参考提交记录",
            "IMAGE_TO_IMAGE",
            resolveExecutionStartOwner("p-3", "IMAGE_TO_IMAGE", "TEXT_TO_IMAGE"),
        )
    }

    // ── P1-5：/queue 快照对账 ─────────────────────────────────────

    @Test
    fun `快照发出前登记且不在响应里的活跃任务应删除`() {
        assertTrue(
            "请求发出时它就存在，响应里没有 = 服务器上已经没了（完成/被清）",
            shouldDropFromQueueSnapshot(
                isActive = true,
                presentInResponse = false,
                registeredSeq = 5L,
                snapshotSeq = 10L,
            ),
        )
    }

    @Test
    fun `快照在飞期间新登记的任务必须保留`() {
        // 本次修复的核心：请求发出后才 registerJob 的任务必然不在那份旧快照里，
        // 旧实现会把它当"服务器上不存在"删掉 —— 刚提交的任务凭空消失。
        assertFalse(
            "请求在飞期间新登记的任务有宽限期，不能被旧快照删掉",
            shouldDropFromQueueSnapshot(
                isActive = true,
                presentInResponse = false,
                registeredSeq = 11L,
                snapshotSeq = 10L,
            ),
        )
    }

    @Test
    fun `响应里存在的任务永远保留`() {
        assertFalse(
            shouldDropFromQueueSnapshot(
                isActive = true,
                presentInResponse = true,
                registeredSeq = 1L,
                snapshotSeq = 10L,
            ),
        )
    }

    @Test
    fun `已完成或失败的任务不走快照删除`() {
        // 完成/失败条目有自己的清理路径（markCompleted/markFailed），
        // 这里只负责"活跃但服务器上没了"的那一类。
        assertFalse(
            shouldDropFromQueueSnapshot(
                isActive = false,
                presentInResponse = false,
                registeredSeq = 1L,
                snapshotSeq = 10L,
            ),
        )
    }

    @Test
    fun `没有快照信息时退回旧语义`() {
        // 默认参数是 Long.MAX_VALUE：没有快照 = 不做宽限，宁可少保留也不留幽灵任务。
        assertTrue(
            shouldDropFromQueueSnapshot(
                isActive = true,
                presentInResponse = false,
                registeredSeq = Long.MAX_VALUE,
                snapshotSeq = Long.MAX_VALUE,
            ),
        )
    }

    @Test
    fun `从未登记过序号的条目按最旧处理`() {
        // 重启后 restoreState 恢复的条目没有登记序号（Long.MIN_VALUE）：
        // 它们"早于一切快照"，服务器上不在就该删 —— 正是原本的期望行为。
        assertTrue(
            shouldDropFromQueueSnapshot(
                isActive = true,
                presentInResponse = false,
                registeredSeq = Long.MIN_VALUE,
                snapshotSeq = 0L,
            ),
        )
    }
}
