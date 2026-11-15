package sh.hnet.comfychair.model

import android.content.Context
import android.util.Log
import org.json.JSONObject

/**
 * LoRA 实际效果档案（随 APK 内置一份，离线可查）。
 *
 * 数据来自人工维护的 `lora_effects.json`（`model_intros` 的 `effect` 字段即由它合并而来），
 * 因此本表的角色是**兜底 / 对照源**：
 * - 详情抽屉里条目自身没有 `effect` 时，按文件名到这里兜底取一条；
 * - 浏览页顶部有独立的「LoRA 效果档案」分区，整表可见。
 *
 * 键是小写文件名，值是纯文本段落；数据里有相当一部分条目值为 null/空（人工还没补上），
 * 解析时直接跳过——`all()` 里**只含有值的条目**。
 *
 * 加载范式与 [ModelIntroTable] 一致：懒加载 + 双重检查 + 失败退化为空表（不影响主流程）
 * + 提供测试注入口；[Log] 包一层是因为 `android.util.Log` 在 JVM 单测里是 stub。
 */
object LoraEffectTable {

    private const val TAG = "LoraEffectTable"

    /** Log 包装（测试环境无 Log 实现，忽略即可，见 [ModelIntroTable] 同名实现）。 */
    private fun logW(message: String, e: Exception? = null) {
        try {
            if (e != null) Log.w(TAG, message, e) else Log.w(TAG, message)
        } catch (_: Throwable) {
            // 测试环境无 Log 实现，忽略
        }
    }

    /** 内置版本（随 APK 打包）。 */
    const val ASSET_PATH = "lora_effects.json"

    @Volatile
    private var byFile: Map<String, String> = emptyMap()

    /** 表是否已装好（供界面区分「还没加载」与「确实没数据」）。 */
    val isLoaded: Boolean
        get() = byFile.isNotEmpty()

    /**
     * 懒加载：优先用服务器同步下来的缓存（filesDir），没有才退回 APK 内置的一份。
     *
     * 效果档案只是锦上添花，任何失败都保持空表，绝不让它影响模型选择主流程。
     */
    fun ensureLoaded(context: Context) {
        if (isLoaded) return
        synchronized(this) {
            if (isLoaded) return
            val text = resolveInitialJson(context) ?: run {
                logW("LoRA 效果档案不可用（缓存与内置都读不到），相关分区将不显示")
                return
            }
            install(text)
        }
    }

    /** 缓存文件（filesDir 下），与 [ModelIntroTable.cacheFile] 同一约定。 */
    fun cacheFile(context: Context): java.io.File = java.io.File(context.filesDir, ASSET_PATH)

    private fun readCacheText(context: Context): String? = try {
        val f = cacheFile(context)
        if (f.exists()) f.readText() else null
    } catch (e: Exception) {
        logW("读取 LoRA 效果档案缓存失败", e)
        null
    }

    private fun readAssetText(context: Context): String? = try {
        context.assets.open(ASSET_PATH).use { it.readBytes().decodeToString() }
    } catch (e: Exception) {
        logW("内置 LoRA 效果档案不可用", e)
        null
    }

    /**
     * 「服务器同步下来的缓存」与「APK 内置的一份」二选一，坏缓存不能让档案整体消失。
     */
    private fun resolveInitialJson(context: Context): String? {
        readCacheText(context)?.let { cache ->
            if (canParse(cache)) return cache
            logW("本地缓存的 LoRA 效果档案不可用，回退 APK 内置表")
        }
        return readAssetText(context)?.takeIf { canParse(it) }
    }

    private fun canParse(json: String): Boolean = try {
        parse(json) != null
    } catch (_: Exception) {
        false
    }

    /**
     * 全部有值条目（键 = 小写文件名，值 = 效果文本）。
     *
     * 值为 null/空的条目在解析时就被跳过，这里拿到的都是可直接显示的。
     */
    fun all(): Map<String, String> = byFile

    /**
     * 按文件名查效果文本（大小写不敏感；带路径时取末段文件名再查）。
     *
     * @return 命中返回效果文本；未收录、入参为空、表未加载都返回 null
     */
    fun lookup(fileName: String?): String? {
        if (fileName.isNullOrBlank()) return null
        // 反斜杠先归一成正斜杠（调用方可能拿服务器返回的路径直接来查），再取末段文件名
        val key = fileName.trim().replace('\\', '/').substringAfterLast('/').lowercase()
        if (key.isEmpty()) return null
        return byFile[key]
    }

    /**
     * 有值、但文件名不在 [knownFiles] 里的条目，按文件名排序。
     *
     * 「孤儿」= 效果档案收录了、模型介绍表里却没有的 LoRA（数据侧人工先记了效果，
     * 模型还没进全量溯源）——它们在独立分区里要标出来，点击不能跳详情抽屉。
     * [knownFiles] 接受任意大小写，比较前统一转小写。
     */
    fun orphans(knownFiles: Set<String>): List<Pair<String, String>> {
        val known = knownFiles.map { it.lowercase() }.toSet()
        return byFile.entries
            .filter { it.key !in known }
            .map { it.key to it.value }
            .sortedBy { it.first }
    }

    /** 清空（测试时用）。 */
    fun clear() {
        synchronized(this) {
            byFile = emptyMap()
        }
    }

    /**
     * 载入一份表（测试用）。
     *
     * 解析失败时退化为空表而不抛异常：效果数据坏了不该拖垮模型选择。
     */
    internal fun setTableJsonForTest(json: String) = install(json)

    internal fun clearForTest() = clear()

    private fun install(json: String): Boolean {
        val parsed = try {
            parse(json)
        } catch (e: Exception) {
            logW("LoRA 效果档案解析失败", e)
            null
        }
        if (parsed == null) {
            byFile = emptyMap()
            return false
        }
        byFile = parsed
        return true
    }

    /**
     * 解析整张表：值必须是**非空字符串**才算数。
     *
     * 不能只用 `optString` 判断——数据里约三分之一条目的值是 JSON `null`，
     * 而 org.json 的 `optString` 会把 null 哨兵转成字面量 `"null"` 字符串返回，
     * 必须先过 `isNull` 再取，最后再用 `isNotBlank` 兜掉空串。
     */
    private fun parse(json: String): Map<String, String>? {
        val root = JSONObject(json)
        val out = HashMap<String, String>(root.length() * 2)
        val keys = root.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            if (root.isNull(key)) continue
            val value = root.optString(key, "")
            if (value.isNotBlank()) out[key.lowercase()] = value
        }
        // 一条有效数据都没有视为"这份数据不能用"（与 ModelIntroTable 的空表语义一致）
        return out.takeIf { it.isNotEmpty() }
    }
}
