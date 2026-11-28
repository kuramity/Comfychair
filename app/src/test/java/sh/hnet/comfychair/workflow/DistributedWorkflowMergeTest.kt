package sh.hnet.comfychair.workflow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.hnet.comfychair.WorkflowManager
import sh.hnet.comfychair.WorkflowType

/**
 * 下发预置流与既有工作流的合并谓词测试（B5）。
 *
 * 合并语义（按 name 分组，下发层永远不产生重复条目）：
 * 1. 下发条目与**内置**（isBuiltIn=true）条目同名 → 替换内置版（重名下发优先）；
 * 2. 下发条目与**用户自建**（isBuiltIn=false）条目同名 → 丢弃下发版（用户流优先于下发）；
 * 3. 下发独有 → 追加到列表尾部；
 * 4. 下发清单内部同名 → 后到覆盖先到（防服务器清单重复条目产生重复流）。
 */
class DistributedWorkflowMergeTest {

    private fun wf(
        id: String,
        name: String,
        builtIn: Boolean,
        type: WorkflowType = WorkflowType.TTI,
    ): WorkflowManager.Workflow = WorkflowManager.Workflow(
        id = id,
        name = name,
        description = "",
        jsonContent = "{}",
        type = type,
        isBuiltIn = builtIn,
    )

    // ---------------- ① 同名下发替换内置 ----------------

    @Test
    fun `同名下发替换内置版`() {
        val base = listOf(wf("builtin-1", "人脸修复", builtIn = true))
        val distributed = listOf(wf("dist-1", "人脸修复", builtIn = true))

        val merged = DistributedWorkflowMerge.merge(base, distributed)

        assertEquals(1, merged.size)
        assertEquals("dist-1", merged[0].id)
    }

    @Test
    fun `同名用户流保留下发版被丢弃`() {
        val base = listOf(wf("user-1", "我的流", builtIn = false))
        val distributed = listOf(wf("dist-1", "我的流", builtIn = true))

        val merged = DistributedWorkflowMerge.merge(base, distributed)

        assertEquals(1, merged.size)
        assertEquals("user-1", merged[0].id)
    }

    @Test
    fun `内置与用户同名并存时内置被替换用户保留`() {
        val base = listOf(
            wf("builtin-1", "共用名", builtIn = true),
            wf("user-1", "共用名", builtIn = false),
        )
        val distributed = listOf(wf("dist-1", "共用名", builtIn = true))

        val merged = DistributedWorkflowMerge.merge(base, distributed)

        assertEquals(2, merged.size)
        assertEquals(listOf("dist-1", "user-1"), merged.map { it.id })
    }

    // ---------------- ② 下发独有追加 ----------------

    @Test
    fun `下发独有条目追加到尾部`() {
        val base = listOf(wf("builtin-1", "内置流", builtIn = true))
        val distributed = listOf(
            wf("dist-1", "新下发流", builtIn = true, type = WorkflowType.TTV),
            wf("dist-2", "新下发流二", builtIn = true, type = WorkflowType.ITV),
        )

        val merged = DistributedWorkflowMerge.merge(base, distributed)

        assertEquals(3, merged.size)
        assertEquals("内置流", merged[0].name)
        assertEquals(listOf("新下发流", "新下发流二"), merged.drop(1).map { it.name })
    }

    // ---------------- ③ 边界 ----------------

    @Test
    fun `空下发清单时原样返回`() {
        val base = listOf(wf("builtin-1", "内置流", builtIn = true))
        assertTrue(DistributedWorkflowMerge.merge(base, emptyList()) === base)
    }

    @Test
    fun `下发内部同名时后到覆盖先到`() {
        val distributed = listOf(
            wf("dist-1", "同名", builtIn = true),
            wf("dist-2", "同名", builtIn = true),
        )

        val merged = DistributedWorkflowMerge.merge(emptyList(), distributed)

        assertEquals(1, merged.size)
        assertEquals("dist-2", merged[0].id)
    }

    @Test
    fun `替换保序不重排既有列表`() {
        val base = listOf(
            wf("builtin-1", "A", builtIn = true),
            wf("builtin-2", "B", builtIn = true),
            wf("builtin-3", "C", builtIn = true),
        )
        val distributed = listOf(wf("dist-B", "B", builtIn = true))

        val merged = DistributedWorkflowMerge.merge(base, distributed)

        assertEquals(listOf("builtin-1", "dist-B", "builtin-3"), merged.map { it.id })
    }
}
