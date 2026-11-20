package sh.hnet.comfychair.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 查看器退出清理计划的单测（G6，任务书 12.2）。
 *
 * 钉住三件事，对应任务书的三条实施指令：
 * 1. **单一清理入口**：三条退出路径（下滑、双指收缩、系统返回）拿到的清理清单
 *    必须是**同一个实例**（引用相等），而不只是"内容碰巧一样"——
 *    只要有人按 reason 加一个 if 分支去"少清几项"，断言立刻变红；
 * 2. **清单内容**：必须覆盖页内（ImageViewer）与屏幕级（MediaViewerScreen）两段
 *    字段，且与"刻意保留"的字段互斥（防止把退出快照要延续的画面状态误加进清理，
 *    那会把"残影"换成"抽帧"）；
 * 3. **注册表行为**：同一份复位函数被三条路径依次调用；重复进出不累积注册；
 *    fire 期间注销不得抛异常（组件离开组合的真实时序）。
 *
 * 生产接线由 [ViewerExitWiringTest] 以源码文本方式钉住（这里够不到 Compose 运行时）。
 */
class ViewerExitCleanupTest {

    private val allReasons = listOf(
        ViewerExitReason.DISMISS_DRAG,
        ViewerExitReason.PINCH_IN,
        ViewerExitReason.SYSTEM_BACK,
        ViewerExitReason.AUTO_CLOSE
    )

    // ---------- 1. 清理清单与原因无关（"不能各留一份"） ----------

    @Test
    fun `三条退出路径的清理清单是同一个实例`() {
        val drag = ViewerExitCleanup.plan(ViewerExitReason.DISMISS_DRAG)
        val pinch = ViewerExitCleanup.plan(ViewerExitReason.PINCH_IN)
        val back = ViewerExitCleanup.plan(ViewerExitReason.SYSTEM_BACK)
        // 引用相等而不是 equals：任何"按 reason 复制一份清单再删两项"的写法都会变红
        assertSame("下滑与双指收缩的清理清单必须是同一实例", drag, pinch)
        assertSame("系统返回与双指收缩的清理清单必须是同一实例", pinch, back)
    }

    @Test
    fun `全部退出原因的清单都相同`() {
        val reference = ViewerExitCleanup.plan(ViewerExitReason.PINCH_IN)
        for (reason in allReasons) {
            assertSame("reason=$reason 拿到了另一份清理清单", reference, ViewerExitCleanup.plan(reason))
        }
    }

    // ---------- 2. 清单内容 ----------

    @Test
    fun `清单等于页内字段加屏幕级字段`() {
        val plan = ViewerExitCleanup.plan(ViewerExitReason.PINCH_IN)
        assertEquals(ViewerExitCleanup.PAGE_FIELDS + ViewerExitCleanup.SCREEN_FIELDS, plan)
    }

    @Test
    fun `页内清单覆盖退出防重入与三条在途动画`() {
        val plan = ViewerExitCleanup.plan(ViewerExitReason.PINCH_IN)
        // 退出防重入：重复收缩 / 转场中再来一次手势不得发起第二次退出
        assertTrue("缺 exitCommitted：退出可被重复发起", plan.contains("exitCommitted"))
        assertTrue(plan.contains("isFlyBackAnimating"))
        assertTrue(plan.contains("pendingSingleTap"))
        // 三条在途动画协程都要取消（任务书实施指令 2/3：中断与取消都要回收）
        assertTrue("在途动画协程回收不全: $plan",
            plan.containsAll(listOf("zoomJob", "settleJob", "flyBackJob")))
    }

    @Test
    fun `收缩路径不再维护内容层淡出状态`() {
        // G6 的动画时序统一：双指收缩原先自播一段内容层淡出（isPinchClosing +
        // pinchCloseJob + 两个 Animatable），播完把标志翻回 false 让图片重新不透明，
        // 随后窗口快照把这层图片淡到宿主页上 = 残影。这几项必须从清理清单里消失
        val plan = ViewerExitCleanup.plan(ViewerExitReason.PINCH_IN)
        assertFalse(plan.contains("isPinchClosing"))
        assertFalse(plan.contains("pinchCloseJob"))
        assertFalse(plan.contains("pinchCloseScaleAnimatable"))
    }

    @Test
    fun `屏幕级清单覆盖越界位移与档位回弹动画`() {
        val plan = ViewerExitCleanup.plan(ViewerExitReason.SYSTEM_BACK)
        // pager 的 graphicsLayer 平移量必须归零，否则退出快照是一幅"贴歪的图"
        assertTrue("缺 overscrollPx：退出快照会带横向位移", plan.contains("overscrollPx"))
        assertTrue(plan.contains("overscrollRawDrag"))
        assertTrue(plan.contains("overscrollSettleJob"))
        assertTrue(plan.contains("progressJob"))
        assertTrue(plan.contains("pagerGestureActive"))
    }

    @Test
    fun `要复位的字段与刻意保留的字段互斥`() {
        val reset = (ViewerExitCleanup.PAGE_FIELDS + ViewerExitCleanup.SCREEN_FIELDS).toSet()
        for (kept in ViewerExitCleanup.KEPT_FIELDS) {
            assertFalse("$kept 同时出现在复位清单与保留清单里（口径冲突）", reset.contains(kept))
        }
    }

    @Test
    fun `退出快照要延续的画面状态在保留清单里`() {
        val kept = ViewerExitCleanup.KEPT_FIELDS
        assertTrue(
            "画面延续字段不全：$kept",
            kept.containsAll(
                listOf(
                    "scale",
                    "offset",
                    "isAnimating",
                    "dismissDragY",
                    "dismissDragX",
                    "isDismissSettling",
                    "sideProgress",
                    "gestureBaseProgress",
                    "maskRevealed"
                )
            )
        )
    }

    // ---------- 3. 动画时序：三条路径都不得自播一段 ----------

    @Test
    fun `三条退出路径一律不自播内容层退出动画`() {
        // 任务书 12.1 实施指令 3"禁止各自维护一份动画时序"在退出侧的落点：
        // 转场只有窗口层那一段（viewer_fade_out_shrink），谁再加一段就变红
        for (reason in allReasons) {
            assertFalse(
                "$reason 不该由内容层自播一段退出动画",
                ViewerExitCleanup.playsContentSideTransition(reason)
            )
        }
    }

    // ---------- 4. 注册表 ----------

    @Test
    fun `同一份复位函数被三条退出路径依次调用`() {
        val registry = ViewerExitRegistry()
        val seen = mutableListOf<ViewerExitReason>()
        val cleanup: (ViewerExitReason) -> Unit = { seen += it }
        val unregister = registry.register(cleanup)

        registry.fire(ViewerExitReason.DISMISS_DRAG)
        registry.fire(ViewerExitReason.PINCH_IN)
        registry.fire(ViewerExitReason.SYSTEM_BACK)

        // 三条路径调用的清理函数就是注册进去的那一个（没有第二份实现）
        assertEquals(
            listOf(
                ViewerExitReason.DISMISS_DRAG,
                ViewerExitReason.PINCH_IN,
                ViewerExitReason.SYSTEM_BACK
            ),
            seen
        )
        unregister()
    }

    @Test
    fun `重复注册同一函数不累积`() {
        // 反证 G6 验收项"重复进出不得累积"：重组/重进若忘记注销，复位函数会被执行多份
        val registry = ViewerExitRegistry()
        val cleanup: (ViewerExitReason) -> Unit = {}
        val first = registry.register(cleanup)
        registry.register(cleanup)
        assertEquals(1, registry.registered)
        first()
    }

    @Test
    fun `注销后不再被调用`() {
        val registry = ViewerExitRegistry()
        var calls = 0
        val unregister = registry.register { calls++ }
        registry.fire(ViewerExitReason.PINCH_IN)
        assertEquals(1, calls)
        unregister()
        assertEquals(0, registry.registered)
        registry.fire(ViewerExitReason.PINCH_IN)
        assertEquals("注销后仍被调用（退出清理会写到已销毁的组件上）", 1, calls)
    }

    @Test
    fun `回调内部注销自己不得抛异常`() {
        // 真实时序：fire 发生在组件离开组合的过程中，复位回调可能顺手注销自己。
        // 局部变量不能用 lateinit，这里用可空引用 + 注册后再赋值
        val registry = ViewerExitRegistry()
        var firstCalls = 0
        var secondCalls = 0
        var unregisterFirst: (() -> Unit)? = null
        val first: (ViewerExitReason) -> Unit = {
            firstCalls++
            unregisterFirst?.invoke()
        }
        unregisterFirst = registry.register(first)
        val unregisterSecond = registry.register { secondCalls++ }

        registry.fire(ViewerExitReason.SYSTEM_BACK)

        assertEquals(1, firstCalls)
        assertEquals("注销发生在遍历中途也不能漏掉后一个回调", 1, secondCalls)
        assertEquals(1, registry.registered)
        unregisterSecond()
        assertEquals(0, registry.registered)
    }

    @Test
    fun `注册表为空时退出清理不抛异常`() {
        // 单图模式视频页、或 ImageViewer 尚未进入组合（退出走 BackHandler）时的真实状态
        val registry = ViewerExitRegistry()
        for (reason in allReasons) {
            registry.fire(reason)
        }
        assertEquals(0, registry.registered)
    }
}
