package sh.hnet.comfychair.viewmodel

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * P3-11：SettingsViewModel 的事件流在「没有收集者」时也不能挂起发布方。
 *
 * 回归点：`MutableSharedFlow()`（replay=0、extraBufferCapacity=0）在无订阅者时
 * `emit` 会永久挂起，把同一协程里的后续语句全部卡住（清缓存后发不出 RefreshNeeded、
 * 备份恢复停在中途）。现在改为「有缓冲 + DROP_OLDEST」，这里用真实 ViewModel 的内部
 * 流做回归：一旦有人改回默认构造，emit 会超时、用例失败。
 */
class SettingsEventFlowTest {

    @Test
    fun `没有收集者时发布事件立即返回`() = runBlocking {
        val viewModel = SettingsViewModel()
        assertEquals(0, viewModel.eventFlowForTest.subscriptionCount.value)

        // 超时即为回归：默认无缓冲的 SharedFlow 在这里会永远挂起
        withTimeout(2_000) {
            viewModel.eventFlowForTest.emit(SettingsEvent.ShowToast(1))
            viewModel.eventFlowForTest.emit(SettingsEvent.RefreshNeeded)
            viewModel.eventFlowForTest.emit(SettingsEvent.NavigateToLogin)
        }
    }

    @Test
    fun `有收集者时连续发布的事件按序送达`() = runBlocking {
        val viewModel = SettingsViewModel()
        val received = mutableListOf<SettingsEvent>()
        val job = launch { viewModel.eventFlowForTest.collect { received += it } }

        // 等订阅真正建立，避免第一条事件还没人接就被丢弃
        viewModel.eventFlowForTest.subscriptionCount.first { it > 0 }

        // 业务代码真实存在这种四连发（备份恢复：toast → toast → refresh → 回登录页）。
        // 缓冲若只有 1 条，同一协程里背靠背发的第一、二条会被 DROP_OLDEST 挤掉。
        val burst = listOf(
            SettingsEvent.ShowToast(1),
            SettingsEvent.ShowToast(2),
            SettingsEvent.RefreshNeeded,
            SettingsEvent.NavigateToLogin
        )
        withTimeout(2_000) {
            burst.forEach { viewModel.eventFlowForTest.emit(it) }
        }

        withTimeout(2_000) {
            while (received.size < burst.size) delay(10)
        }
        assertEquals(burst, received)

        job.cancel()
    }
}