package sh.hnet.comfychair

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 节点属性编辑（nodeAttributeEdits）采样参数旁路的**源码级接线钉**（sampler-F2）。
 *
 * 背景：提交前的 sampler/scheduler 清洗在各页面 ViewModel 的 prepareWorkflowJson 里
 * （消费 SamplerOptions.sanitize*）；而 Workflow Editor 的节点属性编辑走的是另一条路 ——
 * WorkflowManager 私有版 `applyNodeAttributeEdits(processedJson, workflowId)`，它是
 * 所有 prepare* 的共同最后一环，直接把编辑值 put 进节点 inputs。这条路径不经过页面
 * UiState，清洗管不到：备份恢复 / 服务器切换留下的非法 `sampler_name`/`scheduler`
 * 会原样进 ComfyUI 的 validate_inputs() → 400。修复即在私有版内对这两个字段清洗，
 * 本测试钉住这段接线不被悄悄删掉：
 *
 * 1. 私有版块内必须有 `sampler_name` → `SamplerOptions.sanitizeSampler` 的映射分支；
 * 2. 必须有 `scheduler` → `SamplerOptions.sanitizeScheduler` 的映射分支；
 * 3. 写回节点的必须是**清洗后的值**（`inputs.put(inputName, safeValue)`）—— 有人把
 *    put 改回原 `value` 时清洗等于白做，这里红。
 *
 * 反证：删掉两个 when 分支（旁路重新敞开）或把 put 改回原值（清洗结果被丢弃），
 * 对应断言立即变红。
 */
class NodeAttributeEditsSamplerSanitizeWiringTest {

    private val managerPath = "src/main/java/sh/hnet/comfychair/WorkflowManager.kt"

    /** 单测工作目录是模块根（app/），与 ViewerDismissWiringTest 同口径。 */
    private fun source(path: String): String {
        val file = File(path)
        assertTrue("源文件不存在：${file.absolutePath} —— 单测工作目录应为 app/ 模块根", file.exists())
        return file.readText()
    }

    /** 行首空格数。 */
    private fun leadingSpaces(line: String): Int = line.takeWhile { it == ' ' }.count()

    /** 逐行大括号净增量（跳过注释/字符串/字符字面量）。 */
    private fun braceDeltas(lines: List<String>): IntArray {
        val deltas = IntArray(lines.size)
        var inBlockComment = false
        var inString = false
        var inChar = false
        for ((row, raw) in lines.withIndex()) {
            var i = 0
            var delta = 0
            while (i < raw.length) {
                val c = raw[i]
                val n = if (i + 1 < raw.length) raw[i + 1] else ' '
                when {
                    inBlockComment -> if (c == '*' && n == '/') {
                        inBlockComment = false
                        i++
                    }
                    inString -> when {
                        c == '\\' -> i++
                        c == '"' -> inString = false
                    }
                    inChar -> when {
                        c == '\\' -> i++
                        c == '\'' -> inChar = false
                    }
                    c == '/' && n == '/' -> i = raw.length
                    c == '/' && n == '*' -> {
                        inBlockComment = true
                        i++
                    }
                    c == '"' -> inString = true
                    c == '\'' -> inChar = true
                    c == '{' -> delta++
                    c == '}' -> delta--
                }
                i++
            }
            deltas[row] = delta
        }
        return deltas
    }

    /** 从 startIdx 起按大括号配平取块区间，收尾缩进与签名行一致。 */
    private fun blockRangeAt(lines: List<String>, startIdx: Int, what: String): IntRange {
        val deltas = braceDeltas(lines)
        val want = leadingSpaces(lines[startIdx])
        var depth = 0
        var started = false
        for (i in startIdx until lines.size) {
            depth += deltas[i]
            if (depth > 0) started = true
            if (started && depth == 0) {
                if (leadingSpaces(lines[i]) != want) {
                    throw AssertionError(
                        "$what：按大括号配平得到的收尾与签名行缩进不一致 —— 块边界不可信"
                    )
                }
                return startIdx..i
            }
        }
        throw AssertionError("$what：从第 ${startIdx + 1} 行起大括号配不平")
    }

    @Test
    fun `私有版节点编辑入口必须对 sampler_name 与 scheduler 清洗`() {
        val body = source(managerPath)
        val lines = body.lines()
        // 带 private 前缀锁定私有版（处理 workflowId 的那个）：公共版 applyNodeAttributeEdits(json, edits)
        // 目前无调用者，签名不同不会混配
        val startIdx = lines.indexOfFirst {
            it.trimStart().startsWith("private fun applyNodeAttributeEdits(processedJson")
        }
        assertTrue("源码里找不到私有版 applyNodeAttributeEdits(processedJson, workflowId)", startIdx >= 0)
        val range = blockRangeAt(lines, startIdx, "私有版 applyNodeAttributeEdits")
        val block = lines.subList(range.first, range.last + 1).joinToString("\n")

        // 反证：删掉 sampler_name 清洗分支 → 正则不匹配 → 红
        assertTrue(
            "节点编辑里 sampler_name 的字符串值必须过 SamplerOptions.sanitizeSampler" +
                "（否则节点属性编辑是提交前清洗的旁路，非法值会 400）",
            Regex(
                """inputName\s*==\s*"sampler_name"\s*&&\s*value\s+is\s+String[\s\S]*?""" +
                    """SamplerOptions\.sanitizeSampler"""
            ).containsMatchIn(block)
        )
        // 反证：删掉 scheduler 清洗分支 → 红
        assertTrue(
            "节点编辑里 scheduler 的字符串值必须过 SamplerOptions.sanitizeScheduler",
            Regex(
                """inputName\s*==\s*"scheduler"\s*&&\s*value\s+is\s+String[\s\S]*?""" +
                    """SamplerOptions\.sanitizeScheduler"""
            ).containsMatchIn(block)
        )

        // 清单口径：清洗用的是 ConnectionManager.modelCache 的服务器清单（与页面口径一致）
        assertTrue(
            "清洗清单必须取自 ConnectionManager.modelCache（服务器 /object_info，与页面口径一致）",
            block.contains("ConnectionManager.modelCache.value.samplers") &&
                block.contains("ConnectionManager.modelCache.value.schedulers")
        )

        // 反证：put 改回原 value（清洗结果被丢弃）→ 正则不匹配 → 红
        assertTrue(
            "写回节点的必须是清洗后的值（inputs.put(inputName, safeValue)），不是原始编辑值",
            block.contains("inputs.put(inputName, safeValue)")
        )
        val legacyPut = Regex("""inputs\.put\(inputName,\s*value\)""")
        assertTrue(
            "发现按原始编辑值直接写回的 put（inputs.put(inputName, value)）—— 清洗被旁路",
            !legacyPut.containsMatchIn(block)
        )
    }
}
