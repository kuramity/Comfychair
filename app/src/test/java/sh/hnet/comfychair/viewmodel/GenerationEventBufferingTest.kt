package sh.hnet.comfychair.viewmodel

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 钉住 F5（P1-3 的遗留）：复位之后迟到的**瞬时事件**（预览 / 清预览）不得再被投递或缓冲。
 *
 * 缺陷场景（2026-11-19 复核）：
 * 1. `dispatchEvent` 在 OkHttp 线程产生一条 `PreviewImage`，把「投递/入缓冲」post 到主线程；
 * 2. 同一线程紧接着 `completeGeneration → resetGenerationState`，它的缓冲清理也 post 到主线程；
 * 3. 若清理先入队、投递后入队，那张上一轮的预览会被重新写进 `bufferedPreviews`；
 * 4. 下次该 owner 注册 handler 时，`registerEventHandler` 把它当"缓冲预览"投出去 ——
 *    界面闪一帧上一轮的旧画面。
 *
 * 判定只看「当前 GenerationState 是否还在为同一个 owner 生成」，完成事件不适用
 *（它们本来就要活过复位，等 handler 来取）。
 */
class GenerationEventBufferingTest {

    @Test
    fun `同一次生成进行中的预览属于当前这一轮`() {
        assertTrue(
            "生成中且 owner 一致 = 正常预览，必须放行",
            isCurrentGenerationEvent(
                isGenerating = true,
                stateOwnerId = "TEXT_TO_IMAGE",
                eventOwnerId = "TEXT_TO_IMAGE",
            ),
        )
    }

    @Test
    fun `复位后的预览判为残留`() {
        // resetGenerationState 写回的是 GenerationState()：isGenerating=false、ownerId=null
        assertFalse(
            "复位后 state 已清空，上一轮的预览不能再投递/缓冲",
            isCurrentGenerationEvent(
                isGenerating = false,
                stateOwnerId = null,
                eventOwnerId = "TEXT_TO_IMAGE",
            ),
        )
    }

    @Test
    fun `owner 已被换成本轮另一个页面时旧预览判为残留`() {
        assertFalse(
            "state 的 owner 与事件定格的 owner 不一致：这条事件属于上一轮",
            isCurrentGenerationEvent(
                isGenerating = true,
                stateOwnerId = "IMAGE_TO_IMAGE",
                eventOwnerId = "TEXT_TO_IMAGE",
            ),
        )
    }

    @Test
    fun `事件没有 owner 时判为残留`() {
        // executingOwner 为 null = 事件产生时没有可信归属，缓冲下来也无处投递
        assertFalse(
            isCurrentGenerationEvent(
                isGenerating = true,
                stateOwnerId = "TEXT_TO_IMAGE",
                eventOwnerId = null,
            ),
        )
    }

    @Test
    fun `未在生成时任何 owner 的瞬时事件都不放行`() {
        listOf(null, "TEXT_TO_IMAGE", "IMAGE_TO_VIDEO").forEach { owner ->
            assertFalse(
                "isGenerating=false 时不应有预览：owner=$owner",
                isCurrentGenerationEvent(false, owner, owner),
            )
        }
    }
}
