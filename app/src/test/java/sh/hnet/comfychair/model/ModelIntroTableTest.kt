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
}
