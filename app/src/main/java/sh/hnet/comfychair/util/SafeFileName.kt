package sh.hnet.comfychair.util

/**
 * 把服务器返回的文件名净化成**只能落在指定目录内**的本地文件名（P2-4）。
 *
 * 背景：3D 产物（`/history` 的 `3d` 字段）与图片/视频一样由服务器提供 filename，
 * 而修复前直接把 `result.filename` 拼进 `File(filesDir/model3d, filename)`：
 * - `../../shared_prefs/xxx` 可以跳出缓存目录（路径穿越）；
 * - `a/b.glb` 会在缓存目录里建子目录，后续按文件名读不到；
 * - Windows 风格的反斜杠在部分文件系统/工具链上同样会被解释成分隔符。
 *
 * 规则（白名单，fail-closed）：
 * 1. 先取最后一段（`/` 与 `\` 都当分隔符），`File(name).name` 的语义在这里显式实现，
 *    避免依赖运行平台的路径规则；
 * 2. 只保留 Unicode 字母/数字与 `.` `_` `-` `(` `)` 空格，其余替换成 `_`；
 * 3. `.`、`..`、以 `.` 开头的隐藏名、净化后为空 → 返回 null（调用方报下载失败）；
 * 4. 过长时截断主干、保留扩展名（Android 单个文件名上限 255 字节）。
 *
 * 纯 JVM（只用 java.io.File 做最终校验），可在单测里直接覆盖。
 */
internal object SafeFileName {

    /** 允许出现在文件名里的字符；`\p{L}` / `\p{N}` 含中文、日文等 Unicode 字母与数字。 */
    private val DISALLOWED = Regex("[^\\p{L}\\p{N}._()\\- ]")

    /** 本地文件名主干的最大字符数（给扩展名留余量；中文按字符计，UTF-8 下仍远小于 255 字节）。 */
    private const val MAX_BASE_LENGTH = 120

    /**
     * @return 安全的文件名；无法得到安全名字时返回 null
     */
    fun sanitize(raw: String, directory: java.io.File? = null): String? {
        // 1. 取最后一段（同时处理 / 与 \，与平台无关）
        val base = raw.substringAfterLast('/').substringAfterLast('\\').trim()
        if (base.isEmpty() || base == "." || base == "..") return null
        // 隐藏文件/纯点开头一律拒绝：对 3D 产物没有合法用途
        if (base.startsWith(".")) return null

        // 2. 白名单替换（控制字符、冒号、引号、路径分隔符等一律变 _）
        val cleaned = base.replace(DISALLOWED, "_").trim()
        if (cleaned.isEmpty() || cleaned == "." || cleaned == "..") return null

        // 3. 过长截断主干、保留扩展名
        val name = if (cleaned.length <= MAX_BASE_LENGTH) {
            cleaned
        } else {
            val dot = cleaned.lastIndexOf('.')
            val ext = if (dot in 1 until cleaned.length - 1) cleaned.substring(dot) else ""
            val stem = if (ext.isEmpty()) cleaned else cleaned.substring(0, dot)
            stem.take((MAX_BASE_LENGTH - ext.length).coerceAtLeast(1)) + ext
        }

        // 4. 最终校验：净化后的名字里不能还有分隔符（防御性，正常已被白名单替换）
        if (name.contains('/') || name.contains('\\') || name == "." || name == "..") return null

        if (directory != null) {
            // 防御纵深：即使白名单将来被放宽，也确保目标规范路径仍在该目录内
            return try {
                val dirPath = directory.canonicalFile
                val target = java.io.File(dirPath, name).canonicalFile
                val dirPrefix = dirPath.path + java.io.File.separator
                if (target.path.startsWith(dirPrefix)) name else null
            } catch (_: Exception) {
                null
            }
        }
        return name
    }
}