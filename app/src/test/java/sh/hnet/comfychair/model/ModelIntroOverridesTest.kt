package sh.hnet.comfychair.model

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
 * 介绍库**用户覆盖层**的数据层测试（第一阶段：纯数据，无界面）。
 *
 * 两层用例：
 * 1. **纯函数**（[ModelIntroOverrides]）：合并规则（隐藏剔除 / 整条替换 / 覆盖层新增 /
 *    hidden 优先）、键归一、序列化往返、坏文件退化、原子写不残留 tmp；
 * 2. **表级**（[ModelIntroTable]）：`hide` / `restore` / `hiddenFiles` / `hiddenIntro`
 *    落盘生效，以及最要紧的一条——**内置库更新与服务器热更新落盘之后，覆盖层仍然生效**
 *    （合并发生在读取时，数据文件怎么换都压不掉用户的卸载）。
 *
 * 「导入介绍」功能已下线，`ModelIntroTable.importEntries` 随之删除；但老版本写进覆盖层的
 * `entry` 记录还在用户磁盘上，所以表级用例改用 [seedOverrides] **直接落盘覆盖层文件**再重新
 * 绑定，钉住这条读取链路（整条替换 / 新增可查 / hidden 优先 / 已卸载展示数据取值）。
 *
 * 所有用例都在 JVM 上跑：覆盖层文件用 [TemporaryFolder] 注入（[ModelIntroTable.bindOverridesForTest]），
 * 不依赖 Context。
 */
class ModelIntroOverridesTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** 内置表（3 条，覆盖 LoRA / 大模型 / VAE 三种目录段）。 */
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
          "summary": "A 的摘要"
        },
        "b.safetensors": {
          "file": "b.safetensors",
          "rel": "checkpoints/b.safetensors",
          "kind": "大模型",
          "name": "B",
          "summary": "B 的摘要"
        },
        "c.safetensors": {
          "file": "c.safetensors",
          "rel": "vae/c.safetensors",
          "kind": "VAE",
          "name": "C",
          "summary": "C 的摘要"
        }
      },
      "by_rel": {
        "loras/sub/a.safetensors": "a.safetensors",
        "checkpoints/b.safetensors": "b.safetensors",
        "vae/c.safetensors": "c.safetensors"
      }
    }
    """.trimIndent()

    /** 模拟「APK 内置库更新」：同样的文件名，内容换了一版。 */
    private val updatedJson = """
    {
      "version": 2,
      "generated_at": "2026-11-23 09:00:00",
      "models": {
        "a.safetensors": {
          "file": "A.safetensors",
          "rel": "loras/sub/a.safetensors",
          "kind": "LoRA",
          "name": "A 新版",
          "summary": "新数据里的 A"
        },
        "b.safetensors": {
          "file": "b.safetensors",
          "rel": "checkpoints/b.safetensors",
          "kind": "大模型",
          "name": "B 新版",
          "summary": "新数据里的 B"
        },
        "c.safetensors": {
          "file": "c.safetensors",
          "rel": "vae/c.safetensors",
          "kind": "VAE",
          "name": "C 新版",
          "summary": "新数据里的 C"
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

    // ---------------- 纯函数：合并规则 ----------------

    @Test
    fun `applyOverrides_隐藏生效_条目被剔除`() {
        val base = listOf(intro("a.safetensors"), intro("b.safetensors"), intro("c.safetensors"))
        val out = ModelIntroOverrides.applyOverrides(
            base,
            mapOf("b.safetensors" to OverrideEntry(hidden = true)),
        )
        assertEquals(listOf("a.safetensors", "c.safetensors"), out.map { it.file })
    }

    @Test
    fun `applyOverrides_entry整条替换_位置不变`() {
        val base = listOf(intro("a.safetensors"), intro("b.safetensors"), intro("c.safetensors"))
        val replacement = ModelIntro(file = "b.safetensors", kind = "大模型", name = "B 导入版")
        val out = ModelIntroOverrides.applyOverrides(
            base,
            mapOf("b.safetensors" to OverrideEntry(entry = replacement)),
        )
        assertEquals(3, out.size)
        assertEquals("顺序必须保持内置库顺序", listOf("a.safetensors", "b.safetensors", "c.safetensors"), out.map { it.file })
        assertEquals("B 导入版", out[1].name)
        assertEquals("替换必须是整条，不是字段合并", "B 导入版", out[1].name)
    }

    @Test
    fun `applyOverrides_entry新增_内置库没有的键追加在末尾`() {
        val base = listOf(intro("a.safetensors"), intro("b.safetensors"))
        val added = ModelIntro(file = "custom.safetensors", kind = "LoRA", name = "自定义")
        val out = ModelIntroOverrides.applyOverrides(
            base,
            mapOf("custom.safetensors" to OverrideEntry(entry = added)),
        )
        assertEquals(3, out.size)
        assertEquals("custom.safetensors", out.last().file)
        assertEquals("自定义", out.last().name)
    }

    @Test
    fun `applyOverrides_hidden优先于entry_卸载不会被更新翻回来`() {
        val base = listOf(intro("a.safetensors"), intro("b.safetensors"))
        val out = ModelIntroOverrides.applyOverrides(
            base,
            mapOf(
                "b.safetensors" to OverrideEntry(
                    hidden = true,
                    entry = ModelIntro(file = "b.safetensors", name = "更新后的 B"),
                )
            ),
        )
        assertEquals(listOf("a.safetensors"), out.map { it.file })
    }

    @Test
    fun `applyOverrides_空覆盖层原样返回`() {
        val base = listOf(intro("a.safetensors"), intro("b.safetensors"))
        assertEquals(base, ModelIntroOverrides.applyOverrides(base, emptyMap()))
    }

    @Test
    fun `applyOverrides_键大小写与路径归一`() {
        val base = listOf(intro("a.safetensors"), intro("b.safetensors"))
        val out = ModelIntroOverrides.applyOverrides(
            base,
            // 大写 + 反斜杠 + 带目录：都必须归一到 b.safetensors
            mapOf("loras\\Sub\\B.SafeTensors" to OverrideEntry(hidden = true)),
        )
        assertEquals(listOf("a.safetensors"), out.map { it.file })
    }

    @Test
    fun `applyOverrides_内置库重复同名条目只留第一条`() {
        val first = ModelIntro(file = "dup.safetensors", name = "第一条")
        val second = ModelIntro(file = "dup.safetensors", name = "第二条")
        val out = ModelIntroOverrides.applyOverrides(listOf(first, second), emptyMap())
        assertEquals(2, out.size) // 空覆盖层不参与去重（原样返回，保持 O(1) 快路径）
        val hidden = ModelIntroOverrides.applyOverrides(
            listOf(first, second),
            mapOf("other.safetensors" to OverrideEntry(hidden = true)),
        )
        assertEquals(1, hidden.size)
        assertEquals("第一条", hidden[0].name)
    }

    // ---------------- 纯函数：序列化 / 反序列化 ----------------

    @Test
    fun `序列化往返_entry全字段不丢`() {
        val full = ModelIntro(
            file = "Full.safetensors",
            rel = "loras/image/krea2/full.safetensors",
            kind = "LoRA",
            name = "Full Model",
            type = "LoRA（LoCon）",
            base = "Krea 2",
            author = "someone",
            version = "v2.0",
            summary = "「Full」是 Krea 2 底模的 LoRA…",
            effect = "效果：……",
            params = linkedMapOf("步数" to "8", "CFG" to "1"),
            authorParams = linkedMapOf("推荐分辨率" to "1024x1024"),
            companions = "搭配 VAE：x.safetensors",
            tags = listOf("tag1", "tag2"),
            usedIn = listOf("竖叉"),
            url = "https://civitai.com/models/1",
            introOriginal = "作者原文……",
            source = "civitai",
            missing = true,
            loraWeight = LoraWeightSuggestion(
                min = 0.6,
                max = 1.0,
                default = 0.8,
                source = "intro_original",
                quote = "0.6 to 1.0",
            ),
            localPositioning = "动漫插画 + 真实皮肤质感",
            communityParams = linkedMapOf(
                "sampler" to CommunityParam("Euler a", 17),
                "cfg" to CommunityParam("3", 12),
            ),
            communitySamples = CommunitySamples(total = 50, withMeta = 33, withParams = 22),
            communityPrompt = CommunityPrompt(positive = "masterpiece", negative = "bad hands", likes = 5),
            confidenceNote = "medium：命名匹配推断",
            introMissing = true,
        )
        val json = ModelIntroOverrides.serialize(
            mapOf("full.safetensors" to OverrideEntry(entry = full))
        )
        val parsed = ModelIntroOverrides.parse(json)
        assertEquals(1, parsed.size)
        assertEquals("覆盖层里的条目必须与内置条目一样完整", full, parsed.getValue("full.safetensors").entry)
    }

    @Test
    fun `解析_hidden字符串true也识别`() {
        val parsed = ModelIntroOverrides.parse("""{"a.safetensors":{"hidden":"true"}}""")
        assertTrue(parsed.getValue("a.safetensors").hidden)
    }

    @Test
    fun `解析_空壳记录不占位`() {
        val parsed = ModelIntroOverrides.parse(
            """{"a.safetensors":{"hidden":false},"b.safetensors":{}}"""
        )
        assertTrue("hidden=false 且无 entry 的壳不该留在覆盖层里", parsed.isEmpty())
    }

    @Test
    fun `解析_坏JSON退化为空表不抛异常`() {
        assertTrue(ModelIntroOverrides.parse("{ 半截写入 ").isEmpty())
        assertTrue(ModelIntroOverrides.parse("").isEmpty())
        assertTrue(ModelIntroOverrides.parse(null).isEmpty())
    }

    // ---------------- 文件读写 ----------------

    @Test
    fun `保存后重新读取一致_且不残留临时文件`() {
        val overrides = mapOf(
            "a.safetensors" to OverrideEntry(hidden = true),
            "custom.safetensors" to OverrideEntry(entry = ModelIntro(file = "custom.safetensors", name = "自定义")),
        )
        assertTrue(ModelIntroOverrides.save(overrideFile, overrides))
        assertEquals("落盘内容必须能原样读回", overrides, ModelIntroOverrides.load(overrideFile))

        val leftovers = tmp.root.listFiles()?.filter { it.name.endsWith(".tmp") }
        assertTrue("原子写不得残留临时文件：$leftovers", leftovers.isNullOrEmpty())
    }

    @Test
    fun `丢失或损坏的覆盖层文件按空覆盖层处理`() {
        assertTrue(ModelIntroOverrides.load(File(tmp.root, "not_exists.json")).isEmpty())
        overrideFile.writeText("{ 半截写入 ")
        assertTrue("坏文件只应让覆盖层失效，内置库照常可用", ModelIntroOverrides.load(overrideFile).isEmpty())
    }

    // ---------------- 表级：隐藏 / 恢复 ----------------

    @Test
    fun `隐藏生效_lookup与all与lookupByRel都不再返回`() {
        assertTrue(ModelIntroTable.hide("A.safetensors"))

        assertNull("隐藏后按文件名查不到", ModelIntroTable.lookup("a.safetensors"))
        assertNull("隐藏后按 rel 也查不到", ModelIntroTable.lookupByRel("loras/sub/a.safetensors"))
        assertEquals(
            listOf("b.safetensors", "c.safetensors"),
            ModelIntroTable.all().map { it.file }.sorted(),
        )
        assertEquals(listOf("a.safetensors"), ModelIntroTable.hiddenFiles())
        // 落盘检查：覆盖层里必须真的记下了卸载
        assertTrue(
            "卸载记录应写进覆盖层：${overrideFile.readText()}",
            overrideFile.readText().contains("\"hidden\":true"),
        )
    }

    @Test
    fun `恢复生效_条目重新出现`() {
        assertTrue(ModelIntroTable.hide("a.safetensors"))
        assertNull(ModelIntroTable.lookup("a.safetensors"))

        assertTrue(ModelIntroTable.restore("a.safetensors"))
        assertEquals("A", ModelIntroTable.lookup("a.safetensors")?.name)
        assertEquals(3, ModelIntroTable.all().size)
        assertTrue("恢复后隐藏名单清空", ModelIntroTable.hiddenFiles().isEmpty())
        assertTrue("恢复后覆盖层不留空壳", ModelIntroOverrides.load(overrideFile).isEmpty())
    }

    @Test
    fun `恢复的幂等性_没隐藏过也返回成功`() {
        assertTrue(ModelIntroTable.restore("a.safetensors"))
        assertTrue(ModelIntroTable.restore("not_exists.safetensors"))
        assertEquals(3, ModelIntroTable.all().size)
    }

    @Test
    fun `卸载持久化_冷启动重新绑定后仍隐藏`() {
        assertTrue(ModelIntroTable.hide("a.safetensors"))

        // 模拟冷启动：内存全清 → 重新装同一份内置表 → 重新绑定同一个覆盖层文件
        ModelIntroTable.clearForTest()
        ModelIntroTable.setTableJsonForTest(baseJson)
        ModelIntroTable.bindOverridesForTest(overrideFile)

        assertNull("冷启动后卸载必须仍然生效", ModelIntroTable.lookup("a.safetensors"))
        assertEquals(listOf("a.safetensors"), ModelIntroTable.hiddenFiles())
    }

    @Test
    fun `未绑定覆盖层文件时hide返回false且视图不变`() {
        ModelIntroTable.clearForTest()
        ModelIntroTable.setTableJsonForTest(baseJson)

        assertFalse("没有落盘目标时不能谎报成功", ModelIntroTable.hide("a.safetensors"))
        assertNotNull("内存视图必须保持原样", ModelIntroTable.lookup("a.safetensors"))
    }

    // ---------------- 表级：覆盖层里的 entry（旧版导入写入的记录，读取链路仍要生效） ----------------

    @Test
    fun `覆盖层entry_内置条目被整条替换`() {
        seedOverrides(
            "b.safetensors" to OverrideEntry(
                entry = ModelIntro(
                    file = "b.safetensors",
                    rel = "checkpoints/b.safetensors",
                    kind = "大模型",
                    name = "B 导入版",
                    summary = "导入的摘要",
                )
            )
        )

        val hit = ModelIntroTable.lookup("b.safetensors")!!
        assertEquals("B 导入版", hit.name)
        assertEquals("导入的摘要", hit.summary)
        assertEquals(3, ModelIntroTable.all().size)
    }

    @Test
    fun `覆盖层entry新增_按文件名与rel都能查到且出现在all里`() {
        seedOverrides(
            "my_custom.safetensors" to OverrideEntry(
                entry = ModelIntro(
                    file = "my_custom.safetensors",
                    rel = "loras/custom/my_custom.safetensors",
                    kind = "LoRA",
                    name = "自定义 LoRA",
                )
            )
        )

        assertEquals("自定义 LoRA", ModelIntroTable.lookup("my_custom.safetensors")?.name)
        assertEquals(
            "覆盖层新增条目的 rel 也要能被 lookupByRel 命中",
            "自定义 LoRA",
            ModelIntroTable.lookupByRel("loras/custom/my_custom.safetensors")?.name,
        )
        assertTrue(ModelIntroTable.all().any { it.file == "my_custom.safetensors" })
        assertEquals(4, ModelIntroTable.all().size)
    }

    @Test
    fun `覆盖层hidden优先于entry_要显示必须显式恢复`() {
        seedOverrides(
            "b.safetensors" to OverrideEntry(
                hidden = true,
                entry = ModelIntro(file = "b.safetensors", name = "B 导入版"),
            )
        )
        assertNull("卸载过的条目不会因为 entry 悄悄回到列表", ModelIntroTable.lookup("b.safetensors"))

        assertTrue(ModelIntroTable.restore("b.safetensors"))
        assertEquals("恢复后看到的是覆盖层里那版", "B 导入版", ModelIntroTable.lookup("b.safetensors")?.name)
    }

    // ---------------- 表级：数据更新后覆盖层仍然生效（本任务核心） ----------------

    @Test
    fun `内置库更新后覆盖层仍生效`() {
        seedOverrides(
            "a.safetensors" to OverrideEntry(hidden = true),
            "b.safetensors" to OverrideEntry(
                entry = ModelIntro(file = "b.safetensors", name = "B 导入版"),
            ),
        )

        // 模拟 APK 内置库整份更新（数据文件被换成新的一版）
        ModelIntroTable.setTableJsonForTest(updatedJson)

        assertNull("内置更新不得把卸载的条目放回来", ModelIntroTable.lookup("a.safetensors"))
        assertEquals("覆盖层条目必须压过新版内置条目", "B 导入版", ModelIntroTable.lookup("b.safetensors")?.name)
        assertEquals("C 不在覆盖层里，用新版数据", "C 新版", ModelIntroTable.lookup("c.safetensors")?.name)
        assertEquals(2, ModelIntroTable.all().size)
    }

    @Test
    fun `服务器热更新落盘后覆盖层仍生效`() {
        seedOverrides(
            "a.safetensors" to OverrideEntry(hidden = true),
            "c.safetensors" to OverrideEntry(
                entry = ModelIntro(file = "c.safetensors", name = "C 导入版"),
            ),
        )

        // 走真实的同步路径：ModelIntroSync.apply → reloadFrom（热更新整份替换内存表）
        val cacheFile = File(tmp.root, ModelIntroTable.CACHE_FILE_NAME)
        val result = ModelIntroSync.apply(
            remoteJson = updatedJson,
            cacheFile = cacheFile,
            localGeneratedAt = "2026-11-22 16:22:28",
            localVersion = 1,
        )
        assertEquals(ModelIntroSync.Result.UPDATED, result)

        assertNull("热更新不得把卸载的条目放回来", ModelIntroTable.lookup("a.safetensors"))
        assertEquals("覆盖层条目必须压过热更新条目", "C 导入版", ModelIntroTable.lookup("c.safetensors")?.name)
        assertEquals("B 用热更新的新版数据", "B 新版", ModelIntroTable.lookup("b.safetensors")?.name)
        assertEquals(2, ModelIntroTable.all().size)
        assertEquals(listOf("a.safetensors"), ModelIntroTable.hiddenFiles())
    }

    // ---------------- 表级：界面用的补充入口（v4.21） ----------------

    @Test
    fun `已卸载条目的展示数据可查_有entry的取覆盖层内容`() {
        seedOverrides(
            "a.safetensors" to OverrideEntry(hidden = true),
            "custom.safetensors" to OverrideEntry(
                entry = ModelIntro(file = "custom.safetensors", name = "自定义"),
            ),
        )
        // 覆盖层没有 entry 时，回退内置库里的名字
        assertEquals("A", ModelIntroTable.hiddenIntro("a.safetensors")?.name)
        assertNull("隐藏条目不在视图里", ModelIntroTable.lookup("a.safetensors"))

        // 再卸载这个覆盖层新增条目：展示数据优先用覆盖层里那份 entry
        assertTrue(ModelIntroTable.hide("custom.safetensors"))
        assertEquals("自定义", ModelIntroTable.hiddenIntro("custom.safetensors")?.name)
        assertNull("内置库没有的条目也查不到", ModelIntroTable.hiddenIntro("not_exists.safetensors"))
    }

    /**
     * 直接落盘一份覆盖层再重新绑定，等价于「老版本导入 / 卸载留下的记录已经在磁盘上」。
     *
     * 导入功能下线后 `ModelIntroTable.importEntries` 也删了，表级用例不再有写 entry 的入口，
     * 但读取链路（applyOverrides / hiddenIntro / rebuildView 的 rel 补索引）必须照旧生效，
     * 所以这里用 [ModelIntroOverrides.save] 造出磁盘状态。
     *
     * 必须 [ModelIntroTable.clearForTest] 再重新绑定：`bindOverrides` 对同一个路径只读一次，
     * 不重绑就看不到刚写入的文件。
     */
    private fun seedOverrides(vararg entries: Pair<String, OverrideEntry>) {
        assertTrue(ModelIntroOverrides.save(overrideFile, mapOf(*entries)))
        ModelIntroTable.clearForTest()
        ModelIntroTable.setTableJsonForTest(baseJson)
        ModelIntroTable.bindOverridesForTest(overrideFile)
    }

    /** 造一条最小条目（纯函数用例用）。 */
    private fun intro(file: String) = ModelIntro(file = file, kind = "LoRA", name = file)
}