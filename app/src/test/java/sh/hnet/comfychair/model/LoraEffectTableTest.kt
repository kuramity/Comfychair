package sh.hnet.comfychair.model

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * LoRA 效果档案表的查询逻辑测试。
 *
 * 两类用例：
 * 1. **构造数据**——总量口径（有值/孤儿划分）、值非空保证；
 * 2. **注入构造数据**——null 安全、大小写、排序这些边界行为。
 *
 * 不依赖任何外部数据文件。
 */
class LoraEffectTableTest {

    @After
    fun tearDown() {
        LoraEffectTable.clearForTest()
    }

    // ---------------- 构造数据 ----------------

    /** 注入一份小型档案表，覆盖「有值 / null / 空串」三种形态。 */
    private fun loadSyntheticTable() {
        LoraEffectTable.setTableJsonForTest(
            """
            {"a.safetensors":"效果甲",
             "b.safetensors":"效果乙",
             "c.safetensors":null,
             "d.safetensors":""}
            """.trimIndent()
        )
    }

    @Test
    fun `有值条目数与孤儿划分符合注入数据`() {
        loadSyntheticTable()
        // null 与空串都被跳过 → 只剩 2 条有值
        assertEquals(2, LoraEffectTable.all().size)

        val known = setOf("a.safetensors")
        val orphans = LoraEffectTable.orphans(known)
        assertEquals("介绍表未收录的那条必须进孤儿", listOf("b.safetensors"), orphans.map { it.first })
        assertEquals("孤儿条目要带上效果文本", "效果乙", orphans.single().second)
    }

    @Test
    fun `有值条目都不含空白值`() {
        loadSyntheticTable()
        // 解析层保证 all() 里只有非空文本——这里钉住"值都是可直接显示的"
        LoraEffectTable.all().forEach { (key, value) ->
            assertTrue("键 $key 的值为空", value.isNotBlank())
        }
    }

    // ---------------- 注入构造数据 ----------------

    @Test
    fun `null与空值条目被跳过_不崩`() {
        // 数据里约三分之一条目的值是 JSON null，org.json 的 optString 会把 null 哨兵
        // 转成字面量 "null" 字符串——解析必须先过 isNull 再取（这是本表最要紧的坑）
        LoraEffectTable.setTableJsonForTest(
            """
            {"a.safetensors":"有效果文本",
             "b.safetensors":null,
             "c.safetensors":"",
             "d.safetensors":"   "}
            """.trimIndent()
        )
        assertEquals(mapOf("a.safetensors" to "有效果文本"), LoraEffectTable.all())
    }

    @Test
    fun `坏JSON退化为空表_不抛异常`() {
        LoraEffectTable.setTableJsonForTest("{ not json ")
        assertTrue(LoraEffectTable.all().isEmpty())
    }

    @Test
    fun `全部条目都是null时视为数据不可用_空表`() {
        LoraEffectTable.setTableJsonForTest("""{"a.safetensors":null,"b.safetensors":""}""")
        assertTrue(LoraEffectTable.all().isEmpty())
    }

    @Test
    fun `lookup大小写不敏感且取末段文件名`() {
        LoraEffectTable.setTableJsonForTest("""{"x.safetensors":"效果"}""")
        assertEquals("效果", LoraEffectTable.lookup("X.SAFETENSORS"))
        assertEquals("效果", LoraEffectTable.lookup("loras/dir/X.SAFETENSORS"))
        // 调用方拿服务器返回的反斜杠路径直接查也能命中
        assertEquals("效果", LoraEffectTable.lookup("loras\\dir\\X.SAFETENSORS"))
        assertNull(LoraEffectTable.lookup("missing.safetensors"))
        assertNull(LoraEffectTable.lookup(null))
        assertNull(LoraEffectTable.lookup("  "))
    }

    @Test
    fun `orphans按文件名排序且排除已知文件`() {
        LoraEffectTable.setTableJsonForTest(
            """
            {"b.safetensors":"B 效果",
             "a.safetensors":"A 效果",
             "c.safetensors":"C 效果",
             "known.safetensors":"已收录"}
            """.trimIndent()
        )
        // knownFiles 接受任意大小写（比较前统一转小写）
        val orphans = LoraEffectTable.orphans(setOf("KNOWN.safetensors"))
        assertEquals(
            listOf("a.safetensors", "b.safetensors", "c.safetensors"),
            orphans.map { it.first }
        )
        assertEquals("A 效果", orphans[0].second)
    }
}
