package sh.hnet.comfychair.workflow.dynamic

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.hnet.comfychair.workflow.TemplateKeyRegistry
import java.io.File

/**
 * [DynamicParamScanner] 嵌套 dict 输入的单测（回归 + 真实资产钉住）。
 *
 * 背景：rgthree 的 Power Lora Loader 节点参数是一层嵌套 dict
 * （`"inputs": {"lora_1": {"on": "{{lora1_on}}", "strength": "{{lora1_strength}}"}}`），
 * 旧实现只遍历 inputs 的直接子键，这 6 个占位符永远不被索引，
 * 导致参数面板不出现控件、defaults 不生效、占位符字面量原样发给服务器。
 *
 * 分两部分：
 * 1. 构造数据的行为钉住（嵌套索引 / 原有行为 / 不误收 / 跳过已识别）
 * 2. 真实资产（prebuilt_33 / prebuilt_34 MOSS 4K）钉住 6 个 lora 占位符并打印全量清单
 */
class DynamicParamScannerNestedInputTest {

    private fun workflowOf(nodesJson: String) = JSONObject("""{"name":"t","nodes":$nodesJson}""")

    private fun scanPlaceholders(json: String) =
        DynamicParamScanner.scan(json).map { it.placeholder }

    // ================================================================
    // 1. 构造数据的行为钉住
    // ================================================================

    @Test
    fun `嵌套 dict 里的占位符能被索引且 inputName 记为父键子键路径`() {
        val json = workflowOf(
            """
            {
              "40":{"class_type":"Power Lora Loader (rgthree)","inputs":{
                "lora_1":{"on":"{{lora1_on}}","lora":"moss\\\\moody_girl.safetensors","strength":"{{lora1_strength}}","strengthTwo":null},
                "lora_2":{"on":"{{lora2_on}}","strength":"{{lora2_strength}}"},
                "lora_3":{"on":"{{lora3_on}}","strength":"{{lora3_strength}}"}
              }}
            }
            """.trimIndent()
        )

        val refs = DynamicParamScanner.scan(json.toString()).associateBy { it.placeholder }

        assertEquals(
            "6 个嵌套占位符必须全部进索引（文件名等非占位符字符串除外）",
            setOf("lora1_on", "lora1_strength", "lora2_on", "lora2_strength", "lora3_on", "lora3_strength"),
            refs.keys
        )
        // inputName 用「父键.子键」路径形式，便于 UI 与排查定位
        assertEquals("lora_1.on", refs.getValue("lora1_on").inputName)
        assertEquals("lora_1.strength", refs.getValue("lora1_strength").inputName)
        assertEquals("lora_2.on", refs.getValue("lora2_on").inputName)
        assertEquals("lora_2.strength", refs.getValue("lora2_strength").inputName)
        assertEquals("lora_3.on", refs.getValue("lora3_on").inputName)
        assertEquals("lora_3.strength", refs.getValue("lora3_strength").inputName)
        // 其余定位信息照旧
        assertEquals("40", refs.getValue("lora1_on").nodeId)
        assertEquals("Power Lora Loader (rgthree)", refs.getValue("lora1_strength").classType)
    }

    @Test
    fun `直接子键的占位符仍能被索引（原有行为不受影响）`() {
        val json = workflowOf(
            """
            {
              "30":{"class_type":"KSampler","inputs":{"steps":"{{my_steps}}","model":["1",0]}},
              "50":{"class_type":"Power Lora Loader (rgthree)","inputs":{"lora_1":{"on":"{{lora1_on}}"}}}
            }
            """.trimIndent()
        )

        val refs = DynamicParamScanner.scan(json.toString()).associateBy { it.placeholder }

        assertEquals(setOf("my_steps", "lora1_on"), refs.keys)
        assertEquals("直接子键的 inputName 仍是裸字段名", "steps", refs.getValue("my_steps").inputName)
    }

    @Test
    fun `嵌套里的非占位符字符串 数字 布尔 null 连线数组都不产生条目`() {
        val json = workflowOf(
            """
            {
              "40":{"class_type":"Power Lora Loader (rgthree)","inputs":{
                "lora_1":{"on":"{{lora1_on}}","lora":"moss\\\\plain.safetensors","strength":0.8,"enabled":true,"extra":null},
                "model":["1",0],"count":8,"flag":true,"nothing":null
              }}
            }
            """.trimIndent()
        )

        val refs = DynamicParamScanner.scan(json.toString())

        assertEquals("只有嵌套里的真占位符进索引", listOf("lora1_on"), refs.map { it.placeholder })
    }

    @Test
    fun `嵌套里嵌套一层以上的结构不再下钻（只递归一层）`() {
        val json = workflowOf(
            """
            {
              "40":{"class_type":"WeirdNode","inputs":{
                "outer":{"inner":{"deep":"{{too_deep}}"},"flat":"{{one_level}}"}
              }}
            }
            """.trimIndent()
        )

        val refs = DynamicParamScanner.scan(json.toString())

        assertEquals(
            "二层嵌套里的占位符不下钻，一层嵌套正常索引",
            listOf("one_level"), refs.map { it.placeholder }
        )
        assertEquals("outer.flat", refs[0].inputName)
    }

    @Test
    fun `嵌套里的已识别占位符仍被跳过（设计约束 1）`() {
        assertTrue("前置自检：steps 必须是已识别占位符", TemplateKeyRegistry.isRecognizedPlaceholder("steps"))
        val json = workflowOf(
            """
            {
              "40":{"class_type":"Power Lora Loader (rgthree)","inputs":{
                "lora_1":{"on":"{{lora1_on}}","strength":"{{steps}}"}
              }}
            }
            """.trimIndent()
        )

        val refs = DynamicParamScanner.scan(json.toString())

        assertEquals("嵌套里的已识别占位符不得进索引", listOf("lora1_on"), refs.map { it.placeholder })
        assertFalse(refs.any { it.inputName == "lora_1.strength" })
    }

    @Test
    fun `同一占位符嵌套命中与直接命中并存时仍只留一条且按节点 id 排序取最前`() {
        val json = workflowOf(
            """
            {
              "300":{"class_type":"LaterNode","inputs":{"lora_1":{"on":"{{dup}}"}}},
              "100":{"class_type":"EarlierNode","inputs":{"x":"{{dup}}"}}
            }
            """.trimIndent()
        )

        val refs = DynamicParamScanner.scan(json.toString())

        assertEquals(1, refs.size)
        assertEquals("100", refs[0].nodeId)
        assertEquals("EarlierNode", refs[0].classType)
        assertEquals("x", refs[0].inputName)
    }

    // ================================================================
    // 2. 真实资产钉住（prebuilt_33 / prebuilt_34 MOSS 4K HD）
    // ================================================================

    /** JVM 单测的工作目录是模块目录（app/），assets 在 src/main/assets 下。 */
    private fun assetFile(name: String): File? {
        for (dir in listOf("src/main/assets/prebuilt_workflows", "app/src/main/assets/prebuilt_workflows")) {
            val f = File(dir, name)
            if (f.isFile) return f
        }
        return null
    }

    private fun scanRealAsset(name: String): List<DynamicParamRef> {
        val f = assetFile(name)
        org.junit.Assume.assumeTrue("assets 不可用时跳过（换机/CI 布局差异）", f != null)
        return DynamicParamScanner.scan(f!!.readText())
    }

    @Test
    fun `prebuilt_33_moss_4k_hd 的 6 个 lora 占位符全部被扫描到`() {
        val refs = scanRealAsset("prebuilt_33_moss_4k_hd.json")
        val byName = refs.associateBy { it.placeholder }
        // 资产里三个 rgthree 节点（707/851/924）各自只有一个嵌套槽 lora_1，
        // lora2_*/lora3_* 的占位符也挂在各自节点的 lora_1.on / lora_1.strength 下
        val expected = mapOf(
            "lora1_on" to ("707" to "lora_1.on"),
            "lora1_strength" to ("707" to "lora_1.strength"),
            "lora2_on" to ("851" to "lora_1.on"),
            "lora2_strength" to ("851" to "lora_1.strength"),
            "lora3_on" to ("924" to "lora_1.on"),
            "lora3_strength" to ("924" to "lora_1.strength")
        )

        val missing = expected.keys.subtract(byName.keys)
        assertTrue("6 个 lora 占位符必须全部被扫描到，缺的是: $missing", missing.isEmpty())
        expected.forEach { (placeholder, loc) ->
            val (nodeId, inputName) = loc
            assertEquals(
                "$placeholder 的定位信息不符",
                DynamicParamRef(placeholder, nodeId, "Power Lora Loader (rgthree)", inputName),
                byName.getValue(placeholder)
            )
        }
        println("[prebuilt_33 全量占位符清单]")
        refs.forEach { println("  ${it.placeholder} @ node=${it.nodeId} ${it.classType}.${it.inputName}") }
    }

    @Test
    fun `prebuilt_34_moss_4k_hd_preview 实际存在的 lora 占位符全部被扫描到`() {
        val refs = scanRealAsset("prebuilt_34_moss_4k_hd_preview.json")
        val byName = refs.associateBy { it.placeholder }
        // 该资产（预览版）只保留了第三个 lora 槽：文件里实际只有 lora3_* 两个占位符，
        // lora1_*/lora2_* 在 JSON 原文中就不存在，不应被凭空扫出
        val expected = mapOf(
            "lora3_on" to ("924" to "lora_1.on"),
            "lora3_strength" to ("924" to "lora_1.strength")
        )

        val missing = expected.keys.subtract(byName.keys)
        assertTrue("资产里实际存在的 lora 占位符必须全部被扫描到，缺的是: $missing", missing.isEmpty())
        expected.forEach { (placeholder, loc) ->
            val (nodeId, inputName) = loc
            assertEquals(
                "$placeholder 的定位信息不符",
                DynamicParamRef(placeholder, nodeId, "Power Lora Loader (rgthree)", inputName),
                byName.getValue(placeholder)
            )
        }
        assertTrue(
            "文件里不存在的 lora1/lora2 占位符不得凭空出现在扫描结果里",
            byName.keys.none { it.startsWith("lora1_") || it.startsWith("lora2_") }
        )
        println("[prebuilt_34 全量占位符清单]")
        refs.forEach { println("  ${it.placeholder} @ node=${it.nodeId} ${it.classType}.${it.inputName}") }
    }
}
