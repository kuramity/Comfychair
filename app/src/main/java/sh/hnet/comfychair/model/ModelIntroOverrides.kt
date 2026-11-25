package sh.hnet.comfychair.model

import android.content.Context
import android.util.Log
import org.json.JSONObject
import sh.hnet.comfychair.util.AtomicFileWriter
import java.io.File

/**
 * 用户覆盖层里的一条记录（`model_intro_overrides.json` 的一个值）。
 *
 * 两种用法互不冲突：
 * - [hidden] = true：本地「卸载」——条目从视图里消失，但内置库/缓存里那份**一个字节都不删**，
 *   恢复（[ModelIntroTable.restore]）只是把这个标记改回 false；
 * - [entry] != null：一条完整条目（**旧版「导入介绍」写入的，该功能已下线**），读视图时
 *   **整条替换**同名条目；内置库里没有这个文件名时就是「新增」。App 升级后磁盘上仍可能
 *   留着这些记录，所以读取与合并链路必须继续支持，只是不再有写入口。
 *
 * 两个字段同时存在时 **hidden 优先**：卸载过的条目不会因为一次内容更新悄悄回到列表里
 * （要显示必须先恢复）。
 *
 * [entry] 里的 `customFields`（用户自定义字段）随整条一起落盘，读写都走
 * [ModelIntroTable.introToJson] / [ModelIntroTable.introFromJson] 这一套映射——
 * **没有这个键的老记录（老用户磁盘上的 `model_intro_overrides.json`）照常解析**，
 * 读回来就是空列表，一条都不新增。
 */
data class OverrideEntry(
    val hidden: Boolean = false,
    val entry: ModelIntro? = null,
)

/**
 * 介绍库的**用户覆盖层**：内置 `assets/model_intros.json` 与服务器热更新落盘的缓存都只读，
 * 用户能做的一切（卸载 / 恢复）只写这一个文件 `filesDir/model_intro_overrides.json`
 * （旧版的导入内容也留在同一份文件里，见 [OverrideEntry.entry]）。
 *
 * **为什么合并发生在读取时**：内置库随 APK 更新、服务器热更新（[ModelIntroSync]）都会整份替换
 * 磁盘上的数据文件——如果卸载直接改数据文件，下一次更新就全丢了。这里把覆盖层单独存一份，
 * [ModelIntroTable] 每次装载数据后（内置 / 缓存 / 热更新都算）都重新 [applyOverrides] 一遍，
 * 所以数据怎么更新覆盖层都还在。
 *
 * 文件形态（键统一归一成小写文件名）：
 * ```
 * {
 *   "awpainting_v1.2.safetensors": {"hidden": true},
 *   "my_custom_model.safetensors": {
 *     "entry": { "file": "my_custom_model.safetensors",
 *                "customFields": [{"label":"基座","value":"Qwen-Image-2.1"}] }
 *   }
 * }
 * ```
 *
 * 写入沿用项目统一的原子写（`.tmp` → flush + fsync → 原子改名）并**写后读回校验**：
 * 写坏等于把用户的卸载记录弄丢（条目重新冒出来），比不写更糟；校验失败回滚到写前内容。
 *
 * 这个对象只在 [load] / [save] 两处碰磁盘，判定逻辑（[parse] / [serialize] / [applyOverrides]）
 * 全是纯函数，JVM 单测可直接钉住（不依赖 Context）。
 */
object ModelIntroOverrides {

    private const val TAG = "ModelIntroOverrides"

    /** 覆盖层文件名（位于 filesDir）。 */
    const val FILE_NAME = "model_intro_overrides.json"

    /** Log 包装：`android.util.Log` 在 JVM 单测里是 stub（调用即抛），不能让它成为故障源。 */
    private fun logW(message: String, e: Exception? = null) {
        try {
            if (e != null) Log.w(TAG, message, e) else Log.w(TAG, message)
        } catch (_: Throwable) {
            // 测试环境无 Log 实现，忽略
        }
    }

    /** 覆盖层文件（App 私有目录下）。 */
    fun fileIn(filesDir: File): File = File(filesDir, FILE_NAME)

    fun fileIn(context: Context): File = fileIn(context.filesDir)

    /**
     * 覆盖层的键归一：反斜杠转正斜杠 → 取末段文件名 → 小写 → 去首尾空白。
     *
     * 与 [ModelIntroTable.lookup] 同一口径（服务器给的模型串可能带子目录、分隔符不统一），
     * 空串返回空串（调用方据此丢弃这条记录）。
     */
    internal fun normalizeKey(file: String): String =
        file.trim().replace('\\', '/').substringAfterLast('/').trim().lowercase()

    /**
     * 读取覆盖层。
     *
     * **任何读取/解析失败都退化为空表**（记日志）：覆盖层坏了只应让「卸载 / 恢复」失效，
     * 绝不能连带把内置介绍库也弄没——用户至少还能看到随 APK 内置的那份。
     * 原文件保留不删，留着排查。
     */
    fun load(file: File): Map<String, OverrideEntry> {
        if (!file.exists()) return emptyMap()
        val text = try {
            file.readText()
        } catch (e: Exception) {
            logW("覆盖层文件读取失败，按空覆盖层处理（内置库照常可用）: ${file.name}", e)
            return emptyMap()
        }
        return parse(text)
    }

    /**
     * 解析覆盖层 JSON（纯函数）。
     *
     * 容忍性：
     * - 顶层不是对象 / 坏 JSON → 空表；
     * - 单个键的值不是对象 → 跳过该键；
     * - `hidden` 缺失/类型漂移（字符串 `"true"`、数字 `1`）按 [ModelIntroTable] 的宽容布尔读；
     * - `hidden=false` 且 `entry` 缺失的空壳**不占位**（等价于没写过）；
     * - `entry` 里没有 `customFields` 键的老记录 → 该字段解析为空列表（老磁盘数据原样读得回）。
     */
    internal fun parse(json: String?): Map<String, OverrideEntry> {
        if (json.isNullOrBlank()) return emptyMap()
        val root = try {
            JSONObject(json)
        } catch (e: Exception) {
            logW("覆盖层 JSON 解析失败，按空覆盖层处理（内置库照常可用）", e)
            return emptyMap()
        }
        val out = LinkedHashMap<String, OverrideEntry>()
        val keys = root.keys()
        while (keys.hasNext()) {
            val rawKey = keys.next()
            val key = normalizeKey(rawKey)
            if (key.isEmpty()) continue
            val obj = root.optJSONObject(rawKey) ?: continue
            val hidden = obj.lenientBoolean("hidden")
            val entry = obj.optJSONObject("entry")
                ?.let { ModelIntroTable.introFromJson(it, fallbackFile = rawKey) }
            if (!hidden && entry == null) continue
            out[key] = OverrideEntry(hidden = hidden, entry = entry)
        }
        return out
    }

    /**
     * 序列化覆盖层（纯函数，[parse] 的逆操作）。
     *
     * 只写有意义的字段：`hidden` 只在 true 时写，`entry` 只在非空时写；
     * 两者都空的记录整条不写（读回来也是空壳）。
     */
    internal fun serialize(overrides: Map<String, OverrideEntry>): String {
        val root = JSONObject()
        for ((rawKey, value) in overrides) {
            val key = normalizeKey(rawKey)
            if (key.isEmpty()) continue
            val obj = JSONObject()
            if (value.hidden) obj.put("hidden", true)
            value.entry?.let { obj.put("entry", ModelIntroTable.introToJson(it)) }
            if (obj.length() == 0) continue
            root.put(key, obj)
        }
        return root.toString()
    }

    /**
     * 原样保存覆盖层（[ModelIntroTable] 的写入口都汇到这里）。
     *
     * @return true = 已原子落盘且读回与写入内容一致；false = 写失败（已回滚到写前状态），
     *         调用方**不得**更新内存视图（否则磁盘与界面会不一致，重启后卸载的条目复活）。
     */
    fun save(file: File, overrides: Map<String, OverrideEntry>): Boolean {
        val json = serialize(overrides)
        val previous: String? = try {
            if (file.exists()) file.readText() else null
        } catch (e: Exception) {
            logW("覆盖层写前备份失败（将无法回滚）: ${file.name}", e)
            null
        }
        return try {
            file.parentFile?.mkdirs()
            writeTextAtomically(file, json)
            val readBack = file.readText()
            if (readBack == json) {
                true
            } else {
                logW("覆盖层写后读回与写入不一致，回滚: ${file.name}")
                rollback(file, previous)
                false
            }
        } catch (e: Exception) {
            logW("覆盖层保存失败（已尝试回滚）: ${file.name}", e)
            rollback(file, previous)
            false
        }
    }

    /** 写失败后的回滚：有旧内容就写回，原本没文件就删掉半成品。 */
    private fun rollback(file: File, previous: String?) {
        try {
            if (previous != null) {
                writeTextAtomically(file, previous)
            } else {
                file.delete()
            }
        } catch (e: Exception) {
            logW("覆盖层回滚失败: ${file.name}", e)
        }
    }

    /**
     * 原子写文本：`.tmp` → flush + fsync → 原子改名覆盖目标。
     *
     * 实现统一到 [sh.hnet.comfychair.util.AtomicFileWriter]（与图库索引 / object_info 缓存
     * 同一套兜底顺序）；这里保留 internal 入口，写路径与单测都走它。
     */
    internal fun writeTextAtomically(file: File, content: String) {
        AtomicFileWriter.writeText(file, content)
    }

    /** [applyOverrides] 的单条版本：命中覆盖层时返回替换后的条目，隐藏返回 null。 */
    internal fun applyTo(intro: ModelIntro, override: OverrideEntry?): ModelIntro? = when {
        override == null -> intro
        override.hidden -> null
        else -> override.entry ?: intro
    }

    /**
     * 把覆盖层叠加到内置库上（纯函数，**合并发生在读取时**）。
     *
     * 规则：
     * - `hidden = true` → 剔除该条目；
     * - `entry != null`（且未隐藏）→ 用覆盖层里的完整条目**整条替换**；
     * - 覆盖层有、内置库没有、且未隐藏 → **追加**（旧版导入新增的条目）；
     * - 其余原样保留，**内置库的顺序不变**（新增条目按键名字典序追加在末尾，
     *   保证同一份输入永远得到同一个顺序）。
     *
     * 键匹配统一走 [normalizeKey]（小写文件名，带路径的也认）；内置库里重复的同名条目只留第一条。
     */
    fun applyOverrides(
        base: List<ModelIntro>,
        overrides: Map<String, OverrideEntry>,
    ): List<ModelIntro> {
        if (overrides.isEmpty()) return base
        val normalized = HashMap<String, OverrideEntry>(overrides.size * 2)
        for ((key, value) in overrides) {
            val k = normalizeKey(key)
            if (k.isNotEmpty()) normalized[k] = value
        }
        if (normalized.isEmpty()) return base

        val out = ArrayList<ModelIntro>(base.size)
        val seen = HashSet<String>(base.size * 2)
        for (intro in base) {
            val key = normalizeKey(intro.file)
            if (!seen.add(key)) continue
            val merged = applyTo(intro, normalized[key]) ?: continue
            out += merged
        }
        // 内置库没有的键：未隐藏且有 entry 才追加（旧版导入留下的新增）
        normalized.keys.sorted().forEach { key ->
            if (key in seen) return@forEach
            val value = normalized.getValue(key)
            if (value.hidden) return@forEach
            value.entry?.let { out += it }
        }
        return out
    }
}