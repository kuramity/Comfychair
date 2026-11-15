package sh.hnet.comfychair.update

/**
 * 更新日志（assets/CHANGELOG.md）里一个版本的条目。
 */
data class ChangelogEntry(
    val version: String,      // "v4.14.0"
    val date: String,         // "2026-11-15"
    val versionCode: Int?,    // 122（解析不到为 null）
    val items: List<String>,  // 条目列表（去掉 "- " 前缀）
)

/**
 * 解析内置 CHANGELOG.md。
 *
 * 版本行格式固定为 `## <versionName> | <YYYY-MM-DD> | code<versionCode>`；
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
        val items = mutableListOf<String>()

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
                // 条目行：`- ` 或 `* ` 开头，去掉前缀后 trim；空白条目丢弃
                val content = when {
                    line.startsWith("- ") -> line.removePrefix("- ")
                    line.startsWith("* ") -> line.removePrefix("* ")
                    else -> null
                }?.trim()
                if (!content.isNullOrEmpty()) current.items += content
            }
            // 其余行（一级标题、空行、版本头之前的文字）一律忽略
        }
        current?.let { entries += it.toEntry() }
        return entries
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
