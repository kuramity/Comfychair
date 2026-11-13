package sh.hnet.comfychair.repository

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 图库 3D 识别的纯解析测试（v4.3.0 新增）。
 *
 * 背景（真机 v4.2.2 反馈的实际缺陷）：IDOL 图生3D 跑完后，用户在图库里只能看到
 * 预览 png —— glb 写在 /history 的**独立字段 `3d`** 里，且常与预览图的 `images`
 * 分属同一 promptId 的不同 node。解析器必须在 promptId 层跨 node 收集 `3d`、
 * 配给图片条目，否则 3D 产物在图库里没有任何入口。
 *
 * 以下 JSON 结构取自服务器实测（promptId cd420ba4…：node 914 出 glb、node 915 出预览图）。
 */
class GalleryHistoryParserTest {

    /** 组一个 outputs 条目：单 node 单数组。 */
    private fun node(key: String, arr: JSONArray): JSONObject = JSONObject().put(key, arr)

    private fun mediaEntry(filename: String, subfolder: String = "", type: String = "output"): JSONObject =
        JSONObject().apply {
            put("filename", filename)
            put("subfolder", subfolder)
            put("type", type)
        }

    /** 组整份 /history：每个 promptId 传 outputs 的 node 映射。 */
    private fun history(vararg prompts: Pair<String, JSONObject>): JSONObject =
        JSONObject().apply {
            prompts.forEach { (pid, outputs) ->
                put(pid, JSONObject().put("outputs", outputs))
            }
        }

    // ------------------------------------------------------------
    // 1. IDOL 实测结构：3d 与 images 在同一 promptId 的不同 node → 配对
    // ------------------------------------------------------------
    @Test
    fun glbFromOtherNodeIsPairedOntoImageEntry() {
        val outputs = JSONObject()
            .put("914", node("3d", JSONArray().put(mediaEntry("idol_3d_67_00001_.glb"))))
            .put("915", node("images", JSONArray().put(mediaEntry("share.image.png", subfolder = "", type = "temp"))))

        val items = GalleryHistoryParser.parse(history("p1" to outputs))

        assertEquals(1, items.size)
        val item = items[0]
        assertEquals("share.image.png", item.filename)
        assertNotNull("图片条目必须带上同 prompt 的 3D 产物", item.threeD)
        assertEquals("idol_3d_67_00001_.glb", item.threeD!!.filename)
        assertEquals("output", item.threeD!!.type)
    }

    // ------------------------------------------------------------
    // 2. `3d` 数组混有非 glb 文件时优先 .glb
    // ------------------------------------------------------------
    @Test
    fun glbEntryIsPreferredInside3dArray() {
        val outputs = JSONObject()
            .put(
                "914",
                node(
                    "3d",
                    JSONArray()
                        .put(mediaEntry("texture_atlas.png"))
                        .put(mediaEntry("model.glb"))
                )
            )
            .put("915", node("images", JSONArray().put(mediaEntry("preview.png"))))

        val items = GalleryHistoryParser.parse(history("p1" to outputs))

        assertEquals("model.glb", items[0].threeD!!.filename)
    }

    // ------------------------------------------------------------
    // 3. 只有 glb、没有预览图 → glb 自身成为条目（缩略图走 3D 图标兜底）
    // ------------------------------------------------------------
    @Test
    fun glbOnlyOutputBecomesStandaloneEntry() {
        val outputs = JSONObject()
            .put("914", node("3d", JSONArray().put(mediaEntry("idol_3d_67_00009_.glb"))))

        val items = GalleryHistoryParser.parse(history("p1" to outputs))

        assertEquals(1, items.size)
        val item = items[0]
        assertEquals("idol_3d_67_00009_.glb", item.filename)
        assertFalse(item.isVideo)
        assertNotNull(item.threeD)
        assertEquals(item.filename, item.threeD!!.filename)
    }

    // ------------------------------------------------------------
    // 4. 回归保护：普通出图/视频流（无 3d 字段）行为不变
    // ------------------------------------------------------------
    @Test
    fun regularWorkflowEntriesStayUntouched() {
        val outputs = JSONObject()
            .put("1", node("images", JSONArray().put(mediaEntry("zimage_00001_.png"))))
            .put("2", node("videos", JSONArray().put(mediaEntry("h3_00001_.mp4"))))

        val items = GalleryHistoryParser.parse(history("p1" to outputs))

        assertEquals(2, items.size)
        val image = items.first { !it.isVideo }
        val video = items.first { it.isVideo }
        assertNull("普通流的条目不得带 3D 标记", image.threeD)
        assertNull(video.threeD)
        assertEquals("zimage_00001_.png", image.filename)
        assertEquals("h3_00001_.mp4", video.filename)
    }

    // ------------------------------------------------------------
    // 5. 空 `3d` 数组等同于没有 3D 产物
    // ------------------------------------------------------------
    @Test
    fun empty3dArrayIsIgnored() {
        val outputs = JSONObject()
            .put("914", node("3d", JSONArray()))
            .put("915", node("images", JSONArray().put(mediaEntry("a.png"))))

        val items = GalleryHistoryParser.parse(history("p1" to outputs))

        assertEquals(1, items.size)
        assertNull(items[0].threeD)
    }

    // ------------------------------------------------------------
    // 6. 多 prompt 并存时配对不跨 prompt 泄漏
    // ------------------------------------------------------------
    @Test
    fun pairingDoesNotLeakAcrossPrompts() {
        val with3d = JSONObject()
            .put("914", node("3d", JSONArray().put(mediaEntry("a.glb"))))
            .put("915", node("images", JSONArray().put(mediaEntry("a.png"))))
        val without3d = JSONObject()
            .put("1", node("images", JSONArray().put(mediaEntry("b.png"))))

        val items = GalleryHistoryParser.parse(
            history("pid-3d" to with3d, "pid-plain" to without3d)
        )

        assertEquals(2, items.size)
        val a = items.first { it.filename == "a.png" }
        val b = items.first { it.filename == "b.png" }
        assertEquals("a.glb", a.threeD!!.filename)
        assertNull("3D 配对不得泄漏到其他 prompt 的条目", b.threeD)
    }

    // ------------------------------------------------------------
    // 7. 排序约定保持：新条目在前（index 降序）
    // ------------------------------------------------------------
    @Test
    fun newestFirstOrderingPreserved() {
        val outputs = JSONObject()
            .put("1", node("images", JSONArray().put(mediaEntry("first.png"))))
            .put("2", node("images", JSONArray().put(mediaEntry("second.png"))))

        val items = GalleryHistoryParser.parse(history("p1" to outputs))

        assertTrue(items[0].index > items[1].index)
        assertEquals("second.png", items[0].filename)
    }
}
