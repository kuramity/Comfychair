package sh.hnet.comfychair.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.hnet.comfychair.util.SingleFlight

/**
 * 「从服务器取介绍数据」这一层的纯逻辑测试。
 *
 * 只测两件能脱离 Android 跑的事：
 * 1. **响应校验**——服务器可能返回 HTML 错误页、半截 JSON、空表、超大响应，
 *    这一层要先把它们挡在门外，绝不允许脏数据流到 [ModelIntroSync.apply]；
 * 2. **取用地址**——地址必须来自 [ModelIntroSync.REMOTE_PATH] 这一个常量，
 *    否则改了常量却漏改手拼的字符串，线上就会 404。
 *
 * 另含并发守卫 [SingleFlight] 的行为测试（T2 的接线依赖它）。
 */
class ModelIntroFetchTest {

    private val goodJson = """
    {
      "version": 1,
      "generated_at": "2026-09-23 12:09:33",
      "count": 1,
      "models": { "x_v1.safetensors": { "file": "x_v1.safetensors", "name": "X" } }
    }
    """.trimIndent()

    // ---------------- ① 可用响应 ----------------

    @Test
    fun `正常介绍数据可解码为文本`() {
        val decoded = ModelIntroFetch.decodePayload(goodJson.toByteArray(Charsets.UTF_8))
        assertNotNull(decoded)
        assertTrue(decoded!!.contains("\"models\""))
    }

    @Test
    fun `带 UTF-8 BOM 的响应也能解码`() {
        // 某些中间代理/编辑器会给文件加上 BOM，直接 JSONObject 解析会失败
        val withBom = "\uFEFF" + goodJson
        assertTrue(ModelIntroFetch.isUsablePayload(withBom))
        assertNotNull(ModelIntroFetch.decodePayload(withBom.toByteArray(Charsets.UTF_8)))
    }

    @Test
    fun `前后有空白的响应也能解码`() {
        assertNotNull(ModelIntroFetch.decodePayload("\n\n  $goodJson  \n".toByteArray(Charsets.UTF_8)))
    }

    // ---------------- ② 坏响应一律拒绝 ----------------

    @Test
    fun `空响应被拒绝`() {
        assertNull(ModelIntroFetch.decodePayload(null))
        assertNull(ModelIntroFetch.decodePayload(ByteArray(0)))
        assertNull(ModelIntroFetch.decodePayload("   \n ".toByteArray(Charsets.UTF_8)))
    }

    @Test
    fun `HTML 错误页被拒绝`() {
        val html = "<!DOCTYPE html><html><body>502 Bad Gateway</body></html>"
        assertFalse(ModelIntroFetch.isUsablePayload(html))
        assertNull(ModelIntroFetch.decodePayload(html.toByteArray(Charsets.UTF_8)))
    }

    @Test
    fun `半截 JSON 被拒绝`() {
        for (broken in listOf("{", "{ \"models\": {", """{"models": {"a": {""")) {
            assertFalse("半截 JSON 应被拒绝：$broken", ModelIntroFetch.isUsablePayload(broken))
            assertNull(ModelIntroFetch.decodePayload(broken.toByteArray(Charsets.UTF_8)))
        }
    }

    @Test
    fun `空模型表被拒绝`() {
        val empty = """{"version": 2, "generated_at": "2026-09-24 00:00:00", "count": 0, "models": {}}"""
        assertFalse(ModelIntroFetch.isUsablePayload(empty))
        assertNull(ModelIntroFetch.decodePayload(empty.toByteArray(Charsets.UTF_8)))
    }

    @Test
    fun `缺 models 字段被拒绝`() {
        assertFalse(ModelIntroFetch.isUsablePayload("""{"version":2,"generated_at":"2026-09-24 00:00:00"}"""))
    }

    @Test
    fun `JSON 数组被拒绝`() {
        assertFalse(ModelIntroFetch.isUsablePayload("""[{"file":"a.safetensors"}]"""))
    }

    @Test
    fun `非法 UTF-8 字节不会抛异常`() {
        // 服务器把二进制/压缩内容原样吐回来时，解码不能把异常抛给调用方
        val garbage = byteArrayOf(0x1F, 0x8B.toByte(), 0x08, 0x00, 0xFF.toByte(), 0xFE.toByte())
        assertNull(ModelIntroFetch.decodePayload(garbage))
    }

    // ---------------- ③ 响应大小上限 ----------------

    @Test
    fun `响应上限为 4MB`() {
        assertEquals(4L * 1024 * 1024, ModelIntroFetch.MAX_INTRO_BYTES)
    }

    // ---------------- ④ 取用地址 ----------------

    @Test
    fun `取用地址拼接自 REMOTE_PATH 常量`() {
        val url = ModelIntroFetch.buildUrl("http://192.168.1.10:8188")
        assertEquals("http://192.168.1.10:8188" + ModelIntroSync.REMOTE_PATH, url)
        assertEquals(
            "http://192.168.1.10:8188/view?filename=model_intros.json&subfolder=comfychair&type=output",
            url,
        )
    }

    @Test
    fun `结尾带斜杠的地址不会拼出双斜杠`() {
        assertEquals(
            "http://host:8188" + ModelIntroSync.REMOTE_PATH,
            ModelIntroFetch.buildUrl("http://host:8188/"),
        )
    }

    // ---------------- ⑤ 并发守卫（T2 依赖） ----------------

    @Test
    fun `同一时刻只有一个任务能进入`() {
        val gate = SingleFlight()
        assertTrue("首个任务应拿到执行权", gate.tryEnter())
        assertFalse("在飞期间第二个任务应被跳过", gate.tryEnter())
        assertTrue(gate.isInFlight)
    }

    @Test
    fun `释放后可以再次进入`() {
        val gate = SingleFlight()
        assertTrue(gate.tryEnter())
        gate.exit()
        assertFalse(gate.isInFlight)
        assertTrue("释放后应能重新进入", gate.tryEnter())
    }

    @Test
    fun `异常路径也必须能复位`() {
        val gate = SingleFlight()
        try {
            gate.tryEnter()
            throw IllegalStateException("模拟同步过程中的异常")
        } catch (_: IllegalStateException) {
            gate.exit()
        }
        assertFalse("异常后标志位必须复位，否则后续同步永久被跳过", gate.isInFlight)
        assertTrue(gate.tryEnter())
    }

    @Test
    fun `重复释放不会破坏闸门`() {
        val gate = SingleFlight()
        gate.tryEnter()
        gate.exit()
        gate.exit()
        assertFalse(gate.isInFlight)
        assertTrue(gate.tryEnter())
    }
}
