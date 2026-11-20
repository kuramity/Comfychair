package sh.hnet.comfychair.util

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * P2-4：`/view` URL 的查询参数必须由 HttpUrl 编码，不能被 filename 改写结构。
 */
class ComfyViewUrlTest {

    @Test
    fun `filename 里的分隔符与额外参数只作为值`() {
        val url = ComfyViewUrl.build(
            baseUrl = "http://192.168.1.10:8188",
            filename = "a&type=temp&x.png",
            subfolder = "sub folder",
            type = "output"
        )
        val parsed = url!!.toHttpUrlOrNull()!!

        assertEquals("/view", parsed.encodedPath)
        assertEquals("a&type=temp&x.png", parsed.queryParameter("filename"))
        assertEquals("sub folder", parsed.queryParameter("subfolder"))
        assertEquals("output", parsed.queryParameter("type"))
        assertNull("不能被注入额外参数", parsed.queryParameter("x"))
    }

    @Test
    fun `preview 参数可选追加`() {
        val url = ComfyViewUrl.build("https://host:8443", "a.png", "", "output", "jpeg;75")
        val parsed = url!!.toHttpUrlOrNull()!!

        assertEquals("jpeg;75", parsed.queryParameter("preview"))
    }

    @Test
    fun `非法或非 http 协议返回 null`() {
        assertNull(ComfyViewUrl.build("not a url", "a.png", "", "output"))
        assertNull(ComfyViewUrl.build("ftp://host:21", "a.png", "", "output"))
    }
}