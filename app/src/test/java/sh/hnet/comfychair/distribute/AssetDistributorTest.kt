package sh.hnet.comfychair.distribute

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.hnet.comfychair.util.SingleFlight
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * AssetDistributor 下发管线的 JVM 纯逻辑测试（B5）。
 *
 * 网络拉取用假 fetch 注入（回调同步或挂起可控），不碰 Android——管线里每一步的
 * 决策正确性在这里钉住：
 * - 拉（manifest → 资产字节）→ 比（版本只进不退）→ 验（SHA + 准入双重）→ 落盘（原子写）；
 * - 任一资产坏：该资产不落盘、**manifest_state 不写**（下次连接重试，最终一致），
 *   其余合法资产照常落盘；
 * - 远端版本旧/相同：整体 SKIPPED，本地一个字节都不动。
 */
class AssetDistributorTest {

    private fun tempDir(): File = java.nio.file.Files.createTempDirectory("asset-dist").toFile()

    // ---------------- 假件 ----------------

    /** 服务器端文件表：filename → 字节。 */
    private class FakeServer(val files: MutableMap<String, ByteArray>) {
        /** fetch 调用记录（按文件名计次） */
        val fetched = mutableListOf<String>()

        fun fetch(filename: String, callback: (ByteArray?) -> Unit) {
            fetched.add(filename)
            callback(files[filename])  // 同步回调：整条链同步走完
        }
    }

    private fun manifest(
        version: Int,
        generatedAt: String?,
        entries: List<Triple<AssetKind, String, ByteArray>>,
    ): ByteArray {
        val root = JSONObject()
        root.put("version", version)
        if (generatedAt != null) root.put("generated_at", generatedAt)
        val assets = org.json.JSONArray()
        for ((kind, filename, bytes) in entries) {
            val entry = JSONObject()
            entry.put("kind", when (kind) {
                AssetKind.PREBUILT_WORKFLOW -> "prebuilt_workflow"
                AssetKind.SKILL_MD -> "skill_md"
                AssetKind.RULE_TABLE -> "rule_table"
            })
            entry.put("filename", filename)
            entry.put("sha256", AssetDistributor.sha256Hex(bytes))
            entry.put("size", bytes.size.toLong())
            assets.put(entry)
        }
        root.put("assets", assets)
        return root.toString().toByteArray(StandardCharsets.UTF_8)
    }

    /** 合法的最小预置工作流字节（CheckpointLoaderSimple → TTI，input key 齐全）。 */
    private fun validWorkflowBytes(name: String): ByteArray {
        val workflow = JSONObject()
        workflow.put("name", name)
        val nodes = JSONObject()
        val loader = JSONObject()
        loader.put("class_type", "CheckpointLoaderSimple")
        loader.put("inputs", JSONObject().put("ckpt_name", "models/a.safetensors"))
        nodes.put("1", loader)
        workflow.put("nodes", nodes)
        // 补齐 TTI 必需的输入 key（占位符形态即可，keys 校验对占位符同样认）
        val sampler = JSONObject()
        sampler.put("class_type", "KSampler")
        sampler.put("inputs", JSONObject()
            .put("positive_prompt", "{{positive_prompt}}")
            .put("negative_prompt", "{{negative_prompt}}"))
        nodes.put("2", sampler)
        return workflow.toString().toByteArray(StandardCharsets.UTF_8)
    }

    private val validRuleTableBytes = """
        {"version": 1, "recognized_placeholders": ["positive_prompt"], "recognized_patterns": []}
    """.trimIndent().toByteArray(StandardCharsets.UTF_8)

    // ---------------- ① spec 必测三条 ----------------

    @Test
    fun `新版本全流程落盘并更新 manifest_state`() {
        val root = tempDir()
        try {
            val workflowBytes = validWorkflowBytes("下发流")
            val server = FakeServer(mutableMapOf(
                "asset_manifest.json" to manifest(3, "2026-11-28 12:00:00", listOf(
                    Triple(AssetKind.PREBUILT_WORKFLOW, "prebuilt_35_test.json", workflowBytes),
                    Triple(AssetKind.RULE_TABLE, "placeholder_rules.json", validRuleTableBytes),
                )),
                "prebuilt_35_test.json" to workflowBytes,
                "placeholder_rules.json" to validRuleTableBytes,
            ))
            val results = mutableListOf<AssetDistributor.SyncResult>()

            AssetDistributor().sync(root, server::fetch) { results.add(it) }

            assertEquals(1, results.size)
            val result = results.single()
            assertEquals(AssetDistributor.Outcome.COMPLETED, result.outcome)
            assertEquals(setOf(AssetKind.PREBUILT_WORKFLOW, AssetKind.RULE_TABLE), result.writtenKinds)

            // 落盘位置与内容逐一核对
            val workflowFile = File(File(root, AssetKind.PREBUILT_WORKFLOW.dirName), "prebuilt_35_test.json")
            assertTrue(workflowFile.isFile)
            assertTrue(workflowFile.readBytes().contentEquals(workflowBytes))
            val rulesFile = File(File(root, AssetKind.RULE_TABLE.dirName), "placeholder_rules.json")
            assertTrue(rulesFile.isFile)
            assertTrue(rulesFile.readBytes().contentEquals(validRuleTableBytes))

            // manifest_state 已记录远端版本（下次同版本 → SKIPPED）
            val state = JSONObject(File(root, AssetDistributor.STATE_FILENAME).readText())
            assertEquals(3, state.getInt("version"))
            assertEquals("2026-11-28 12:00:00", state.getString("generated_at"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `坏SHA的资产不落盘且不写state其余合法资产照常`() {
        val root = tempDir()
        try {
            val goodWorkflow = validWorkflowBytes("好流")
            val badRule = "corrupted".toByteArray(StandardCharsets.UTF_8)  // 与清单 SHA 不符
            val server = FakeServer(mutableMapOf(
                "asset_manifest.json" to manifest(3, "2026-11-28 12:00:00", listOf(
                    Triple(AssetKind.PREBUILT_WORKFLOW, "prebuilt_01_good.json", goodWorkflow),
                    Triple(AssetKind.RULE_TABLE, "placeholder_rules.json", validRuleTableBytes),
                )),
                "prebuilt_01_good.json" to goodWorkflow,
                "placeholder_rules.json" to badRule,
            ))
            val results = mutableListOf<AssetDistributor.SyncResult>()

            AssetDistributor().sync(root, server::fetch) { results.add(it) }

            val result = results.single()
            assertEquals(AssetDistributor.Outcome.FAILED, result.outcome)
            // 坏 SHA 资产绝不落盘
            assertFalse(File(File(root, AssetKind.RULE_TABLE.dirName), "placeholder_rules.json").exists())
            // 合法资产照常落盘
            assertTrue(File(File(root, AssetKind.PREBUILT_WORKFLOW.dirName), "prebuilt_01_good.json").isFile)
            // 整体失败不写 state：下次连接会重试整个 manifest（最终一致）
            assertFalse(File(root, AssetDistributor.STATE_FILENAME).exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `版本回退时整体跳过且本地不动`() {
        val root = tempDir()
        try {
            // 本地已是 v5 / 较新的 generated_at
            val state = JSONObject()
                .put("version", 5)
                .put("generated_at", "2026-11-29 00:00:00")
                .toString().toByteArray(StandardCharsets.UTF_8)
            File(root, AssetDistributor.STATE_FILENAME).writeBytes(state)
            val existing = File(File(root, AssetKind.RULE_TABLE.dirName).apply { mkdirs() }, "placeholder_rules.json")
            existing.writeBytes(validRuleTableBytes)

            // 远端是更旧的 v4
            val server = FakeServer(mutableMapOf(
                "asset_manifest.json" to manifest(4, "2026-11-28 12:00:00", listOf(
                    Triple(AssetKind.RULE_TABLE, "placeholder_rules.json", "newer? no—older".toByteArray(StandardCharsets.UTF_8)),
                )),
            ))
            val results = mutableListOf<AssetDistributor.SyncResult>()

            AssetDistributor().sync(root, server::fetch) { results.add(it) }

            assertEquals(AssetDistributor.Outcome.SKIPPED, results.single().outcome)
            // 资产一个都没拉（只拉了 manifest）
            assertEquals(listOf("asset_manifest.json"), server.fetched)
            // 本地文件与 state 原样未动
            assertTrue(existing.readBytes().contentEquals(validRuleTableBytes))
            assertEquals(5, JSONObject(File(root, AssetDistributor.STATE_FILENAME).readText()).getInt("version"))
        } finally {
            root.deleteRecursively()
        }
    }

    // ---------------- ② 版本比对细化 ----------------

    @Test
    fun `generated_at相同且version相同时跳过`() {
        val root = tempDir()
        try {
            val state = JSONObject()
                .put("version", 3)
                .put("generated_at", "2026-11-28 12:00:00")
                .toString().toByteArray(StandardCharsets.UTF_8)
            File(root, AssetDistributor.STATE_FILENAME).writeBytes(state)
            val server = FakeServer(mutableMapOf(
                "asset_manifest.json" to manifest(3, "2026-11-28 12:00:00", emptyList()),
            ))

            val results = mutableListOf<AssetDistributor.SyncResult>()
            AssetDistributor().sync(root, server::fetch) { results.add(it) }

            assertEquals(AssetDistributor.Outcome.SKIPPED, results.single().outcome)
            assertEquals(1, server.fetched.size)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `generated_at更新而version不变时仍落盘`() {
        val root = tempDir()
        try {
            val state = JSONObject()
                .put("version", 3)
                .put("generated_at", "2026-11-27 00:00:00")
                .toString().toByteArray(StandardCharsets.UTF_8)
            File(root, AssetDistributor.STATE_FILENAME).writeBytes(state)
            val server = FakeServer(mutableMapOf(
                "asset_manifest.json" to manifest(3, "2026-11-28 08:00:00", listOf(
                    Triple(AssetKind.SKILL_MD, "skill.md", "新规则".toByteArray(StandardCharsets.UTF_8)),
                )),
                "skill.md" to "新规则".toByteArray(StandardCharsets.UTF_8),
            ))

            val results = mutableListOf<AssetDistributor.SyncResult>()
            AssetDistributor().sync(root, server::fetch) { results.add(it) }

            assertEquals(AssetDistributor.Outcome.COMPLETED, results.single().outcome)
            assertTrue(File(File(root, AssetKind.SKILL_MD.dirName), "skill.md").isFile)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `本地无state时视为全新下发`() {
        val root = tempDir()
        try {
            val server = FakeServer(mutableMapOf(
                "asset_manifest.json" to manifest(1, null, listOf(
                    Triple(AssetKind.SKILL_MD, "skill.md", "正文".toByteArray(StandardCharsets.UTF_8)),
                )),
                "skill.md" to "正文".toByteArray(StandardCharsets.UTF_8),
            ))

            val results = mutableListOf<AssetDistributor.SyncResult>()
            AssetDistributor().sync(root, server::fetch) { results.add(it) }

            assertEquals(AssetDistributor.Outcome.COMPLETED, results.single().outcome)
        } finally {
            root.deleteRecursively()
        }
    }

    // ---------------- ③ 拉取失败与坏 manifest ----------------

    @Test
    fun `manifest拉取失败整体失败`() {
        val root = tempDir()
        try {
            val server = FakeServer(mutableMapOf())  // 空表 → manifest 拉不到

            val results = mutableListOf<AssetDistributor.SyncResult>()
            AssetDistributor().sync(root, server::fetch) { results.add(it) }

            assertEquals(AssetDistributor.Outcome.FAILED, results.single().outcome)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `manifest非法JSON整体失败`() {
        val root = tempDir()
        try {
            val server = FakeServer(mutableMapOf(
                "asset_manifest.json" to "not json".toByteArray(StandardCharsets.UTF_8),
            ))

            val results = mutableListOf<AssetDistributor.SyncResult>()
            AssetDistributor().sync(root, server::fetch) { results.add(it) }

            assertEquals(AssetDistributor.Outcome.FAILED, results.single().outcome)
            // state 不存在
            assertFalse(File(root, AssetDistributor.STATE_FILENAME).exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `manifest零有效条目时失败不写state`() {
        val root = tempDir()
        try {
            // 唯一条目文件名非法 → 条目全部被拒 → 空清单视为服务器端异常
            val server = FakeServer(mutableMapOf(
                "asset_manifest.json" to """
                    {"version": 2, "assets": [
                        {"kind": "rule_table", "filename": "../evil.json", "sha256": "${"f".repeat(64)}", "size": 1}
                    ]}
                """.trimIndent().toByteArray(StandardCharsets.UTF_8),
            ))

            val results = mutableListOf<AssetDistributor.SyncResult>()
            AssetDistributor().sync(root, server::fetch) { results.add(it) }

            assertEquals(AssetDistributor.Outcome.FAILED, results.single().outcome)
            assertFalse(File(root, AssetDistributor.STATE_FILENAME).exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `资产准入失败不落盘且整体失败`() {
        val root = tempDir()
        try {
            // SHA 与清单一致，但内容是坏工作流（类型判不出）→ 准入拒绝
            val badWorkflow = """{"nodes": {"1": {"class_type": "KSampler", "inputs": {"seed": "{{seed}}"}}}}"""
                .toByteArray(StandardCharsets.UTF_8)
            val server = FakeServer(mutableMapOf(
                "asset_manifest.json" to manifest(2, "2026-11-28 12:00:00", listOf(
                    Triple(AssetKind.PREBUILT_WORKFLOW, "prebuilt_bad.json", badWorkflow),
                )),
                "prebuilt_bad.json" to badWorkflow,
            ))

            val results = mutableListOf<AssetDistributor.SyncResult>()
            AssetDistributor().sync(root, server::fetch) { results.add(it) }

            assertEquals(AssetDistributor.Outcome.FAILED, results.single().outcome)
            assertFalse(File(File(root, AssetKind.PREBUILT_WORKFLOW.dirName), "prebuilt_bad.json").exists())
        } finally {
            root.deleteRecursively()
        }
    }

    // ---------------- ④ SHA 工具与闸门 ----------------

    @Test
    fun `sha256Hex 输出已知摘要`() {
        // SHA-256("hello") = 2cf24d…9824（公开测试向量）
        assertEquals(
            "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824",
            AssetDistributor.sha256Hex("hello".toByteArray(StandardCharsets.UTF_8)),
        )
    }

    @Test
    fun `闸门防重入`() {
        val root = tempDir()
        try {
            val gate = SingleFlight()
            val distributor = AssetDistributor(gate = gate)
            var fetchCalls = 0
            // 挂起型 fetch：回调不执行，第一次 sync 停在"等 manifest 回调"
            val pendingCallbacks = mutableListOf<(ByteArray?) -> Unit>()
            val hangingFetch: (String, (ByteArray?) -> Unit) -> Unit = { _, callback ->
                fetchCalls++
                pendingCallbacks.add(callback)
                Unit
            }

            distributor.sync(root, hangingFetch)
            assertTrue(gate.isInFlight)

            // 第二次 sync：闸门被占，直接返回（不再发 fetch）
            val results2 = mutableListOf<AssetDistributor.SyncResult>()
            distributor.sync(root, hangingFetch) { results2.add(it) }
            assertEquals(1, fetchCalls)
            assertEquals(0, results2.size)

            // 释放第一次：完成回调仍会正常走完（闸门释放）
            pendingCallbacks.single().invoke(null)
            assertEquals(1, fetchCalls)
            assertFalse("同步结束后闸门必须复位", gate.isInFlight)

            // 复位后可以再次进闸
            distributor.sync(root, hangingFetch)
            assertEquals(2, fetchCalls)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `onComplete回调抛异常后闸门仍复位`() {
        val root = tempDir()
        try {
            val gate = SingleFlight()
            val distributor = AssetDistributor(gate = gate)
            // manifest 拉不到 → FAILED 终态：finish 先 release 再调 onComplete（AssetDistributor.finish），
            // 回调抛出的异常被 finish 的兜底吃掉、不冒泡回 sync 调用方
            val server = FakeServer(mutableMapOf())
            distributor.sync(root, server::fetch) {
                assertFalse("release 必须先于 onComplete（否则回调里触发新同步会被误跳过）", gate.isInFlight)
                throw IllegalStateException("回调自身炸了")
            }

            // 回调异常被吞、闸门仍复位
            assertFalse("完成回调抛异常后闸门必须复位", gate.isInFlight)

            // 复位的行为证据：第二次 sync 不被跳过、能再次拉取
            val server2 = FakeServer(mutableMapOf())
            val results = mutableListOf<AssetDistributor.SyncResult>()
            distributor.sync(root, server2::fetch) { results.add(it) }
            assertEquals(listOf(AssetDistributor.MANIFEST_FILENAME), server2.fetched)
            assertEquals(AssetDistributor.Outcome.FAILED, results.single().outcome)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `fetch发起阶段同步抛异常时闸门复位且不发回调`() {
        val root = tempDir()
        try {
            val gate = SingleFlight()
            val distributor = AssetDistributor(gate = gate)
            // enqueue 一返回就同步抛出：sync 的兜底 catch 自己 release 后吞掉（刻意不在
            // finally 里放闸——异步回调到来之前提前放闸会让闸门保护彻底失效），
            // 也不发 onComplete（没有 FAILED 假象，本次同步就是「没发生」）
            val boomFetch: (String, (ByteArray?) -> Unit) -> Unit = { _, _ ->
                throw IllegalStateException("enqueue 失败（模拟发起阶段同步抛出）")
            }
            val results = mutableListOf<AssetDistributor.SyncResult>()
            distributor.sync(root, boomFetch) { results.add(it) }

            assertTrue(results.isEmpty())
            assertFalse("发起阶段异常后闸门必须复位", gate.isInFlight)

            // 复位的行为证据：第二次 sync 不被跳过、能再次拉取（空表 → manifest 拉不到 → FAILED）
            val server = FakeServer(mutableMapOf())
            distributor.sync(root, server::fetch) { results.add(it) }
            assertEquals(listOf(AssetDistributor.MANIFEST_FILENAME), server.fetched)
            assertEquals(AssetDistributor.Outcome.FAILED, results.single().outcome)
        } finally {
            root.deleteRecursively()
        }
    }
}
