package sh.hnet.comfychair.connection

import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * LoRA 下拉列表（[ConnectionManager] 的模型缓存）必须剔除"未选择"取值。
 *
 * 根因（commit 428a392 修复的 400）：
 * ```
 * Value not in list: lora_name: 'None' not in (list of length 66)
 * ```
 * LoRA 列表是**所有**节点 `lora_name` 选项的并集，其中 CR Load LoRA / Lora Loader /
 * Efficient Loader / easy *_Loader 等节点各带一个字面量 `"None"` 表示"不加 LoRA"。
 * 它并进下拉后被用户当成真实模型选中，提交给 LoraLoaderModelOnly 时不在服务器的 66 项里 → 400。
 *
 * `extractModelLists()` 是 ConnectionManager 的私有方法（生产代码不动），这里用反射直接钉住
 * 它返回的 `ModelCache.loras`：这是"None 永远进不了下拉"的唯一防线。
 */
class LoraModelListFilterTest {

    private val realLoraA = "image\\krea2\\v1.safetensors"
    private val realLoraB = "krea2-edit.safetensors"

    /** 构造一小段 /object_info：每个节点类只有一个 lora_name 下拉 */
    private fun objectInfo(vararg nodes: Pair<String, List<String>>): JSONObject {
        val root = JSONObject()
        nodes.forEach { (classType, options) ->
            val optionArray = JSONArray()
            options.forEach { optionArray.put(it) }
            val inputSpec = JSONArray().apply {
                put(optionArray)
                put(JSONObject())
            }
            root.put(classType, JSONObject().apply {
                put("input", JSONObject().apply {
                    put("required", JSONObject().apply {
                        put("lora_name", inputSpec)
                    })
                })
                put("output", JSONArray().apply { put("MODEL") })
            })
        }
        return root
    }

    /** 走生产代码路径取模型缓存里的 LoRA 列表（extractModelLists 为私有，用反射调用） */
    private fun extractLoras(): List<String> {
        val method = ConnectionManager::class.java.getDeclaredMethod("extractModelLists")
        method.isAccessible = true
        return (method.invoke(ConnectionManager) as ModelCache).loras
    }

    @After
    fun tearDown() {
        // ConnectionManager 是全局单例，测试用的假 /object_info 必须清掉，免得影响别的测试
        ConnectionManager.nodeTypeRegistry.parseObjectInfo(JSONObject())
    }

    @Test
    fun `LoRA 列表剔除未选择的 None 保留真实模型`() {
        // LoraLoaderModelOnly 的选项里没有 None；另外两个节点各带一个字面量 None
        ConnectionManager.nodeTypeRegistry.parseObjectInfo(
            objectInfo(
                "LoraLoaderModelOnly" to listOf(realLoraA, realLoraB),
                "CR Load LoRA" to listOf("None", realLoraA),
                "Lora Loader" to listOf("None", realLoraB)
            )
        )

        val rawUnion = ConnectionManager.nodeTypeRegistry.getOptionsForField("lora_name")
        assertTrue(
            "根因对照：下拉的原始并集里确实混着字面量 None（若将来改成在解析层剔除，请同步更新本测试）",
            "None" in rawUnion
        )

        val loras = extractLoras()

        assertFalse(
            "『None』不是模型：留在下拉里被选中就会当 LoRA 名提交 → Value not in list 400",
            "None" in loras
        )
        assertTrue("空/空白项也不能出现在下拉里", loras.none { it.isBlank() })
        assertEquals(
            "两个真实模型必须全部保留",
            setOf(realLoraA, realLoraB),
            loras.toSet()
        )
        assertEquals("真实模型的条数不能少", 2, loras.size)
    }

    @Test
    fun `名字里含 None 的真实 LoRA 不会被过滤掉`() {
        ConnectionManager.nodeTypeRegistry.parseObjectInfo(
            objectInfo(
                "LoraLoaderModelOnly" to listOf(
                    "None.safetensors",
                    "image/krea2/none_mix.safetensors",
                    "NoneStyle_v3.safetensors"
                ),
                "easy LoraLoader" to listOf("None", "None.safetensors")
            )
        )

        val loras = extractLoras()

        assertEquals(
            "判定按整串精确匹配：名字里含 None 的真实文件必须一个不少地留在下拉里",
            listOf("None.safetensors", "NoneStyle_v3.safetensors", "image/krea2/none_mix.safetensors"),
            loras
        )
    }

    @Test
    fun `所有节点都只报 None 时列表为空且不抛异常`() {
        ConnectionManager.nodeTypeRegistry.parseObjectInfo(
            objectInfo(
                "CR Load LoRA" to listOf("None"),
                "easy LoraLoader" to listOf("None", "  none  ")
            )
        )

        assertEquals("全是未选择取值 → 下拉里一个 LoRA 都不该剩", emptyList<String>(), extractLoras())
    }
}
