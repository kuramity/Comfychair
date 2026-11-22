package sh.hnet.comfychair.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 钉住断线重连对账判定（[resolveReconcileOutcome]）的完整输入矩阵。
 *
 * 真实的收尾动作（dispatch 事件 / markFailed / reset）发生在 [GenerationViewModel]
 * 里（依赖 Android Context），JVM 单测够不着；这里钉的是唯一判定点 ——
 * 「三个信号（产物 / status_str / 是否在队列）→ 四种处置」的映射。
 * 回归背景：原实现只认"有产物"，断线期间失败的任务走静默 reset，无任何提示。
 */
class GenerationReconcilePolicyTest {

    @Test
    fun `有产物即完成，status_str 无关紧要`() {
        // 成功任务偶尔也会带 status_str（如 "success"）；产物优先
        assertEquals(
            ReconcileOutcome.COMPLETED,
            resolveReconcileOutcome(
                outputsPresent = true,
                historyStatusStr = "success",
                inQueue = false,
            )
        )
        assertEquals(
            ReconcileOutcome.COMPLETED,
            resolveReconcileOutcome(
                outputsPresent = true,
                historyStatusStr = "",
                inQueue = true,
            )
        )
    }

    @Test
    fun `history 显式报错时判失败，不再查队列`() {
        // 断线期间失败的任务：history 有条目（status_str=error）但 outputs 为空。
        // 修复前这类任务落到"不在队列 → 静默 reset"，用户得不到失败提示。
        assertEquals(
            ReconcileOutcome.FAILED_REPORTED,
            resolveReconcileOutcome(
                outputsPresent = false,
                historyStatusStr = "error",
                inQueue = false,
            )
        )
    }

    @Test
    fun `没产物没报错但在队列时继续等`() {
        // 排队等 GPU 的慢任务：history 里还没条目（或没 outputs），/queue 里还在
        assertEquals(
            ReconcileOutcome.STILL_RUNNING,
            resolveReconcileOutcome(
                outputsPresent = false,
                historyStatusStr = "",
                inQueue = true,
            )
        )
    }

    @Test
    fun `三信号全空判任务丢失`() {
        // 服务器重启 / 队列被清：history 无产物、status 非空字符串也不是 error、不在队列。
        // 修复前这里是静默 resetGenerationState()，现在要求按"任务丢失"提示收尾。
        assertEquals(
            ReconcileOutcome.LOST,
            resolveReconcileOutcome(
                outputsPresent = false,
                historyStatusStr = "",
                inQueue = false,
            )
        )
    }

    @Test
    fun `status_str 非 error 的其它值不影响队列侧判定`() {
        // history 条目存在但 status_str 是别的值（如 "success" 却没产物）时：
        // 交给队列信号裁决 —— 在队列 = 还在跑，不在 = 丢
        assertEquals(
            ReconcileOutcome.STILL_RUNNING,
            resolveReconcileOutcome(
                outputsPresent = false,
                historyStatusStr = "success",
                inQueue = true,
            )
        )
        assertEquals(
            ReconcileOutcome.LOST,
            resolveReconcileOutcome(
                outputsPresent = false,
                historyStatusStr = "success",
                inQueue = false,
            )
        )
    }
}
