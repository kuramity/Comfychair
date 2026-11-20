package sh.hnet.comfychair.taglibrary.state

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P2-20 的行为钉子：[PromptBridge] 的注册/注销必须是一对完整闭环。
 *
 * 修复前四个生成页面的 ViewModel 只在 init 里 register，onCleared 不注销 ——
 * 静态 map 一直钉着已销毁的 ViewModel（及其 uiState），页面来回切换就持续累积。
 * 这里直接验证桥接层的注销语义；「四个 ViewModel 是否真的调了」由
 * `P2LongTailWiringTest` 的源码级钉子兜住（JVM 单测起不了那几个 ViewModel）。
 */
class PromptBridgeUnregisterTest {

    /** 每个用例用独立 ownerId，避免单例里的状态互相污染 */
    private val ownerId = "P2_20_TEST_OWNER"

    private class FakeTarget(
        override val ownerId: String,
        private var prompt: String = ""
    ) : PromptBridge.Target {
        var lastWritten: String? = null
            private set

        override val workflowId: StateFlow<String> = MutableStateFlow("wf-1")
        override val workflowName: StateFlow<String> = MutableStateFlow("测试工作流")

        override fun readPrompt(channel: PromptBridge.Channel): String = prompt

        override fun writePrompt(channel: PromptBridge.Channel, text: String) {
            prompt = text
            lastWritten = text
        }
    }

    @After
    fun cleanup() {
        PromptBridge.unregister(ownerId)
        PromptBridge.setActive("")
    }

    @Test
    fun `注册后能查到目标，激活后可以直接写入提示词`() {
        val target = FakeTarget(ownerId)
        PromptBridge.register(target)
        PromptBridge.setActive(ownerId)

        assertSame(target, PromptBridge.targetOf(ownerId))
        assertSame(target, PromptBridge.activeTarget())
        assertTrue(PromptBridge.appendTag(PromptBridge.Channel.POSITIVE, "1girl", 1.2f))
        assertEquals("(1girl:1.2), ", target.lastWritten)
    }

    @Test
    fun `注销后目标消失，且活动归属被清空`() {
        PromptBridge.register(FakeTarget(ownerId))
        PromptBridge.setActive(ownerId)
        assertEquals(ownerId, PromptBridge.activeOwnerId.value)

        PromptBridge.unregister(ownerId)

        assertNull("注销后不应再能查到目标（否则静态 map 钉住已销毁的 ViewModel）", PromptBridge.targetOf(ownerId))
        assertEquals("注销当前活动目标时必须把活动归属清空", "", PromptBridge.activeOwnerId.value)
        assertNull(PromptBridge.activeTarget())
    }

    @Test
    fun `注销不存在的归属是幂等的`() {
        // onCleared 可能因为页面从未激活 / 重复销毁被调用多次，不能抛异常
        PromptBridge.unregister("NEVER_REGISTERED_OWNER")
        PromptBridge.unregister("NEVER_REGISTERED_OWNER")
    }

    @Test
    fun `注销别的归属不会误清当前活动目标`() {
        PromptBridge.register(FakeTarget(ownerId))
        PromptBridge.setActive(ownerId)

        PromptBridge.unregister("ANOTHER_OWNER")

        assertEquals(ownerId, PromptBridge.activeOwnerId.value)
        assertEquals(ownerId, PromptBridge.activeTarget()?.ownerId)
    }

    @Test
    fun `重新注册会替换旧实例（页面重建后写入新实例）`() {
        val old = FakeTarget(ownerId)
        PromptBridge.register(old)
        val fresh = FakeTarget(ownerId)
        PromptBridge.register(fresh)
        PromptBridge.setActive(ownerId)

        assertTrue(PromptBridge.appendTag(PromptBridge.Channel.NEGATIVE, "blurry", 1.0f))
        // 权重 1.0 是"中性"，不加括号（见 AppliedTag.toPromptToken）
        assertEquals("blurry, ", fresh.lastWritten)
        assertNull("旧实例不应再被写入", old.lastWritten)
    }
}