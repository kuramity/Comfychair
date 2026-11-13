package sh.hnet.comfychair.ui.components.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.hnet.comfychair.workflow.dynamic.DynamicParam
import sh.hnet.comfychair.workflow.dynamic.DynamicParamControl

/**
 * 「节点参数」分组的静默问题扫描（[DynamicParamIssueScanner]）+ UI 映射
 * （[buildDynamicParamConfig]）单测（纯逻辑，可 JVM 跑）。
 *
 * 钉住三件在审查里被点名的事：
 * 1. 只扫**节点输入值位置**的 token（`description` / `name` / `defaults` 不算参数，问题 4）
 * 2. `takenNames` 只报「已识别名字写在内置参数不管辖的输入上」（正常用法不报，问题 3）；
 *    其中正/负向提示词是**全文替换**，真实承载输入不止 `CLIPTextEncode.text`，
 *    还包含 `MiniMaxH3*.prompt` / `PrimitiveStringMultiline.value` / `CLIPTextEncodeSDXL.text_g|text_l`
 *    等（别名表 `PROMPT_TEXT_INPUTS`，复审判出的新缺陷）
 * 3. Bool 值解析、label 拼接、下拉选项原序透传（问题 5 的映射层保证）
 */
class DynamicParamIssueScannerTest {

    private fun workflow(nodesJson: String) = """{"name":"t","nodes":$nodesJson}"""

    // ================================================================
    // 1. 非法名（只认节点输入位置）
    // ================================================================

    @Test
    fun `节点输入上的非法名会被报出`() {
        val json = workflow(
            """{"1":{"class_type":"KSampler","inputs":{"steps":"{{my-bad-name}}"}}}"""
        )

        assertEquals(listOf("my-bad-name"), DynamicParamIssueScanner.scan(json).invalidNames)
    }

    @Test
    fun `嵌在节点输入文本里的非法 token 也会被报出`() {
        val json = workflow(
            """{"1":{"class_type":"SaveImage","inputs":{"filename_prefix":"out-{{my-bad-name}}"}}}"""
        )

        assertEquals(listOf("my-bad-name"), DynamicParamIssueScanner.scan(json).invalidNames)
    }

    @Test
    fun `非法名去重但保持首次出现顺序`() {
        val json = workflow(
            """
            {"1":{"class_type":"A","inputs":{"x":"{{b-bad}}","y":"{{a-bad}}"}},
             "2":{"class_type":"B","inputs":{"z":"{{b-bad}}"}}}
            """.trimIndent()
        )

        assertEquals(listOf("b-bad", "a-bad"), DynamicParamIssueScanner.scan(json).invalidNames)
    }

    @Test
    fun `描述 名称 与 defaults 里的 token 不再误报（问题 4）`() {
        val json = """
            {"name":"{{my-bad-name}}",
             "description":"提示词模板里写着 {{my-bad-name}} 和 {{another-bad}}",
             "defaults":{"{{my-bad-name}}":1,"my_steps":30},
             "nodes":{"1":{"class_type":"CLIPTextEncode","inputs":{"text":"{{positive_prompt}}"}}}}
        """.trimIndent()

        val issues = DynamicParamIssueScanner.scan(json)

        assertFalse("description/name/defaults 不是节点输入，不该报非法名", issues.hasInvalidNames)
        assertFalse(issues.hasTakenNames)
    }

    // ================================================================
    // 2. takenNames：只有「内置名字写到别的输入上」才报
    // ================================================================

    @Test
    fun `内置占位符用在内置管辖的输入上不报（否则每张工作流都常驻提示）`() {
        val json = workflow(
            """
            {"6":{"class_type":"CLIPTextEncode","inputs":{"text":"{{positive_prompt}}"}},
             "7":{"class_type":"CLIPTextEncode","inputs":{"text":"{{negative_prompt}}"}},
             "294":{"class_type":"KSampler","inputs":{"steps":"{{steps}}","cfg":"{{cfg}}","seed":"{{seed}}",
                    "sampler_name":"{{sampler_name}}","scheduler":"{{scheduler}}","denoise":"{{denoise}}"}},
             "11":{"class_type":"TripleCLIPLoader","inputs":{"clip_name1":"{{clip_name1}}","clip_name2":"{{clip_name2}}"}},
             "301":{"class_type":"UnetLoaderGGUF","inputs":{"unet_name":"{{unet_name}}"}},
             "304":{"class_type":"VAELoader","inputs":{"vae_name":"{{vae_name}}"}},
             "9":{"class_type":"RandomNoise","inputs":{"noise_seed":"{{noise_seed}}"}}}
            """.trimIndent()
        )

        val issues = DynamicParamIssueScanner.scan(json)

        assertFalse("正常用法不该产生任何提示", issues.hasTakenNames)
        assertFalse(issues.hasInvalidNames)
    }

    @Test
    fun `内置的 scale_by 落在 UltimateSDUpscale 的 upscale_by 上不报（prebuilt_19 的正常用法）`() {
        // 「放大倍数」内置于 App（`{{scale_by}}` 走全文替换 + 通用区的数字控件），
        // 但两个节点家族的输入名不同：ImageScaleBy 叫 scale_by，UltimateSDUpscale 叫 upscale_by。
        // 后者正是 prebuilt_19 暴露给用户的「放大倍数」，不该弹「名字由内置参数处理」的提示（Fix Round 1 / I-5）
        val json = workflow(
            """
            {"199":{"class_type":"UltimateSDUpscale","inputs":{"upscale_by":"{{scale_by}}","tile_width":1024,"tile_height":1024}},
             "232":{"class_type":"ImageScaleBy","inputs":{"scale_by":"{{hires_scale}}"}}}
            """.trimIndent()
        )

        val issues = DynamicParamIssueScanner.scan(json)

        assertTrue("两个承载位置都是内置管辖，不该有提示：$issues", issues.takenNames.isEmpty())
        assertFalse(issues.hasInvalidNames)
    }

    @Test
    fun `内置的 scale_by 落在无关输入上仍然报 taken（别名只放行 upscale_by）`() {
        val json = workflow(
            """{"999":{"class_type":"MyPluginNode","inputs":{"zoom":"{{scale_by}}"}}}"""
        )

        assertEquals(listOf("scale_by"), DynamicParamIssueScanner.scan(json).takenNames)
    }

    @Test
    fun `内置名字写在内置不管辖的输入上报为 taken（问题 3）`() {
        val json = workflow(
            """
            {"7":{"class_type":"MyPluginNode","inputs":{"step_count":"{{steps}}","lora":"{{lora_name}}"}}}
            """.trimIndent()
        )

        val issues = DynamicParamIssueScanner.scan(json)

        // 顺序确定：节点按引擎规则排序，同一节点内输入名按字典序（lora < step_count）
        assertEquals(listOf("lora_name", "steps"), issues.takenNames)
        assertFalse(issues.hasInvalidNames)
    }

    @Test
    fun `提示词的图追踪输入（text）算内置管辖`() {
        // 内置正/负向提示词走图追踪注入 CLIPTextEncode.text（不是映射表里的 positive_text），
        // 若不特判，任何工作流都会把 positive_prompt 误报成 taken
        val json = workflow(
            """{"6":{"class_type":"CLIPTextEncode","inputs":{"text":"prefix {{positive_prompt}}"}}}"""
        )

        assertFalse(DynamicParamIssueScanner.scan(json).hasTakenNames)
    }

    @Test
    fun `提示词落在 MiniMaxH3ImageToVideo prompt 上不报（prebuilt 13-16 的正常用法）`() {
        // 正/负向提示词是全文替换（"{{positive_prompt}}" → 提示词），不限定输入名；
        // 视频工作流的提示词节点输入就叫 prompt。别名表只登记 text 时这里会误报。
        val json = workflow(
            """
            {"1":{"class_type":"MiniMaxH3ImageToVideo","inputs":{"prompt":"{{positive_prompt}}","length":121}},
             "2":{"class_type":"MiniMaxH3ReferenceToVideo","inputs":{"prompt":"{{positive_prompt}}"}}}
            """.trimIndent()
        )

        val issues = DynamicParamIssueScanner.scan(json)

        assertFalse("MiniMaxH3*.prompt 是真实承载提示词的输入，不该报 taken", issues.hasTakenNames)
        assertFalse(issues.hasInvalidNames)
    }

    @Test
    fun `提示词落在 PrimitiveStringMultiline value 与 Krea2EditGroundedEncode prompt 上不报（prebuilt 08-09）`() {
        // prebuilt_08/09 的提示词形态：正向 → PrimitiveStringMultiline.value，
        // 负向 → Krea2EditGroundedEncode.prompt。两者都是真实承载输入。
        val json = workflow(
            """
            {"1":{"class_type":"PrimitiveStringMultiline","inputs":{"value":"{{positive_prompt}}"}},
             "2":{"class_type":"Krea2EditGroundedEncode","inputs":{"prompt":"{{negative_prompt}}"}}}
            """.trimIndent()
        )

        val issues = DynamicParamIssueScanner.scan(json)

        assertFalse("PrimitiveStringMultiline.value / Krea2EditGroundedEncode.prompt 不该报 taken", issues.hasTakenNames)
        assertFalse(issues.hasInvalidNames)
    }

    @Test
    fun `提示词落在 SDXL 双编码器的 text_g text_l 上不报`() {
        val json = workflow(
            """
            {"1":{"class_type":"CLIPTextEncodeSDXL","inputs":{"text_g":"{{positive_prompt}}","text_l":"{{negative_prompt}}"}}}
            """.trimIndent()
        )

        assertFalse(DynamicParamIssueScanner.scan(json).hasTakenNames)
    }

    @Test
    fun `提示词落在 String 节点上不报，而内置名落在无关输入上照旧要报`() {
        // 同一个节点里：提示词是真实承载输入（不报），steps 写到自定义输入上仍是真争抢（要报）
        val json = workflow(
            """
            {"1":{"class_type":"String","inputs":{"String":"{{positive_prompt}}"}},
             "2":{"class_type":"MyPluginNode","inputs":{"step_count":"{{steps}}"}}}
            """.trimIndent()
        )

        val issues = DynamicParamIssueScanner.scan(json)

        assertEquals(listOf("steps"), issues.takenNames)
    }

    // ================================================================
    // 3. 边界：连线 / 非字符串 / 非法 JSON / 裸节点表
    // ================================================================

    @Test
    fun `连线 数值 布尔与非法 JSON 都不产生问题`() {
        val json = workflow(
            """{"1":{"class_type":"A","inputs":{"model":["2",0],"count":8,"flag":true,"none":null}}}"""
        )

        val issues = DynamicParamIssueScanner.scan(json)

        assertFalse(issues.hasInvalidNames)
        assertFalse(issues.hasTakenNames)
        assertFalse(DynamicParamIssueScanner.scan("not json").hasInvalidNames)
        assertFalse(DynamicParamIssueScanner.scan("{}").hasInvalidNames)
    }

    @Test
    fun `裸节点表同样能扫描（与引擎的 nodesOf 判定一致）`() {
        val raw = """{"1":{"class_type":"A","inputs":{"x":"{{my-bad-name}}"}}}"""

        assertEquals(listOf("my-bad-name"), DynamicParamIssueScanner.scan(raw).invalidNames)
    }

    // ================================================================
    // 4. UI 映射（buildDynamicParamConfig）
    // ================================================================

    private fun boolField(value: String): DynamicParamField.Control.Bool {
        val config = buildDynamicParamConfig(
            workflowId = "w",
            params = listOf(DynamicParam("f", "1", "N", "flag", DynamicParamControl.BoolField(null))),
            values = mapOf("f" to value)
        ) { _, _ -> }
        return config.fields.single().control as DynamicParamField.Control.Bool
    }

    @Test
    fun `Bool 值解析忽略大小写，空值算关`() {
        assertTrue(boolField("true").checked)
        assertTrue(boolField("TRUE").checked)
        assertFalse(boolField("false").checked)
        assertFalse(boolField("").checked)
    }

    @Test
    fun `classType 为空时标签用输入名，否则拼 class_type 点 输入名`() {
        val config = buildDynamicParamConfig(
            workflowId = "w",
            params = listOf(
                DynamicParam("a", "1", "", "my_input", DynamicParamControl.TextField(false, null)),
                DynamicParam("b", "2", "MyNode", "other", DynamicParamControl.TextField(false, null))
            ),
            values = emptyMap()
        ) { _, _ -> }

        assertEquals("my_input", config.fields[0].nodeLabel)
        assertEquals("MyNode.other", config.fields[1].nodeLabel)
    }

    @Test
    fun `下拉选项按服务器原序透传，映射层不排序（问题 5）`() {
        // scheduler 的服务器原序以 simple 开头，按字母序会变成 beta 开头
        val options = listOf("simple", "karras", "beta", "normal")
        val config = buildDynamicParamConfig(
            workflowId = "w",
            params = listOf(
                DynamicParam(
                    "s", "1", "KSampler", "scheduler",
                    DynamicParamControl.ComboField(options, null)
                )
            ),
            values = mapOf("s" to "simple")
        ) { _, _ -> }

        val combo = config.fields.single().control as DynamicParamField.Control.Combo
        assertEquals(options, combo.options)
        assertEquals("用户已保存的值原样带出", "simple", config.fields.single().value)
    }

    @Test
    fun `registryReady 原样带进配置（保证与字段同一次快照）`() {
        val config = buildDynamicParamConfig(
            workflowId = "w",
            params = emptyList(),
            values = emptyMap(),
            registryReady = true
        ) { _, _ -> }

        assertTrue(config.registryReady)
        assertTrue(config.fields.isEmpty())
    }
}
