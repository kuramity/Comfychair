package sh.hnet.comfychair.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.hnet.comfychair.model.LoraSelection
import sh.hnet.comfychair.lora.LoraChainManager

/**
 * LoRA 链条编辑的纯逻辑（新增 / 改名 / 列表刷新后的过滤）。
 *
 * 钉住 400 修复里最容易静默回退的两行（commit 428a392）：
 * - [LoraChainManager.addLora] 新增项的 name 必须是**空串**（下拉里的「（不使用 LoRA）」）。
 *   旧实现取 `availableLoras.first()`：用户点了"添加"什么都没选，就已经把一个自己没要的
 *   LoRA 悄悄提交上去了；若那个第一项恰好是字面量 `"None"`，就是那条 400。
 * - [LoraChainManager.updateLoraName] 必须走 [LoraSelection.normalizeName] 归一化。
 */
class LoraChainManagerTest {

    private val available = listOf(
        "a.safetensors",
        "image/krea2/none_mix.safetensors"
    )

    // ── addLora ──────────────────────────────────────────────────────────

    @Test
    fun `新增项的 name 是空串 而不是列表里的第一个 LoRA`() {
        val chain = LoraChainManager.addLora(emptyList(), available)

        assertEquals(1, chain.size)
        assertEquals(
            "新增行必须是「（不使用 LoRA）」的空名，由用户主动挑一项才会被注入",
            "",
            chain[0].name
        )
        assertNotEquals(
            "不能悄悄替用户选中列表第一项（旧实现行为；第一项是 None 时直接 400）",
            available.first(),
            chain[0].name
        )
        assertFalse("新项不能命中任何真实 LoRA 名", chain[0].name in available)
        assertEquals(
            LoraSelection.DEFAULT_STRENGTH.toDouble(),
            chain[0].strength.toDouble(),
            1e-6
        )
    }

    @Test
    fun `availableLoras 为空时不崩溃且不新增项`() {
        val chain = listOf(LoraSelection("a.safetensors"))

        val result = LoraChainManager.addLora(chain, emptyList())

        assertEquals("服务器没报出任何 LoRA 时按原链条返回，不抛异常", chain, result)
    }

    @Test
    fun `达到链条上限后不再新增`() {
        val full = (0 until LoraSelection.MAX_CHAIN_LENGTH).map { LoraSelection("$it.safetensors") }
        assertEquals(full, LoraChainManager.addLora(full, available))

        val over = full + LoraSelection("extra.safetensors")
        assertEquals("已超上限的链条原样返回，不裁剪也不追加", over, LoraChainManager.addLora(over, available))
    }

    @Test
    fun `连续添加会填满链条并停在上限`() {
        var chain = emptyList<LoraSelection>()
        repeat(LoraSelection.MAX_CHAIN_LENGTH + 3) {
            chain = LoraChainManager.addLora(chain, available)
        }

        assertEquals(LoraSelection.MAX_CHAIN_LENGTH, chain.size)
        assertTrue("每一行都该是待用户选择的空名", chain.all { it.name.isEmpty() })
    }

    @Test
    fun `添加不修改传入的链条`() {
        val chain = listOf(LoraSelection("a.safetensors"))
        LoraChainManager.addLora(chain, available)
        assertEquals(1, chain.size)
        assertEquals("a.safetensors", chain[0].name)
    }

    // ── updateLoraName：归一化 ───────────────────────────────────────────

    @Test
    fun `改名走归一化 选回不使用会落成空串`() {
        val chain = listOf(LoraSelection("a.safetensors"), LoraSelection("b.safetensors"))

        // 菜单「（不使用 LoRA）」传入空串；历史存档/外部输入可能传 "None"
        assertEquals("", LoraChainManager.updateLoraName(chain, 0, "")[0].name)
        assertEquals("", LoraChainManager.updateLoraName(chain, 0, "None")[0].name)
        assertEquals("", LoraChainManager.updateLoraName(chain, 1, "  none  ")[1].name)
        assertEquals(
            "真实名字不能被归一化改掉",
            "image/krea2/none_mix.safetensors",
            LoraChainManager.updateLoraName(chain, 0, "image/krea2/none_mix.safetensors")[0].name
        )
        assertEquals("只改目标行", "b.safetensors", LoraChainManager.updateLoraName(chain, 0, "None")[1].name)
    }

    @Test
    fun `越界下标改不动任何东西`() {
        val chain = listOf(LoraSelection("a.safetensors"))
        assertEquals(chain, LoraChainManager.updateLoraName(chain, 9, "b.safetensors"))
        assertEquals(chain, LoraChainManager.updateLoraName(chain, -1, "b.safetensors"))
    }

    // ── updateLoraStrength：范围收口 ─────────────────────────────────────

    @Test
    fun `大权重不会被夹回旧上限`() {
        // 滑块范围放到 0~10 后，界面上能设到的值就必须能原样穿过这一层：
        // 这里若还按 0~2 夹，界面显示 10、提交却是 2（静默失真）
        val chain = listOf(LoraSelection("a.safetensors"))

        assertEquals(10.0f, LoraChainManager.updateLoraStrength(chain, 0, 10.0f)[0].strength, 1e-6f)
        assertEquals(5.5f, LoraChainManager.updateLoraStrength(chain, 0, 5.5f)[0].strength, 1e-6f)
        assertEquals("手动输入的小数要保留", 3.75f, LoraChainManager.updateLoraStrength(chain, 0, 3.75f)[0].strength, 1e-6f)
    }

    @Test
    fun `越界权重仍被夹进新范围`() {
        val chain = listOf(LoraSelection("a.safetensors"))

        assertEquals(10.0f, LoraChainManager.updateLoraStrength(chain, 0, 99.0f)[0].strength, 1e-6f)
        assertEquals(0.0f, LoraChainManager.updateLoraStrength(chain, 0, -5.0f)[0].strength, 1e-6f)
    }

    // ── filterUnavailable：刷新后保留空名草稿 ───────────────────────────

    @Test
    fun `服务器列表刷新后空名草稿行不会被删掉`() {
        val chain = listOf(
            LoraSelection(""),                       // 用户刚加、还没挑
            LoraSelection("a.safetensors"),          // 仍在服务器列表里
            LoraSelection("gone.safetensors")        // 已从服务器删掉
        )

        val filtered = LoraChainManager.filterUnavailable(chain, available)

        assertEquals(
            "空名草稿行被顺手删掉的话，用户刚加的那行会在列表刷新后凭空消失",
            listOf("", "a.safetensors"),
            filtered.map { it.name }
        )
    }
}
