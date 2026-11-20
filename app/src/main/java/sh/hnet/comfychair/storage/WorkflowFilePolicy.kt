package sh.hnet.comfychair.storage

import java.io.File

/**
 * 用户工作流文件名的安全策略（P1-18）。
 *
 * 背景：备份恢复时 `filename` 直接取自备份 JSON（**外部输入**），原实现是
 * `File(dir, filename).writeText(...)` 与 `File(dir, filename).readText()`，
 * 没有任何校验。一份被构造过的备份里写
 * `"filename": "../../../shared_prefs/AppSettings.xml"` 就能改写/读取应用私有文件
 * （`user_workflows` 目录在 `filesDir` 下一层，`../` 即可逃出去）。
 *
 * 两道闸：
 * 1. [isValidFilename]：只接受**单层文件名**——不含路径分隔符（含 Windows 反斜杠）、
 *    不含 `..`、不含控制字符、不含 Windows 盘符/备用流冒号、不以 `.` 开头（隐藏文件）；
 * 2. [resolveInDir]：写/读之前再按 `canonicalPath` 复核真实落点必须就是目标目录的直接子项，
 *    挡住符号链接/junction 造成的逃逸。
 *
 * 纯 Kotlin（只用 `java.io.File`），可直接 JVM 单测。
 */
object WorkflowFilePolicy {

    /** 文件名长度上限：与工作流名上限同量级，避免超长名打爆文件系统 */
    private const val MAX_FILENAME_LENGTH = 200

    /** 控制字符（含 NUL、换行、制表）——这些在文件名里一律非法 */
    private val CONTROL_CHARS = Regex("[\\u0000-\\u001f\\u007f]")

    /** 路径分隔符：正斜杠与 Windows 反斜杠都要拒（备份可能跨平台迁移） */
    private val PATH_SEPARATORS = charArrayOf('/', '\\')

    /**
     * 文件名是否允许落盘/读取。
     *
     * @return true 表示这是一个"安全的单层文件名"
     */
    fun isValidFilename(filename: String?): Boolean {
        if (filename == null) return false
        val name = filename.trim()
        if (name.isEmpty() || name.length > MAX_FILENAME_LENGTH) return false
        // ".."（目录穿越）与单独的 "." 都拒绝
        if (name == "." || name.contains("..")) return false
        if (name.any { it in PATH_SEPARATORS }) return false
        // Windows 盘符（C:）与 NTFS 备用数据流（a.txt:evil）
        if (name.contains(':')) return false
        if (CONTROL_CHARS.containsMatchIn(name)) return false
        // 隐藏文件：正常工作流名由 sanitizeFilenameSegment 产出，不可能以点开头
        if (name.startsWith(".")) return false
        return true
    }

    /**
     * 把文件名解析成 [dir] 内的安全 [File]。
     *
     * @return 校验通过时返回 `dir/<filename>` 的规范路径；任何一项校验不通过（含
     *   规范化后不在 [dir] 内）返回 null，调用方应跳过该条目。
     */
    fun resolveInDir(dir: File, filename: String?): File? {
        if (!isValidFilename(filename)) return null
        val name = filename!!.trim()
        return try {
            val root = dir.canonicalFile
            val rootPath = root.canonicalPath
            val target = File(root, name).canonicalFile
            // 必须恰好是 root 的直接子项：父目录规范路径与 root 完全一致
            if (target.parentFile?.canonicalPath == rootPath) target else null
        } catch (e: Exception) {
            // IO/权限异常一律视为不安全
            null
        }
    }
}
