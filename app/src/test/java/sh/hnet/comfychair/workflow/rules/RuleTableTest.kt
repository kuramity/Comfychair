package sh.hnet.comfychair.workflow.rules

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.hnet.comfychair.workflow.TemplateKeyRegistry
import java.io.File
import java.nio.file.Files

/**
 * RuleTable 的等价性钉子（B3-T1b）。
 *
 * 防漂移核心：DEFAULT_TABLE 是 TemplateKeyRegistry.RECOGNIZED_PLACEHOLDERS 现值的数据化副本，
 * 上游清单一变，双向 diff 与行为等价测试必须先响，提示同步更新规则文件与内置表。
 */
class RuleTableTest {

    /** 规则文件文本（双候选路径：从仓库根跑 / 从 app 目录跑，同 ModelIntroMarkdownTest 的写法）。 */
    private fun rulesFileText(): String? = listOf(
        File("src/main/assets/rules/placeholder_rules.json"),
        File("app/src/main/assets/rules/placeholder_rules.json"),
    ).firstOrNull { it.isFile }?.readText()

    /** JVM 单测没有 AssetManager、不会走 load()，current() 恒为 DEFAULT_TABLE —— 正好钉住内置表本身。 */
    private val defaultTable: RuleTable.Table = RuleTable.current()

    // ---------------- ① 防漂移核心：与 TemplateKeyRegistry 现值比对 ----------------

    @Test
    fun `DEFAULT_TABLE 的 recognized 与 TemplateKeyRegistry 完全一致`() {
        val expected = TemplateKeyRegistry.RECOGNIZED_PLACEHOLDERS
        val actual = defaultTable.recognized
        val onlyInRegistry = expected - actual
        val onlyInTable = actual - expected
        assertTrue("DEFAULT_TABLE 缺少（TemplateKeyRegistry 有而规则表无）：$onlyInRegistry", onlyInRegistry.isEmpty())
        assertTrue("DEFAULT_TABLE 多出（规则表有而 TemplateKeyRegistry 无）：$onlyInTable", onlyInTable.isEmpty())
    }

    @Test
    fun `DEFAULT_TABLE 的编号型模式与原 TemplateKeyRegistry 值一致`() {
        // 原 RECOGNIZED_PLACEHOLDER_PATTERNS 字段已随 B3 委托迁入 RuleTable 并删除，
        // 期望值取自迁移前的现值（两条编号型模式）；上游改 pattern 时这里必须同步
        // （下方行为等价测试兜底）。
        assertEquals(
            listOf("""reference_image_\d+""", """reference_audio_\d+"""),
            defaultTable.patterns.map { it.pattern },
        )
    }

    /** 行为等价兜底：不依赖 private 字段，对全部清单 + 模式正反样本比对两套判定的结论。 */
    @Test
    fun `isRecognized 与 TemplateKeyRegistry 判定行为等价`() {
        val samples = TemplateKeyRegistry.RECOGNIZED_PLACEHOLDERS + listOf(
            "reference_image_3", "reference_image_17", "reference_audio_1", "reference_audio_42",
            "reference_imag_1", "reference_image_", "unknown_param", "negative_prompt2",
        )
        for (p in samples) {
            assertEquals(
                "占位符 \"$p\" 的识别结论与 TemplateKeyRegistry 不一致",
                TemplateKeyRegistry.isRecognizedPlaceholder(p),
                RuleTable.isRecognized(p),
            )
        }
    }

    // ---------------- ② 规则文件 = DEFAULT_TABLE（两份数据不得各自漂移）----------------

    @Test
    fun `规则文件解析结果与 DEFAULT_TABLE 全字段一致`() {
        val raw = rulesFileText()
        org.junit.Assume.assumeTrue("placeholder_rules.json 不在磁盘上时跳过", raw != null)
        val parsed = RuleTable.parseTableJson(raw!!)
        assertEquals(defaultTable.version, parsed.version)
        assertEquals(defaultTable.recognized, parsed.recognized)
        // Regex 没有 equals 重写，比对 pattern 文本
        assertEquals(defaultTable.patterns.map { it.pattern }, parsed.patterns.map { it.pattern })
        assertEquals(defaultTable.meta, parsed.meta)
    }

    // ---------------- ③ 行为断言 ----------------

    @Test
    fun `isRecognized 对编号型占位符返回 true`() {
        assertTrue(RuleTable.isRecognized("reference_image_3"))
        assertTrue(RuleTable.isRecognized("reference_audio_5"))
        // 模式不应误伤：少数字、拼错都不算
        assertFalse(RuleTable.isRecognized("reference_image_"))
        assertFalse(RuleTable.isRecognized("reference_imag_1"))
    }

    @Test
    fun `forbiddenIn 按 meta 判定 width 的编辑类禁用`() {
        assertTrue(RuleTable.forbiddenIn("width", "iti_editing"))
        assertTrue(RuleTable.forbiddenIn("width", "iti_inpainting"))
        assertFalse("width 在 TTI 是合法通道", RuleTable.forbiddenIn("width", "tti"))
        assertFalse("seed 无 forbidden_in 约束", RuleTable.forbiddenIn("seed", "iti_editing"))
        assertFalse("未登记 meta 的占位符恒无约束", RuleTable.forbiddenIn("totally_unknown", "tti"))
    }

    // ---------------- ④ overlay 优先级（B5：下发规则表覆盖 assets，坏 overlay 回退）----------------

    private fun tempDir(): File = Files.createTempDirectory("rule-table").toFile()

    private fun tableRaw(version: Int, key: String): String =
        """{"version": $version, "recognized_placeholders": ["$key"], "recognized_patterns": []}"""

    @Test
    fun `overlay 有效时优先于 assets`() {
        val parsed = RuleTable.pickTable(
            overlayRaw = tableRaw(9, "overlay_only_key"),
            assetsRaw = tableRaw(1, "positive_prompt"),
        )
        assertEquals(9, parsed.version)
        assertEquals(setOf("overlay_only_key"), parsed.recognized)
    }

    @Test
    fun `overlay 缺失时用 assets`() {
        val parsed = RuleTable.pickTable(
            overlayRaw = null,
            assetsRaw = tableRaw(1, "positive_prompt"),
        )
        assertEquals(1, parsed.version)
        assertEquals(setOf("positive_prompt"), parsed.recognized)
    }

    @Test
    fun `overlay 坏 schema 时回退 assets`() {
        val parsed = RuleTable.pickTable(
            overlayRaw = """{"version": 0, "recognized_placeholders": []}""", // require 失败的坏表
            assetsRaw = tableRaw(1, "positive_prompt"),
        )
        assertEquals("坏 overlay 必须回退 assets，绝不能用半生效的表", 1, parsed.version)
        assertEquals(setOf("positive_prompt"), parsed.recognized)
    }

    @Test
    fun `overlay 与 assets 都坏时回退内置默认表`() {
        val parsed = RuleTable.pickTable(
            overlayRaw = "not json",
            assetsRaw = "not json either",
        )
        assertEquals(defaultTable.version, parsed.version)
        assertEquals(defaultTable.recognized, parsed.recognized)
    }

    @Test
    fun `assets 坏且无 overlay 时回退内置默认表`() {
        val parsed = RuleTable.pickTable(overlayRaw = null, assetsRaw = null)
        assertEquals(defaultTable.recognized, parsed.recognized)
    }
}
