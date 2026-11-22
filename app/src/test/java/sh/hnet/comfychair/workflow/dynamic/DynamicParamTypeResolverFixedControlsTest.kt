package sh.hnet.comfychair.workflow.dynamic

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.hnet.comfychair.workflow.InputDefinition
import sh.hnet.comfychair.workflow.NodeTypeRegistry
import java.io.File

/**
 * [DynamicParamTypeResolver] 的 App 端固定控件表（FIXED_CONTROLS）单测（回归 + 真实资产钉住）。
 *
 * 背景：MOSS 4K 的 6 个 LoRA 占位符挂在 rgthree Power Lora Loader 的 `lora_1` 下，
 * 而 `lora_1` 在服务器 `/object_info` 里是连接类型（不是 widget），查不到字段定义，
 * 类型推导退化成单行文本框——用户得手打 `true` / `0.75` 才能开 LoRA。
 * FIXED_CONTROLS 按占位符名直接指定控件（开关 / 数字框），优先于服务器字段定义。
 *
 * 分三部分：
 * 1. 构造数据钉住控件类型与优先级（高于服务器定义；combo 同级更高）
 * 2. 未登记占位符与已登记 combo 的行为不变（回归）
 * 3. 真实资产（prebuilt_33 MOSS 4K HD）走「scan → resolve」全链路 + defaults 初值一致性
 *
 * 反证说明：本类测试的反证靠临时移除 FIXED_CONTROLS 里的 `lora1_on` 登记条目后重跑——
 * 第 1 部分的 `lora1_on` 断言必须变红（已实际执行并记录），证明断言真的依赖登记表。
 */
class DynamicParamTypeResolverFixedControlsTest {

    // ================================================================
    // 公共工具
    // ================================================================

    /** 用真实 NodeTypeRegistry 解析一段构造出来的 /object_info（与 DynamicParamEngineTest 同款） */
    private fun objectInfo(vararg defs: Triple<String, String, String>): NodeTypeRegistry {
        val root = JSONObject()
        defs.forEach { (classType, inputName, spec) ->
            val node = root.optJSONObject(classType) ?: JSONObject().apply {
                put("input", JSONObject().apply { put("required", JSONObject()) })
                put("output", org.json.JSONArray())
            }.also { root.put(classType, it) }
            node.getJSONObject("input").getJSONObject("required").put(inputName, org.json.JSONArray(spec))
        }
        return NodeTypeRegistry().apply { parseObjectInfo(root) }
    }

    /** 服务器信息为空的生产 source：模拟 lora_1 查不到定义的真实情形 */
    private fun emptySource() = NodeTypeRegistryDefinitionSource(NodeTypeRegistry())

    private fun resolveOf(
        placeholder: String,
        classType: String,
        inputName: String,
        source: DynamicParamDefinitionSource
    ): DynamicParam = DynamicParamTypeResolver.resolve(
        DynamicParamRef(placeholder, "1", classType, inputName), source
    )

    /** rgthree lora 槽占位符的快捷解析（默认服务器查不到定义） */
    private fun resolveLora(placeholder: String, source: DynamicParamDefinitionSource = emptySource()) =
        resolveOf(placeholder, "Power Lora Loader (rgthree)", "lora_1.on", source)

    private fun assertStrengthControl(control: DynamicParamControl, expectedDefault: Double) {
        assertTrue("必须是数字控件", control is DynamicParamControl.NumberField)
        control as DynamicParamControl.NumberField
        assertFalse("强度是浮点数，不是整数控件", control.isInteger)
        assertEquals(0.0, control.min!!, 0.0)
        assertEquals(2.0, control.max!!, 0.0)
        assertEquals(0.05, control.step!!, 0.0)
        assertEquals(expectedDefault, control.default as Double, 0.0)
    }

    // ================================================================
    // 1. 构造数据：控件类型与优先级
    // ================================================================

    @Test
    fun `lora1_on 命中固定控件表解析为开关且默认关闭`() {
        val control = resolveLora("lora1_on").control

        assertTrue("必须是开关控件（登记前这里退化为文本框）", control is DynamicParamControl.BoolField)
        assertEquals(false, (control as DynamicParamControl.BoolField).default)
    }

    @Test
    fun `lora1_strength 解析为数字控件且范围步进默认值符合预期`() {
        val control = resolveLora("lora1_strength").control

        assertStrengthControl(control, expectedDefault = 1.0)
    }

    @Test
    fun `六个 lora 占位符全部命中固定控件且 lora3 默认 0 点 75`() {
        val onNames = listOf("lora1_on", "lora2_on", "lora3_on")
        onNames.forEach { name ->
            val control = resolveLora(name).control
            assertTrue("$name 必须是开关控件", control is DynamicParamControl.BoolField)
            assertEquals("$name 默认全关（用户决策）", false, (control as DynamicParamControl.BoolField).default)
        }

        assertStrengthControl(resolveLora("lora1_strength").control, 1.0)
        assertStrengthControl(resolveLora("lora2_strength").control, 1.0)
        assertStrengthControl(resolveLora("lora3_strength").control, 0.75)
    }

    @Test
    fun `固定控件优先于服务器字段定义`() {
        // 服务器就算给 lora_1 挂了字段定义（或未来字段形状变化），登记表也不被覆盖
        val registry = objectInfo(
            Triple("Power Lora Loader (rgthree)", "lora_1", """["STRING", {"default": "junk"}]"""),
            Triple("Power Lora Loader (rgthree)", "lora_2", """["INT", {"default": 3, "min": 1, "max": 9, "step": 1}]""")
        )
        val source = NodeTypeRegistryDefinitionSource(registry)

        assertTrue(
            "STRING 定义不得覆盖登记的开关",
            resolveLora("lora1_on", source).control is DynamicParamControl.BoolField
        )
        assertStrengthControl(resolveLora("lora1_strength", source).control, 1.0)
    }

    @Test
    fun `服务器把 lora_1 标成连接类型时同样命中固定控件`() {
        val registry = objectInfo(Triple("Power Lora Loader (rgthree)", "lora_1", """["MODEL"]"""))
        val source = NodeTypeRegistryDefinitionSource(registry)

        val control = resolveLora("lora2_strength", source).control

        assertStrengthControl(control, 1.0)
    }

    // ================================================================
    // 2. 回归：未登记占位符与已登记 combo 行为不变
    // ================================================================

    @Test
    fun `未登记的占位符仍按服务器定义推导（detail_prompt_face 是多行文本）`() {
        val registry = objectInfo(
            Triple("CLIPTextEncode", "text", """["STRING", {"default": "", "multiline": true}]""")
        )
        val source = NodeTypeRegistryDefinitionSource(registry)

        val control = resolveOf("detail_prompt_face", "CLIPTextEncode", "text", source).control

        assertTrue("未登记占位符不得被固定控件表误伤", control is DynamicParamControl.TextField)
        assertEquals(true, (control as DynamicParamControl.TextField).multiline)
    }

    @Test
    fun `未登记且查不到定义的占位符仍退化为单行文本框而不是猜`() {
        val control = resolveOf("brand_new_placeholder", "SomeNode", "some_input", emptySource()).control

        assertTrue(control is DynamicParamControl.TextField)
        assertEquals(false, (control as DynamicParamControl.TextField).multiline)
        assertNull("查不到定义就没有默认值可给", (control as DynamicParamControl.TextField).default)
    }

    @Test
    fun `已登记 combo（moss_mode）行为不变：仍推出固定选项下拉`() {
        // FIXED_CONTROLS 命中检查在 combo 之后，moss_mode 不受影响
        val control = resolveOf("moss_mode", "CRZCompare", "a", emptySource()).control

        assertTrue("moss_mode 必须仍是下拉", control is DynamicParamControl.ComboField)
        assertEquals(
            listOf("moss_25", "moss_spec25", "moss_50", "moss_c25", "moss_28"),
            (control as DynamicParamControl.ComboField).options
        )
        assertNull("固定下拉与固定控件都不带默认值语义（初值由 defaults 决定）", control.default)
    }

    // ================================================================
    // 3. 真实资产：prebuilt_33 走「scan → resolve」全链路
    // ================================================================

    /** JVM 单测的工作目录是模块目录（app/），assets 在 src/main/assets 下。 */
    private fun assetFile(name: String): File? {
        for (dir in listOf("src/main/assets/prebuilt_workflows", "app/src/main/assets/prebuilt_workflows")) {
            val f = File(dir, name)
            if (f.isFile) return f
        }
        return null
    }

    @Test
    fun `prebuilt_33 真实资产 scan 到 resolve 全链路 6 个 lora 占位符控件类型正确`() {
        val f = assetFile("prebuilt_33_moss_4k_hd.json")
        org.junit.Assume.assumeTrue("assets 不可用时跳过（换机/CI 布局差异）", f != null)

        // 真实资产扫描 + 空 registry（模拟服务器 /object_info 里 lora_1 查不到 widget 定义）resolve
        val refs = DynamicParamScanner.scan(f!!.readText())
        val params = refs.map { DynamicParamTypeResolver.resolve(it, emptySource()) }
        val byName = params.associateBy { it.placeholder }

        // 3 个开关：类型与默认全关
        listOf("lora1_on", "lora2_on", "lora3_on").forEach { name ->
            val param = byName.getValue(name)
            assertTrue("$name 必须解析为开关（真实资产全链路）", param.control is DynamicParamControl.BoolField)
            assertEquals("$name 默认全关", false, (param.control as DynamicParamControl.BoolField).default)
        }

        // 3 个强度数字框：范围 0..2 步进 0.05，默认值与资产 defaults 一致
        assertStrengthControl(byName.getValue("lora1_strength").control, 1.0)
        assertStrengthControl(byName.getValue("lora2_strength").control, 1.0)
        assertStrengthControl(byName.getValue("lora3_strength").control, 0.75)

        // 全链路定位信息照旧（真实节点 707/851/924）
        assertEquals("707", byName.getValue("lora1_on").nodeId)
        assertEquals("924", byName.getValue("lora3_strength").nodeId)
        assertEquals("Power Lora Loader (rgthree)", byName.getValue("lora2_strength").classType)
    }

    @Test
    fun `prebuilt_33 真实 defaults 喂入后初值与固定控件 default 一致（两处默认值不打架）`() {
        val f = assetFile("prebuilt_33_moss_4k_hd.json")
        org.junit.Assume.assumeTrue("assets 不可用时跳过（换机/CI 布局差异）", f != null)

        val workflow = JSONObject(f!!.readText())
        val refs = DynamicParamScanner.scan(workflow.toString())
        val params = refs.map { DynamicParamTypeResolver.resolve(it, emptySource()) }

        val resolved = DynamicParamValues.resolveAll(
            params, emptyMap(), workflow.optJSONObject("defaults")
        ).associate { it.param.placeholder to it.value }

        // defaults 优先于控件 default，但两处一致 → 面板初值与登记语义完全相同
        assertEquals("false", resolved["lora1_on"])
        assertEquals("false", resolved["lora2_on"])
        assertEquals("false", resolved["lora3_on"])
        assertEquals("1", resolved["lora1_strength"])
        assertEquals("1", resolved["lora2_strength"])
        assertEquals("0.75", resolved["lora3_strength"])
    }
}
