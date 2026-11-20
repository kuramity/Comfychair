package sh.hnet.comfychair.connection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * G7 / G8 的模型列表恢复判定单测（任务书 12.3 / 12.4 点名要抽的两个纯函数）。
 *
 * 覆盖任务书指定的四条场景：
 * 1. **全空需重拉**（G7 现象：三类型全空）；
 * 2. **部分有值且加载中不重拉**（真在飞时不叠加请求）；
 * 3. **失败态可见**（单类失败不被整体成功掩盖，且决策给重拉机会）；
 * 4. **已有数据不被清**（健康态一律 SKIP，重拉路径也不清空——由 [ModelCache] 的
 *    copy 语义与 [markCategoriesFailed] 的「只改状态不动列表」共同保证）。
 */
class ModelListRecoveryTest {

    // ── ① 单类终态与失败原因（G8 指令 3：节点缺失 / 字段缺失 / 请求失败）──────

    @Test
    fun `请求失败三类都落 FAILED 且原因为请求失败`() {
        // /object_info 整体挂了：候选数、字段形态都不必再看，直接判请求失败
        val d = diagnoseCategory(
            requestFailed = true,
            inputFieldExists = false,
            fieldHasOptions = false,
            optionCount = 0
        )
        assertEquals(CategoryLoadState.FAILED, d.state)
        assertEquals(CategoryFailureReason.REQUEST_FAILED, d.reason)
    }

    @Test
    fun `UNET 来源节点缺失判为失败而非空`() {
        // 关键区分点：E6「UNET 空、VAE/CLIP 正常」里，若服务器根本没有声明 unet_name 的
        // 加载器节点，这是**提取失败**（可重试、要显示原因），不能被当成「服务器真没模型」
        val d = diagnoseCategory(
            requestFailed = false,
            inputFieldExists = false,
            fieldHasOptions = false,
            optionCount = 0
        )
        assertEquals(CategoryLoadState.FAILED, d.state)
        assertEquals(CategoryFailureReason.NODE_MISSING, d.reason)
    }

    @Test
    fun `字段被声明但提不出COMBO候选判为字段缺失`() {
        val d = diagnoseCategory(
            requestFailed = false,
            inputFieldExists = true,
            fieldHasOptions = false,
            optionCount = 0
        )
        assertEquals(CategoryLoadState.FAILED, d.state)
        assertEquals(CategoryFailureReason.FIELD_MISSING, d.reason)
    }

    @Test
    fun `字段形态正常但候选为空判为服务器真没模型`() {
        // EMPTY_OK 不是失败：重试也拉不出东西，UI 必须与 FAILED 严格区分（不给重试）
        val d = diagnoseCategory(
            requestFailed = false,
            inputFieldExists = true,
            fieldHasOptions = true,
            optionCount = 0
        )
        assertEquals(CategoryLoadState.EMPTY_OK, d.state)
        assertEquals(CategoryFailureReason.NONE, d.reason)
    }

    @Test
    fun `有候选即为OK`() {
        val d = diagnoseCategory(
            requestFailed = false,
            inputFieldExists = true,
            fieldHasOptions = true,
            optionCount = 42
        )
        assertEquals(CategoryLoadState.OK, d.state)
        assertEquals(CategoryFailureReason.NONE, d.reason)
    }

    // ── ② 全部终态判定（G8 指令 2：加载态仅在全部类型进入终态后清除）──────────

    @Test
    fun `任一类别PENDING就不算全部终态`() {
        assertFalse(allCoreCategoriesTerminal(CategoryLoadState.PENDING, CategoryLoadState.OK, CategoryLoadState.OK))
        assertFalse(allCoreCategoriesTerminal(CategoryLoadState.OK, CategoryLoadState.PENDING, CategoryLoadState.OK))
        assertFalse(allCoreCategoriesTerminal(CategoryLoadState.OK, CategoryLoadState.OK, CategoryLoadState.PENDING))
    }

    @Test
    fun `失败与真没模型都算终态`() {
        // 终态不等于成功：FAILED / EMPTY_OK 都已收敛，不该再让界面停在「正在加载」
        assertTrue(allCoreCategoriesTerminal(CategoryLoadState.FAILED, CategoryLoadState.EMPTY_OK, CategoryLoadState.OK))
    }

    // ── ③ 重拉决策（G7 指令 1/3）──────────────────────────────────────────

    @Test
    fun `全空需重拉`() {
        // 场景 1：后台返回后三类都没数据（从未加载成功 / 被清空），必须重拉
        val decision = decideModelListReload(
            connectionSettled = true,
            inFlight = false,
            unetState = CategoryLoadState.EMPTY_OK,
            vaeState = CategoryLoadState.EMPTY_OK,
            clipState = CategoryLoadState.EMPTY_OK,
            hasAnyCoreData = false
        )
        assertEquals(ModelListReloadDecision.RELOAD, decision)
    }

    @Test
    fun `部分有值且加载中不重拉`() {
        // 场景 2：UNET 还没到、VAE/CLIP 已有值，但确实有一个 /object_info 在飞 ——
        // 不叠加请求（8MB 的重复拉取没有收益，还会互相覆盖）
        val decision = decideModelListReload(
            connectionSettled = true,
            inFlight = true,
            unetState = CategoryLoadState.PENDING,
            vaeState = CategoryLoadState.OK,
            clipState = CategoryLoadState.OK,
            hasAnyCoreData = true
        )
        assertEquals(ModelListReloadDecision.SKIP_IN_FLIGHT, decision)
    }

    @Test
    fun `僵尸加载态且无在飞请求必须重拉救回`() {
        // E7「UNET 长期显示正在加载不收敛」的收口：类别停在 PENDING 但根本没有请求在飞
        // （回调没回来 / 磁盘预填把 isLoading 顶回去了），此时必须允许重拉
        val decision = decideModelListReload(
            connectionSettled = true,
            inFlight = false,
            unetState = CategoryLoadState.PENDING,
            vaeState = CategoryLoadState.OK,
            clipState = CategoryLoadState.OK,
            hasAnyCoreData = true
        )
        assertEquals(ModelListReloadDecision.RELOAD, decision)
    }

    @Test
    fun `单类失败可见且给重拉机会`() {
        // 场景 3：G8 的 E6 —— UNET 提取失败（节点缺失），VAE/CLIP 正常。
        // 决策必须给出重拉，而不是因为「整体已加载且有数据」就跳过
        val decision = decideModelListReload(
            connectionSettled = true,
            inFlight = false,
            unetState = CategoryLoadState.FAILED,
            vaeState = CategoryLoadState.OK,
            clipState = CategoryLoadState.OK,
            hasAnyCoreData = true
        )
        assertEquals(ModelListReloadDecision.RELOAD, decision)

        // 且失败态在 UI 侧确实可见（不是被整体成功掩盖的沉默空框）
        assertEquals(
            ModelListUiState.FAILED,
            resolveModelListUiState(state = CategoryLoadState.FAILED, optionCount = 0)
        )
    }

    @Test
    fun `已有数据且健康时不重拉`() {
        // 场景 4：三核心类都拿到候选 —— 必检什么都不做。
        // 「不重拉」正是「已有数据不被清空」的第一道保障（G7 指令 1 红线）
        val decision = decideModelListReload(
            connectionSettled = true,
            inFlight = false,
            unetState = CategoryLoadState.OK,
            vaeState = CategoryLoadState.OK,
            clipState = CategoryLoadState.OK,
            hasAnyCoreData = true
        )
        assertEquals(ModelListReloadDecision.SKIP_HEALTHY, decision)
    }

    @Test
    fun `连接未收敛时不发起重拉`() {
        // G7 指令 3：不得在过渡态下发起而再次被吞 —— 哪怕此刻列表全空也要等收敛
        val decision = decideModelListReload(
            connectionSettled = false,
            inFlight = false,
            unetState = CategoryLoadState.PENDING,
            vaeState = CategoryLoadState.PENDING,
            clipState = CategoryLoadState.PENDING,
            hasAnyCoreData = false
        )
        assertEquals(ModelListReloadDecision.SKIP_TRANSIENT_CONNECTION, decision)
    }

    // ── ④ 重拉/失败路径不得清空已有数据（G7 指令 1/4）───────────────────────

    @Test
    fun `单类失败只改状态不动列表`() {
        // ConnectionManager.markCategoriesFailed 用的就是这个 copy 形态：
        // 请求失败时列表原样保留（磁盘预填/上次成功的数据还能显示），只有状态与错误被写
        val before = ModelCache(
            unets = listOf("dreamshaper_8.safetensors"),
            vaes = listOf("qwen_image_vae.safetensors"),
            clips = listOf("qwen_3_06b_base.safetensors"),
            isLoaded = true,
            isLoading = true
        )
        val after = before.copy(
            isLoading = false,
            lastError = "Failed to fetch server data",
            unetState = CategoryLoadState.FAILED,
            vaeState = CategoryLoadState.FAILED,
            clipState = CategoryLoadState.FAILED,
            unetFailure = CategoryFailureReason.REQUEST_FAILED,
            vaeFailure = CategoryFailureReason.REQUEST_FAILED,
            clipFailure = CategoryFailureReason.REQUEST_FAILED
        )
        assertEquals(before.unets, after.unets)
        assertEquals(before.vaes, after.vaes)
        assertEquals(before.clips, after.clips)
        assertFalse("失败态不得留在加载中", after.isLoading)
        assertEquals(CategoryLoadState.FAILED, after.unetState)
    }

    @Test
    fun `重拉发起时只置加载态不清列表`() {
        // fetchServerData 开头的 copy(isLoading=true, 三类=PENDING) 形态：
        // 已有候选必须原样留着，界面继续显示旧数据 + 加载语义，绝不出现「点了刷新先白屏」
        val before = ModelCache(
            unets = listOf("a.safetensors"),
            vaes = listOf("v.safetensors"),
            clips = listOf("c.safetensors"),
            unetState = CategoryLoadState.OK,
            vaeState = CategoryLoadState.OK,
            clipState = CategoryLoadState.OK,
            isLoaded = true
        )
        val after = before.copy(
            isLoading = true,
            lastError = null,
            unetState = CategoryLoadState.PENDING,
            vaeState = CategoryLoadState.PENDING,
            clipState = CategoryLoadState.PENDING
        )
        assertEquals(before.unets, after.unets)
        assertEquals(before.vaes, after.vaes)
        assertEquals(before.clips, after.clips)
        assertTrue(after.isLoaded)
    }

    @Test
    fun `核心列表全空的口径与hasAnyCoreModel一致`() {
        assertFalse(
            ModelCache(checkpoints = listOf("x"), samplers = listOf("euler")).hasAnyCoreModel()
        )
        assertTrue(ModelCache(vaes = listOf("v")).hasAnyCoreModel())
    }

    // ── ⑤ 默认值不得是「已终态」（否则僵尸加载判定会瞎）──────────────────────

    @Test
    fun `默认缓存三类都是未完成态`() {
        val cache = ModelCache()
        assertEquals(CategoryLoadState.PENDING, cache.unetState)
        assertEquals(CategoryLoadState.PENDING, cache.vaeState)
        assertEquals(CategoryLoadState.PENDING, cache.clipState)
        assertFalse(cache.stateOf(CoreCategory.UNET).isTerminal())
    }
}
