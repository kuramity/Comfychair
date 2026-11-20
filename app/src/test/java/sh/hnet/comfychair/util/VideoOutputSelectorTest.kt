package sh.hnet.comfychair.util

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P2-6：视频产物的挑选规则。
 *
 * 修复前是 `videos ?: gifs ?: images` 后取 `[0]`：
 * - 空数组会遮蔽后面节点里的真视频；
 * - 不校验扩展名，images 里的 png 会被当视频下载；
 * - outputs.keys() 顺序不定，同一份历史可能挑到不同文件。
 */
class VideoOutputSelectorTest {

    private fun mediaEntry(
        filename: String,
        subfolder: String = "",
        type: String = "output"
    ): JSONObject = JSONObject().apply {
        put("filename", filename)
        put("subfolder", subfolder)
        put("type", type)
    }

    private fun node(arrayKey: String, vararg entries: JSONObject): JSONObject =
        JSONObject().put(arrayKey, JSONArray().apply { entries.forEach { put(it) } })

    @Test
    fun `空 videos 数组不会遮蔽后续节点里的视频`() {
        val outputs = JSONObject()
            .put("1", node("videos")) // 空数组：旧实现 `videos ?: ...` 会在这里提前结束
            .put("2", node("images", mediaEntry("clip.mp4", type = "temp")))

        val video = VideoOutputSelector.first(outputs)

        assertEquals("clip.mp4", video?.filename)
        assertEquals("temp", video?.type)
    }

    @Test
    fun `videos 数组里的 png 会被跳过而去 images 里找真视频`() {
        val outputs = JSONObject().put(
            "1",
            JSONObject()
                .put("videos", JSONArray().put(mediaEntry("preview.png")))
                .put("images", JSONArray().put(mediaEntry("anim.webm")))
        )

        val video = VideoOutputSelector.first(outputs)

        assertEquals("anim.webm", video?.filename)
    }

    @Test
    fun `只有非视频文件时返回 null`() {
        val outputs = JSONObject().put(
            "1",
            JSONObject()
                .put("images", JSONArray().put(mediaEntry("a.png")).put(mediaEntry("b.jpg")))
        )

        assertNull(VideoOutputSelector.first(outputs))
        assertTrue(VideoOutputSelector.findAll(outputs).isEmpty())
    }

    @Test
    fun `subfolder 与 type 原样带出`() {
        val outputs = JSONObject().put(
            "7",
            node("videos", mediaEntry("a.mp4", subfolder = "sub/dir", type = "output"))
        )

        val video = VideoOutputSelector.first(outputs)

        assertEquals("a.mp4", video?.filename)
        assertEquals("sub/dir", video?.subfolder)
        assertEquals("output", video?.type)
    }

    @Test
    fun `扩展名判定不区分大小写`() {
        val outputs = JSONObject().put("1", node("videos", mediaEntry("CLIP.MP4")))

        assertEquals("CLIP.MP4", VideoOutputSelector.first(outputs)?.filename)
    }

    @Test
    fun `同一文件同时出现在 videos 与 images 时只保留一条`() {
        val entry = mediaEntry("dup.mp4")
        val outputs = JSONObject().put(
            "1",
            JSONObject()
                .put("videos", JSONArray().put(entry))
                .put("images", JSONArray().put(entry))
        )

        assertEquals(1, VideoOutputSelector.findAll(outputs).size)
    }

    @Test
    fun `遍历所有节点而不是停在第一个非空数组`() {
        val outputs = JSONObject()
            .put("1", node("videos", mediaEntry("first.mp4")))
            .put("2", node("videos", mediaEntry("second.mp4")))

        val all = VideoOutputSelector.findAll(outputs)

        assertEquals(2, all.size)
        assertTrue(all.any { it.filename == "first.mp4" })
        assertTrue(all.any { it.filename == "second.mp4" })
    }

    @Test
    fun `videos 字段优先于 images，即使节点 id 更靠后`() {
        val outputs = JSONObject()
            .put("1", node("images", mediaEntry("gif_or_image.gif")))
            .put("2", node("videos", mediaEntry("final.mp4")))

        val video = VideoOutputSelector.first(outputs)

        assertEquals("保留 videos 优先语义", "final.mp4", video?.filename)
    }

    @Test
    fun `outputs 为空时安全返回`() {
        assertNull(VideoOutputSelector.first(null))
        assertNull(VideoOutputSelector.first(JSONObject()))
    }

    @Test
    fun `缺失 type 时按 output 兜底`() {
        val entry = JSONObject().apply {
            put("filename", "a.mp4")
            put("subfolder", "")
        }
        val outputs = JSONObject().put("1", node("videos", entry))

        assertEquals("output", VideoOutputSelector.first(outputs)?.type)
    }
}