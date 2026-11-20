package sh.hnet.comfychair.storage

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 启动清理任务的准入判定。
 *
 * 核心诉求：读取失败或服务器数量异常归零时中止清理，
 * 避免把仍在使用的凭据 / 参数当成「过期数据」删掉。
 */
class MaintenancePlanTest {

    private fun decide(
        serversOk: Boolean = true,
        workflowsOk: Boolean = true,
        workflowCount: Int = 30,
        serverCount: Int = 1,
        previousServerCount: Int = 1,
        explicitDeletion: Boolean = false
    ) = MaintenancePlan.decide(
        serversOk = serversOk,
        workflowsOk = workflowsOk,
        workflowCount = workflowCount,
        serverCount = serverCount,
        previousServerCount = previousServerCount,
        explicitDeletion = explicitDeletion
    )

    private fun assertAbort(decision: MaintenancePlan.Decision) {
        assertTrue("期望中止清理，实际：$decision", decision is MaintenancePlan.Decision.Abort)
    }

    @Test
    fun `服务器读取失败时中止`() {
        assertAbort(decide(serversOk = false, serverCount = 0))
    }

    @Test
    fun `工作流读取失败时中止`() {
        assertAbort(decide(workflowsOk = false))
    }

    @Test
    fun `数量稳定时正常清理`() {
        val decision = decide(serverCount = 2, previousServerCount = 2)
        assertTrue("期望继续清理，实际：$decision", decision is MaintenancePlan.Decision.Proceed)
    }

    @Test
    fun `服务器数由有变零且非显式删除时中止`() {
        assertAbort(decide(serverCount = 0, previousServerCount = 2, explicitDeletion = false))
    }

    @Test
    fun `服务器数由有变零但属于显式删除时正常清理`() {
        val decision = decide(serverCount = 0, previousServerCount = 2, explicitDeletion = true)
        assertTrue("期望继续清理，实际：$decision", decision is MaintenancePlan.Decision.Proceed)
    }

    @Test
    fun `从未有过服务器时正常清理`() {
        val decision = decide(serverCount = 0, previousServerCount = 0)
        assertTrue("期望继续清理，实际：$decision", decision is MaintenancePlan.Decision.Proceed)
    }

    @Test
    fun `工作流列表为空时中止`() {
        // 内置工作流恒存在（30 个），空列表只可能是读取失败后的静默空集
        assertAbort(decide(workflowCount = 0))
    }

    @Test
    fun `工作流数量正常时继续清理`() {
        val decision = decide(workflowCount = 30)
        assertTrue("期望继续清理，实际：$decision", decision is MaintenancePlan.Decision.Proceed)
    }

    @Test
    fun `上次加载有错误时视为读取失败并中止`() {
        // A4-2：部分加载失败（catch 吞异常）会让列表只剩内置工作流，
        // 继续清理会把用户工作流参数当孤儿删掉
        val workflowsOk = MaintenancePlan.workflowsLoadOk(lastLoadHadErrors = true)
        assertAbort(decide(workflowsOk = workflowsOk))
    }

    @Test
    fun `上次加载无错误时视为成功`() {
        assertTrue(MaintenancePlan.workflowsLoadOk(lastLoadHadErrors = false))
    }
}