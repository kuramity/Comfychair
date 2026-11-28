package sh.hnet.comfychair.distribute

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import sh.hnet.comfychair.WorkflowType
import java.io.File

/**
 * 三类资产准入校验的正反例测试（B5）。
 *
 * 准入是「坏文件绝不落盘」的第一道闸：SHA 只能证明字节与清单一致，证明不了内容可用。
 * 各类准入口径：
 * - PREBUILT_WORKFLOW：与用户手动导入同款（detectWorkflowType / validateWorkflowKeys 先例，
 *   WorkflowManagementViewModel:211/:357）——类型可判定 + 必需输入 key 齐全；带 {{...}} 占位符
 *   残留的模板形态还要求占位符齐全（validateWorkflow）。
 * - SKILL_MD：严格 UTF-8 解码（malformed 即拒）+ 非空 + 无 BOM。
 * - RULE_TABLE：RuleTable.parseTableJson 的 schema 校验器不抛即收（现成准入）。
 */
class AssetAdmissionTest {

    /** 读 assets 真实文件（双路径：从仓库根跑 / 从 app 目录跑，与 RuleTableTest 同口径）。 */
    private fun assetText(relPath: String): String? = listOf(
        File("src/main/assets/$relPath"),
        File("app/src/main/assets/$relPath"),
    ).firstOrNull { it.isFile }?.readText()

    private fun assetBytes(relPath: String): ByteArray? = listOf(
        File("src/main/assets/$relPath"),
        File("app/src/main/assets/$relPath"),
    ).firstOrNull { it.isFile }?.readBytes()

    // ---------------- ① PREBUILT_WORKFLOW ----------------

    @Test
    fun `真实预置工作流（模板形态）通过准入`() {
        val bytes = assetBytes("prebuilt_workflows/prebuilt_02_zimage_pornmaster_t2i.json")
        assumeTrue("预置资产不在磁盘上时跳过", bytes != null)

        val result = AssetAdmission.admit(AssetKind.PREBUILT_WORKFLOW, bytes!!)

        val accepted = result as? AdmissionResult.Accepted
        assertNotNull("真实预置流必须通过准入：$result", accepted)
        assertEquals(WorkflowType.ITI_EDITING, accepted!!.workflowType)
        assertTrue("Accept 必须带回解码文本（落盘侧直接写字节，解码文本供装配用）", accepted.decodedText.isNotEmpty())
    }

    @Test
    fun `真实预置工作流（原始 key 形态无占位符）通过准入`() {
        // prebuilt_03 是 4x 放大流：全文没有 {{positive_prompt}}，靠真实输入 key 通过 keys 校验
        val bytes = assetBytes("prebuilt_workflows/prebuilt_03_zimage_4x_upscale.json")
        assumeTrue("预置资产不在磁盘上时跳过", bytes != null)

        val result = AssetAdmission.admit(AssetKind.PREBUILT_WORKFLOW, bytes!!)

        assertTrue("原始 key 形态必须通过准入：$result", result is AdmissionResult.Accepted)
    }

    @Test
    fun `带占位符残留但类型判不出时拒绝`() {
        // 有 {{...}} 残留，但没有任何可判定类型的节点（无 loader / 无 LoadImage / 无视频节点）
        val text = """{"nodes": {"1": {"class_type": "KSampler", "inputs": {"seed": "{{seed}}"}}}}"""

        val result = AssetAdmission.admit(AssetKind.PREBUILT_WORKFLOW, text.toByteArray(Charsets.UTF_8))

        assertTrue("带占位符残留且类型缺采样的工作流必须被拒", result is AdmissionResult.Rejected)
    }

    @Test
    fun `显式类型非法且节点判不出类型时拒绝`() {
        // workflow_type 是非法枚举值（拼写错误/新版本类型），节点构成也判不出类型——
        // 两路类型判定都失败的下发流必须拒（下发版必须能进某个通道）
        val text = """
            {"workflow_type": "BOGUS_TYPE", "nodes": {"1": {"class_type": "KSampler", "inputs": {"seed": "{{seed}}"}}}}
        """.trimIndent()

        val result = AssetAdmission.admit(AssetKind.PREBUILT_WORKFLOW, text.toByteArray(Charsets.UTF_8))

        assertTrue("类型双路判不出的工作流必须被拒：$result", result is AdmissionResult.Rejected)
    }

    @Test
    fun `最小 TTI 工作流（原始 key 齐全）通过准入`() {
        // graph-traced 键（positive_text 等）在 direct keys 校验里豁免，最小可运行形态应放行
        val text = """
            {"nodes": {"1": {"class_type": "CheckpointLoaderSimple", "inputs": {"ckpt_name": "a.safetensors"}}}}
        """.trimIndent()

        val result = AssetAdmission.admit(AssetKind.PREBUILT_WORKFLOW, text.toByteArray(Charsets.UTF_8))

        assertTrue("最小 TTI 应通过准入：$result", result is AdmissionResult.Accepted)
    }

    @Test
    fun `非 JSON 字节的预置工作流拒绝`() {
        val result = AssetAdmission.admit(AssetKind.PREBUILT_WORKFLOW, "{not json".toByteArray(Charsets.UTF_8))
        assertTrue(result is AdmissionResult.Rejected)
    }

    @Test
    fun `非法 UTF-8 字节的预置工作流拒绝`() {
        // 0xC3 后跟 0x28 是标准的多字节序列截断形态，严格解码必报 malformed
        val result = AssetAdmission.admit(
            AssetKind.PREBUILT_WORKFLOW,
            byteArrayOf(0xC3.toByte(), 0x28, 0x7D) // "�(}"
        )
        assertTrue(result is AdmissionResult.Rejected)
    }

    // ---------------- ② SKILL_MD ----------------

    @Test
    fun `合法技能 Markdown 通过准入`() {
        val text = "# 文生图技能\n\n把用户需求改写成成品提示词。"
        val result = AssetAdmission.admit(AssetKind.SKILL_MD, text.toByteArray(Charsets.UTF_8))
        assertTrue(result is AdmissionResult.Accepted)
        assertEquals(text, (result as AdmissionResult.Accepted).decodedText)
    }

    @Test
    fun `非法 UTF-8 字节的技能文件拒绝`() {
        val result = AssetAdmission.admit(
            AssetKind.SKILL_MD,
            byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0x00)
        )
        assertTrue(result is AdmissionResult.Rejected)
    }

    @Test
    fun `空内容与纯空白的技能文件拒绝`() {
        assertTrue(AssetAdmission.admit(AssetKind.SKILL_MD, ByteArray(0)) is AdmissionResult.Rejected)
        assertTrue(
            AssetAdmission.admit(AssetKind.SKILL_MD, "   \n\t ".toByteArray(Charsets.UTF_8))
                is AdmissionResult.Rejected
        )
    }

    @Test
    fun `带 UTF-8 BOM 的技能文件拒绝`() {
        // BOM 是发送端编码异常的信号（规则文件统一无 BOM），进来会以 \uFEFF 污染正文
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        val body = "正文".toByteArray(Charsets.UTF_8)
        val result = AssetAdmission.admit(AssetKind.SKILL_MD, bom + body)
        assertTrue("BOM 开头的技能文件必须被拒", result is AdmissionResult.Rejected)
    }

    // ---------------- ③ RULE_TABLE ----------------

    @Test
    fun `真实规则文件通过准入`() {
        val bytes = assetBytes("rules/placeholder_rules.json")
        assumeTrue("规则文件不在磁盘上时跳过", bytes != null)

        val result = AssetAdmission.admit(AssetKind.RULE_TABLE, bytes!!)

        assertTrue("合法规则表必须通过准入：$result", result is AdmissionResult.Accepted)
    }

    @Test
    fun `schema 不合规的规则表拒绝`() {
        // recognized 空表 → RuleTable.parseTableJson require 失败
        val text = """{"version": 1, "recognized_placeholders": [], "recognized_patterns": []}"""

        val result = AssetAdmission.admit(AssetKind.RULE_TABLE, text.toByteArray(Charsets.UTF_8))

        assertTrue("坏 schema 的规则表必须被拒", result is AdmissionResult.Rejected)
    }

    @Test
    fun `非 JSON 的规则表拒绝`() {
        val result = AssetAdmission.admit(AssetKind.RULE_TABLE, "garbage".toByteArray(Charsets.UTF_8))
        assertTrue(result is AdmissionResult.Rejected)
    }
}
