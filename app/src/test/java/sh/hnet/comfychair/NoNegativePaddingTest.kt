package sh.hnet.comfychair

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 「Compose 的 padding 系列不接受负值」的源码级钉子。
 *
 * ## 为什么只能静态测
 *
 * `Modifier.padding(负值)` 的校验发生在 Compose 运行时：`PaddingElement.<init>` 里
 * `require` 抛 `IllegalArgumentException: Padding must be non-negative`。JVM 单测
 * 不跑 Compose 布局/组合，普通单测碰不到这条 require，所以这里跟
 * `ViewerDismissWiringTest` 一样读源码字符串做断言。
 *
 * ## 钉住的历史缺陷（v4.17.0 code138 真机崩溃）
 *
 * 图库回收站角标曾写：
 * ```
 * .padding(top = (-4).dp, end = (-6).dp)
 * ```
 * 崩溃栈：
 * ```
 * java.lang.IllegalArgumentException: Padding must be non-negative
 *   at androidx.compose.foundation.layout.PaddingElement.<init>(Padding.kt:483)
 *   at ...GalleryScreenKt.GalleryScreen$lambda$...(GalleryScreen.kt:675)
 * ```
 * 该段只在 `trashEntries.isNotEmpty()` 时渲染 —— 用户删过图后一进图库就崩。
 * 负值的本意是「把角标往右上角外推」，正确做法是 `Modifier.offset`（offset 允许负值，
 * 且只做绘制平移、不参与布局测量）。
 *
 * ## 检查范围
 *
 * - `padding(` / `absolutePadding(` / `PaddingValues(` 三个同一 API 家族的调用；
 * - 只认**负数字面量**（`(-4).dp`、`= -4.dp`、`, -.dp` 等写法），不追踪变量；
 * - 整行注释（以 `//`、`*` 或块注释起始符开头的行）跳过 —— 文档/注释里常引用旧写法作示例，
 *   若不排除会导致「注释写着 -4.dp 就误报」；
 * - 支持跨行调用（按括号配对取实参，而不是只看同一行）。
 *
 * 本测试**不是恒真断言**：先于修复实现、对仍含负 padding 的源码运行过一次，确认变红
 * （见提交说明/交付记录）；且内置 `检测器自检` 用例，若正则在后续改动中失效会直接报错。
 */
class NoNegativePaddingTest {

    /** 与 Compose 布局 API 同族的三个入口。 */
    private val apiNames = listOf("padding", "absolutePadding", "PaddingValues")

    /**
     * 负数字面量（单位 dp）的三种写法：
     * 1. `(-4).dp` / `(-4.5).dp`  —— 括号包负数再取 .dp；
     * 2. `= -4.dp` / `, -4.dp` / 实参开头 `-4.dp`（如 `padding(-4.dp)`）—— 参数值直接是负数；
     * 3. `-.dp`（题目点名的退化写法）。
     */
    private val negativeDpPatterns = listOf(
        Regex("""\(\s*-\s*\d+(?:\.\d+)?\s*\)\s*\.dp"""),
        Regex("""(?:^|[=,(])\s*-\s*\d+(?:\.\d+)?\s*\.dp"""),
        Regex("""(?:^|[=,(])\s*-\s*\.\s*dp""")
    )

    private data class Hit(val line: Int, val api: String, val args: String)

    /**
     * 定位 `src/main/java`。单测工作目录是模块根（app/），`ViewerDismissWiringTest`
     * 已用 `src/main/java/...` 证明；任务书要求的 `app/src/main/java` 也一并支持，
     * 这样无论从模块根还是从仓库根执行都不会扫空目录。
     */
    private fun locateSourceRoot(): File {
        val candidates = listOf(
            File("app/src/main/java"),
            File("src/main/java"),
            File("../app/src/main/java")
        )
        val root = candidates.firstOrNull { it.isDirectory }
        assertTrue(
            "找不到主源码目录，尝试过：" +
                candidates.joinToString(" | ") { it.absolutePath } +
                "（单测工作目录应为 app/ 模块根）",
            root != null
        )
        return root!!
    }

    /** 按括号配对返回与 `openIdx` 处 `(` 对应的 `)` 下标；找不到返回 null。 */
    private fun matchingParen(text: String, openIdx: Int): Int? {
        var depth = 0
        val limit = minOf(text.length, openIdx + 4000)
        for (i in openIdx until limit) {
            when (text[i]) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) return i
                }
            }
        }
        return null
    }

    /**
     * 扫描一段源码，返回所有「padding 家族调用里出现负数字面量」的位置。
     * 行号按原文件计（整行注释被替换为空行，行数不变）。
     */
    private fun scan(text: String): List<Hit> {
        val code = text.lines().joinToString("\n") { line ->
            val trimmed = line.trimStart()
            if (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")) {
                ""
            } else {
                line
            }
        }
        val hits = mutableListOf<Hit>()
        for (api in apiNames) {
            val regex = Regex("(?<![A-Za-z0-9_])" + Regex.escape(api) + "\\(")
            for (match in regex.findAll(code)) {
                val openIdx = match.range.last
                val closeIdx = matchingParen(code, openIdx) ?: continue
                val args = code.substring(openIdx + 1, closeIdx)
                if (negativeDpPatterns.none { it.containsMatchIn(args) }) continue
                val line = code.substring(0, match.range.first).count { it == '\n' } + 1
                hits += Hit(line, api, args.trim().replace(Regex("\\s+"), " "))
            }
        }
        return hits.sortedBy { it.line }
    }

    // ============================================================
    // 一、检测器自检（防止主断言恒真）
    // ============================================================

    @Test
    fun `检测器自检 四种负值写法必须全部命中`() {
        val sample = """
            val a = Modifier.padding(top = (-4).dp, end = (-6).dp)
            val b = Modifier.padding(-4.dp)
            val c = Modifier.absolutePadding(left = -2.dp)
            val d = PaddingValues(bottom = -8.dp)
            val e = Modifier.padding(top = 4.dp)
        """.trimIndent()
        val hits = scan(sample)
        assertEquals(
            "检测器自检失败：应命中 4 处负值（(-4).dp / -4.dp / absolutePadding -2.dp / " +
                "PaddingValues -8.dp），实际命中 ${hits.size} 处：$hits —— " +
                "若为 0 说明主断言会恒真，必须先修正检测逻辑",
            4,
            hits.size
        )
        assertEquals(
            "自检行号错位：第一处应报在第 1 行，实际：$hits",
            1,
            hits.first().line
        )
    }

    @Test
    fun `检测器自检 注释里的旧写法不算命中`() {
        val sample = """
            // 旧写法 .padding(top = (-4).dp, end = (-6).dp) 会造成运行时崩溃
             * .padding(-4.dp)
            val ok = Modifier.padding(horizontal = 4.dp)
        """.trimIndent()
        val hits = scan(sample)
        assertEquals("注释行不得计入命中，实际：$hits", 0, hits.size)
    }

    // ============================================================
    // 二、主源码全量扫描
    // ============================================================

    @Test
    fun `主源码不得出现负值 padding`() {
        val root = locateSourceRoot()
        val files = root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .sortedBy { it.path }
            .toList()

        // 防空扫：317 个主源码文件（v4.17.0 实测），给一个宽松下限即可
        assertTrue(
            "扫描结果异常：${root.absolutePath} 下只找到 ${files.size} 个 .kt 文件，疑似根目录定位错误",
            files.size >= 50
        )
        assertTrue(
            "扫描结果异常：没有扫到 GalleryScreen.kt，说明根目录定位错误（${root.absolutePath}）",
            files.any { it.name == "GalleryScreen.kt" }
        )

        val violations = mutableListOf<String>()
        for (file in files) {
            for (hit in scan(file.readText())) {
                violations += "${file.relativeTo(root).path}:${hit.line} —— " +
                    "${hit.api}(...) 实参含负值：${hit.args}"
            }
        }

        assertTrue(
            buildString {
                appendLine("Compose 的 padding 系列不接受负值：运行时 require 会抛")
                appendLine("IllegalArgumentException: Padding must be non-negative（Padding.kt:483）。")
                appendLine("角标外推请改用 Modifier.offset(x = ..., y = ...)（offset 允许负值）。")
                appendLine("命中 ${violations.size} 处：")
                violations.forEach { appendLine("  $it") }
            },
            violations.isEmpty()
        )
    }
}