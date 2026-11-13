package sh.hnet.comfychair.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * LoRA「不使用」取值的归一化与反序列化。
 *
 * 钉住 400 的根因修复（commit 428a392）：
 * ```
 * Value not in list: lora_name: 'None' not in (list of length 66)
 * ```
 * App 的 LoRA 列表是各节点类 `lora_name` 选项的**并集**，其中 CR Load LoRA / Lora Loader /
 * Efficient Loader / easy *_Loader 等节点各带一个字面量 `"None"`（表示"不加载"）。
 * 它混进下拉后被当成真实 LoRA 名提交，而 LoraLoaderModelOnly 只有 66 项 → 服务器 400。
 *
 * 这一组测试要同时钉住两件相反的事：
 * 1. 「未选择」的取值（None/null/空）必须落成空串——回退 [LoraSelection.normalizeName]
 *    或 [LoraSelection.fromJsonString] 里那行丢弃，下面的用例必须变红；
 * 2. **不能误伤**名字里含 None 的真实模型文件——过滤写宽（如 contains/startWith 判定）
 *    会让 LoRA 列表凭空少模型，同样必须变红。
 */
class LoraSelectionNormalizeTest {

    // ── normalizeName：未选择取值 → 空串 ──────────────────────────────────

    @Test
    fun `None 的任意大小写与前后空白都归一化为空串`() {
        // 服务器报的原文就是首字母大写的 'None'；下拉里的选项去掉大小写后都必须被认出来，
        // 否则用户选中它仍会把一个不存在的模型名提交上去
        listOf("None", "none", "NONE", "nOnE", "  None  ", "\tNone\n", " none ").forEach { raw ->
            assertEquals("『$raw』表示不使用 LoRA，必须归一化为空串", "", LoraSelection.normalizeName(raw))
        }
    }

    @Test
    fun `历史遗留的 null 字面量同样视为不使用`() {
        // 旧偏好里也见过 "null" 写法（不是 JSON 的 null，是字符串），口径与 None 一致
        listOf("null", "NULL", "Null", " null ").forEach { raw ->
            assertEquals("『$raw』表示不使用 LoRA，必须归一化为空串", "", LoraSelection.normalizeName(raw))
        }
    }

    @Test
    fun `空串 纯空白串与 null 归一化后仍是空串`() {
        assertEquals("", LoraSelection.normalizeName(""))
        assertEquals("", LoraSelection.normalizeName("   "))
        assertEquals("", LoraSelection.normalizeName("\t \n"))
        assertEquals("", LoraSelection.normalizeName(null))
    }

    // ── normalizeName：合法名不被误伤（重点）────────────────────────────

    @Test
    fun `名字里含 None 的真实模型文件必须原样保留`() {
        val legalNames = listOf(
            "None.safetensors",                        // 扩展名紧跟在 None 后面
            "none.safetensors",
            "NoneType_v2.safetensors",
            "StrongNone.safetensors",
            "none_mix.safetensors",
            "none_of_them.safetensors",
            "NoneStyle_v3.ckpt"
        )
        legalNames.forEach { name ->
            assertEquals(
                "『$name』是真实模型文件名，判定必须按整串精确匹配，不能按子串/前缀（误判=下拉里凭空少一个 LoRA）",
                name,
                LoraSelection.normalizeName(name)
            )
        }
    }

    @Test
    fun `带目录的真实模型路径不被误伤`() {
        // 服务器上的 LoRA 就是这样带相对目录的；目录名里出现 none 也不能被当成未选择
        val windowsStyle = "image\\krea2\\none_mix.safetensors"
        val unixStyle = "image/krea2/none_mix.safetensors"
        assertEquals(windowsStyle, LoraSelection.normalizeName(windowsStyle))
        assertEquals(unixStyle, LoraSelection.normalizeName(unixStyle))
    }

    @Test
    fun `归一化只裁前后空白 不改动内容与大小写`() {
        assertEquals("Krea2_LoRA.safetensors", LoraSelection.normalizeName("  Krea2_LoRA.safetensors  "))
        assertEquals("Image\\Krea2\\Mix.safetensors", LoraSelection.normalizeName("Image\\Krea2\\Mix.safetensors"))
    }

    // ── fromJsonString：空名项丢弃、合法项保留 ───────────────────────────

    @Test
    fun `反序列化丢弃未选择项 合法项保留且权重正确`() {
        val json = """
        [
          {"name":"None","strength":0.8},
          {"name":"image/krea2/v1.safetensors","strength":0.65},
          {"name":"","strength":1.0},
          {"name":"  none  ","strength":0.3},
          {"name":"None.safetensors","strength":0.5}
        ]
        """.trimIndent()

        val result = LoraSelection.fromJsonString(json)

        assertEquals("三条未选择项（None / 空 / none）必须丢弃，两条真实项保留", 2, result.size)
        assertEquals("image/krea2/v1.safetensors", result[0].name)
        assertEquals("权重必须按存档原值读回", 0.65, result[0].strength.toDouble(), 1e-6)
        assertEquals("None.safetensors 是真实文件，不能连它一起丢掉", "None.safetensors", result[1].name)
        assertEquals(0.5, result[1].strength.toDouble(), 1e-6)
    }

    @Test
    fun `整条链都是未选择时反序列化为空列表`() {
        val json = """[{"name":"None","strength":1.0},{"name":"none","strength":1.0},{"name":"","strength":1.0}]"""
        assertTrue("全是不使用 → 链条为空，提交时不注入任何 LoRA 节点", LoraSelection.fromJsonString(json).isEmpty())
    }

    @Test
    fun `缺省强度的合法项按默认强度处理`() {
        val result = LoraSelection.fromJsonString("""[{"name":"a.safetensors"}]""")
        assertEquals(1, result.size)
        assertEquals(LoraSelection.DEFAULT_STRENGTH.toDouble(), result[0].strength.toDouble(), 1e-6)
    }

    @Test
    fun `空串 空白串与非法 JSON 都反序列化为空列表`() {
        assertTrue(LoraSelection.fromJsonString(null).isEmpty())
        assertTrue(LoraSelection.fromJsonString("").isEmpty())
        assertTrue(LoraSelection.fromJsonString("   ").isEmpty())
        assertTrue("不是数组", LoraSelection.fromJsonString("{\"name\":\"a.safetensors\"}").isEmpty())
        assertTrue("JSON 截断", LoraSelection.fromJsonString("[{\"name\":\"a.safetensors\"}").isEmpty())
    }

    @Test
    fun `序列化再反序列化保留合法项与权重`() {
        val chain = listOf(
            LoraSelection("a.safetensors", 0.75f),
            LoraSelection("image/krea2/none_mix.safetensors", 1.25f)
        )
        assertEquals(chain, LoraSelection.fromJsonString(LoraSelection.toJsonString(chain)))
    }
}
