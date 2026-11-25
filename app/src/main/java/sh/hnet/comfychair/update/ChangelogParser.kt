package sh.hnet.comfychair.update

/**
 * 更新日志里的一行条目。
 *
 * @param text 去掉前缀后的正文（可能仍带行内 Markdown 标记，渲染时由
 *             [ChangelogParser.parseInline] 切开）
 * @param level 1 = 一级条目（`- ` / `* `），2 = 一级条目下的二级说明（`· ` / `• `）
 */
data class ChangelogItem(
    val text: String,
    val level: Int,
)

/**
 * 更新日志（assets/CHANGELOG.md）里一个版本的条目。
 */
data class ChangelogEntry(
    val version: String,      // "v4.14.0"
    val date: String,         // "2026-11-15"
    val versionCode: Int?,    // 122（解析不到为 null）
    val items: List<ChangelogItem>,
)

/** 行内 Markdown 片段的样式（只认两种最常见、也最影响可读性的）。 */
enum class InlineStyle { Normal, Bold, Code }

/** 行内 Markdown 的一段。 */
data class InlineSegment(val text: String, val style: InlineStyle)

/**
 * 解析内置 CHANGELOG.md。
 *
 * 版本行格式固定为 `## <versionName> | <YYYY-MM-DD> | code<versionCode>`；
 * 条目行支持两种层级：
 * - 一级：`- ` / `* ` 开头（卡片里画成 `•`）
 * - 二级：`· ` / `• ` 开头（changelog 里是「两个空格 + · + 空格」，卡片里缩进画成 `·`）
 *
 * 任何不符合格式的行都直接跳过，全程不抛异常——日志文件由人手维护，
 * 容错比严格校验更重要。纯 Kotlin，不依赖 Android 类型，可 JVM 单测。
 */
object ChangelogParser {

    /** 单个版本的中间累积态：遇到下一个版本头或文末时结算成 [ChangelogEntry]。 */
    private class Pending(
        val version: String,
        val date: String,
        val versionCode: Int?,
    ) {
        val items = mutableListOf<ChangelogItem>()

        fun toEntry(): ChangelogEntry = ChangelogEntry(version, date, versionCode, items.toList())
    }

    /** 解析 markdown；任何不符合格式的行都跳过，不得抛异常。 */
    fun parse(markdown: String): List<ChangelogEntry> {
        if (markdown.isBlank()) return emptyList()

        val entries = mutableListOf<ChangelogEntry>()
        var current: Pending? = null

        for (rawLine in markdown.lineSequence()) {
            val line = rawLine.trim()
            if (line.startsWith("## ")) {
                // 版本头：结算上一个版本，再开新的。缺 code 段的版本头不丢（code 记 null）
                current?.let { entries += it.toEntry() }
                current = parseHeader(line)
                continue
            }
            if (current != null) {
                parseItem(line)?.let { current!!.items += it }
            }
            // 其余行（一级标题、空行、版本头之前的文字）一律忽略
        }
        current?.let { entries += it.toEntry() }
        return entries
    }

    /**
     * 解析一行条目。
     *
     * 二级判定放在一级之后：`· ` 与 `• ` 只在「不是 - / * 开头」时才认作二级，
     * 避免未来有人把一级条目也写成 `•` 时被降级。
     */
    private fun parseItem(line: String): ChangelogItem? {
        val (prefix, level) = when {
            line.startsWith("- ") -> "- " to 1
            line.startsWith("* ") -> "* " to 1
            line.startsWith("· ") -> "· " to 2
            line.startsWith("• ") -> "• " to 2
            else -> return null
        }
        val content = line.removePrefix(prefix).trim()
        return if (content.isEmpty()) null else ChangelogItem(content, level)
    }

    /**
     * 把一行正文按行内 Markdown 切开：只认 `**粗体**` 和 `` `代码` ``。
     *
     * 规则刻意保守：
     * - 成对标记才算数，**未闭合的 `**` / 反引号按字面输出**（绝不吞掉剩下的文字）；
     * - 空内容（`****`、` `` `）不认，按字面输出；
     * - 其余字符一律原样保留（包括中文引号、箭头、括号）。
     *
     * 真机现状（2026-11-26）：CHANGELOG 正文里大量 `**粗体**` 与 `` `标识符` ``，
     * 旧实现整行当纯文本画，用户看到的就是一堆星号和反引号。
     */
    fun parseInline(text: String): List<InlineSegment> {
        if (text.isEmpty()) return emptyList()
        val out = mutableListOf<InlineSegment>()
        val buffer = StringBuilder()
        var i = 0

        fun flushBuffer() {
            if (buffer.isNotEmpty()) {
                out += InlineSegment(buffer.toString(), InlineStyle.Normal)
                buffer.clear()
            }
        }

        while (i < text.length) {
            when {
                text.startsWith("**", i) -> {
                    val end = text.indexOf("**", i + 2)
                    if (end > i + 2) {
                        flushBuffer()
                        out += InlineSegment(text.substring(i + 2, end), InlineStyle.Bold)
                        i = end + 2
                    } else {
                        buffer.append("**")
                        i += 2
                    }
                }
                text[i] == '`' -> {
                    val end = text.indexOf('`', i + 1)
                    if (end > i + 1) {
                        flushBuffer()
                        out += InlineSegment(text.substring(i + 1, end), InlineStyle.Code)
                        i = end + 1
                    } else {
                        buffer.append('`')
                        i++
                    }
                }
                else -> {
                    buffer.append(text[i])
                    i++
                }
            }
        }
        flushBuffer()
        return out
    }

    /**
     * 解析版本头行。`|` 分段：至少一个 `|` 才认作版本头；
     * 第三段（code）缺失或不是 `code<数字>` 时 versionCode 记 null，版本本身保留。
     */
    private fun parseHeader(line: String): Pending? {
        val segments = line.removePrefix("## ").split('|')
        if (segments.size < 2) return null
        val version = segments[0].trim()
        if (version.isEmpty()) return null
        val date = segments[1].trim()
        val versionCode = segments.getOrNull(2)?.let { parseVersionCode(it) }
        return Pending(version, date, versionCode)
    }

    /** code 段形如 "code122"；也兼容纯数字。解析不出返回 null。 */
    private fun parseVersionCode(segment: String): Int? {
        val text = segment.trim()
        val body = if (text.startsWith("code", ignoreCase = true)) text.drop(4) else text
        return body.trim().toIntOrNull()
    }
}