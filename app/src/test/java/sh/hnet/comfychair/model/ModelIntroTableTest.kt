package sh.hnet.comfychair.model

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * 模型介绍表的查询逻辑测试。
 *
 * 重点守两件事：
 * 1. **键是模型文件名**（服务器返回的路径可能带子目录、可能是反斜杠，都要能命中）；
 * 2. 没收录的模型必须老实返回 null —— App 端据此显示「暂无介绍」，绝不猜。
 */
class ModelIntroTableTest {

    private val sampleJson = """
    {
      "version": 1,
      "count": 2,
      "models": {
        "awpainting_v1.2.safetensors": {
          "file": "AWPainting_v1.2.safetensors",
          "rel": "checkpoints/SD1.5/anime/AWPainting_v1.2.safetensors",
          "kind": "大模型",
          "name": "AWPainting",
          "type": "大模型（Checkpoint）",
          "base": "SD 1.5",
          "author": "DynamicWang",
          "summary": "「AWPainting」是 SD 1.5 底模的大模型…",
          "params": {"步数": "20 步", "CFG": "5.7", "采样器": "dpmpp_2m", "调度器": "karras"},
          "companions": "搭配 VAE：vae-ft-mse-840000-ema-pruned.safetensors"
        },
        "二次元：anythingv5v3_v5prtre.safetensors": {
          "file": "二次元：AnythingV5V3_v5PrtRE.safetensors",
          "rel": "checkpoints/SD1.5/anime/二次元：AnythingV5V3_v5PrtRE.safetensors",
          "kind": "大模型",
          "name": "万象熔炉 | Anything XL",
          "base": "SD 1.5",
          "author": "Yuno779",
          "summary": "「万象熔炉 | Anything XL」是 SD 1.5 底模的大模型…"
        }
      },
      "by_rel": {
        "checkpoints/sd1.5/anime/awpainting_v1.2.safetensors": "awpainting_v1.2.safetensors",
        "checkpoints/sd1.5/anime/二次元：anythingv5v3_v5prtre.safetensors": "二次元：anythingv5v3_v5prtre.safetensors"
      }
    }
    """.trimIndent()

    @Before
    fun setUp() {
        ModelIntroTable.setTableJsonForTest(sampleJson)
    }

    @After
    fun tearDown() {
        ModelIntroTable.clearForTest()
    }

    @Test
    fun `按文件名精确匹配`() {
        val hit = ModelIntroTable.lookup("AWPainting_v1.2.safetensors")
        assertNotNull(hit)
        assertEquals("AWPainting", hit!!.name)
        assertEquals("DynamicWang", hit.author)
    }

    @Test
    fun `大小写不敏感`() {
        assertNotNull(ModelIntroTable.lookup("awpainting_V1.2.SafeTensors"))
    }

    @Test
    fun `服务器返回带子目录的反斜杠路径也能命中`() {
        val hit = ModelIntroTable.lookup("checkpoints\\SD1.5\\anime\\AWPainting_v1.2.safetensors")
        assertNotNull(hit)
        assertEquals("AWPainting", hit!!.name)
    }

    @Test
    fun `正斜杠路径同样能命中`() {
        assertNotNull(ModelIntroTable.lookup("checkpoints/SD1.5/anime/AWPainting_v1.2.safetensors"))
    }

    @Test
    fun `只有文件名片段也能命中`() {
        assertNotNull(ModelIntroTable.lookup("anime/AWPainting_v1.2.safetensors"))
    }

    @Test
    fun `中文文件名能命中`() {
        val hit = ModelIntroTable.lookup("二次元：AnythingV5V3_v5PrtRE.safetensors")
        assertNotNull(hit)
        assertEquals("万象熔炉 | Anything XL", hit!!.name)
    }

    @Test
    fun `未收录的模型返回 null`() {
        assertNull(ModelIntroTable.lookup("some_model_not_in_table.safetensors"))
    }

    @Test
    fun `空字符串与 null 不崩且返回 null`() {
        assertNull(ModelIntroTable.lookup(""))
        assertNull(ModelIntroTable.lookup(null))
        assertNull(ModelIntroTable.lookup("   "))
    }

    @Test
    fun `表为空时查询安全返回 null`() {
        ModelIntroTable.clearForTest()
        assertNull(ModelIntroTable.lookup("AWPainting_v1.2.safetensors"))
    }

    @Test
    fun `坏 JSON 不抛异常_退化为空表`() {
        ModelIntroTable.setTableJsonForTest("{ this is not json ")
        assertNull(ModelIntroTable.lookup("AWPainting_v1.2.safetensors"))
    }

    @Test
    fun `缺字段的条目也能解析_值为空而非崩`() {
        ModelIntroTable.setTableJsonForTest(
            """{"version":1,"models":{"bare.safetensors":{"file":"bare.safetensors"}}}"""
        )
        val hit = ModelIntroTable.lookup("bare.safetensors")
        assertNotNull(hit)
        assertEquals("bare.safetensors", hit!!.file)
        assertNull(hit.name)
        assertNull(hit.summary)
    }

    @Test
    fun `只有模板套话的条目不给查看入口`() {
        ModelIntroTable.setTableJsonForTest(
            """
            {"version":1,"models":{"bare.safetensors":{
              "file":"bare.safetensors",
              "kind":"扩散模型",
              "summary":"「bare.safetensors」是扩散模型。（暂无更多介绍信息）"
            }}}
            """.trimIndent()
        )
        val hit = ModelIntroTable.lookup("bare.safetensors")!!
        // 摘要只是套话，界面上不该因此挂一个点开只有废话的 ⓘ
        org.junit.Assert.assertFalse(hit.hasContent)
    }

    @Test
    fun `有底模或作者就算有内容`() {
        ModelIntroTable.setTableJsonForTest(
            """
            {"version":1,"models":{"x.safetensors":{
              "file":"x.safetensors","base":"SD 1.5","summary":"无信息量的套话"
            }}}
            """.trimIndent()
        )
        org.junit.Assert.assertTrue(ModelIntroTable.lookup("x.safetensors")!!.hasContent)
    }

    @Test
    fun `推荐参数与搭配建议字段能读到`() {
        val hit = ModelIntroTable.lookup("AWPainting_v1.2.safetensors")
        // params 自 2026-09-25 起是键值对（原先是一整行拼接的字符串）
        assertEquals("20 步", hit!!.params["步数"])
        assertEquals("5.7", hit.params["CFG"])
        assertEquals(true, hit.companions?.contains("vae-ft-mse-840000"))
    }

    @Test
    fun `列表型字段可解析`() {
        ModelIntroTable.setTableJsonForTest(
            """
            {"version":1,"models":{"x.safetensors":{
              "file":"x.safetensors",
              "params":{"步数":"28 步","CFG":"1"},
              "author_params":{"推荐分辨率":"1024x1024"},
              "tags":["mature body"],
              "used_in":["竖叉","潜空间放大"]
            }}}
            """.trimIndent()
        )
        val hit = ModelIntroTable.lookup("x.safetensors")!!
        // keywords/downloads 已在 2026-09-25 从数据契约里删掉（用户明确不要），
        // 这里改钉新增的两个键值对字段，顺带守住 JSON 里的顺序（LinkedHashMap）
        assertEquals(listOf("步数", "CFG"), hit.params.keys.toList())
        assertEquals("28 步", hit.params["步数"])
        assertEquals("1024x1024", hit.authorParams["推荐分辨率"])
        assertEquals(listOf("mature body"), hit.tags)
        assertEquals(listOf("竖叉", "潜空间放大"), hit.usedIn)
    }

    @Test
    fun `旧的字符串形态 params 仍能读出来`() {
        // 兼容性：老数据里 params 是一整行文本，不该因为字段改型就崩或丢内容
        ModelIntroTable.setTableJsonForTest(
            """
            {"version":1,"models":{"y.safetensors":{
              "file":"y.safetensors",
              "params":"28 步 · CFG 1 · euler"
            }}}
            """.trimIndent()
        )
        val hit = ModelIntroTable.lookup("y.safetensors")!!
        assertEquals("28 步 · CFG 1 · euler", hit.params["说明"])
    }

    // ---------------- 2026-11-15 全量接入（18 字段 / by_rel / removed）----------------

    @Test
    fun `全部字段都能映射进数据类_含新增的kind`() {
        ModelIntroTable.setTableJsonForTest(
            """
            {"version":2,"generated_at":"2026-11-15 00:00:00","models":{"full.safetensors":{
              "file":"Full.safetensors",
              "rel":"loras/image/krea2/full.safetensors",
              "kind":"LoRA",
              "source":"civitai",
              "name":"Full Model",
              "type":"LoRA（LoCon）",
              "base":"Krea 2",
              "author":"someone",
              "version":"v2.0",
              "tags":["tag1"],
              "summary":"「Full」是 Krea 2 底模的 LoRA…",
              "intro_original":"作者原文……",
              "author_params":{"CFG":"1"},
              "params":{"步数":"8"},
              "companions":"搭配 VAE：x.safetensors",
              "effect":"效果：……",
              "used_in":["竖叉"],
              "url":"https://civitai.com/models/1"
            }}}
            """.trimIndent()
        )
        val hit = ModelIntroTable.lookup("full.safetensors")!!
        assertEquals("Full.safetensors", hit.file)
        assertEquals("loras/image/krea2/full.safetensors", hit.rel)
        assertEquals("LoRA", hit.kind)
        assertEquals("civitai", hit.source)
        assertEquals("Full Model", hit.name)
        assertEquals("LoRA（LoCon）", hit.type)
        assertEquals("Krea 2", hit.base)
        assertEquals("someone", hit.author)
        assertEquals("v2.0", hit.version)
        assertEquals(listOf("tag1"), hit.tags)
        assertEquals(mapOf("CFG" to "1"), hit.authorParams)
        assertEquals(mapOf("步数" to "8"), hit.params)
        assertEquals("搭配 VAE：x.safetensors", hit.companions)
        assertEquals("效果：……", hit.effect)
        assertEquals(listOf("竖叉"), hit.usedIn)
        assertEquals("https://civitai.com/models/1", hit.url)
        assertEquals("作者原文……", hit.introOriginal)
    }

    @Test
    fun `removed键读到missing属性`() {
        // 数据侧 2026-11-15 起改用 removed，App 端属性名保持 missing 不变
        ModelIntroTable.setTableJsonForTest(
            """{"version":1,"models":{"r.safetensors":{"file":"r.safetensors","removed":true}}}"""
        )
        assertEquals(true, ModelIntroTable.lookup("r.safetensors")!!.missing)
    }

    @Test
    fun `旧键名missing也兼容`() {
        ModelIntroTable.setTableJsonForTest(
            """{"version":1,"models":{"r.safetensors":{"file":"r.safetensors","missing":true}}}"""
        )
        assertEquals(true, ModelIntroTable.lookup("r.safetensors")!!.missing)
    }

    @Test
    fun `removed与missing都没有时为false`() {
        ModelIntroTable.setTableJsonForTest(
            """{"version":1,"models":{"r.safetensors":{"file":"r.safetensors"}}}"""
        )
        assertEquals(false, ModelIntroTable.lookup("r.safetensors")!!.missing)
    }

    // ---------------- lenientBoolean：布尔字段的类型宽容 ----------------

    @Test
    fun `removed为字符串true也读到missing`() {
        // org.json 的 optBoolean 只认 boolean，字符串 "true" 会被当成 false；
        // 数据链路是脚本生成的，类型漂移不该让"已删除"被静默当成"未删除"
        ModelIntroTable.setTableJsonForTest(
            """{"version":1,"models":{"r.safetensors":{"file":"r.safetensors","removed":"true"}}}"""
        )
        assertEquals(true, ModelIntroTable.lookup("r.safetensors")!!.missing)
    }

    @Test
    fun `removed字符串TRUE带空白也读到true`() {
        // 忽略大小写与首尾空白
        ModelIntroTable.setTableJsonForTest(
            """{"version":1,"models":{"r.safetensors":{"file":"r.safetensors","removed":" TRUE "}}}"""
        )
        assertEquals(true, ModelIntroTable.lookup("r.safetensors")!!.missing)
    }

    @Test
    fun `removed为数字1也读到true`() {
        ModelIntroTable.setTableJsonForTest(
            """{"version":1,"models":{"r.safetensors":{"file":"r.safetensors","removed":1}}}"""
        )
        assertEquals(true, ModelIntroTable.lookup("r.safetensors")!!.missing)
    }

    @Test
    fun `removed字符串false或数字0读到false`() {
        ModelIntroTable.setTableJsonForTest(
            """{"version":1,"models":{"a.safetensors":{"file":"a.safetensors","removed":"false"},"b.safetensors":{"file":"b.safetensors","removed":0}}}"""
        )
        assertEquals(false, ModelIntroTable.lookup("a.safetensors")!!.missing)
        assertEquals(false, ModelIntroTable.lookup("b.safetensors")!!.missing)
    }

    @Test
    fun `removed为布尔true的既有行为回归保护`() {
        ModelIntroTable.setTableJsonForTest(
            """{"version":1,"models":{"r.safetensors":{"file":"r.safetensors","removed":true}}}"""
        )
        assertEquals(true, ModelIntroTable.lookup("r.safetensors")!!.missing)
    }

    @Test
    fun `lookupByRel按相对路径精确命中`() {
        // 顶层 by_rel 的键是小写 rel；传入原始大小写也要能命中
        val hit = ModelIntroTable.lookupByRel("checkpoints/SD1.5/anime/AWPainting_v1.2.safetensors")
        assertNotNull(hit)
        assertEquals("AWPainting", hit!!.name)
    }

    @Test
    fun `lookupByRel大小写与反斜杠变体也命中`() {
        assertNotNull(
            ModelIntroTable.lookupByRel("Checkpoints\\SD1.5\\Anime\\AWPainting_v1.2.SAFETENSORS")
        )
    }

    @Test
    fun `lookupByRel多带models前缀也能命中`() {
        assertNotNull(
            ModelIntroTable.lookupByRel("models/checkpoints/sd1.5/anime/awpainting_v1.2.safetensors")
        )
    }

    @Test
    fun `lookup与lookupByRel命中同一条目`() {
        val path = "checkpoints/SD1.5/anime/AWPainting_v1.2.safetensors"
        val byName = ModelIntroTable.lookup(path)
        val byRel = ModelIntroTable.lookupByRel(path)
        assertNotNull(byName)
        assertNotNull(byRel)
        assertEquals(byName, byRel)
    }

    @Test
    fun `lookupByRel未收录或空参返回null`() {
        assertNull(ModelIntroTable.lookupByRel("loras/not_in_table.safetensors"))
        assertNull(ModelIntroTable.lookupByRel(null))
        assertNull(ModelIntroTable.lookupByRel(""))
        assertNull(ModelIntroTable.lookupByRel("   "))
    }

    @Test
    fun `by_rel悬空值跳过_不崩且返回null`() {
        ModelIntroTable.setTableJsonForTest(
            """
            {"version":1,"models":{"x.safetensors":{"file":"x.safetensors"}},
             "by_rel":{"loras/ghost.safetensors":"ghost.safetensors"}}
            """.trimIndent()
        )
        // 指向 models 里没有的键（悬空值）直接跳过，不影响其余条目
        assertNull(ModelIntroTable.lookupByRel("loras/ghost.safetensors"))
        assertNotNull(ModelIntroTable.lookup("x.safetensors"))
    }

    @Test
    fun `kind八个枚举值都原样保留`() {
        val kinds = listOf(
            "大模型", "扩散模型", "LoRA", "放大模型", "VAE", "图像编码器", "几何估计", "背景去除"
        )
        kinds.forEachIndexed { index, k ->
            ModelIntroTable.setTableJsonForTest(
                """{"version":1,"models":{"k$index.safetensors":{"file":"k$index.safetensors","kind":"$k"}}}"""
            )
            assertEquals(k, ModelIntroTable.lookup("k$index.safetensors")!!.kindLabel())
        }
    }

    @Test
    fun `kind未知新类型原样显示不丢成null`() {
        ModelIntroTable.setTableJsonForTest(
            """{"version":1,"models":{"n.safetensors":{"file":"n.safetensors","kind":"未来新类型"}}}"""
        )
        assertEquals("未来新类型", ModelIntroTable.lookup("n.safetensors")!!.kindLabel())
    }

    @Test
    fun `kind空值或空白或缺省返回null_整行不显示`() {
        ModelIntroTable.setTableJsonForTest(
            """{"version":1,"models":{"e.safetensors":{"file":"e.safetensors","kind":""}}}"""
        )
        assertNull(ModelIntroTable.lookup("e.safetensors")!!.kindLabel())
        ModelIntroTable.setTableJsonForTest(
            """{"version":1,"models":{"e.safetensors":{"file":"e.safetensors","kind":"   "}}}"""
        )
        assertNull(ModelIntroTable.lookup("e.safetensors")!!.kindLabel())
        ModelIntroTable.setTableJsonForTest(
            """{"version":1,"models":{"e.safetensors":{"file":"e.safetensors"}}}"""
        )
        assertNull(ModelIntroTable.lookup("e.safetensors")!!.kindLabel())
    }

    @Test
    fun `空对象条目null安全_不抛异常`() {
        ModelIntroTable.setTableJsonForTest("""{"models":{"a.safetensors":{}}}""")
        val all = ModelIntroTable.all()
        assertEquals(1, all.size)
        val hit = all[0]
        // file 缺省用键名兜底，其余可空字段全 null、集合字段全空
        assertEquals("a.safetensors", hit.file)
        assertNull(hit.rel)
        assertNull(hit.kind)
        assertNull(hit.name)
        assertNull(hit.type)
        assertNull(hit.base)
        assertNull(hit.author)
        assertNull(hit.version)
        assertNull(hit.summary)
        assertNull(hit.effect)
        assertNull(hit.companions)
        assertNull(hit.url)
        assertNull(hit.introOriginal)
        assertNull(hit.source)
        assertEquals(false, hit.missing)
        org.junit.Assert.assertTrue(hit.params.isEmpty())
        org.junit.Assert.assertTrue(hit.authorParams.isEmpty())
        org.junit.Assert.assertTrue(hit.tags.isEmpty())
        org.junit.Assert.assertTrue(hit.usedIn.isEmpty())
    }
}
