package sh.hnet.comfychair.model

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 「自定义字段」与「编辑器写入口」的数据层测试。
 *
 * 覆盖四块，顺序即风险顺序：
 * 1. **兼容性**（最要紧）：老格式（只有 hidden / 只有 entry、完全没有 `customFields` 键）
 *    必须原样解析、不报错，且读出来是空列表；老记录序列化回去**不得多出任何键**；
 * 2. **序列化往返保真**：中文 label、引号/反斜杠/换行/emoji 等特殊字符、空列表、
 *    空白项剔除、null/非数组的容错；
 * 3. **写入口**：[ModelIntroTable.upsertEntry]（内置整条替换 / 自建新增两条路径 +
 *    hidden 保留 + 失败不更新内存）、[ModelIntroTable.deleteCustomEntry]（只允许删自建）、
 *    [ModelIntroTable.isCustomEntry]；
 * 4. **模板**：三套模板的 id / 显示名 / 预填 label 与「值一律为空」。
 *
 * 全部在 JVM 上跑：覆盖层文件用 [TemporaryFolder] 注入，不依赖 Context。
 */
class CustomFieldTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** 内置表：a / b / c 三条，其中 rel 齐全，便于同时验 lookup 与 lookupByRel。 */
    private val baseJson = """
    {
      "version": 1,
      "generated_at": "2026-11-22 16:22:28",
      "models": {
        "a.safetensors": {
          "file": "A.safetensors",
          "rel": "loras/sub/a.safetensors",
          "kind": "LoRA",
          "name": "A",
          "base": "SD 1.5"
        },
        "b.safetensors": {
          "file": "b.safetensors",
          "rel": "checkpoints/b.safetensors",
          "kind": "大模型",
          "name": "B",
          "base": "Illustrious"
        },
        "c.safetensors": {
          "file": "c.safetensors",
          "rel": "vae/c.safetensors",
          "kind": "VAE",
          "name": "C"
        }
      },
      "by_rel": {
        "loras/sub/a.safetensors": "a.safetensors",
        "checkpoints/b.safetensors": "b.safetensors",
        "vae/c.safetensors": "c.safetensors"
      }
    }
    """.trimIndent()

    private lateinit var overrideFile: File

    @Before
    fun setUp() {
        ModelIntroTable.setTableJsonForTest(baseJson)
        overrideFile = ModelIntroOverrides.fileIn(tmp.root)
        ModelIntroTable.bindOverridesForTest(overrideFile)
    }

    @After
    fun tearDown() {
        ModelIntroTable.clearForTest()
    }

    // ---------------- 1. 老数据兼容性 ----------------

    @Test
    fun `老格式覆盖层_没有customFields键_解析不报错且等于空列表`() {
        // 这就是老用户磁盘上的样子（旧版「导入介绍」写的，字段一个都没少，只是没有 customFields）
        val legacy = """
        {
          "b.safetensors": {"entry": {"file":"b.safetensors","kind":"大模型","name":"B 导入版",
                                      "params":{"步数":"28 步"},"tags":["t1"]}},
          "a.safetensors": {"hidden": true}
        }
        """.trimIndent()
        val parsed = ModelIntroOverrides.parse(legacy)

        assertEquals(2, parsed.size)
        val entry = parsed.getValue("b.safetensors").entry
        assertNotNull("老记录必须照常读出", entry)
        assertEquals("老数据解析出的自定义字段必须是空列表", emptyList<CustomField>(), entry!!.customFields)
        assertEquals("B 导入版", entry.name)
        assertEquals(mapOf("步数" to "28 步"), entry.params)
        assertTrue("hidden 记录不受影响", parsed.getValue("a.safetensors").hidden)
    }

    @Test
    fun `老格式内置表_没有customFields键_装载后每条都是空列表`() {
        // baseJson 里刻意不含 customFields：装完表后所有条目都必须是空列表而不是 null / 抛异常
        val all = ModelIntroTable.all()
        assertEquals(3, all.size)
        assertTrue(all.all { it.customFields == emptyList<CustomField>() })
        assertEquals("A", ModelIntroTable.lookup("a.safetensors")?.name)
    }

    @Test
    fun `老记录序列化回去不得多出customFields键`() {
        val oldHidden = ModelIntroOverrides.serialize(mapOf("a.safetensors" to OverrideEntry(hidden = true)))
        assertEquals("卸载记录的字节形态不能变", """{"a.safetensors":{"hidden":true}}""", oldHidden)

        val oldEntry = ModelIntroOverrides.serialize(
            mapOf("b.safetensors" to OverrideEntry(entry = ModelIntro(file = "b.safetensors", name = "B 导入版")))
        )
        assertFalse("没有自定义字段就不该写这个键：$oldEntry", oldEntry.contains("customFields"))
        assertFalse("空列表不该写成 \"customFields\":[]", oldEntry.contains("\"customFields\""))
    }

    @Test
    fun `customFields缺失_null_空数组_非数组_一律为空列表`() {
        fun fields(json: String): List<CustomField> =
            ModelIntroTable.introFromJson(JSONObject(json), fallbackFile = "x.safetensors").customFields

        assertEquals(emptyList<CustomField>(), fields("""{"file":"x.safetensors"}"""))
        assertEquals(emptyList<CustomField>(), fields("""{"customFields":null}"""))
        assertEquals(emptyList<CustomField>(), fields("""{"customFields":[]}"""))
        assertEquals(emptyList<CustomField>(), fields("""{"customFields":"不是数组"}"""))
        assertEquals(emptyList<CustomField>(), fields("""{"customFields":{}}"""))
        assertEquals(1, fields("""{"customFields":[{"label":"基座","value":"Flux"}]}""").size)
    }

    @Test
    fun `空白项与坏元素跳过_不渲染空行`() {
        val json = """
        {"customFields":[
          {"label":"基座","value":"Flux"},
          {"label":"","value":"没名字"},
          {"label":"  ","value":"名字全空白"},
          {"label":"值空白","value":""},
          {"label":"值空白","value":"   "},
          {"只有label":"基座"},
          "不是对象",
          {"value":"只有值"}
        ]}
        """.trimIndent()
        val fields = ModelIntroTable.introFromJson(JSONObject(json), fallbackFile = "x.safetensors").customFields
        assertEquals(listOf(CustomField("基座", "Flux")), fields)
    }

    // ---------------- 2. 序列化往返保真 ----------------

    @Test
    fun `序列化往返_中文与特殊字符不丢`() {
        val fields = listOf(
            CustomField("基座", "Qwen-Image-2.1"),
            CustomField("触发词", "1girl, masterpiece, \"引号\", \\反斜杠, {大括号}"),
            CustomField("推荐权重", "0.6–0.8（换行\n与制表\t都有）"),
            CustomField("emoji 😀", "https://civitai.com/models/1?a=1&b=2"),
            CustomField("效果", "逗号,分号;冒号:与「」《》——全角"),
        )
        val intro = ModelIntro(file = "dup.safetensors", name = "往返", customFields = fields)

        val viaTable = ModelIntroTable.introFromJson(ModelIntroTable.introToJson(intro), fallbackFile = "dup.safetensors")
        assertEquals("introToJson / introFromJson 必须逐字往返", fields, viaTable.customFields)

        val viaOverrides = ModelIntroOverrides.parse(
            ModelIntroOverrides.serialize(mapOf("dup.safetensors" to OverrideEntry(entry = intro)))
        ).getValue("dup.safetensors").entry
        assertEquals("覆盖层落盘链路也必须逐字往返", intro, viaOverrides)
    }

    @Test
    fun `序列化_顺序按用户排的序保持`() {
        val fields = listOf(
            CustomField("第三行", "3"),
            CustomField("第一行", "1"),
            CustomField("第二行", "2"),
        )
        val parsed = ModelIntroOverrides.parse(
            ModelIntroOverrides.serialize(mapOf("k.safetensors" to OverrideEntry(entry = ModelIntro(file = "k.safetensors", customFields = fields))))
        ).getValue("k.safetensors").entry!!
        assertEquals(listOf("第三行", "第一行", "第二行"), parsed.customFields.map { it.label })
    }

    @Test
    fun `序列化_只剔除空白项_有效项一个不丢`() {
        val intro = ModelIntro(
            file = "mix.safetensors",
            customFields = listOf(
                CustomField("留着", "有值"),
                CustomField("值空白", "  "),
                CustomField("", "没名字"),
                CustomField("也留着", "0"),
            ),
        )
        val obj = ModelIntroTable.introToJson(intro)
        val arr = obj.getJSONArray("customFields")
        assertEquals(2, arr.length())
        assertEquals("留着", arr.getJSONObject(0).getString("label"))
        assertEquals("也留着", arr.getJSONObject(1).getString("label"))
    }

    // ---------------- 3. 写入口：upsertEntry ----------------

    @Test
    fun `upsertEntry_内置条目_整条替换且条数不变`() {
        val edited = ModelIntro(
            file = "b.safetensors",
            rel = "checkpoints/b.safetensors",
            kind = "大模型",
            name = "B 我改过",
            customFields = listOf(CustomField("底模", "Flux.1 D"), CustomField("备注", "自己记的")),
        )
        assertTrue(ModelIntroTable.upsertEntry(edited))

        val hit = ModelIntroTable.lookup("b.safetensors")!!
        assertEquals("B 我改过", hit.name)
        assertEquals(edited.customFields, hit.customFields)
        assertNull("整条替换：没写的字段不该从内置条目继承", hit.base)
        assertEquals(3, ModelIntroTable.all().size)
        assertEquals("rel 索引要跟着指向替换后的条目", "B 我改过", ModelIntroTable.lookupByRel("checkpoints/b.safetensors")?.name)
        assertTrue("必须真的落盘", overrideFile.readText().contains("customFields"))

        // 冷启动：重新装表 + 重新绑定同一个覆盖层文件
        ModelIntroTable.clearForTest()
        ModelIntroTable.setTableJsonForTest(baseJson)
        ModelIntroTable.bindOverridesForTest(overrideFile)
        assertEquals("编辑必须在重启后还在", "B 我改过", ModelIntroTable.lookup("b.safetensors")?.name)
        assertEquals(2, ModelIntroTable.lookup("b.safetensors")!!.customFields.size)
    }

    @Test
    fun `upsertEntry_内置库没有的_追加成自建条目`() {
        val custom = ModelIntro(
            file = "my_custom.safetensors",
            rel = "loras/custom/my_custom.safetensors",
            kind = "LoRA",
            name = "我的 LoRA",
            customFields = ModelIntroTemplates.LORA.customFields.map {
                it.copy(value = if (it.label == "名字") "我的 LoRA" else "")
            },
        )
        assertTrue(ModelIntroTable.upsertEntry(custom))

        assertEquals(4, ModelIntroTable.all().size)
        assertEquals("我的 LoRA", ModelIntroTable.lookup("my_custom.safetensors")?.name)
        assertEquals(
            "自建条目的 rel 也要能被 lookupByRel 命中",
            "我的 LoRA",
            ModelIntroTable.lookupByRel("loras/custom/my_custom.safetensors")?.name,
        )
        assertTrue(ModelIntroTable.isCustomEntry("my_custom.safetensors"))
        assertFalse("内置条目不是自建", ModelIntroTable.isCustomEntry("b.safetensors"))
        // 模板预填空值 → 落盘只留下真正填了的那一行
        val saved = ModelIntroTable.lookup("my_custom.safetensors")!!
        assertEquals(listOf("名字"), saved.customFields.map { it.label })
    }

    @Test
    fun `upsertEntry_内存与磁盘一致_空白行当场剔除`() {
        // 编辑器草稿：模板预填的空值行 + 用户没起名的行，保存时当场剔除
        val draft = ModelIntro(
            file = "draft.safetensors",
            customFields = listOf(
                CustomField("名字", "草稿条目"),
                CustomField("底模", ""),
                CustomField("", "没起名字"),
                CustomField("备注", "有值"),
            ),
        )
        assertTrue(ModelIntroTable.upsertEntry(draft))
        val inMemory = ModelIntroTable.lookup("draft.safetensors")!!.customFields
        assertEquals(listOf("名字", "备注"), inMemory.map { it.label })

        // 冷启动读回来的必须与本次会话看到的一模一样（不能"当场有空行、重启才消失"）
        ModelIntroTable.clearForTest()
        ModelIntroTable.setTableJsonForTest(baseJson)
        ModelIntroTable.bindOverridesForTest(overrideFile)
        assertEquals(inMemory, ModelIntroTable.lookup("draft.safetensors")!!.customFields)
    }

    @Test
    fun `upsertEntry_保留hidden标记_不会把卸载的条目悄悄放回列表`() {
        assertTrue(ModelIntroTable.hide("b.safetensors"))
        val edited = ModelIntro(file = "b.safetensors", name = "B 改过", customFields = listOf(CustomField("基座", "Flux")))
        assertTrue(ModelIntroTable.upsertEntry(edited))
        assertNull("已卸载的条目编辑后仍然不出现", ModelIntroTable.lookup("b.safetensors"))
        assertEquals(listOf("b.safetensors"), ModelIntroTable.hiddenFiles())

        assertTrue(ModelIntroTable.restore("b.safetensors"))
        assertEquals("B 改过", ModelIntroTable.lookup("b.safetensors")?.name)
        assertEquals(listOf(CustomField("基座", "Flux")), ModelIntroTable.lookup("b.safetensors")!!.customFields)
    }

    @Test
    fun `upsertEntry_文件名为空白_返回false且视图不变`() {
        assertFalse(ModelIntroTable.upsertEntry(ModelIntro(file = "   ", name = "没有文件名")))
        assertEquals(3, ModelIntroTable.all().size)
        assertFalse("失败不该留下半成品文件", overrideFile.exists())
    }

    @Test
    fun `upsertEntry_未绑定覆盖层文件_返回false且内存不变`() {
        ModelIntroTable.clearForTest()
        ModelIntroTable.setTableJsonForTest(baseJson)

        assertFalse("没有落盘目标时不能谎报成功", ModelIntroTable.upsertEntry(ModelIntro(file = "b.safetensors", name = "B 改过")))
        assertEquals("B", ModelIntroTable.lookup("b.safetensors")?.name)
        assertFalse(ModelIntroTable.deleteCustomEntry("whatever.safetensors"))
    }

    // ---------------- 3. 写入口：deleteCustomEntry ----------------

    @Test
    fun `deleteCustomEntry_删得掉自建条目`() {
        assertTrue(
            ModelIntroTable.upsertEntry(
                ModelIntro(file = "mine.safetensors", name = "我的", customFields = listOf(CustomField("说明", "自建的")))
            )
        )
        assertEquals(4, ModelIntroTable.all().size)

        assertTrue(ModelIntroTable.deleteCustomEntry("mine.safetensors"))
        assertNull(ModelIntroTable.lookup("mine.safetensors"))
        assertEquals(3, ModelIntroTable.all().size)
        assertFalse("覆盖层里必须真的删掉", ModelIntroOverrides.load(overrideFile).containsKey("mine.safetensors"))
        assertFalse(ModelIntroTable.isCustomEntry("mine.safetensors"))

        // 冷启动后仍然不存在
        ModelIntroTable.clearForTest()
        ModelIntroTable.setTableJsonForTest(baseJson)
        ModelIntroTable.bindOverridesForTest(overrideFile)
        assertNull(ModelIntroTable.lookup("mine.safetensors"))
    }

    @Test
    fun `deleteCustomEntry_内置条目被拒_编辑过的也被拒`() {
        // 内置但没被编辑过
        assertFalse("内置条目只能 hide，不能删", ModelIntroTable.deleteCustomEntry("b.safetensors"))
        assertNotNull(ModelIntroTable.lookup("b.safetensors"))

        // 内置 + 编辑过（覆盖层有记录，但键在内置库里存在）→ 仍然拒
        assertTrue(ModelIntroTable.upsertEntry(ModelIntro(file = "b.safetensors", name = "B 改过")))
        assertFalse(ModelIntroTable.deleteCustomEntry("B.SafeTensors"))
        assertEquals("拒绝删除必须一点副作用都没有", "B 改过", ModelIntroTable.lookup("b.safetensors")?.name)
        assertFalse(ModelIntroTable.isCustomEntry("b.safetensors"))

        // 只能 hide
        assertTrue(ModelIntroTable.hide("b.safetensors"))
        assertNull(ModelIntroTable.lookup("b.safetensors"))
    }

    @Test
    fun `deleteCustomEntry_没记录_只有卸载标记_入参空白_都被拒`() {
        assertFalse("覆盖层里没有这条", ModelIntroTable.deleteCustomEntry("not_exists.safetensors"))
        assertFalse("入参空白", ModelIntroTable.deleteCustomEntry("  "))

        assertTrue(ModelIntroTable.hide("a.safetensors"))
        assertFalse("只有 hidden 标记的记录不是自建条目，取消隐藏请用 restore", ModelIntroTable.deleteCustomEntry("a.safetensors"))
        assertEquals("拒绝后卸载记录必须还在", listOf("a.safetensors"), ModelIntroTable.hiddenFiles())
        assertFalse(ModelIntroTable.isCustomEntry("a.safetensors"))
    }

    @Test
    fun `deleteCustomEntry_带着hidden的自建条目_整条摘掉`() {
        val custom = ModelIntro(file = "ghost.safetensors", name = "自建且已卸载")
        assertTrue(ModelIntroTable.upsertEntry(custom))
        assertTrue(ModelIntroTable.hide("ghost.safetensors"))
        assertEquals(listOf("ghost.safetensors"), ModelIntroTable.hiddenFiles())

        assertTrue(ModelIntroTable.deleteCustomEntry("ghost.safetensors"))
        assertTrue("整条记录（含 hidden 标记）都要消失", ModelIntroOverrides.load(overrideFile).isEmpty())
        assertTrue(ModelIntroTable.hiddenFiles().isEmpty())
    }

    // ---------------- 4. 模板 ----------------

    @Test
    fun `模板_三套且id唯一`() {
        val templates = ModelIntroTemplates.builtInTemplates()
        assertEquals(3, templates.size)
        assertEquals(listOf("lora", "checkpoint", "generic"), templates.map { it.id })
        assertEquals("id 必须唯一", templates.size, templates.map { it.id }.toSet().size)
        assertEquals(listOf("LoRA", "大模型", "通用"), templates.map { it.displayName })
        assertEquals(listOf(TemplateIcon.LORA, TemplateIcon.CHECKPOINT, TemplateIcon.GENERIC), templates.map { it.icon })
        assertEquals("LoRA", ModelIntroTemplates.findById("lora")?.displayName)
        assertNull(ModelIntroTemplates.findById("没有这个模板"))
        assertNull(ModelIntroTemplates.findById(null))
        assertNull(ModelIntroTemplates.findById(""))
    }

    @Test
    fun `模板_预填字段数量与label正确且值全为空`() {
        assertEquals(
            listOf("名字", "底模", "版本", "LoRA 效果", "触发词", "推荐权重"),
            ModelIntroTemplates.LORA.customFields.map { it.label },
        )
        assertEquals(
            listOf("名字", "底模", "版本", "模型效果", "推荐参数"),
            ModelIntroTemplates.CHECKPOINT.customFields.map { it.label },
        )
        assertEquals(
            listOf("名字", "说明", "效果"),
            ModelIntroTemplates.GENERIC.customFields.map { it.label },
        )
        for (t in ModelIntroTemplates.builtInTemplates()) {
            assertTrue("${t.id} 的预填值必须全为空", t.customFields.all { it.value.isEmpty() })
            assertTrue("${t.id} 的 label 不能空白", t.customFields.all { it.label.isNotBlank() })
        }
    }

    @Test
    fun `模板_与展示层共用同一批label常量`() {
        // 漂移检查：模板写的字段名必须与元信息头用的是同一个字符串
        assertEquals(ModelIntroPresentation.LABEL_NAME, ModelIntroTemplates.LORA.customFields[0].label)
        assertEquals(ModelIntroPresentation.LABEL_BASE, ModelIntroTemplates.CHECKPOINT.customFields[1].label)
        assertEquals(ModelIntroPresentation.LABEL_MODEL_EFFECT, ModelIntroTemplates.CHECKPOINT.customFields[3].label)
        assertEquals(ModelIntroPresentation.LABEL_LORA_EFFECT, ModelIntroTemplates.LORA.customFields[3].label)
    }

    @Test
    fun `模板套到自建条目上_用户填完能落盘`() {
        val filled = ModelIntroTemplates.GENERIC.customFields.map {
            it.copy(value = "${it.label}的内容")
        }
        assertTrue(ModelIntroTable.upsertEntry(ModelIntro(file = "tpl.safetensors", customFields = filled)))
        val saved = ModelIntroTable.lookup("tpl.safetensors")!!
        assertEquals(filled, saved.customFields)
        assertEquals(4, ModelIntroTable.all().size)
    }

    // ---------------- 附加：合并语义没变（自定义字段穿过 applyOverrides） ----------------

    @Test
    fun `applyOverrides_自建条目带着自定义字段追加到末尾`() {
        val base = listOf(ModelIntro(file = "a.safetensors"), ModelIntro(file = "b.safetensors"))
        val added = ModelIntro(file = "z.safetensors", customFields = listOf(CustomField("基座", "Flux")))
        val out = ModelIntroOverrides.applyOverrides(base, mapOf("z.safetensors" to OverrideEntry(entry = added)))
        assertEquals(listOf("a.safetensors", "b.safetensors", "z.safetensors"), out.map { it.file })
        assertEquals(listOf(CustomField("基座", "Flux")), out.last().customFields)
        assertEquals("内置条目不受影响", emptyList<CustomField>(), out[0].customFields)
    }
}
