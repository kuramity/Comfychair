package sh.hnet.comfychair.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 系统备份规则契约。
 *
 * 备份规则从「全量 include」改成白名单：用户自建数据随备份走，
 * 凭据 / 服务器配置 / 任务队列一律不出设备（含换机迁移）。
 * 这里直接读 XML 文本做断言，防止规则被改回全量或白名单写漏。
 */
class BackupRulesContractTest {

    /** 允许备份的用户数据（必须逐个出现在 include 行里）。 */
    private val whitelisted = listOf(
        "AppSettings.xml",
        "PromptPresetPrefs.xml",
        "WorkflowValuesPrefs.xml",
        "TextToImageFragmentPrefs.xml",
        "ImageToImageFragmentPrefs.xml",
        "TextToVideoFragmentPrefs.xml",
        "ImageToVideoFragmentPrefs.xml",
        "GenerationViewModelPrefs.xml",
        "tag_library_state.xml",
        "tag_library_prefs.xml",
        "UserWorkflowsPrefs.xml",
        "SkillPromptPrefs.xml"
    )

    /** 绝不允许离开设备的敏感数据。 */
    private val sensitive = listOf(
        "CredentialPrefs.xml",
        "ServerPrefs.xml",
        "JobRegistryPrefs.xml"
    )

    private fun readRuleFile(fileName: String): String {
        val candidates = listOf(
            File("src/main/res/xml/$fileName"),
            File("app/src/main/res/xml/$fileName")
        )
        val file = candidates.firstOrNull { it.exists() }
            ?: error("找不到备份规则文件 $fileName，尝试过：${candidates.map { it.absolutePath }}")
        // 先剥掉 XML 注释，避免注释里出现的标签名被误判为真实规则
        return file.readText().replace(Regex("(?s)<!--.*?-->"), "")
    }

    private fun includeLines(raw: String) = raw.lines().filter { it.contains("<include") }

    private fun excludeLines(raw: String) = raw.lines().filter { it.contains("<exclude") }

    /** 单个分支内的白名单 / 排除项断言。 */
    private fun assertBranchRules(branchName: String, raw: String) {
        val includes = includeLines(raw)
        val excludes = excludeLines(raw)

        for (name in sensitive) {
            assertTrue("$branchName：敏感文件 $name 必须出现在 exclude 行里", excludes.any { it.contains(name) })
            assertFalse("$branchName：敏感文件 $name 不得出现在 include 行里", includes.any { it.contains(name) })
        }

        for (name in whitelisted) {
            assertTrue("$branchName：用户数据 $name 必须在 include 行里", includes.any { it.contains(name) })
        }

        val fullSharedPrefInclude = includes.filter {
            it.contains("domain=\"sharedpref\"") && it.contains("path=\".\"")
        }
        assertTrue(
            "$branchName：不得存在 sharedpref 全量 include，实际：$fullSharedPrefInclude",
            fullSharedPrefInclude.isEmpty()
        )
    }

    @Test
    fun `backup_rules_白名单与排除项正确`() {
        assertBranchRules("backup_rules.xml", readRuleFile("backup_rules.xml"))
    }

    @Test
    fun `data_extraction_rules_云端备份与设备迁移都按白名单处理`() {
        val raw = readRuleFile("data_extraction_rules.xml")
        assertTrue("缺少 cloud-backup 分支", raw.contains("<cloud-backup>"))

        val parts = raw.split("<device-transfer>")
        assertEquals("应恰好包含 cloud-backup 与 device-transfer 两个分支", 2, parts.size)

        assertBranchRules("cloud-backup", parts[0])
        assertBranchRules("device-transfer", parts[1])
    }
}