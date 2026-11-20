package sh.hnet.comfychair.model

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Server 的 JSON 序列化：protocol 必须向后兼容。
 *
 * - 旧数据没有 protocol 字段 → 读回 null（自动），行为不变；
 * - protocol 为 null 时不写字段，避免污染旧数据；
 * - 显式 http / https 能完整往返。
 *
 * 另含 P1-18：id 必须是合法 UUID，否则整条丢弃（id 会被拼进缓存文件名，
 * 带 `../` 的 id 能把文件写到 filesDir 之外）。
 */
class ServerJsonTest {

    /** 与 UuidUtils.generateRandomId() 同形态的 id */
    private val validId = "6f5a1a3c-8f2e-4b9a-9d31-2c7e4b5a6d10"

    private fun sample(protocol: String?) = Server(
        id = validId,
        name = "家用",
        hostname = "192.168.1.5",
        port = 8188,
        protocol = protocol
    )

    @Test
    fun `protocol 为 null 时不写字段`() {
        val json = sample(null).toJson()
        assertFalse(json.has("protocol"))
    }

    @Test
    fun `显式 http 写入并能读回`() {
        val json = sample("http").toJson()
        assertEquals("http", json.getString("protocol"))
        assertEquals("http", Server.fromJson(json)?.protocol)
    }

    @Test
    fun `显式 https 写入并能读回`() {
        val json = sample("https").toJson()
        assertEquals("https", Server.fromJson(json)?.protocol)
    }

    @Test
    fun `缺失 protocol 字段读回 null`() {
        val json = JSONObject()
            .put("id", validId)
            .put("name", "家用")
            .put("hostname", "192.168.1.5")
            .put("port", 8188)
            .put("authType", "NONE")
        val parsed = Server.fromJson(json)
        assertNull(parsed?.protocol)
        assertEquals("192.168.1.5", parsed?.hostname)
    }

    // ---------------- P1-18：id 必须是合法 UUID ----------------

    @Test
    fun `合法 UUID 的 id 能正常解析`() {
        val server = Server.fromJson(sample(null).toJson())
        assertNotNull(server)
        assertEquals(validId, server?.id)
        assertTrue(Server.isValidId(validId))
        // 大写形态同样合法（跨平台/手工备份可能写成大写）
        assertTrue(Server.isValidId(validId.uppercase()))
    }

    @Test
    fun `路径穿越形态的 id 被整条丢弃`() {
        // id 会被拼进缓存文件名：带 ../ 就能逃出 filesDir
        val evilIds = listOf(
            "../../evil",
            "../../../shared_prefs/AppSettings.xml",
            "..\\..\\evil",
            "6f5a1a3c-8f2e-4b9a-9d31-2c7e4b5a6d10/../../x",
            "s1",
            "",
            "   ",
            "6f5a1a3c8f2e4b9a9d312c7e4b5a6d10",
        )
        for (evil in evilIds) {
            val json = JSONObject()
                .put("id", evil)
                .put("name", "evil")
                .put("hostname", "192.168.1.5")
                .put("port", 8188)
            assertNull("id='$evil' 应被拒绝", Server.fromJson(json))
            assertFalse("id='$evil' 不应通过 isValidId", Server.isValidId(evil))
        }
    }
}
