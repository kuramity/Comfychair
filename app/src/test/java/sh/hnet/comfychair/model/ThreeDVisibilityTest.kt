package sh.hnet.comfychair.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「3D 分段可见性」的判定测试（v4.3.0 修的真机 bug）。
 *
 * 用户实测现象：生成过一次 3D 后，切到**普通出图流**，分段仍是三段
 * 「3D 模型 / 渲染图 / 原图」，把原来的「原图 / 预览」两段挤掉；反复切换后闪退。
 * 根因是判据用「全局 holder 有没有值」，而 holder 是单例、与当前工作流无关。
 *
 * 下面每条用例对应一种真实切换场景，第 1 条就是用户报的那条。
 */
class ThreeDVisibilityTest {

    private val result = ThreeDResultHolder.Result(
        filename = "idol_3d_67_00001_.glb",
        subfolder = "",
        type = "output"
    )

    // 1. 用户主诉：holder 有值，但当前是普通出图流 → 绝不能显示 3D 分段
    @Test
    fun plainWorkflowNeverShowsThreeD() {
        assertNull(
            "普通出图流不该出现 3D 分段（否则会挤掉原图/预览两段）",
            ThreeDVisibility.visible(
                result = result,
                is3DWorkflow = false,
                boundWorkflowId = "wf-zimage",
                currentWorkflowId = "wf-zimage"
            )
        )
    }

    // 2. 当前是 3D 流、结果正是它产出的 → 显示（用户期望的"持久化到对应 3D 流"）
    @Test
    fun matchingThreeDWorkflowShowsResult() {
        val shown = ThreeDVisibility.visible(
            result = result,
            is3DWorkflow = true,
            boundWorkflowId = "wf-idol",
            currentWorkflowId = "wf-idol"
        )
        assertNotNull("3D 流下应当看到自己的模型", shown)
        assertEquals("idol_3d_67_00001_.glb", shown!!.filename)
    }

    // 3. 切到"另一个 3D 流"（如四视图流），但结果属于 IDOL → 不显示（不串味）
    @Test
    fun otherThreeDWorkflowDoesNotShowForeignResult() {
        assertNull(
            "两个不同的 3D 流之间切换时不能看到别人的模型",
            ThreeDVisibility.visible(
                result = result,
                is3DWorkflow = true,
                boundWorkflowId = "wf-idol",
                currentWorkflowId = "wf-hunyuan-mv"
            )
        )
    }

    // 4. 还没绑定过归属（页面刚创建）→ 不显示，避免"一进页面就冒出上次的模型"
    @Test
    fun unboundResultIsHidden() {
        assertNull(
            ThreeDVisibility.visible(
                result = result,
                is3DWorkflow = true,
                boundWorkflowId = null,
                currentWorkflowId = "wf-idol"
            )
        )
    }

    // 5. 没有任何 3D 结果 → 不显示（普通路径）
    @Test
    fun nullResultIsHidden() {
        assertNull(
            ThreeDVisibility.visible(
                result = null,
                is3DWorkflow = true,
                boundWorkflowId = "wf-idol",
                currentWorkflowId = "wf-idol"
            )
        )
    }

    // 6. 空 id 也算不匹配（工作流还没加载完时 selectedWorkflowId 是空串）
    @Test
    fun emptyWorkflowIdsDoNotMatch() {
        assertNull(
            ThreeDVisibility.visible(
                result = result,
                is3DWorkflow = true,
                boundWorkflowId = "",
                currentWorkflowId = ""
            )
        )
    }

    // ── v4.4.0：顶部入口的显隐与点击行为（用户要求「去掉结果依赖」）──

    // 7. 选中 3D 流时，**即使一次都没生成过**（result=null）入口也要出现
    @Test
    fun controlShowsOnThreeDWorkflowWithoutAnyResult() {
        assertTrue(
            "3D 流下没生成过也该有入口，否则用户永远打不开第一次",
            ThreeDVisibility.controlVisible(is3DWorkflow = true)
        )
    }

    // 8. 普通出图流：入口绝不出现（切回去立刻消失）
    @Test
    fun controlHiddenOnPlainWorkflow() {
        assertFalse(
            "普通出图流不该出现 3D 入口",
            ThreeDVisibility.controlVisible(is3DWorkflow = false)
        )
    }

    // 9. 空结果点击：必须是提示分支，不能进查看器（进去了就会对 null 解包闪退）
    @Test
    fun emptyResultNeverOpensViewer() {
        assertFalse(
            "没有结果时点击只能给提示，绝不能进查看器",
            ThreeDVisibility.opensViewer(null)
        )
    }

    // 10. 有结果点击：正常打开查看器
    @Test
    fun existingResultOpensViewer() {
        assertTrue(ThreeDVisibility.opensViewer(result))
    }

    // ── v4.4.1：工作流 id 必须按模式取（编辑模式 vs 修复模式）──

    // 11. 回归现场：编辑模式下修复模式那个 id 是空串，取错就把整条 3D 链路吞掉
    @Test
    fun editingModeUsesEditingWorkflowId() {
        val id = ThreeDVisibility.activeWorkflowId(
            isEditingMode = true,
            plainId = "",                       // 修复模式未选 → 空串（实测真实状态）
            editingId = "b660f40b-c376"         // 编辑模式选中的 3D 流
        )
        assertEquals("b660f40b-c376", id)
        assertTrue("取到的 id 不能是空串，否则 visible() 会直接吞掉结果", id.isNotBlank())
    }

    // 12. 修复模式下用修复模式的 id（别反过来把编辑模式的带进来）
    @Test
    fun inpaintingModeUsesPlainWorkflowId() {
        assertEquals(
            "wf-inpaint",
            ThreeDVisibility.activeWorkflowId(
                isEditingMode = false,
                plainId = "wf-inpaint",
                editingId = "wf-edit"
            )
        )
    }
}
