package sh.hnet.comfychair.workflow

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.hnet.comfychair.WorkflowManager

class ImageSlotInjectorTest {

    private val flf2v = """
    {"nodes":{
      "275":{"class_type":"LoadImage","inputs":{"image":"{{reference_image_1}}"}},
      "282":{"class_type":"LoadImage","inputs":{"image":"{{image_filename}}"}},
      "337":{"class_type":"ImageResizeKJv2","inputs":{"image":"275x"}},
      "334":{"class_type":"ImageResizeKJv2","inputs":{"image":"282x"}},
      "259":{"class_type":"MiniMaxH3ImageToVideo","inputs":{
          "first_frame":["337",0],"last_frame":["334",0]}}
    }}
    """.trimIndent().replace("\"275x\"", "[\"275\",0]").replace("\"282x\"", "[\"282\",0]")

    @Test
    fun `只填首帧时尾帧节点被删除且首帧仍连在 first_frame 上`() {
        val out = WorkflowManager.injectImageSlots(flf2v, mapOf("reference_image_1" to "a.png"))
        val nodes = JSONObject(out).getJSONObject("nodes")

        assertFalse("尾帧 LoadImage 应被删除", nodes.has("282"))
        assertTrue("首帧 LoadImage 应保留", nodes.has("275"))
        assertEquals("a.png", nodes.getJSONObject("275").getJSONObject("inputs").getString("image"))

        val lastFrameInputs = nodes.getJSONObject("259").getJSONObject("inputs")
        assertFalse("指向已删除节点的连线应被清掉", lastFrameInputs.has("last_frame"))
        assertTrue("首帧连线必须保留", lastFrameInputs.has("first_frame"))
    }

    @Test
    fun `两个都填时两条连线都保留且不互相串位`() {
        val out = WorkflowManager.injectImageSlots(
            flf2v, mapOf("reference_image_1" to "first.png", "image_filename" to "last.png")
        )
        val nodes = JSONObject(out).getJSONObject("nodes")

        assertEquals("first.png", nodes.getJSONObject("275").getJSONObject("inputs").getString("image"))
        assertEquals("last.png", nodes.getJSONObject("282").getJSONObject("inputs").getString("image"))

        val inputs = nodes.getJSONObject("259").getJSONObject("inputs")
        assertEquals("337", inputs.getJSONArray("first_frame").getString(0))
        assertEquals("334", inputs.getJSONArray("last_frame").getString(0))
    }

    @Test
    fun `工作流没有的图位会被补出新的编号节点`() {
        val ref2v = """
        {"nodes":{
          "308":{"class_type":"LoadImage","inputs":{"image":"{{image_filename}}"}},
          "316":{"class_type":"MiniMaxH3ReferenceToVideo","inputs":{
              "ref_images.ref_image_0":["308",0]}}
        }}
        """.trimIndent()
        // 工作流只有一个参考图位（编号 0），这里用户要传到第 2 个位置（编号 1）
        val out = WorkflowManager.injectImageSlots(
            ref2v, mapOf("image_filename" to "a.png", "reference_image_1" to "b.png")
        )
        val nodes = JSONObject(out).getJSONObject("nodes")
        val inputs = nodes.getJSONObject("316").getJSONObject("inputs")

        assertTrue("新的编号输入口应被创建", inputs.has("ref_images.ref_image_1"))
        val newRef = inputs.getJSONArray("ref_images.ref_image_1")
        val newNode = nodes.getJSONObject(newRef.getString(0))
        assertEquals("LoadImage", newNode.getString("class_type"))
        assertEquals("b.png", newNode.getJSONObject("inputs").getString("image"))
    }

    // ---- 以下为简报之外补充的用例，逐条钉住本任务对简报实现的关键修改 ----

    /**
     * 真实 flf2v 里的中转节点还吃着别的连线（337/334 的 width/height 来自 265），
     * 所以"没人引用它"这条并不能识别它 —— 必须靠"入口是通用数据口"来判废。
     *
     * 不剪掉 334 的后果：334 的必填输入 image 被清掉后节点还在，
     * ComfyUI 提交时报 required_input_missing（/object_info 中 ImageResizeKJv2.image 是 required），
     * 整张工作流失败 —— 等于没修（Task 1 实测结论「只断线不剪枝会留下缺必填输入的 ImageResizeKJv2，反而报错」）。
     */
    private val flf2vReal = """
    {"nodes":{
      "265":{"class_type":"PrimitiveInt","inputs":{"value":512}},
      "275":{"class_type":"LoadImage","inputs":{"image":"{{reference_image_1}}"}},
      "282":{"class_type":"LoadImage","inputs":{"image":"{{image_filename}}"}},
      "337":{"class_type":"ImageResizeKJv2","inputs":{
          "image":["275",0],"width":["265",0],"upscale_method":"lanczos"}},
      "334":{"class_type":"ImageResizeKJv2","inputs":{
          "image":["282",0],"width":["265",0],"upscale_method":"lanczos"}},
      "259":{"class_type":"MiniMaxH3ImageToVideo","inputs":{
          "first_frame":["337",0],"last_frame":["334",0]}}
    }}
    """.trimIndent()

    @Test
    fun `丢了自己的图的中转节点要跟着图位一起剪掉`() {
        val out = WorkflowManager.injectImageSlots(flf2vReal, mapOf("reference_image_1" to "a.png"))
        val nodes = JSONObject(out).getJSONObject("nodes")

        assertTrue("首帧链路上的中转节点应保留", nodes.has("337"))
        assertFalse("尾帧 LoadImage 应被删除", nodes.has("282"))
        assertFalse("尾帧链路上丢了输入的中转节点应被剪掉", nodes.has("334"))
        assertTrue("没吃过图位的共享节点不能被牵连", nodes.has("265"))

        val inputs = nodes.getJSONObject("259").getJSONObject("inputs")
        assertTrue("首帧连线必须保留", inputs.has("first_frame"))
        assertFalse("尾帧连线应被清掉", inputs.has("last_frame"))
    }

    @Test
    fun `只填尾帧时首帧节点被删且尾帧仍连在 last_frame 上`() {
        val out = WorkflowManager.injectImageSlots(flf2vReal, mapOf("image_filename" to "b.png"))
        val nodes = JSONObject(out).getJSONObject("nodes")

        assertFalse("首帧 LoadImage 应被删除", nodes.has("275"))
        assertFalse("首帧链路上的中转节点应被剪掉", nodes.has("337"))
        assertTrue("尾帧 LoadImage 应保留", nodes.has("282"))
        assertEquals("b.png", nodes.getJSONObject("282").getJSONObject("inputs").getString("image"))

        val inputs = nodes.getJSONObject("259").getJSONObject("inputs")
        assertFalse("首帧连线应被清掉", inputs.has("first_frame"))
        assertTrue("尾帧连线必须保留（甲类图位不补位）", inputs.has("last_frame"))
    }

    /**
     * 裁定 1：文件名格式化由调用方负责，注入器原样写入 image 字段 —— 不加也不减后缀。
     */
    @Test
    fun `assignment 里的文件名原样写入不加不减后缀`() {
        val ref2v = """
        {"nodes":{
          "308":{"class_type":"LoadImage","inputs":{"image":"{{image_filename}}"}},
          "316":{"class_type":"MiniMaxH3ReferenceToVideo","inputs":{
              "ref_images.ref_image_0":["308",0]}}
        }}
        """.trimIndent()
        val out = WorkflowManager.injectImageSlots(ref2v, mapOf("image_filename" to "a.png [input]"))

        val nodes = JSONObject(out).getJSONObject("nodes")
        assertEquals("a.png [input]", nodes.getJSONObject("308").getJSONObject("inputs").getString("image"))
    }

    /**
     * 调用方给空串时按"留空"处理：ComfyUI 不接受空文件名，
     * 把空串写进 image 字段会让整张工作流因"图片不存在"失败。
     */
    @Test
    fun `空串按留空处理不会写出空文件名`() {
        val out = WorkflowManager.injectImageSlots(
            flf2vReal,
            mapOf("reference_image_1" to "a.png", "image_filename" to "")
        )
        val nodes = JSONObject(out).getJSONObject("nodes")

        assertTrue("有图的图位照常写入", nodes.has("275"))
        assertEquals("a.png", nodes.getJSONObject("275").getJSONObject("inputs").getString("image"))
        assertFalse("空串图位应被当成留空删掉", nodes.has("282"))
        assertFalse("它的中转节点也应剪掉", nodes.has("334"))
    }

    // ---- 图生视频的图位表契约（resolveImageSlotFiles）----

    /**
     * 调用方漏传 `image_filename`（Task 5 传参不全就会这样）时，主图由代码兜底补上。
     * 不兜底的后果：主图节点被当"留空"删掉，且没有任何报错——静默退化成没有主图的生成。
     */
    @Test
    fun `图生视频漏传主图时由代码兜底补上主图仍被保留`() {
        val slotFiles = WorkflowManager.resolveImageSlotFiles(
            mapOf("reference_image_1" to "ref.png"),
            "src.png"
        )
        assertNotNull("有图时不应退化成字符串替换分支", slotFiles)
        assertEquals("主图必须由代码补进表里", "src.png", slotFiles!!["image_filename"])
        assertEquals("调用方给的值不能动", "ref.png", slotFiles["reference_image_1"])

        // 用兜底后的表跑真实 flf2v 结构：主图节点必须还在
        val out = WorkflowManager.injectImageSlots(flf2vReal, slotFiles)
        val nodes = JSONObject(out).getJSONObject("nodes")
        assertTrue("主图节点必须保留", nodes.has("282"))
        assertEquals("src.png", nodes.getJSONObject("282").getJSONObject("inputs").getString("image"))
        assertEquals("参考图节点也应保留", "ref.png", nodes.getJSONObject("275").getJSONObject("inputs").getString("image"))
    }

    /**
     * 全空串的 map 与空 map 必须走同一条分支（原来的字符串替换）。
     * 用 `isNotEmpty()` 判空会把全空串当成"传了图"，走注入器把整条图片链路删掉——
     * 与空 map 的行为完全不同，而 UI 侧很容易构造出全空串的表。
     */
    @Test
    fun `图生视频全空串的图位表与空表走同一条分支`() {
        assertNull("空 map 走原来的字符串替换分支", WorkflowManager.resolveImageSlotFiles(emptyMap(), "src.png"))
        assertNull(
            "全空串的 map 与空 map 行为一致",
            WorkflowManager.resolveImageSlotFiles(mapOf("image_filename" to ""), "src.png")
        )
        assertNull(
            "多个空串也一样",
            WorkflowManager.resolveImageSlotFiles(
                mapOf("image_filename" to "", "reference_image_1" to ""),
                "src.png"
            )
        )

        // 正常传参：原样使用，不改调用方给的值（含后缀约定）
        val normal = WorkflowManager.resolveImageSlotFiles(
            mapOf("image_filename" to "src.png [input]", "reference_image_1" to "ref.png [input]"),
            "ignored.png"
        )
        assertNotNull(normal)
        assertEquals("src.png [input]", normal!!["image_filename"])
        assertEquals("ref.png [input]", normal["reference_image_1"])
    }

    /**
     * 历史遗留写法：编辑模式模板里参考图节点写的是魔法文件名而不是占位符。
     * 旧实现的第二遍清理认得这种写法，泛化后不能丢 —— 否则用户不传参考图时
     * 会留下一个指向不存在的图片文件的 LoadImage。
     */
    @Test
    fun `历史遗留的魔法文件名写法也按图位清理`() {
        val legacy = """
        {"nodes":{
          "21":{"class_type":"LoadImage","inputs":{"image":"{{image_filename}}"}},
          "9":{"class_type":"LoadImage","inputs":{"image":"reference_image_1.png [input]"}},
          "141":{"class_type":"ImageScaleToTotalPixels","inputs":{"image":["21",0],"megapixels":2.0}},
          "142":{"class_type":"ImageScaleToTotalPixels","inputs":{"image":["9",0],"megapixels":2.0}},
          "3":{"class_type":"Krea2EditGroundedEncode","inputs":{
              "clip":["15",0],"image":["141",0],"image_b":["142",0]}}
        }}
        """.trimIndent()

        val out = WorkflowManager.injectImageSlots(legacy, mapOf("image_filename" to "src.png [input]"))
        val nodes = JSONObject(out).getJSONObject("nodes")

        assertFalse("遗留写法的参考图节点在留空时应被删除", nodes.has("9"))
        assertFalse("它的中转节点应被剪掉", nodes.has("142"))
        assertTrue("主图链路不能受影响", nodes.has("21") && nodes.has("141"))
        assertFalse("指向具名口的连线应被清掉", nodes.getJSONObject("3").getJSONObject("inputs").has("image_b"))
    }

    /**
     * 裁定 2：图生图编辑模式的第一遍替换已经把用户提供的图位写好了，
     * 此时占位符已经不在 JSON 里，注入器不得凭占位符名"补"一个节点出来 ——
     * 否则会把原本接好的图位换成新建节点（还丢掉调用方写的后缀）。
     */
    @Test
    fun `已被调用方替换过的图位不会被重复建节点`() {
        // 模拟图生图编辑流程第一遍替换之后的样子：两个占位符都已替换为带 [input] 的文件名
        val afterPassOne = """
        {"nodes":{
          "318":{"class_type":"LoadImage","inputs":{"image":"src.png [input]"}},
          "295":{"class_type":"LoadImage","inputs":{"image":"ref.png [input]"}},
          "316":{"class_type":"MiniMaxH3ReferenceToVideo","inputs":{
              "ref_images.ref_image_0":["318",0],"ref_images.ref_image_1":["295",0]}}
        }}
        """.trimIndent()

        val out = WorkflowManager.injectImageSlots(
            afterPassOne,
            mapOf("image_filename" to "src.png", "reference_image_1" to "ref.png")
        )
        val nodes = JSONObject(out).getJSONObject("nodes")

        assertEquals("不应凭空多出节点", 3, nodes.length())
        val inputs = nodes.getJSONObject("316").getJSONObject("inputs")
        assertEquals("原图位应保持接线不动", "295", inputs.getJSONArray("ref_images.ref_image_1").getString(0))
        assertEquals("ref.png [input]", nodes.getJSONObject("295").getJSONObject("inputs").getString("image"))
    }

    /**
     * 裁定 2 的真实场景：图生图编辑工作流（prebuilt_08 结构）没有编号容器，
     * 用户提供的 reference_image_1 已被第一遍替换，注入器必须什么都不做。
     */
    @Test
    fun `没有编号容器的编辑工作流不会凭空新建编号节点`() {
        val edit = """
        {"nodes":{
          "21":{"class_type":"LoadImage","inputs":{"image":"src.png [input]"}},
          "9":{"class_type":"LoadImage","inputs":{"image":"ref.png [input]"}},
          "141":{"class_type":"ImageScaleToTotalPixels","inputs":{"image":["21",0],"megapixels":2.0}},
          "142":{"class_type":"ImageScaleToTotalPixels","inputs":{"image":["9",0],"megapixels":2.0}},
          "3":{"class_type":"Krea2EditGroundedEncode","inputs":{
              "clip":["15",0],"image":["141",0],"image_b":["142",0]}}
        }}
        """.trimIndent()

        val out = WorkflowManager.injectImageSlots(
            edit,
            mapOf("image_filename" to "src.png", "reference_image_1" to "ref.png")
        )
        val nodes = JSONObject(out).getJSONObject("nodes")

        assertEquals("不应凭空多出节点", 5, nodes.length())
        val inputs = nodes.getJSONObject("3").getJSONObject("inputs")
        assertEquals("image 不应被改写", "141", inputs.getJSONArray("image").getString(0))
        assertEquals("image_b 不应被改写", "142", inputs.getJSONArray("image_b").getString(0))
    }

    /**
     * 编辑模式参考图留空：图位节点与它的中转节点一并清掉，连线指向的具名口被清空
     * （Krea2EditGroundedEncode.image_b 在 /object_info 里是 optional，清掉后工作流仍有效，
     * 这正是"允许只填一部分图位"的落地点）。
     */
    @Test
    fun `编辑模式留空参考图时链路被清干净不留缺输入的节点`() {
        val edit = """
        {"nodes":{
          "21":{"class_type":"LoadImage","inputs":{"image":"{{image_filename}}"}},
          "9":{"class_type":"LoadImage","inputs":{"image":"{{reference_image_1}}"}},
          "141":{"class_type":"ImageScaleToTotalPixels","inputs":{"image":["21",0],"megapixels":2.0}},
          "142":{"class_type":"ImageScaleToTotalPixels","inputs":{"image":["9",0],"megapixels":2.0}},
          "3":{"class_type":"Krea2EditGroundedEncode","inputs":{
              "clip":["15",0],"image":["141",0],"image_b":["142",0]}}
        }}
        """.trimIndent()

        val out = WorkflowManager.injectImageSlots(edit, mapOf("image_filename" to "src.png [input]"))
        val nodes = JSONObject(out).getJSONObject("nodes")

        assertFalse("留空的参考图节点应被删除", nodes.has("9"))
        assertFalse("它的中转节点应被剪掉", nodes.has("142"))
        assertTrue("主图链路不能受影响", nodes.has("21") && nodes.has("141"))

        val inputs = nodes.getJSONObject("3").getJSONObject("inputs")
        assertEquals("主图接线保持不动", "141", inputs.getJSONArray("image").getString(0))
        assertFalse("留空口的连线应被清掉", inputs.has("image_b"))
    }

    // ---- 以下为 Task 8 复审新问题 1（静默丢图）的回归锁 ----

    /**
     * 真实 ref2v：两个占位符分别接在 ref_images 的 0/1 号口上。
     *
     * 用户在界面上把图位删光后再点「+ 添加」，`nextNumberedSlot` 取**最小空位编号**重新分配，
     * 于是新图位的占位符是 `reference_image_0`、编号也是 0 —— 而 `ref_images.ref_image_0` 上
     * 还挂着 `{{image_filename}}` 那个"本次就要删掉"的节点。
     *
     * 旧实现见到编号口已被占用就 `continue`：新图无处可写，旧节点随后被删，
     * 容器里只剩 prompt —— 用户的图被静默丢弃，而界面上格子、图片、Generate 全都正常。
     */
    private val ref2vAllSlotsDeleted = """
    {"nodes":{
      "295":{"class_type":"LoadImage","inputs":{"image":"{{reference_image_1}}"}},
      "308":{"class_type":"LoadImage","inputs":{"image":"{{image_filename}}"}},
      "316":{"class_type":"MiniMaxH3ReferenceToVideo","inputs":{
          "ref_images.ref_image_0":["308",0],"ref_images.ref_image_1":["295",0]}}
    }}
    """.trimIndent()

    @Test
    fun `新建图位占到「即将被删节点」的编号口时图片不能被静默丢弃`() {
        // 删光后提交：表里只有新建的那个图位；主图键已随图位删除而消失，
        // 所以它对应的节点按"留空"被剪掉
        val out = WorkflowManager.injectImageSlots(
            ref2vAllSlotsDeleted, mapOf("reference_image_0" to "itv_new.png")
        )
        val nodes = JSONObject(out).getJSONObject("nodes")
        val inputs = nodes.getJSONObject("316").getJSONObject("inputs")

        val wiring = inputs.getJSONArray("ref_images.ref_image_0")
        val newNode = nodes.getJSONObject(wiring.getString(0))
        assertEquals("LoadImage", newNode.getString("class_type"))
        assertEquals(
            "用户的图必须真的写进工作流",
            "itv_new.png",
            newNode.getJSONObject("inputs").getString("image")
        )
        assertFalse("被删掉的图位节点应清理干净", nodes.has("308"))
        assertFalse("被删掉的图位节点应清理干净", nodes.has("295"))
        assertFalse("它留下的连线也要清掉", inputs.has("ref_images.ref_image_1"))
        assertFalse("提交体里不能留占位符字面量", out.contains("{{"))
    }

    /**
     * 反向对照：编号口上挂的是**真实接线**（不是本次要删的节点）时，仍然不许覆盖。
     *
     * 图生图编辑模式第一遍已把占位符换成文件名，那个节点既不是占位符节点、也不在待删集合里；
     * 用户自己手工接在容器编号口上的图同理。判据改动不能把这两种情形一起"修掉"。
     */
    @Test
    fun `编号口挂着真实接线时不覆盖`() {
        val manual = """
        {"nodes":{
          "308":{"class_type":"LoadImage","inputs":{"image":"{{image_filename}}"}},
          "9":{"class_type":"LoadImage","inputs":{"image":"manual.png"}},
          "316":{"class_type":"MiniMaxH3ReferenceToVideo","inputs":{
              "ref_images.ref_image_0":["308",0],"ref_images.ref_image_1":["9",0]}}
        }}
        """.trimIndent()

        val out = WorkflowManager.injectImageSlots(manual, mapOf("reference_image_1" to "new.png"))
        val nodes = JSONObject(out).getJSONObject("nodes")
        val inputs = nodes.getJSONObject("316").getJSONObject("inputs")

        assertEquals(
            "手工接的图不能被覆盖",
            "9",
            inputs.getJSONArray("ref_images.ref_image_1").getString(0)
        )
        assertEquals("manual.png", nodes.getJSONObject("9").getJSONObject("inputs").getString("image"))
    }
}
