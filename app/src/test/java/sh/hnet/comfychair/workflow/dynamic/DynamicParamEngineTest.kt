package sh.hnet.comfychair.workflow.dynamic

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.hnet.comfychair.model.WorkflowValues
import sh.hnet.comfychair.workflow.InputDefinition
import sh.hnet.comfychair.workflow.NodeTypeRegistry
import sh.hnet.comfychair.workflow.TemplateKeyRegistry

/**
 * 通用节点参数引擎（纯逻辑层）单测。
 *
 * 钉住四块交付物：
 * 1. 扫描与索引（[DynamicParamScanner]）——含设计约束 1「只接管未识别占位符」
 * 2. 类型推导（[DynamicParamTypeResolver]）——含两种 COMBO 格式、node-specific 选项（约束 3）
 * 3. 取值顺序（[DynamicParamValues.resolveValue]）——约束 5
 * 4. 统一替换（[DynamicParamValues.applyDynamicParams]）
 *
 * COMBO 两种格式用**真实** [NodeTypeRegistry.parseObjectInfo] 走一遍，证明注册表把
 * `["COMBO", {"options":[...]}]` 与 `[["a","b"], {...}]` 都归一成了可推导的形态
 * （NodeTypeRegistry 内部只看 `DebugLogger`，其 LOGCAT_ENABLED=false，可在 JVM 单测里跑）。
 */
class DynamicParamEngineTest {

    // ================================================================
    // 1. 扫描与索引
    // ================================================================

    private fun workflowOf(nodesJson: String) = JSONObject("""{"name":"t","nodes":$nodesJson}""")

    @Test
    fun `扫描出未识别占位符并建立 class_type 与输入名的映射`() {
        val json = workflowOf(
            """
            {
              "30":{"class_type":"KSampler","inputs":{"steps":"{{my_steps}}","seed":"{{seed}}","model":["1",0]}},
              "100":{"class_type":"TESpeedMiniMaxH3","inputs":{"mode":"{{h3_mode}}","count":8,"flag":true}},
              "110":{"class_type":"SomePluginNode","inputs":{"text":"prefix-{{embedded}}"}}
            }
            """.trimIndent()
        )

        val refs = DynamicParamScanner.scan(json.toString()).associateBy { it.placeholder }

        assertEquals("只应索引两个整值占位符", setOf("my_steps", "h3_mode"), refs.keys)
        assertEquals("KSampler", refs.getValue("my_steps").classType)
        assertEquals("steps", refs.getValue("my_steps").inputName)
        assertEquals("30", refs.getValue("my_steps").nodeId)
        assertEquals("TESpeedMiniMaxH3", refs.getValue("h3_mode").classType)
        assertEquals("mode", refs.getValue("h3_mode").inputName)
    }

    @Test
    fun `已硬编码的占位符被跳过（设计约束 1）`() {
        val json = workflowOf(
            """
            {
              "1":{"class_type":"CheckpointLoaderSimple","inputs":{"ckpt_name":"{{ckpt_name}}"}},
              "2":{"class_type":"CLIPTextEncode","inputs":{"text":"{{positive_prompt}}"}},
              "3":{"class_type":"KSampler","inputs":{"steps":"{{steps}}","cfg":"{{cfg}}","seed":"{{seed}}"}},
              "4":{"class_type":"LoraLoader","inputs":{"lora_name":"{{lora_name}}"}},
              "5":{"class_type":"RandomNoise","inputs":{"noise_seed":"{{noise_seed}}"}},
              "6":{"class_type":"LoadImage","inputs":{"image":"{{reference_image_2}}"}},
              "7":{"class_type":"EmptyLatentImage","inputs":{"width":"{{width}}","height":"{{height}}"}},
              "8":{"class_type":"MyPluginNode","inputs":{"steps":"{{my_steps}}"}}
            }
            """.trimIndent()
        )

        val refs = DynamicParamScanner.scan(json.toString())

        assertEquals("只剩真正未识别的那一个", listOf("my_steps"), refs.map { it.placeholder })
    }

    @Test
    fun `设计文档约束 1 的清单逐项都算已识别`() {
        val specList = listOf(
            "positive_prompt", "negative_prompt", "steps", "cfg", "seed", "width", "height",
            "megapixels", "aspect_ratio", "speed_mode", "lora_strength", "denoise",
            "sampler_name", "scheduler", "length", "frame_rate", "batch_size",
            "ckpt_name", "unet_name", "vae_name", "text_encoder_name",
            "latent_upscale_model", "upscale_model", "upscale_method", "scale_by",
            "stop_at_clip_layer", "image_filename", "noise_seed", "lora_name",
            "clip_name", "clip_name1", "clip_name2", "clip_name3", "clip_name4",
            "reference_image_1", "reference_image_2", "reference_image_9"
        )
        specList.forEach { name ->
            assertTrue("$name 必须算已识别（否则同一参数会显示两遍）", TemplateKeyRegistry.isRecognizedPlaceholder(name))
        }
    }

    @Test
    fun `从未见过的名字一律不算已识别`() {
        listOf("my_steps", "h3_mode", "cfg2", "stepss", "reference_image", "clip_namex").forEach { name ->
            assertFalse("$name 不应被误判为已识别", TemplateKeyRegistry.isRecognizedPlaceholder(name))
        }
    }

    @Test
    fun `连线与非字符串输入值不参与索引`() {
        val json = workflowOf(
            """
            {
              "1":{"class_type":"A","inputs":{"model":["2",0],"count":8,"flag":true,"nothing":null}},
              "2":{"class_type":"B","inputs":{"x":"{{only_one}}"}}
            }
            """.trimIndent()
        )

        assertEquals(listOf("only_one"), DynamicParamScanner.scan(json.toString()).map { it.placeholder })
    }

    @Test
    fun `嵌在更大字符串里的占位符不进索引（类型无从推导）`() {
        val json = workflowOf(
            """{"1":{"class_type":"A","inputs":{"filename":"out_{{embedded}}.png"}}}"""
        )

        assertTrue(DynamicParamScanner.scan(json.toString()).isEmpty())
    }

    @Test
    fun `同一占位符出现在多个节点上时只留一条并取节点 id 数值排序最前者`() {
        val json = workflowOf(
            """
            {
              "300":{"class_type":"LaterNode","inputs":{"x":"{{dup}}"}},
              "100":{"class_type":"EarlierNode","inputs":{"y":"{{dup}}"}}
            }
            """.trimIndent()
        )

        val refs = DynamicParamScanner.scan(json.toString())

        assertEquals(1, refs.size)
        assertEquals("100", refs[0].nodeId)
        assertEquals("EarlierNode", refs[0].classType)
        assertEquals("y", refs[0].inputName)
    }

    @Test
    fun `非法 JSON 与空节点表返回空列表而不是抛异常`() {
        assertTrue(DynamicParamScanner.scan("not json").isEmpty())
        assertTrue(DynamicParamScanner.scan("{}").isEmpty())
        assertTrue(DynamicParamScanner.scanNodes(JSONObject()).isEmpty())
    }

    @Test
    fun `裸节点表（无 nodes 包裹）同样能扫描`() {
        val raw = JSONObject("""{"5":{"class_type":"A","inputs":{"v":"{{raw_p}}"}}}""")

        assertEquals(listOf("raw_p"), DynamicParamScanner.scan(raw.toString()).map { it.placeholder })
    }

    // ================================================================
    // 2. 类型推导
    // ================================================================

    /** 用真实 NodeTypeRegistry 解析一段构造出来的 /object_info */
    private fun objectInfo(vararg defs: Triple<String, String, String>): NodeTypeRegistry {
        val root = JSONObject()
        defs.forEach { (classType, inputName, spec) ->
            val node = root.optJSONObject(classType) ?: JSONObject().apply {
                put("input", JSONObject().apply { put("required", JSONObject()) })
                put("output", JSONArray())
            }.also { root.put(classType, it) }
            node.getJSONObject("input").getJSONObject("required").put(inputName, JSONArray(spec))
        }
        return NodeTypeRegistry().apply { parseObjectInfo(root) }
    }

    private fun resolve(
        classType: String,
        inputName: String,
        registry: NodeTypeRegistry
    ): DynamicParam {
        val ref = DynamicParamRef(
            placeholder = "p_$inputName",
            nodeId = "1",
            classType = classType,
            inputName = inputName
        )
        return DynamicParamTypeResolver.resolve(ref, NodeTypeRegistryDefinitionSource(registry))
    }

    @Test
    fun `INT 推出带 min max step 的整数数字控件`() {
        val registry = objectInfo(
            Triple("N", "steps", """["INT", {"default": 20, "min": 1, "max": 10000, "step": 1}]""")
        )

        val control = resolve("N", "steps", registry).control
        assertTrue(control is DynamicParamControl.NumberField)
        control as DynamicParamControl.NumberField
        assertTrue("INT 必须是整数控件", control.isInteger)
        assertEquals(1.0, control.min!!, 0.0)
        assertEquals(10000.0, control.max!!, 0.0)
        assertEquals(1.0, control.step!!, 0.0)
        assertEquals(20, control.default)
    }

    @Test
    fun `FLOAT 推出带 min max step 的浮点数字控件`() {
        val registry = objectInfo(
            Triple("N", "scale", """["FLOAT", {"default": 1.5, "min": 0.0, "max": 8.0, "step": 0.05}]""")
        )

        val control = resolve("N", "scale", registry).control
        assertTrue(control is DynamicParamControl.NumberField)
        control as DynamicParamControl.NumberField
        assertFalse("FLOAT 不是整数控件", control.isInteger)
        assertEquals(0.0, control.min!!, 0.0)
        assertEquals(8.0, control.max!!, 0.0)
        assertEquals(0.05, control.step!!, 0.0)
    }

    @Test
    fun `STRING 标识 multiline 时推出多行文本框`() {
        val registry = objectInfo(
            Triple("N", "body", """["STRING", {"default": "hi", "multiline": true}]""")
        )

        val control = resolve("N", "body", registry).control
        assertTrue(control is DynamicParamControl.TextField)
        assertEquals(true, (control as DynamicParamControl.TextField).multiline)
        assertEquals("hi", control.default)
    }

    @Test
    fun `STRING 不带 multiline 时推出单行文本框`() {
        val registry = objectInfo(Triple("N", "note", """["STRING", {"default": "x"}]"""))

        val control = resolve("N", "note", registry).control
        assertTrue(control is DynamicParamControl.TextField)
        assertEquals(false, (control as DynamicParamControl.TextField).multiline)
    }

    @Test
    fun `BOOLEAN 推出开关`() {
        val registry = objectInfo(Triple("N", "enabled", """["BOOLEAN", {"default": true}]"""))

        val control = resolve("N", "enabled", registry).control
        assertTrue(control is DynamicParamControl.BoolField)
        assertEquals(true, control.default)
    }

    @Test
    fun `COMBO 新格式推出下拉且选项与默认值正确`() {
        val registry = objectInfo(
            Triple(
                "TESpeedMiniMaxH3", "mode",
                """["COMBO", {"options": ["standard", "4-step LoRA", "8-step LoRA"], "default": "standard"}]"""
            )
        )

        val control = resolve("TESpeedMiniMaxH3", "mode", registry).control
        assertTrue(control is DynamicParamControl.ComboField)
        control as DynamicParamControl.ComboField
        assertEquals(listOf("standard", "4-step LoRA", "8-step LoRA"), control.options)
        assertEquals("standard", control.default)
    }

    @Test
    fun `COMBO 旧格式也推出下拉（两种格式都要支持）`() {
        val registry = objectInfo(
            Triple("LegacyNode", "mode", """[["a", "b", "c"], {"default": "b"}]""")
        )

        val control = resolve("LegacyNode", "mode", registry).control
        assertTrue("旧格式必须也归一成下拉", control is DynamicParamControl.ComboField)
        control as DynamicParamControl.ComboField
        assertEquals(listOf("a", "b", "c"), control.options)
        assertEquals("b", control.default)
    }

    @Test
    fun `选项必须 node-specific 取，绝不把同名输入的选项并集混进来（设计约束 3）`() {
        // mode 在服务器上有 144 个节点类在用；lora_name 有 31 个。
        // 并集把字面量 "None" 混进来过一次，导致用户报 400。
        val registry = objectInfo(
            Triple("NodeA", "mode", """["COMBO", {"options": ["only_a"], "default": "only_a"}]"""),
            Triple("NodeB", "mode", """["COMBO", {"options": ["only_b", "None"], "default": "only_b"}]""")
        )

        val controlA = resolve("NodeA", "mode", registry).control as DynamicParamControl.ComboField
        val controlB = resolve("NodeB", "mode", registry).control as DynamicParamControl.ComboField

        assertEquals(listOf("only_a"), controlA.options)
        assertEquals(listOf("only_b", "None"), controlB.options)
    }

    @Test
    fun `定义查不到时退化为单行文本框而不是猜类型`() {
        val registry = objectInfo(Triple("N", "steps", """["INT", {}]"""))

        val control = resolve("UnknownNode", "whatever", registry).control
        assertTrue(control is DynamicParamControl.TextField)
        assertEquals(false, (control as DynamicParamControl.TextField).multiline)
        assertNull("查不到定义就没有默认值可给", control.default)
    }

    @Test
    fun `输入名在节点上不存在时同样退化为单行文本框`() {
        val registry = objectInfo(Triple("N", "steps", """["INT", {}]"""))

        val control = resolve("N", "not_there", registry).control
        assertTrue(control is DynamicParamControl.TextField)
        assertNull(control.default)
    }

    @Test
    fun `连接类型输入退化为单行文本框`() {
        val registry = objectInfo(Triple("N", "model", """["MODEL"]"""))

        val control = resolve("N", "model", registry).control
        assertTrue(control is DynamicParamControl.TextField)
    }

    // ================================================================
    // 3. 取值顺序（设计约束 5）
    // ================================================================

    private val intControl = DynamicParamControl.NumberField(
        isInteger = true, min = 1.0, max = null, step = null, default = 20
    )

    @Test
    fun `取值顺序一：已有保存值优先于一切`() {
        val defaults = JSONObject().put("my_steps", 99)

        val value = DynamicParamValues.resolveValue(intControl, "42", defaults, "my_steps")
        assertEquals("42", value)
    }

    @Test
    fun `取值顺序二：没有保存值时取 defaults 里以占位符名命名的键`() {
        val defaults = JSONObject().put("my_steps", 99)

        assertEquals("99", DynamicParamValues.resolveValue(intControl, null, defaults, "my_steps"))
        assertEquals("99", DynamicParamValues.resolveValue(intControl, "", defaults, "my_steps"))
    }

    @Test
    fun `取值顺序三：defaults 里没有该键时取节点定义的 default`() {
        val defaults = JSONObject().put("other_key", 99)

        assertEquals("20", DynamicParamValues.resolveValue(intControl, null, defaults, "my_steps"))
        assertEquals("20", DynamicParamValues.resolveValue(intControl, null, null, "my_steps"))
    }

    @Test
    fun `取值顺序四：三层都没有时给空串`() {
        val noDefault = DynamicParamControl.TextField(multiline = false, default = null)

        assertEquals("", DynamicParamValues.resolveValue(noDefault, null, null, "my_steps"))
    }

    @Test
    fun `defaults 里的浮点默认值丢掉多余的 0`() {
        val defaults = JSONObject().put("my_scale", 1.0)
        val floatControl = DynamicParamControl.NumberField(
            isInteger = false, min = null, max = null, step = null, default = null
        )

        assertEquals("1", DynamicParamValues.resolveValue(floatControl, null, defaults, "my_scale"))
    }

    @Test
    fun `布尔默认值转成小写字符串`() {
        val boolControl = DynamicParamControl.BoolField(default = true)

        assertEquals("true", DynamicParamValues.resolveValue(boolControl, null, null, "my_flag"))
    }

    @Test
    fun `批量取值只覆盖清单里的占位符`() {
        val params = listOf(
            DynamicParam("a", "1", "N", "x", intControl),
            DynamicParam("b", "2", "N", "y", intControl)
        )

        val resolved = DynamicParamValues.resolveAll(params, mapOf("a" to "7"), null)

        assertEquals(mapOf("a" to "7", "b" to "20"), resolved.associate { it.param.placeholder to it.value })
    }

    // ================================================================
    // 4. 统一替换
    // ================================================================

    private fun param(
        placeholder: String,
        control: DynamicParamControl,
        classType: String = "N",
        inputName: String = placeholder
    ) = DynamicParam(placeholder, "1", classType, inputName, control)

    private fun textControl(multiline: Boolean = false) =
        DynamicParamControl.TextField(multiline = multiline, default = null)

    @Test
    fun `数字占位符替换成不加引号的 JSON 数字`() {
        val json = """{"nodes":{"1":{"class_type":"KSampler","inputs":{"steps":"{{my_steps}}"}}}}"""
        val values = listOf(DynamicParamValue(param("my_steps", intControl), "35"))

        val out = DynamicParamValues.applyDynamicParams(json, values)

        assertEquals("""{"nodes":{"1":{"class_type":"KSampler","inputs":{"steps":35}}}}""", out)
        // 落回去必须是数字而不是字符串
        assertEquals(35, JSONObject(out).getJSONObject("nodes").getJSONObject("1")
            .getJSONObject("inputs").get("steps"))
    }

    @Test
    fun `浮点占位符保留用户原样输入的数字形态`() {
        val floatControl = DynamicParamControl.NumberField(
            isInteger = false, min = null, max = null, step = null, default = null
        )
        val json = """{"scale":"{{my_scale}}"}"""
        val values = listOf(DynamicParamValue(param("my_scale", floatControl), "1.75"))

        assertEquals("""{"scale":1.75}""", DynamicParamValues.applyDynamicParams(json, values))
    }

    @Test
    fun `开关占位符替换成裸 true false`() {
        val json = """{"a":"{{f1}}","b":"{{f2}}"}}"""
        val values = listOf(
            DynamicParamValue(param("f1", DynamicParamControl.BoolField(null)), "true"),
            DynamicParamValue(param("f2", DynamicParamControl.BoolField(null)), "0")
        )

        assertEquals("""{"a":true,"b":false}}""", DynamicParamValues.applyDynamicParams(json, values))
    }

    @Test
    fun `文本与下拉占位符替换成带引号的 JSON 字符串`() {
        val json = """{"prompt":"{{my_text}}","mode":"{{my_mode}}"}}"""
        val values = listOf(
            DynamicParamValue(param("my_text", textControl()), "hello world"),
            DynamicParamValue(
                param("my_mode", DynamicParamControl.ComboField(listOf("a", "b"), null)), "b"
            )
        )

        assertEquals(
            """{"prompt":"hello world","mode":"b"}}""",
            DynamicParamValues.applyDynamicParams(json, values)
        )
    }

    @Test
    fun `下拉空值保留占位符不替换（空值不可能是合法选项，保留才能让服务器点名参数）`() {
        // KSampler.sampler_name 的服务器定义 default=null：用户没选就提交，
        // 发 "" 只会得到 Value not in list（还不说是哪个参数）；保留 {{my_mode}} 则错误信息里带名字
        val json = """{"mode":"{{my_mode}}"}"""
        val values = listOf(
            DynamicParamValue(param("my_mode", DynamicParamControl.ComboField(listOf("a", "b"), null)), "")
        )

        assertEquals(json, DynamicParamValues.applyDynamicParams(json, values))
    }

    @Test
    fun `选项里真的含空串时下拉按空串发出`() {
        val json = """{"mode":"{{my_mode}}"}"""
        val values = listOf(
            DynamicParamValue(
                param("my_mode", DynamicParamControl.ComboField(listOf("", "b"), null)), ""
            )
        )

        assertEquals("{\"mode\":\"\"}", DynamicParamValues.applyDynamicParams(json, values))
    }

    @Test
    fun `文本空值照常替换成空字符串（只有下拉才把空值当未选择）`() {
        val json = """{"prompt":"{{my_text}}"}"""
        val values = listOf(DynamicParamValue(param("my_text", textControl()), ""))

        assertEquals("{\"prompt\":\"\"}", DynamicParamValues.applyDynamicParams(json, values))
    }

    @Test
    fun `文本值里的引号与换行被正确转义`() {
        val json = """{"prompt":"{{my_text}}"}}"""
        val values = listOf(DynamicParamValue(param("my_text", textControl()), "a\"b\nc\\d"))

        val out = DynamicParamValues.applyDynamicParams(json, values)

        // 产物必须是合法 JSON，且解析回来的值与用户输入逐字符相等
        assertEquals("a\"b\nc\\d", JSONObject(out).getString("prompt"))
    }

    @Test
    fun `嵌在更大字符串里的占位符按裸文本替换且不加引号`() {
        val json = """{"filename":"out_{{my_text}}.png"}"""
        val values = listOf(DynamicParamValue(param("my_text", textControl()), "abc"))

        assertEquals(
            """{"filename":"out_abc.png"}""",
            DynamicParamValues.applyDynamicParams(json, values)
        )
    }

    @Test
    fun `数字控件拿到不可解析的值时保留占位符不替换（宁可响亮失败也不要塞错值）`() {
        val json = """{"steps":"{{my_steps}}"}"""
        val values = listOf(DynamicParamValue(param("my_steps", intControl), ""))

        assertEquals(json, DynamicParamValues.applyDynamicParams(json, values))
    }

    @Test
    fun `整数控件拿到小数时保留占位符不替换`() {
        val json = """{"steps":"{{my_steps}}"}"""
        val values = listOf(DynamicParamValue(param("my_steps", intControl), "3.5"))

        assertEquals(json, DynamicParamValues.applyDynamicParams(json, values))
    }

    @Test
    fun `单趟替换，值里带占位符语法也不会被连锁替换`() {
        val json = """{"a":"{{outer}}"}"""
        val values = listOf(
            DynamicParamValue(param("outer", textControl()), "{{inner}}"),
            DynamicParamValue(param("inner", textControl()), "should_not_appear")
        )

        assertEquals(
            """{"a":"{{inner}}"}""",
            DynamicParamValues.applyDynamicParams(json, values)
        )
    }

    @Test
    fun `不在取值清单里的占位符原样保留`() {
        val json = """{"a":"{{known}}","b":"{{unknown}}"}}"""
        val values = listOf(DynamicParamValue(param("known", textControl()), "v"))

        assertEquals(
            """{"a":"v","b":"{{unknown}}"}}""",
            DynamicParamValues.applyDynamicParams(json, values)
        )
    }

    @Test
    fun `空清单不改变 JSON`() {
        val json = """{"a":"{{x}}"}}"""

        assertEquals(json, DynamicParamValues.applyDynamicParams(json, emptyList()))
    }

    @Test
    fun `任意未识别占位符从头到尾走通：扫描 - 推导 - 取值 - 替换`() {
        val workflowJson = """
            {"nodes":{
              "259":{"class_type":"MiniMaxH3ImageToVideo","inputs":{
                 "prompt":"{{positive_prompt}}","steps":"{{my_steps}}","mode":"{{h3_mode}}"}}
            }}
        """.trimIndent()
        val registry = objectInfo(
            Triple("MiniMaxH3ImageToVideo", "steps", """["INT", {"default": 4, "min": 1, "max": 100}]"""),
            Triple(
                "MiniMaxH3ImageToVideo", "mode",
                """["COMBO", {"options": ["standard", "8-step LoRA"], "default": "standard"}]"""
            )
        )
        val source = NodeTypeRegistryDefinitionSource(registry)

        // 扫描：positive_prompt 已识别 → 跳过
        val refs = DynamicParamScanner.scan(workflowJson)
        assertEquals(setOf("my_steps", "h3_mode"), refs.map { it.placeholder }.toSet())

        val params = refs.map { DynamicParamTypeResolver.resolve(it, source) }

        // 取值：my_steps 走 defaults，h3_mode 走节点默认值
        val defaults = JSONObject().put("my_steps", 12)
        val resolved = DynamicParamValues.resolveAll(params, emptyMap(), defaults)
        assertEquals(
            mapOf("my_steps" to "12", "h3_mode" to "standard"),
            resolved.associate { it.param.placeholder to it.value }
        )

        // 替换：数字不带引号，下拉带引号，已识别的 positive_prompt 一字不动
        val out = DynamicParamValues.applyDynamicParams(workflowJson, resolved)
        val inputs = JSONObject(out).getJSONObject("nodes").getJSONObject("259").getJSONObject("inputs")
        assertEquals(12, inputs.get("steps"))
        assertEquals("standard", inputs.getString("mode"))
        assertEquals("{{positive_prompt}}", inputs.getString("prompt"))
    }

    // ================================================================
    // 5. 持久化：WorkflowValues 的动态参数能序列化再读回
    // ================================================================

    @Test
    fun `WorkflowValues 的动态参数能序列化再读回`() {
        val saved = WorkflowValues(
            steps = 20,
            dynamicParams = mapOf("my_steps" to "8", "h3_mode" to "8-step LoRA")
        )

        val restored = WorkflowValues.fromJson(WorkflowValues.toJson(saved))

        assertEquals(mapOf("my_steps" to "8", "h3_mode" to "8-step LoRA"), restored.dynamicParams)
    }

    @Test
    fun `没有动态参数时序列化产物里不出现该键且读回是空表`() {
        val json = WorkflowValues.toJson(WorkflowValues(steps = 20))

        assertFalse("不该给每个工作流都塞一个空对象", json.contains("dynamicParams"))
        assertTrue(WorkflowValues.fromJson(json).dynamicParams.isEmpty())
    }

    @Test
    fun `空串值在读回时被丢掉（让取值顺序能回退到 defaults 与节点默认值）`() {
        val json = """{"dynamicParams":{"a":"","b":"keep"}}"""

        assertEquals(mapOf("b" to "keep"), WorkflowValues.fromJson(json).dynamicParams)
    }

    // ================================================================
    // 6. 与既有转义实现等价（WorkflowManager.escapeForJson 已委托到同一份实现）
    // ================================================================

    @Test
    fun `JSON 文本转义覆盖反斜杠 引号 换行 制表与控制字符`() {
        assertEquals("""a\\b""", escapeJsonString("a\\b"))
        assertEquals("""a\"b""", escapeJsonString("a\"b"))
        assertEquals("""a\nb""", escapeJsonString("a\nb"))
        assertEquals("""a\rb""", escapeJsonString("a\rb"))
        assertEquals("""a\tb""", escapeJsonString("a\tb"))
        assertEquals("""a\bb""", escapeJsonString("a\bb"))
        assertEquals("""a\fb""", escapeJsonString("a\u000Cb"))
    }

    @Test
    fun `转义后的文本能原样被 JSON 解析器读回`() {
        val tricky = "line1\nline2\t\"quoted\"\\slash\u000C"

        val json = """{"v":"${escapeJsonString(tricky)}"}"""

        assertEquals(tricky, JSONObject(json).getString("v"))
    }

    // ================================================================
    // 7. 假实现注入：证明类型推导不依赖真实注册表也能工作
    // ================================================================

    private class FakeSource(private val definitions: Map<String, InputDefinition>) :
        DynamicParamDefinitionSource {
        var optionsCalls = mutableListOf<Pair<String, String>>()

        override fun inputDefinitionOf(classType: String, inputName: String) =
            definitions["$classType.$inputName"]

        override fun optionsForNodeInput(classType: String, inputName: String): List<String> {
            optionsCalls.add(classType to inputName)
            return listOf("node_specific")
        }
    }

    @Test
    fun `下拉选项走 node-specific 入口而不是全局并集`() {
        val source = FakeSource(
            mapOf(
                "X.mode" to InputDefinition(
                    name = "mode",
                    type = "ENUM",
                    isRequired = true,
                    options = listOf("global_union_a", "global_union_b")
                )
            )
        )
        val ref = DynamicParamRef("p", "1", "X", "mode")

        val control = DynamicParamTypeResolver.resolve(ref, source).control

        assertEquals("必须是这次 node-specific 查出来的那份", listOf("node_specific"), (control as DynamicParamControl.ComboField).options)
        assertEquals(listOf("X" to "mode"), source.optionsCalls)
    }

    @Test
    fun `node-specific 选项为空时才退化到定义自带的那份`() {
        val source = object : DynamicParamDefinitionSource {
            override fun inputDefinitionOf(classType: String, inputName: String) = InputDefinition(
                name = "mode", type = "ENUM", isRequired = true, options = listOf("fallback")
            )

            override fun optionsForNodeInput(classType: String, inputName: String) = emptyList<String>()
        }

        val control = DynamicParamTypeResolver.resolve(
            DynamicParamRef("p", "1", "X", "mode"), source
        ).control

        assertEquals(listOf("fallback"), (control as DynamicParamControl.ComboField).options)
    }
}
