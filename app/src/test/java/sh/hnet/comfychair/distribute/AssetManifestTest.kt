package sh.hnet.comfychair.distribute

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 资产清单解析的纯逻辑测试（B5）。
 *
 * manifest 由桌面端脚本生成、经同源 /view 下发，App 端单方面定义格式：
 * `{"version": 3, "generated_at": "...", "assets": [{"kind", "filename", "sha256", "size"}]}`。
 *
 * 解析纪律（与 DownloadPolicy 的校验器同口径）：
 * - 顶层结构坏（非 JSON / version <1 / assets 数组缺失）→ 整份拒绝；
 * - 单个条目缺字段 / 字段不合规 → **只拒该条目**，其余条目仍可用；
 * - filename 必须过 WorkflowFilePolicy 同款安全校验（防路径穿越）；
 * - sha256 必须 64 位十六进制（缺失即拒，绝不短路放行）。
 */
class AssetManifestTest {

    // ---------------- ① 合法解析 ----------------

    @Test
    fun `合法 manifest 解析出全部条目`() {
        val raw = """
            {"version": 3, "generated_at": "2026-11-28 12:00:00", "assets": [
                {"kind": "prebuilt_workflow", "filename": "prebuilt_35_test.json", "sha256": "${"a".repeat(64)}", "size": 12345},
                {"kind": "skill_md", "filename": "Qwen-Image-2.1-文生图.md", "sha256": "${"b".repeat(64)}", "size": 999},
                {"kind": "rule_table", "filename": "placeholder_rules.json", "sha256": "${"c".repeat(64)}", "size": 2048}
            ]}
        """.trimIndent()

        val manifest = AssetManifestParser.parse(raw)

        assertEquals(3, manifest.version)
        assertEquals("2026-11-28 12:00:00", manifest.generatedAt)
        assertEquals(3, manifest.entries.size)
        assertEquals(AssetKind.PREBUILT_WORKFLOW, manifest.entries[0].kind)
        assertEquals(AssetKind.SKILL_MD, manifest.entries[1].kind)
        assertEquals(AssetKind.RULE_TABLE, manifest.entries[2].kind)
        assertEquals("prebuilt_35_test.json", manifest.entries[0].filename)
        assertEquals(12345L, manifest.entries[0].size)
        assertEquals("b".repeat(64), manifest.entries[1].sha256)
    }

    @Test
    fun `generated_at 缺失时允许为 null`() {
        val raw = """{"version": 1, "assets": [
            {"kind": "rule_table", "filename": "placeholder_rules.json", "sha256": "${"d".repeat(64)}", "size": 1}
        ]}"""

        val manifest = AssetManifestParser.parse(raw)

        assertNull(manifest.generatedAt)
        assertEquals(1, manifest.entries.size)
    }

    // ---------------- ② 条目级拒绝（整份仍可用其余条目） ----------------

    @Test
    fun `坏条目被拒收且其余条目保留`() {
        val good = """{"kind": "rule_table", "filename": "placeholder_rules.json", "sha256": "${"e".repeat(64)}", "size": 8}"""
        val raw = """
            {"version": 2, "assets": [
                {"kind": "prebuilt_workflow", "filename": "a.json", "sha256": "", "size": 1},
                {"kind": "prebuilt_workflow", "filename": "b.json", "sha256": "nothex", "size": 1},
                {"kind": "prebuilt_workflow", "filename": "c.json", "sha256": "${"f".repeat(64)}"},
                {"kind": "prebuilt_workflow", "filename": "d.json", "sha256": "${"f".repeat(64)}", "size": -1},
                {"kind": "unknown_kind", "filename": "e.json", "sha256": "${"f".repeat(64)}", "size": 1},
                {"kind": "prebuilt_workflow", "filename": "../evil.json", "sha256": "${"f".repeat(64)}", "size": 1},
                {"kind": "prebuilt_workflow", "filename": "sub/dir.json", "sha256": "${"f".repeat(64)}", "size": 1},
                {"kind": "prebuilt_workflow", "filename": "C:\\evil.json", "sha256": "${"f".repeat(64)}", "size": 1},
                {"kind": "prebuilt_workflow", "filename": "", "sha256": "${"f".repeat(64)}", "size": 1},
                "not-an-object",
                $good
            ]}
        """.trimIndent()

        val manifest = AssetManifestParser.parse(raw)

        assertEquals("只有最后一条合法条目通过", 1, manifest.entries.size)
        assertEquals(AssetKind.RULE_TABLE, manifest.entries[0].kind)
    }

    // ---------------- ③ 顶层拒绝 ----------------

    @Test
    fun `非 JSON 文本整份拒绝`() {
        assertThrows(AssetManifestParser.InvalidManifestException::class.java) {
            AssetManifestParser.parse("not json at all")
        }
    }

    @Test
    fun `version 缺失或小于 1 整份拒绝`() {
        assertThrows(AssetManifestParser.InvalidManifestException::class.java) {
            AssetManifestParser.parse("""{"assets": []}""")
        }
        assertThrows(AssetManifestParser.InvalidManifestException::class.java) {
            AssetManifestParser.parse("""{"version": 0, "assets": []}""")
        }
    }

    @Test
    fun `assets 数组缺失整份拒绝`() {
        assertThrows(AssetManifestParser.InvalidManifestException::class.java) {
            AssetManifestParser.parse("""{"version": 1}""")
        }
    }

    // ---------------- ④ 穿越形态逐个钉 ----------------

    @Test
    fun `穿越与非法文件名逐个拒收`() {
        val evilNames = listOf(
            "../../evil.json",
            "sub/evil.json",
            "sub\\evil.json",
            "..",
            ".",
            ".hidden.json",
            "a\nb.json",
        )
        for (name in evilNames) {
            val raw = """
                {"version": 1, "assets": [
                    {"kind": "prebuilt_workflow", "filename": ${org.json.JSONObject.quote(name)},
                     "sha256": "${"f".repeat(64)}", "size": 1}
                ]}
            """.trimIndent()
            val manifest = AssetManifestParser.parse(raw)
            assertTrue("文件名「$name」必须被拒收（条目被丢弃）", manifest.entries.isEmpty())
        }
    }
}
