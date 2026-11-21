package sh.hnet.comfychair.viewmodel.base

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.hnet.comfychair.cache.MediaStateHolder
import sh.hnet.comfychair.lora.LoraChainManager
import sh.hnet.comfychair.model.LoraSelection
import sh.hnet.comfychair.util.DebugLogger
import sh.hnet.comfychair.viewmodel.TextToImageUiState

/**
 * [BaseGenerationViewModel] 上移/抽取出来的共享逻辑的行为钉子：
 *
 * 1. [PlayingVideoRegistry]：同键短路、换键先注销旧键、clear 幂等（原先是两个视频
 *    ViewModel 各自持有的字段 + 两个私有方法，行为必须逐字等价）；
 * 2. `updateLoraChain`：无变化短路（不写状态、不保存）、有变化先写状态再保存、
 *    计算函数拿到的是**同一份状态快照**（不得二次读 `_uiState.value`）。
 *
 * `stopListening` 无法在此覆盖：它需要真实的 `GenerationViewModel`，而后者字段级
 * 构造了 `Handler(Looper.getMainLooper())`（GenerationViewModel.kt:282），JVM 单测
 * 无 Robolectric/Mockito 时无法实例化。该函数的等价性以逐行 diff 比对为准。
 */
class BaseGenerationViewModelTest {

    /** 只做测试入口的最小 ViewModel：不初始化依赖，直接用基类的 _uiState / updateLoraChain。 */
    private class TestVm : BaseGenerationViewModel<TextToImageUiState, Unit>() {

        override val initialState = TextToImageUiState()
        override val generationOwnerId = "TEST_OWNER"

        override fun onInitialize() = Unit

        override fun hasValidConfiguration() = true

        override fun withLoraChain(state: TextToImageUiState, chain: List<LoraSelection>) =
            state.copy(loraChain = chain)

        var saveCount = 0
        var chainObservedAtSave: List<LoraSelection>? = null

        fun update(compute: (TextToImageUiState) -> List<LoraSelection>) =
            updateLoraChain(
                save = {
                    saveCount++
                    chainObservedAtSave = _uiState.value.loraChain
                },
                compute = compute
            )

        fun snapshot(): TextToImageUiState = _uiState.value

        fun replace(state: TextToImageUiState) {
            _uiState.value = state
        }
    }

    private val vm = TestVm()

    /** 本类创建过的登记器：@After 统一 clear，避免残留登记污染其它用例。 */
    private val registries = mutableListOf<PlayingVideoRegistry>()

    private fun newRegistry(): PlayingVideoRegistry = PlayingVideoRegistry().also { registries += it }

    @After
    fun tearDown() {
        registries.forEach { it.clear() }
        registries.clear()
        // 恢复日志默认关闭，避免影响其它用例（日志是全局单例）
        DebugLogger.setEnabled(false)
    }

    // ============================================================
    // PlayingVideoRegistry
    // ============================================================

    /**
     * 打开日志捕获并清空历史条目。
     *
     * 登记器的「短路」语义无法从底表观察：MediaStateHolder 用 Set 存 baseFilename，
     * 去掉短路后的 unregister+register 净效果与短路相同。登记/注销各自会写一条
     * DebugLogger 日志，因此用日志条数与顺序作为可区分证据。
     */
    private fun enableLogCapture() {
        DebugLogger.setEnabled(false)
        DebugLogger.setEnabled(true)
    }

    private fun registerLogCount(): Int =
        DebugLogger.getEntries().count { it.message.startsWith("registerPlayingVideo:") }

    private fun unregisterLogCount(): Int =
        DebugLogger.getEntries().count { it.message.startsWith("unregisterPlayingVideo:") }

    /** 反射读登记器的私有 key（项目既有单测也用反射观察私有状态，见 LoraModelListFilterTest）。 */
    private fun registryKey(registry: PlayingVideoRegistry): MediaStateHolder.MediaKey? {
        val field = PlayingVideoRegistry::class.java.getDeclaredField("key")
        field.isAccessible = true
        return field.get(registry) as MediaStateHolder.MediaKey?
    }

    /** 反射读 MediaStateHolder 里真正生效的登记集合（验证副作用而不只看登记器自己的字段）。 */
    @Suppress("UNCHECKED_CAST")
    private fun registeredBaseNames(): MutableSet<String> {
        val field = MediaStateHolder::class.java.getDeclaredField("playingVideoBaseNames")
        field.isAccessible = true
        return field.get(MediaStateHolder) as MutableSet<String>
    }

    @Test
    fun `同键重复登记不得重复注销与登记`() {
        val registry = newRegistry()
        val key = MediaStateHolder.MediaKey.TtvVideo("p1")

        enableLogCapture()
        registry.mark(key)
        assertEquals("第一次登记必须产生一条登记日志", 1, registerLogCount())
        assertEquals(0, unregisterLogCount())
        val versionAfterFirst = DebugLogger.currentVersion()

        registry.mark(key)

        // 短路的核心证据：第二次 mark 一次注销/登记日志都不产生。
        // 底表是 Set，看集合只能看到"仍是 1 项"（去掉短路也一样），必须看副作用日志。
        assertEquals("同键重复登记必须整体短路", versionAfterFirst, DebugLogger.currentVersion())
        assertEquals(1, registerLogCount())
        assertEquals(0, unregisterLogCount())
        assertSame(key, registryKey(registry))
        assertTrue(registeredBaseNames().contains(key.baseFilename))
    }

    @Test
    fun `换键时先注销旧键再登记新键`() {
        val registry = newRegistry()
        val first = MediaStateHolder.MediaKey.TtvVideo("p1")
        val second = MediaStateHolder.MediaKey.ItvVideo("p2")

        enableLogCapture()
        registry.mark(first)
        registry.mark(second)

        assertSame(second, registryKey(registry))
        val names = registeredBaseNames()
        assertFalse("旧键必须注销", names.contains(first.baseFilename))
        assertTrue("新键必须登记", names.contains(second.baseFilename))

        // 顺序证据：旧键的注销日志必须早于新键的登记日志
        val messages = DebugLogger.getEntries().map { it.message }
        val unregisterIdx = messages.indexOfFirst { it.startsWith("unregisterPlayingVideo:") }
        val registerNewIdx = messages.indexOfFirst {
            it.startsWith("registerPlayingVideo:") && it.contains(second.baseFilename)
        }
        assertTrue("必须有旧键注销日志", unregisterIdx >= 0)
        assertTrue("必须有新键登记日志", registerNewIdx >= 0)
        assertTrue("必须先注销旧键再登记新键", unregisterIdx < registerNewIdx)
    }

    @Test
    fun `clear 注销登记且可重复调用`() {
        val registry = newRegistry()
        val key = MediaStateHolder.MediaKey.TtvVideo("p3")

        registry.mark(key)
        registry.clear()

        assertNull(registryKey(registry))
        assertFalse(registeredBaseNames().contains(key.baseFilename))
        // 幂等：再次 clear 不得抛异常、不得影响其他键
        registry.clear()
        assertNull(registryKey(registry))
    }

    // ============================================================
    // updateLoraChain
    // ============================================================

    @Test
    fun `无变化时不写状态也不保存`() {
        val before = vm.snapshot()

        vm.update { it.loraChain }

        assertSame("无变化必须返回原状态实例", before, vm.snapshot())
        assertEquals("无变化不得调用保存", 0, vm.saveCount)
    }

    @Test
    fun `有变化时先写状态再保存`() {
        val newChain = listOf(LoraSelection("a", 0.5f))

        vm.update { newChain }

        assertEquals(newChain, vm.snapshot().loraChain)
        assertEquals("有变化必须保存一次", 1, vm.saveCount)
        assertSame("保存时状态里必须已经是新链", newChain, vm.chainObservedAtSave)
    }

    @Test
    fun `计算期间的状态写入不得影响本次更新所用快照`() {
        val originalChain = listOf(LoraSelection("orig", 0.4f))
        vm.replace(vm.snapshot().copy(loraChain = originalChain, negativePrompt = "进入时的值"))
        val newChain = listOf(LoraSelection("new", 0.6f))

        vm.update { state ->
            assertEquals("计算函数必须拿到进入时的快照", "进入时的值", state.negativePrompt)
            // 模拟计算期间状态被改：若实现二次读 _uiState.value 再复制，写回会基于这份新状态
            vm.replace(vm.snapshot().copy(negativePrompt = "计算中写入的新值"))
            newChain
        }

        assertEquals("写回必须基于进入时那份快照", "进入时的值", vm.snapshot().negativePrompt)
        assertEquals(newChain, vm.snapshot().loraChain)
        assertEquals(1, vm.saveCount)
    }

    @Test
    fun `真实 LoraChainManager 在无候选项时短路`() {
        // 默认 TextToImageUiState 的 availableLoras 为空 → addLora 原样返回同一实例
        assertTrue(vm.snapshot().availableLoras.isEmpty())

        vm.update { LoraChainManager.addLora(it.loraChain, it.availableLoras) }

        assertEquals("无候选项时不得保存", 0, vm.saveCount)
        assertTrue(vm.snapshot().loraChain.isEmpty())
    }

    @Test
    fun `真实 LoraChainManager 在有空位时追加并保存`() {
        vm.replace(vm.snapshot().copy(availableLoras = listOf("lora-a")))

        vm.update { LoraChainManager.addLora(it.loraChain, it.availableLoras) }

        assertEquals(1, vm.saveCount)
        assertEquals("必须真的追加一项", 1, vm.snapshot().loraChain.size)
        assertSame(vm.snapshot().loraChain, vm.chainObservedAtSave)
    }
}