package sh.hnet.comfychair.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「正面提示词跟随工作流」+「锁开关」的策略测试。
 *
 * 这段判断本来是散在四个 ViewModel 方法里的 if/else，抽成纯函数是为了能在这里逐条钉死语义：
 *
 * **锁上时不保存**（用户原话：锁上时切换工作流"完全不动正面提示词，不保存也不载入"）。
 * 由此有一条必须被接受的后果：**锁定期间手打的词不会落盘**，杀进程后会丢。
 * 这是"完全解耦"语义的直接推论，不是 bug——锁的用途正是"这一份词我要跨工作流一直用"。
 *
 * 另含 ITI 的双流隔离判据（[PositivePromptPolicy.resolveOnSave] 的 `isActiveMode`）：
 * 图生图只有一个正面输入框却有两套工作流记录，保存时不判断当前模式就会互相污染。
 */
class PositivePromptPolicyTest {

    // ---------------- ① 载入：跟随工作流 ----------------

    @Test
    fun `未锁定时载入该工作流已保存的词`() {
        assertEquals(
            "saved words",
            PositivePromptPolicy.resolveOnLoad(
                locked = false, current = "旧的界面值", saved = "saved words", default = "default words"
            ),
        )
    }

    @Test
    fun `未锁定且没存过时回退到工作流默认词`() {
        assertEquals(
            "default words",
            PositivePromptPolicy.resolveOnLoad(
                locked = false, current = "旧的界面值", saved = null, default = "default words"
            ),
        )
    }

    @Test
    fun `未锁定且既没存过也没默认词时为空串`() {
        assertEquals(
            "",
            PositivePromptPolicy.resolveOnLoad(locked = false, current = "旧的界面值", saved = null, default = null),
        )
        assertEquals(
            "",
            PositivePromptPolicy.resolveOnLoad(locked = false, current = "旧的界面值", saved = null, default = ""),
        )
    }

    @Test
    fun `存过空串时用空串而不是回退默认词`() {
        // 空串 = 用户明确清空过这个工作流的词，不该被默认词顶回来
        assertEquals(
            "",
            PositivePromptPolicy.resolveOnLoad(
                locked = false, current = "旧的界面值", saved = "", default = "default words"
            ),
        )
    }

    // ---------------- ② 载入：锁定 ----------------

    @Test
    fun `锁定时载入不改动界面上的词`() {
        assertEquals(
            "我正在打的词",
            PositivePromptPolicy.resolveOnLoad(
                locked = true, current = "我正在打的词", saved = "该工作流存的词", default = "default words"
            ),
        )
    }

    @Test
    fun `锁定时界面为空就保持为空_不载入默认词`() {
        // 锁定语义是"完全不动"：连"空着就补个默认词"这种善意补齐也不做，
        // 否则用户会看到自己清空的输入框莫名冒出一段字
        assertEquals(
            "",
            PositivePromptPolicy.resolveOnLoad(
                locked = true, current = "", saved = "该工作流存的词", default = "default words"
            ),
        )
    }

    // ---------------- ③ 保存：未锁定 ----------------

    @Test
    fun `未锁定时把当前词存进该工作流`() {
        assertEquals(
            "新写的词",
            PositivePromptPolicy.resolveOnSave(
                locked = false, current = "新写的词", existing = "旧的", isActiveMode = true
            ),
        )
    }

    @Test
    fun `未锁定时空串存为 null`() {
        // 与 negativePrompt 的 takeIf { isNotEmpty() } 保持一致：
        // 空 = 没有值，写 null 才能让下次载入回退到工作流默认词
        assertNull(
            PositivePromptPolicy.resolveOnSave(
                locked = false, current = "", existing = "旧的", isActiveMode = true
            ),
        )
    }

    // ---------------- ④ 保存：锁定 ----------------

    @Test
    fun `锁定时不更新该工作流的词`() {
        assertEquals(
            "该工作流原有的词",
            PositivePromptPolicy.resolveOnSave(
                locked = true, current = "锁定期间手打的词", existing = "该工作流原有的词", isActiveMode = true
            ),
        )
    }

    @Test
    fun `锁定时原本没存过就保持没存过`() {
        assertNull(
            PositivePromptPolicy.resolveOnSave(
                locked = true, current = "锁定期间手打的词", existing = null, isActiveMode = true
            ),
        )
    }

    @Test
    fun `锁定期间手打的词不落盘`() {
        // 这是"完全解耦"语义的已知后果，在此固化：锁 = 这份词不归工作流管
        val written = PositivePromptPolicy.resolveOnSave(
            locked = true, current = "临时试验的词", existing = "旧的", isActiveMode = true
        )
        assertEquals("旧的", written)
    }

    // ---------------- ⑤ 保存：ITI 双流隔离 ----------------

    @Test
    fun `非激活模式一律原样保留`() {
        // ITI 只有一个正面输入框却有两套记录：正在编辑修复流时，
        // 编辑流的保存不能把界面上那个词抄进编辑记录，否则来回切模式就互相污染
        assertEquals(
            "编辑流原有的词",
            PositivePromptPolicy.resolveOnSave(
                locked = false, current = "修复流的词", existing = "编辑流原有的词", isActiveMode = false
            ),
        )
        assertNull(
            PositivePromptPolicy.resolveOnSave(
                locked = false, current = "修复流的词", existing = null, isActiveMode = false
            ),
        )
    }

    @Test
    fun `非激活模式的保留优先于未锁定`() {
        assertEquals(
            "编辑流原有的词",
            PositivePromptPolicy.resolveOnSave(
                locked = false, current = "", existing = "编辑流原有的词", isActiveMode = false
            ),
        )
    }

    // ---------------- ⑥ 老数据迁移判据 ----------------

    @Test
    fun `老全局键有值且目标流没记录时应当迁移`() {
        assertTrue(PositivePromptPolicy.shouldMigrateLegacy(legacy = "老词", existingSaved = null))
    }

    @Test
    fun `目标流已有正面词时不覆盖`() {
        assertFalse(
            "用户的既有数据优先于迁移",
            PositivePromptPolicy.shouldMigrateLegacy(legacy = "老词", existingSaved = "该流自己的词"),
        )
    }

    @Test
    fun `目标流曾明确清空过也不迁移`() {
        assertFalse(PositivePromptPolicy.shouldMigrateLegacy(legacy = "老词", existingSaved = ""))
    }

    @Test
    fun `没有老键就什么都不做`() {
        assertFalse(PositivePromptPolicy.shouldMigrateLegacy(legacy = null, existingSaved = null))
        assertFalse(PositivePromptPolicy.shouldMigrateLegacy(legacy = null, existingSaved = "该流自己的词"))
    }

    @Test
    fun `老键为空串时也迁移_保持升级前的空提示词状态`() {
        // 升级前：全局键存在但为空串 → 界面就是空的（不会回退季节默认词）。
        // 迁成空串即保住这个状态；不迁的话用户会突然看到默认词冒出来
        assertTrue(PositivePromptPolicy.shouldMigrateLegacy(legacy = "", existingSaved = null))
    }
}
