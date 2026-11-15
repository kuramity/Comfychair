package sh.hnet.comfychair.model

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * LoRA 效果档案表的查询逻辑测试。
 *
 * 两类用例：
 * 1. **真实数据**（assets 内置那份）——钉住总量特征（有值 47 条 / 孤儿 5 条），数据换版时
 *    这里会响铃提醒同步改数字，而不是悄悄漂移；
 * 2. **注入构造数据**——null 安全、大小写、排序这些边界行为。
 *
 * 真实数据用例在 assets 文件不在（换机/CI 布局不同）时**跳过而不是误报失败**。
 */
class LoraEffectTableTest {

    @After
    fun tearDown() {
        LoraEffectTable.clearForTest()
    }

    // ---------------- 真实数据（assets 内置） ----------------

    /** JVM 单测的工作目录是模块目录（app/），assets 在 src/main/assets 下。 */
    private fun loadAssetText(name: String): String? {
        for (dir in listOf("src/main/assets", "app/src/main/assets")) {
            val f = File(dir, name)
            if (f.exists()) return f.readText()
        }
        return null
    }

    /** 载入真实效果档案；文件不在返回 false 让用例跳过。 */
    private fun loadRealTable(): Boolean {
        val text = loadAssetText("lora_effects.json") ?: return false
        LoraEffectTable.setTableJsonForTest(text)
        return true
    }

    /** 介绍表收录的文件名集合（orphans 判定的 knownFiles）；文件不在返回 null。 */
    private fun loadKnownModelFiles(): Set<String>? {
        val text = loadAssetText("model_intros.json") ?: return null
        val models = org.json.JSONObject(text).getJSONObject("models")
        val out = HashSet<String>()
        val keys = models.keys()
        while (keys.hasNext()) out.add(keys.next())
        return out
    }

    @Test
    fun `真实数据有值条目47条`() {
        assumeTrue("assets 不可用时跳过（换机/CI 布局差异）", loadRealTable())
        assertEquals(47, LoraEffectTable.all().size)
    }

    @Test
    fun `真实数据孤儿条目5条`() {
        assumeTrue("assets 不可用时跳过", loadRealTable())
        val known = loadKnownModelFiles()
        assumeTrue("model_intros.json 不可用时跳过", known != null)
        assertEquals(5, LoraEffectTable.orphans(known!!).size)
    }

    @Test
    fun `真实数据有值条目都不含空白值`() {
        assumeTrue("assets 不可用时跳过", loadRealTable())
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
