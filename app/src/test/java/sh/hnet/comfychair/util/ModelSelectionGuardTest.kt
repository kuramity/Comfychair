package sh.hnet.comfychair.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.hnet.comfychair.connection.ModelCache

/**
 * 钉住 P1-7：空模型缓存不得清空用户已保存的模型 / LoRA 选择。
 *
 * 缺陷链路（2026-11-19 取证）：
 * 1. 断线/重连 → `ConnectionManager.invalidateAll()` → `_modelCache.value = ModelCache()`
 *    （`isLoaded=false`，所有列表为空）；
 * 2. 各生成页的 `modelCache.collect` 照常跑"校验已选模型"，空列表让所有选择变成空串；
 * 3. 同时把 `deferred*`（待恢复值）一并清空；
 * 4. 下一次保存把空选择写回磁盘 → 重启也找不回。
 *
 * 修法有两层，这里两层都钉住：
 * - [ValidationUtils.isUsableModelCache]：空/未加载的快照直接跳过整段更新；
 * - [ValidationUtils.validateModelSelection]：单个类别候选为空时保留原值。
 */
class ModelSelectionGuardTest {

    // ── 第一层：缓存快照是否可用 ──────────────────────────────────

    @Test
    fun `默认空缓存不可用`() {
        assertFalse(
            "invalidateAll() 写进去的就是这个对象：它绝不能用来校验选择",
            ValidationUtils.isUsableModelCache(ModelCache()),
        )
    }

    @Test
    fun `未加载完的缓存即使带列表也不可用`() {
        // 极端情况：列表已经填了一部分但 isLoaded 还是 false，说明这次加载没走完，
        // 用半份数据校验同样会误清选择。
        assertFalse(
            ValidationUtils.isUsableModelCache(
                ModelCache(checkpoints = listOf("a.safetensors"), isLoaded = false)
            ),
        )
    }

    @Test
    fun `已加载但所有模型列表都为空时不可用`() {
        // 服务器确实没有模型属于"可用快照"，但那种情况下清空选择也没有意义；
        // 口径与 MainContainerActivity.onResume 的 cacheEmpty 判断保持一致
        //（采样器/放大方法这类非模型列表不算数：它们不能证明 /object_info 真的到位）。
        assertFalse(
            ValidationUtils.isUsableModelCache(
                ModelCache(
                    isLoaded = true,
                    upscaleMethods = listOf("lanczos"),
                    samplers = listOf("euler"),
                )
            ),
        )
    }

    @Test
    fun `任一模型列表非空即可用`() {
        val usable = listOf(
            ModelCache(checkpoints = listOf("m.safetensors"), isLoaded = true),
            ModelCache(unets = listOf("u.safetensors"), isLoaded = true),
            ModelCache(vaes = listOf("v.safetensors"), isLoaded = true),
            ModelCache(clips = listOf("c.safetensors"), isLoaded = true),
            ModelCache(loras = listOf("l.safetensors"), isLoaded = true),
            ModelCache(textEncoders = listOf("t5.safetensors"), isLoaded = true),
            ModelCache(latentUpscaleModels = listOf("up.safetensors"), isLoaded = true),
            ModelCache(upscaleModels = listOf("4x.pth"), isLoaded = true),
        )

        usable.forEach { cache ->
            assertTrue("至少一类候选存在就说明快照可信：$cache", ValidationUtils.isUsableModelCache(cache))
        }
    }

    // ── 第二层：单类别候选为空时保留已选值 ────────────────────────

    @Test
    fun `候选列表为空时保留已选模型`() {
        assertEquals(
            "空候选只说明这一类暂不可用，不是'用户的选择无效'",
            "flux-dev.safetensors",
            ValidationUtils.validateModelSelection("flux-dev.safetensors", emptyList()),
        )
    }

    @Test
    fun `候选列表为空且未选择时仍为空`() {
        assertEquals(
            "没有已选值就不会凭空造一个出来",
            "",
            ValidationUtils.validateModelSelection("", emptyList()),
        )
    }

    @Test
    fun `候选列表非空且包含已选值时保留`() {
        assertEquals(
            "normal.safetensors",
            ValidationUtils.validateModelSelection("normal.safetensors", listOf("normal.safetensors", "other")),
        )
    }

    @Test
    fun `候选列表非空且不含已选值时清空`() {
        // 服务器上真的没有了这个模型 —— 这才是应该清空的场景。
        assertEquals(
            "",
            ValidationUtils.validateModelSelection("deleted.safetensors", listOf("normal.safetensors")),
        )
    }

    @Test
    fun `断线清空缓存时整组选择保持原样`() {
        // 复刻缺陷现场：一组已选模型 + 断线后的空缓存快照。
        // 逐个走过校验后必须原样返回，否则这些空串会被下一次 saveConfiguration 落盘。
        val selections = mapOf(
            "checkpoint" to "flux-dev.safetensors",
            "vae" to "ae.safetensors",
            "clip" to "t5xxl_fp16.safetensors",
            "lora" to "detail.safetensors",
        )
        val clearedCache = ModelCache() // invalidateAll() 的产物：全空、isLoaded=false

        assertFalse("这一步是前置守卫", ValidationUtils.isUsableModelCache(clearedCache))

        selections.forEach { (name, selected) ->
            assertEquals(
                "$name 的选择不能因为缓存为空被清掉",
                selected,
                ValidationUtils.validateModelSelection(selected, emptyList()),
            )
        }
    }
}
