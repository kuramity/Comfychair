package sh.hnet.comfychair.cache

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P2-2：缓存键必须包含 type 与 serverId。
 *
 * 修复前 `MediaCacheKey` 只有 (promptId, filename)：
 * - 同一个 prompt 的 output 与 temp 同名文件会命中同一份缓存；
 * - MediaCache 是进程级单例，A/B 两台服务器同名的产物会互相串图；
 * - keyString 直接当磁盘文件名，反解时也只能拿回两段。
 */
class MediaCacheKeyTest {

    @Test
    fun `同一文件在 output 与 temp 下生成不同键`() {
        val output = MediaCacheKey("prompt-1", "ComfyUI_00001_.png", "output", "server-a")
        val temp = MediaCacheKey("prompt-1", "ComfyUI_00001_.png", "temp", "server-a")

        assertNotEquals("output/temp 不能互撞", output.keyString, temp.keyString)
        assertNotEquals(output, temp)
    }

    @Test
    fun `同一文件在两台服务器下生成不同键`() {
        val serverA = MediaCacheKey("prompt-1", "ComfyUI_00001_.png", "output", "server-a")
        val serverB = MediaCacheKey("prompt-1", "ComfyUI_00001_.png", "output", "server-b")

        assertNotEquals("跨服务器不能互撞", serverA.keyString, serverB.keyString)
        assertNotEquals(serverA, serverB)
    }

    @Test
    fun `键字符串可以无损反解`() {
        val original = MediaCacheKey(
            promptId = "cd420ba4-1234-5678-9abc-def012345678",
            filename = "ComfyUI_00001_.png",
            type = "temp",
            serverId = "11111111-2222-3333-4444-555555555555"
        )

        val parsed = MediaCacheKey.parseKeyString(original.keyString)

        assertEquals(original, parsed)
    }

    @Test
    fun `文件名与 id 里的下划线不影响反解`() {
        // 编码只对前三段转义，filename 作为最后一段原样保留（自身可以含下划线）
        val original = MediaCacheKey(
            promptId = "prompt_with_underscore",
            filename = "a_b_c_d.png",
            type = "output",
            serverId = "server_with_underscore"
        )

        val parsed = MediaCacheKey.parseKeyString(original.keyString)

        assertEquals("prompt_with_underscore", parsed?.promptId)
        assertEquals("a_b_c_d.png", parsed?.filename)
        assertEquals("output", parsed?.type)
        assertEquals("server_with_underscore", parsed?.serverId)
    }

    @Test
    fun `百分号也能安全往返`() {
        val original = MediaCacheKey("prompt%5F1", "file%25.png", "output", "server%1")
        val parsed = MediaCacheKey.parseKeyString(original.keyString)
        assertEquals(original, parsed)
    }

    @Test
    fun `服务器 id 缺失时用占位符并可反解为空串`() {
        val original = MediaCacheKey("prompt-1", "ComfyUI_00001_.png", "output", "")
        val parsed = MediaCacheKey.parseKeyString(original.keyString)

        assertEquals("", parsed?.serverId)
        assertTrue("键里不能出现连续分隔符（会破坏分段）", !original.keyString.contains("__output_"))
    }

    @Test
    fun `旧格式键按 output 解析且 serverId 为空`() {
        // 旧版本写下的磁盘文件名：{promptId}_{filename}
        val parsed = MediaCacheKey.parseKeyString(
            "cd420ba4-1234-5678-9abc-def012345678_ComfyUI_00001_.png"
        )

        assertEquals("cd420ba4-1234-5678-9abc-def012345678", parsed?.promptId)
        assertEquals("ComfyUI_00001_.png", parsed?.filename)
        assertEquals("output", parsed?.type)
        assertEquals("", parsed?.serverId)
    }

    @Test
    fun `无法解析的文件名返回 null`() {
        assertNull(MediaCacheKey.parseKeyString("v2_only_three_parts"))
        assertNull(MediaCacheKey.parseKeyString("noSeparator"))
        assertNull(MediaCacheKey.parseKeyString("_leading"))
        assertNull(MediaCacheKey.parseKeyString("trailing_"))
    }

    @Test
    fun `type 为空时按 output 归一化`() {
        val blank = MediaCacheKey.of("prompt-1", "a.png", "  ", serverId = "server-a")
        val output = MediaCacheKey("prompt-1", "a.png", "output", "server-a")

        assertEquals(output, blank)
    }
}