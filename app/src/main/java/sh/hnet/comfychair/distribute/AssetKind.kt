package sh.hnet.comfychair.distribute

import org.json.JSONObject
import sh.hnet.comfychair.WorkflowType
import sh.hnet.comfychair.WorkflowValidationResult
import sh.hnet.comfychair.workflow.rules.RuleTable
import sh.hnet.comfychair.workflow.validation.WorkflowJsonAnalyzer
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/**
 * 严格 UTF-8 解码：malformed / unmappable 一律 REPORT（抛异常），绝不静默替换字符。
 * distribute 包内共用（清单解码 / 三类资产准入都从它起步）。
 */
internal fun decodeStrictUtf8(bytes: ByteArray): String? = try {
    Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes))
        .toString()
} catch (e: CharacterCodingException) {
    null
}

/**
 * 服务器下发资产的三类（B5）。
 *
 * [dirName] 是本地覆盖层（`filesDir/overlay/<dirName>/`）与 App 内置 assets 的对应目录名：
 * - PREBUILT_WORKFLOW → `prebuilt_workflows`（与 assets/prebuilt_workflows 同名对应）；
 * - SKILL_MD → `prompts`（与 assets/prompts 同名对应）；
 * - RULE_TABLE → `rules`（与 assets/rules 同名对应）。
 *
 * [wireName] 是 manifest JSON 里 `kind` 字段的取值（App 端单方面定义，服务器端生成脚本照抄）。
 */
enum class AssetKind(val dirName: String, val wireName: String) {
    PREBUILT_WORKFLOW("prebuilt_workflows", "prebuilt_workflow"),
    SKILL_MD("prompts", "skill_md"),
    RULE_TABLE("rules", "rule_table");

    companion object {

        /** 规则表资产的标准文件名（manifest 条目、落盘位置与 RuleTable 读取位置三方共用，单一事实源）。 */
        const val RULE_TABLE_FILENAME = "placeholder_rules.json"

        fun fromWireName(name: String): AssetKind? = entries.firstOrNull { it.wireName == name }
    }
}

/**
 * 准入结论：通过时带回解码文本（落盘侧直接写字节，解码文本供 WorkflowManager 装配复用，
 * 免二次解码）；拒绝时带回原因（进日志，现场排查「为什么这条没生效」用）。
 */
sealed interface AdmissionResult {
    data class Accepted(val decodedText: String, val workflowType: WorkflowType? = null) : AdmissionResult
    data class Rejected(val reason: String) : AdmissionResult
}

/**
 * 资产准入（B5）：SHA 只能证明「字节与清单一致」，证明不了「内容可用」——
 * 落盘前必须再过一遍内容校验，坏文件绝不落盘（哪怕清单 SHA 是对的）。
 *
 * 各类准入口径（与既有先例对齐，不新造标准）：
 * - PREBUILT_WORKFLOW：用户手动导入同款（WorkflowManagementViewModel 的 detectWorkflowType
 *   与 validateWorkflowKeys 先例）——类型可判定 + 必需输入 key 齐全；带 {{...}} 占位符残留的
 *   模板形态还要求模板占位符齐全（validateWorkflow）。占位符形态与原始 key 形态都认
 *   （keys 校验对占位符值同样放行，见 WorkflowJsonAnalyzer.validateWorkflowKeys）。
 * - SKILL_MD：严格 UTF-8 解码（malformed 即拒，不用替换字符悄悄吞坏字节）+ 非空 + 无 BOM
 *   （规则文件统一无 BOM，BOM 是发送端编码异常的信号，进来会污染正文首个字符）。
 * - RULE_TABLE：复用 [RuleTable.parseTableJson] 的 schema 校验器（B3 的现成准入）——
 *   不抛异常即收，抛即拒。
 */
object AssetAdmission {

    fun admit(kind: AssetKind, bytes: ByteArray): AdmissionResult = when (kind) {
        AssetKind.PREBUILT_WORKFLOW -> admitWorkflow(bytes)
        AssetKind.SKILL_MD -> admitSkillMd(bytes)
        AssetKind.RULE_TABLE -> admitRuleTable(bytes)
    }

    private fun admitWorkflow(bytes: ByteArray): AdmissionResult {
        val text = decodeStrictUtf8(bytes) ?: return AdmissionResult.Rejected("不是合法 UTF-8 文本")
        if (text.isBlank()) return AdmissionResult.Rejected("内容为空")
        val json = try {
            JSONObject(text)
        } catch (e: Exception) {
            return AdmissionResult.Rejected("JSON 解析失败: ${e.message}")
        }

        // 类型判定：显式 workflow_type 优先（比自动检测可靠，seedPrebuiltWorkflows 同款），
        // 缺失或非法时退回节点构成检测；两者都判不出 → 拒（下发版必须能进某个通道）。
        val explicit = json.optString("workflow_type", "")
        val type = (
            if (explicit.isNotEmpty()) {
                try {
                    WorkflowType.valueOf(explicit)
                } catch (e: Exception) {
                    null
                }
            } else null
        ) ?: WorkflowJsonAnalyzer.detectWorkflowType(text)
            ?: return AdmissionResult.Rejected("无法判定工作流类型（workflow_type=$explicit）")

        // 必需输入 key 校验（手动导入同款口径）：缺 key 拦，占位符形态放行。
        // 当前 Registry 下 direct keys 全是 graph-traced 豁免项（恒空集），此检查防上游演进
        // （有 key 被移出 GRAPH_TRACED_KEYS 时自动生效）。
        // 注意 validateWorkflow 的 REQUIRED_PATTERNS（uploaded_image.png [input]）**不用于
        // 准入**：真实预置流（ITI_EDITING）用编号型参考图占位符替代了该字面量，全文匹配
        // 会误杀合法形态——与内置预置流（seedPrebuiltWorkflows 只做类型检测）同信任级别，
        // 下发版只多 SHA 与类型两道闸。占位符模板与原始 key 形态都认。
        when (val keyCheck = WorkflowJsonAnalyzer.validateWorkflowKeys(text, type)) {
            is WorkflowValidationResult.MissingKeys ->
                return AdmissionResult.Rejected("缺少必需输入 key: ${keyCheck.missing}")
            is WorkflowValidationResult.InvalidJson ->
                return AdmissionResult.Rejected("JSON 不合法: ${keyCheck.message}")
            else -> {}
        }

        return AdmissionResult.Accepted(decodedText = text, workflowType = type)
    }

    private fun admitSkillMd(bytes: ByteArray): AdmissionResult {
        val text = decodeStrictUtf8(bytes) ?: return AdmissionResult.Rejected("不是合法 UTF-8 文本（含坏字节）")
        if (text.isBlank()) return AdmissionResult.Rejected("内容为空")
        if (text.startsWith('\uFEFF')) return AdmissionResult.Rejected("带 UTF-8 BOM（发送端编码异常信号）")
        return AdmissionResult.Accepted(decodedText = text)
    }

    private fun admitRuleTable(bytes: ByteArray): AdmissionResult {
        val text = decodeStrictUtf8(bytes) ?: return AdmissionResult.Rejected("不是合法 UTF-8 文本")
        return try {
            RuleTable.parseTableJson(text)
            AdmissionResult.Accepted(decodedText = text)
        } catch (e: Exception) {
            AdmissionResult.Rejected("规则表 schema 校验失败: ${e.message}")
        }
    }
}
