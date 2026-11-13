package sh.hnet.comfychair.util

import android.util.Log
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Log severity levels
 */
enum class LogLevel(val label: String) {
    DEBUG("D"),
    INFO("I"),
    WARN("W"),
    ERROR("E")
}

/**
 * A single log entry
 */
data class LogEntry(
    val timestamp: Long,
    val level: LogLevel,
    val tag: String,
    val message: String
) {
    fun format(): String {
        val time = TIME_FORMATTER.format(
            Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault())
        )
        return "[$time] ${level.label}/$tag: $message"
    }

    companion object {
        /**
         * 时间格式化器。
         *
         * DateTimeFormatter 不可变且线程安全，可以全局共享；原先每条日志都新建一个
         * SimpleDateFormat 再丢掉（列表里上千条时就是上千次构造）。
         */
        private val TIME_FORMATTER: DateTimeFormatter =
            DateTimeFormatter.ofPattern("HH:mm:ss.SSS", Locale.US)
    }
}

/**
 * In-memory debug logger singleton.
 * Logs are session-only and cleared when the app closes.
 * Thread-safe implementation using CopyOnWriteArrayList.
 */
object DebugLogger {
    private val entries = CopyOnWriteArrayList<LogEntry>()
    private var enabled = false
    private const val MAX_ENTRIES = 1000

    /**
     * 条目版本号，每次内容变化 +1。
     *
     * 供日志查看界面判断"要不要重新取一遍"——它按 500ms 轮询，原先每次都全量拷贝
     * （`entries.toList()`）再 clear+addAll 整个列表。达到 1000 条上限后条目数恒定但内容
     * 仍在变，所以不能只看 size，必须有版本号。
     */
    @Volatile
    private var version = 0

    /** 当前版本号（轻量读，无拷贝）。 */
    fun currentVersion(): Int = version

    private const val LOGCAT_ENABLED = false
    private const val LOGCAT_TAG = "ComfyChair"

    /**
     * Enable or disable logging.
     * When transitioning from disabled to enabled, clears previous logs and starts fresh.
     */
    fun setEnabled(enabled: Boolean) {
        val wasEnabled = this.enabled
        this.enabled = enabled
        // Only clear and log when transitioning from disabled to enabled
        if (enabled && !wasEnabled) {
            entries.clear()
            log(LogLevel.INFO, "DebugLogger", "Logging started")
        }
    }

    /**
     * Check if logging is enabled.
     */
    fun isEnabled(): Boolean = enabled

    /**
     * Log a message at the specified level.
     * Logs are stored in memory only.
     */
    fun log(level: LogLevel, tag: String, message: String) {
        if (!enabled) return

        val entry = LogEntry(
            timestamp = System.currentTimeMillis(),
            level = level,
            tag = tag,
            message = message
        )

        entries.add(entry)

        // Trim old entries if over limit
        while (entries.size > MAX_ENTRIES) {
            entries.removeAt(0)
        }

        version++

        // Temporary: Also output to LogCat for real-time debugging
        if (LOGCAT_ENABLED) {
            val logcatMessage = "[$tag] $message"
            when (level) {
                LogLevel.DEBUG -> Log.d(LOGCAT_TAG, logcatMessage)
                LogLevel.INFO -> Log.i(LOGCAT_TAG, logcatMessage)
                LogLevel.WARN -> Log.w(LOGCAT_TAG, logcatMessage)
                LogLevel.ERROR -> Log.e(LOGCAT_TAG, logcatMessage)
            }
        }
    }

    /**
     * Log a DEBUG level message.
     */
    fun d(tag: String, message: String) = log(LogLevel.DEBUG, tag, message)

    /**
     * Log an INFO level message.
     */
    fun i(tag: String, message: String) = log(LogLevel.INFO, tag, message)

    /**
     * Log a WARN level message.
     */
    fun w(tag: String, message: String) = log(LogLevel.WARN, tag, message)

    /**
     * Log an ERROR level message.
     */
    fun e(tag: String, message: String) = log(LogLevel.ERROR, tag, message)

    /**
     * Get a copy of all log entries.
     */
    fun getEntries(): List<LogEntry> = entries.toList()

    /**
     * Clear all log entries.
     */
    fun clear() {
        entries.clear()
        version++
    }

    /**
     * Export all logs to a formatted string suitable for saving to a file.
     */
    fun exportToString(): String {
        val header = buildString {
            appendLine("=== ComfyChair Debug Log ===")
            appendLine("Exported: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
            appendLine("Entries: ${entries.size}")
            appendLine("===========================")
            appendLine()
        }
        return header + entries.joinToString("\n") { it.format() }
    }
}

/**
 * Utility object for obfuscating sensitive data in log messages.
 * Replaces actual values with safe placeholders to protect user privacy.
 */
object Obfuscator {
    /**
     * Obfuscate a prompt text. Shows only character count.
     */
    fun prompt(text: String?): String =
        if (text.isNullOrBlank()) "<empty>"
        else "<prompt:${text.length}chars>"

    /**
     * Obfuscate a hostname/server address.
     * Parameter kept for call-site documentation (shows what's being obfuscated).
     */
    @Suppress("UNUSED_PARAMETER")
    fun hostname(host: String?): String = "<server>"

    /**
     * Obfuscate a filename. Shows only the file extension.
     */
    fun filename(name: String?): String =
        name?.let {
            val ext = it.substringAfterLast('.', "")
            if (ext.isNotEmpty()) "<file:.$ext>" else "<file>"
        } ?: "<file>"

    /**
     * Obfuscate a prompt ID. Shows only the first 8 characters.
     */
    fun promptId(id: String?): String =
        id?.take(8)?.plus("...") ?: "<unknown>"

    /**
     * Obfuscate a model name. Shows only the filename portion.
     */
    fun modelName(name: String?): String =
        name?.substringAfterLast('/')?.substringAfterLast('\\') ?: "<model>"

    /**
     * Obfuscate a server name (user-defined name, not hostname).
     * Shows only first 3 characters to help identify which server.
     */
    fun serverName(name: String?): String =
        if (name.isNullOrBlank()) "<server>" else "<server:${name.take(3)}...>"

    /**
     * Obfuscate a workflow name.
     * Shows only first 8 characters to help identify which workflow.
     */
    fun workflowName(name: String?): String =
        if (name.isNullOrBlank()) "<workflow>" else "<workflow:${name.take(8)}...>"
}
