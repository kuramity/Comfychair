package sh.hnet.comfychair.ui.components

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P2-19 的纯逻辑钉子：[ParticleFrameLoopGate] 的状态迁移。
 *
 * 修复前帧循环是 `while (true) { withFrameNanos { } }`，没有活跃特效时也每帧唤醒；
 * 现在空闲即退出，靠这个开关判断"要不要重新拉起"。状态机写错的表现是：
 * - 一直返回 false → 空闲退出后再也画不出来（点击无反应）；
 * - 一直返回 true → 每次点击都白重启一轮循环（原来的空转问题部分回归）。
 */
class ParticleFrameLoopGateTest {

    @Test
    fun `初始状态是空闲`() {
        assertFalse(ParticleFrameLoopGate().isRunning)
    }

    @Test
    fun `空闲时第一次请求需要重启循环`() {
        val gate = ParticleFrameLoopGate()
        assertTrue("循环没在跑，必须让调用方递增令牌把它拉起来", gate.requestFrame())
        assertTrue("请求之后应记录为运行中", gate.isRunning)
    }

    @Test
    fun `循环运行中不重复重启`() {
        val gate = ParticleFrameLoopGate()
        gate.requestFrame()
        // 连续点击（特效还在飞）不应每次都重启循环
        assertFalse(gate.requestFrame())
        assertFalse(gate.requestFrame())
    }

    @Test
    fun `空闲退出后可以再次重启`() {
        val gate = ParticleFrameLoopGate()
        gate.requestFrame()
        gate.onLoopExited()
        assertFalse(gate.isRunning)
        assertTrue("空闲退出后的一次点击必须能重新拉起循环", gate.requestFrame())
    }

    @Test
    fun `重复调用退出是幂等的`() {
        val gate = ParticleFrameLoopGate()
        gate.requestFrame()
        gate.onLoopExited()
        gate.onLoopExited()
        assertFalse(gate.isRunning)
        assertTrue(gate.requestFrame())
    }
}