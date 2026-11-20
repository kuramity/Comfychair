package sh.hnet.comfychair.util

import org.junit.Assert.assertEquals
import org.junit.Test
import sh.hnet.comfychair.workflow.FieldCandidate
import sh.hnet.comfychair.workflow.mapping.FieldMappingAnalyzer

/**
 * 钉住字段映射的自动选中规则（Task 9 验收 8(b) 的回归点：`FieldMappingAnalyzer.autoSelectIndices`）。
 *
 * 真实形态取自 `prebuilt_15_minimaxh3_flf2v`：
 * - 节点 275 `LoadImage.image = {{reference_image_1}}`（首帧）
 * - 节点 282 `LoadImage.image = {{image_filename}}`（尾帧）
 * - 节点 334 `ImageResizeKJv2.image = ["282",0]`（连线，不是字面量 → currentValue 为 null）
 * - 节点 337 `ImageResizeKJv2.image = ["275",0]`（同上）
 *
 * 四个字段 `image` / `reference_image_1` / `reference_image_2` 用的 jsonKey 都是 `image`，
 * 拿到的是**同一份候选列表**，选中谁完全由选择规则决定。
 */
class FieldMappingAnalyzerTest {

    private fun flf2vImageCandidates() = listOf(
        FieldCandidate(
            nodeId = "275", nodeName = "LoadImage", classType = "LoadImage",
            inputKey = "image", currentValue = "{{reference_image_1}}"
        ),
        FieldCandidate(
            nodeId = "282", nodeName = "LoadImage", classType = "LoadImage",
            inputKey = "image", currentValue = "{{image_filename}}"
        ),
        FieldCandidate(
            nodeId = "334", nodeName = "ImageResizeKJv2", classType = "ImageResizeKJv2",
            inputKey = "image", currentValue = null   // 指向 282 的连线
        ),
        FieldCandidate(
            nodeId = "337", nodeName = "ImageResizeKJv2", classType = "ImageResizeKJv2",
            inputKey = "image", currentValue = null   // 指向 275 的连线
        )
    )

    private fun selectFor(vararg fieldKeys: String): Map<String, Int> =
        FieldMappingAnalyzer.autoSelectIndices(
            fieldKeys.toList(),
            fieldKeys.associateWith { flf2vImageCandidates() }
        )

    // ---------- 不变量 1：占位符精确匹配优先（已映射工作流原样保持） ----------

    @Test
    fun `已映射工作流里 image 选中持有 image_filename 的节点，而不是候选 0`() {
        val result = selectFor("image")
        assertEquals("{{image_filename}} 应选中节点 282（候选 1）", 1, result["image"])
    }

    @Test
    fun `已映射工作流里 reference_image_1 选中持有它自己的节点`() {
        val result = selectFor("reference_image_1")
        assertEquals("{{reference_image_1}} 应选中节点 275（候选 0）", 0, result["reference_image_1"])
    }

    @Test
    fun `两个已映射字段各归其位，不会互相顶替`() {
        val result = selectFor("image", "reference_image_1")
        assertEquals(1, result["image"])
        assertEquals(0, result["reference_image_1"])
    }

    // ---------- 不变量 2：不能拿连线当映射目标 ----------

    @Test
    fun `没有对应占位符的字段不会退化成连线候选（修复前这里会选中 334）`() {
        // prebuilt_15 里根本没有 {{reference_image_2}}：此时 275/282 已被 image/reference_image_1
        // 精确匹配占用，剩下能选的是 334/337 —— 它们都是连线。修复前 autoSelectIndex 退化成
        // 候选 0（= 334），保存时把 {{reference_image_2}} 写进 ImageResizeKJv2.image，顶掉 ["282",0]。
        val result = selectFor("image", "reference_image_1", "reference_image_2")
        assertEquals("找不到能落脚的字面量候选时必须返回 -1，绝不能落到连线上", -1, result["reference_image_2"])
    }

    @Test
    fun `候选全是连线时返回 -1`() {
        val connectionOnly = listOf(
            FieldCandidate("334", "ImageResizeKJv2", "ImageResizeKJv2", "image", null)
        )
        val result = FieldMappingAnalyzer.autoSelectIndices(
            listOf("reference_image_2"), mapOf("reference_image_2" to connectionOnly)
        )
        assertEquals(-1, result["reference_image_2"])
    }

    // ---------- 不变量 3：同一个输入键只能归一个字段 ----------

    @Test
    fun `两个字段都没有精确匹配时依次占用不同的字面量候选，不会抢同一个`() {
        val result = selectFor("reference_image_2", "reference_image_3")
        assertEquals("先处理的字段拿第一个字面量候选", 0, result["reference_image_2"])
        assertEquals("后处理的字段要跳过已被占用的输入，拿下一个", 1, result["reference_image_3"])
    }

    // ---------- 保留既有行为：提示词还硬编码在节点里时仍要有默认选中项 ----------

    @Test
    fun `工作流里还没有该字段占位符时退化成第一个字面量候选（硬编码提示词的场景）`() {
        // prebuilt_15 的提示词是写死在 MiniMaxH3ImageToVideo.prompt 里的字面量（不是占位符）。
        // 「打开编辑器 → 保存」必须仍能把这个字段映射上，否则保存会被"Missing Required Fields"挡住。
        val literalPrompt = listOf(
            FieldCandidate("259", "MiniMaxH3ImageToVideo", "MiniMaxH3ImageToVideo", "prompt", "女人变幻姿势，镜头推进")
        )
        val result = FieldMappingAnalyzer.autoSelectIndices(
            listOf("positive_text"), mapOf("positive_text" to literalPrompt)
        )
        assertEquals(0, result["positive_text"])
    }

    @Test
    fun `第一遍的精确匹配不会被第二遍的字段抢走`() {
        // 先跑的字段没有精确匹配，但它的候选里第一个就是 275 —— 不能被它占掉，
        // 275 要留给 reference_image_1 的精确匹配。
        val result = selectFor("reference_image_2", "reference_image_1")
        assertEquals("精确匹配者必须拿到 275", 0, result["reference_image_1"])
        assertEquals("无精确匹配者只能拿剩下的 282", 1, result["reference_image_2"])
    }

    @Test
    fun `没有候选时一律返回 -1`() {
        val result = FieldMappingAnalyzer.autoSelectIndices(
            listOf("image"), mapOf("image" to emptyList())
        )
        assertEquals(-1, result["image"])
    }

    // ---------- 单字段版本语义一致 ----------

    @Test
    fun `单字段版本同样精确匹配优先`() {
        assertEquals(1, FieldMappingAnalyzer.autoSelectIndex("image", flf2vImageCandidates()))
    }

    @Test
    fun `单字段版本跳过连线且尊重已占用集合`() {
        val candidates = flf2vImageCandidates()
        assertEquals(
            "002 无精确匹配 → 跳过连线的 334/337，落到 275",
            0,
            FieldMappingAnalyzer.autoSelectIndex("reference_image_2", candidates)
        )
        assertEquals(
            "275 已被别的字段占用 → 落到 282",
            1,
            FieldMappingAnalyzer.autoSelectIndex("reference_image_2", candidates, setOf("275" to "image"))
        )
        assertEquals(
            "275/282 都被占用 → 没有可落的字面量候选",
            -1,
            FieldMappingAnalyzer.autoSelectIndex(
                "reference_image_2", candidates, setOf("275" to "image", "282" to "image")
            )
        )
    }

    @Test
    fun `单字段版本对空候选返回 -1`() {
        assertEquals(-1, FieldMappingAnalyzer.autoSelectIndex("image", emptyList()))
    }
}
