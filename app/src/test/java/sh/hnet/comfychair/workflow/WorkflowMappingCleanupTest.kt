package sh.hnet.comfychair.workflow

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.hnet.comfychair.WorkflowManager

/**
 * 钉住「保存工作流」两步操作的产物形状：
 * 1. [WorkflowManager.applyFieldMappings] —— 把选中的字段落成 `{{占位符}}`
 * 2. [WorkflowManager.clearStaleAndUnmappedPlaceholders] —— 清掉陈旧/未映射的占位符
 *
 * 两条不变量（Task 9 验收 8(b) 与 §6.2）：
 * - **保存不得改变任何已有连线的归属**：`ImageResizeKJv2.image = ["282",0]` 这种连线要原样保留，
 *   不能被某个字段的占位符顶掉。
 * - **清理不得写空串**：清掉一个输入键就真的 `remove`，不能留下 `"image": ""`——
 *   空串能过 `/prompt` 校验，却会在执行期让 ComfyUI 把空串当目录去打开而抛
 *   `[Errno 13] Permission denied: '<ComfyUI>/input'`，用户只看到一句"Generation failed"。
 */
class WorkflowMappingCleanupTest {

    /** `prebuilt_15_minimaxh3_flf2v` 的图片相关骨架 */
    private fun flf2vJson() = JSONObject(
        """
        {"nodes":{
          "275":{"class_type":"LoadImage","inputs":{"image":"{{reference_image_1}}","upload":"image"}},
          "282":{"class_type":"LoadImage","inputs":{"image":"{{image_filename}}","upload":"image"}},
          "337":{"class_type":"ImageResizeKJv2","inputs":{"image":["275",0]}},
          "334":{"class_type":"ImageResizeKJv2","inputs":{"image":["282",0]}},
          "259":{"class_type":"MiniMaxH3ImageToVideo","inputs":{
              "first_frame":["337",0],"last_frame":["334",0],"prompt":"{{positive_prompt}}"}}
        }}
        """.trimIndent()
    )

    private val flf2vImageFieldMappings = mapOf(
        "image" to ("282" to "image"),
        "reference_image_1" to ("275" to "image")
    )

    // ---------- 不变量 1：保存不改动已有连线 ----------

    @Test
    fun `保存后尾帧连线与首帧连线原样保留`() {
        val nodes = flf2vJson().getJSONObject("nodes")

        // 与修复后的选择规则一致：image→282、reference_image_1→275 是精确匹配；
        // positive_text 没有占位符，退化成字面量候选 259.prompt（提示词硬编码的情形）。
        WorkflowManager.applyFieldMappings(
            nodes,
            flf2vImageFieldMappings + ("positive_text" to ("259" to "prompt"))
        )
        WorkflowManager.clearStaleAndUnmappedPlaceholders(
            nodes,
            placeholderToTargetNodeId = mapOf(
                "image_filename" to "282",
                "reference_image_1" to "275",
                "positive_prompt" to "259"
            ),
            unmappedOptionalKeys = setOf("reference_image_2")
        )

        assertEquals(
            "尾帧连线不能被顶掉",
            "282", nodes.getJSONObject("334").getJSONObject("inputs").getJSONArray("image").getString(0)
        )
        assertEquals(
            "首帧连线不能被顶掉",
            "275", nodes.getJSONObject("337").getJSONObject("inputs").getJSONArray("image").getString(0)
        )
        assertEquals(
            "259 的两条图片连线都要在",
            "337", nodes.getJSONObject("259").getJSONObject("inputs").getJSONArray("first_frame").getString(0)
        )
        assertEquals(
            "259 的两条图片连线都要在",
            "334", nodes.getJSONObject("259").getJSONObject("inputs").getJSONArray("last_frame").getString(0)
        )
        assertEquals(
            "硬编码提示词要能被子段映射上，否则保存会被 Missing Required Fields 挡住",
            "{{positive_prompt}}",
            nodes.getJSONObject("259").getJSONObject("inputs").getString("prompt")
        )
    }

    @Test
    fun `把 reference_image_2 也映射到 ImageResizeKJv2 时连线才会被顶掉（反证：这正是修复前的行为）`() {
        val nodes = flf2vJson().getJSONObject("nodes")
        val buggyMappings = flf2vImageFieldMappings + ("reference_image_2" to ("334" to "image"))

        WorkflowManager.applyFieldMappings(nodes, buggyMappings)

        assertEquals(
            "修复前 autoSelectIndex 退化成候选 0（= 334），保存就把连线换成了字面量占位符",
            "{{reference_image_2}}",
            nodes.getJSONObject("334").getJSONObject("inputs").getString("image")
        )
    }

    // ---------- 不变量 2：清理写"删除"而不是写空串 ----------

    @Test
    fun `陈旧占位符被整键删除而不是写成空串`() {
        val nodes = flf2vJson().getJSONObject("nodes")
        // 假装 reference_image_1 的目标节点被改成了 282，于是 275 上那一份变成"陈旧"
        WorkflowManager.clearStaleAndUnmappedPlaceholders(
            nodes,
            placeholderToTargetNodeId = mapOf("reference_image_1" to "282"),
            unmappedOptionalKeys = emptySet()
        )

        val loadImage275 = nodes.getJSONObject("275").getJSONObject("inputs")
        assertFalse("陈旧占位符所在的输入键应被删掉", loadImage275.has("image"))
    }

    @Test
    fun `未映射的可选占位符被整键删除而不是写成空串`() {
        val nodes = JSONObject(
            """
            {"nodes":{
              "900":{"class_type":"LoadImage","inputs":{"image":"{{reference_image_2}}","upload":"image"}}
            }}
            """.trimIndent()
        ).getJSONObject("nodes")

        WorkflowManager.clearStaleAndUnmappedPlaceholders(
            nodes,
            placeholderToTargetNodeId = emptyMap(),
            unmappedOptionalKeys = setOf("reference_image_2")
        )

        val inputs = nodes.getJSONObject("900").getJSONObject("inputs")
        assertFalse("未映射占位符所在的输入键应被删掉", inputs.has("image"))
    }

    @Test
    fun `已映射且已落到目标节点的占位符不受影响`() {
        val nodes = flf2vJson().getJSONObject("nodes")
        WorkflowManager.clearStaleAndUnmappedPlaceholders(
            nodes,
            placeholderToTargetNodeId = mapOf(
                "image_filename" to "282",
                "reference_image_1" to "275"
            ),
            unmappedOptionalKeys = setOf("reference_image_2")
        )

        assertEquals(
            "{{image_filename}} 在目标节点上，必须原样保留",
            "{{image_filename}}",
            nodes.getJSONObject("282").getJSONObject("inputs").getString("image")
        )
        assertEquals(
            "{{reference_image_1}} 在目标节点上，必须原样保留",
            "{{reference_image_1}}",
            nodes.getJSONObject("275").getJSONObject("inputs").getString("image")
        )
    }

    @Test
    fun `连线不受清理影响（optString 取出的不是占位符正则的形态）`() {
        val nodes = flf2vJson().getJSONObject("nodes")
        WorkflowManager.clearStaleAndUnmappedPlaceholders(
            nodes,
            placeholderToTargetNodeId = mapOf("image_filename" to "999"),  // 谁都对不上，逼它扫一遍
            unmappedOptionalKeys = emptySet()
        )

        assertTrue("334 的 image 仍是连线", nodes.getJSONObject("334").getJSONObject("inputs").get("image") is org.json.JSONArray)
        assertTrue("337 的 image 仍是连线", nodes.getJSONObject("337").getJSONObject("inputs").get("image") is org.json.JSONArray)
    }
}
