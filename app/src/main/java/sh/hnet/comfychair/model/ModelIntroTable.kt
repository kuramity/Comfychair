package sh.hnet.comfychair.model

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * 单个模型的介绍条目。
 *
 * 所有面向用户的文本（summary / params / companions）都是**预生成好的中文**——
 * 电脑端的构建脚本负责把 Civitai 数据与工作流实际用法合成成句，App 这边只负责显示，
 * 不做翻译、不做推断。
 */
data class ModelIntro(
    val file: String,
    /** 相对 `models/` 的路径（正斜杠），同名文件消歧用 */
    val rel: String? = null,
    /** 分类（数据侧的中文枚举：`大模型`/`扩散模型`/`LoRA`/…），未知新类型原样显示 */
    val kind: String? = null,
    /** 模型名（Civitai 上的标题） */
    val name: String? = null,
    /** 中文类型，如「大模型（Checkpoint）」 */
    val type: String? = null,
    /** 底模，如「SD 1.5」「Anima」「Illustrious」 */
    val base: String? = null,
    val author: String? = null,
    val version: String? = null,
    /** 预生成的中文摘要（一句话讲清是什么 + 怎么用） */
    val summary: String? = null,
    /**
     * 「实际效果」——这个模型加上之后会产生什么作用（LoRA 尤其需要）。
     *
     * civitai 不提供该字段，它由人工/代理从作者原文里提炼成中文；原文没写效果的条目不填，
     * 界面不显示这一段（不编造）。
     */
    val effect: String? = null,
    /** 推荐推理参数（结构化键值对：「步数」→「28 步」，界面一行一个） */
    val params: Map<String, String> = emptyMap(),
    /**
     * 作者在简介里写的推荐设置（步数 / CFG / 采样器 / 推荐分辨率…）。
     *
     * 由抓取脚本用固定模式从作者原文里抽出来，**抽不到就是空表**（绝不猜）——
     * 与 [params]（取自用户自己工作流的实际值）是两回事，界面上分两块显示。
     */
    val authorParams: Map<String, String> = emptyMap(),
    /** 搭配建议（该配哪个 VAE / 文本编码器） */
    val companions: String? = null,
    /** 作者推荐 tag（触发词） */
    val tags: List<String> = emptyList(),
    /** 在你的哪些工作流里用过（显示的是工作流**中文名**，不是内部文件名） */
    val usedIn: List<String> = emptyList(),
    val url: String? = null,
    /** 作者原文简介（英文/多语言混排，想查细节时用） */
    val introOriginal: String? = null,
    /**
     * 数据来源：`civitai` / `huggingface` / `unknown`。
     *
     * 3D 模型（Pixal3D 全家桶）走的是 HuggingFace，Civitai 上没有；界面按这个字段
     * 显示来源标识，`unknown` 与空值都不显示（宁可少一行，不给用户看"未知"）。
     */
    val source: String? = null,
    /**
     * 本地文件已被移除（2026-09-28 模型库清理删掉了 28 个文件）。
     *
     * 条目**保留**是为了让用户还能看到"它曾经是什么"；构建脚本已把它的
     * 推荐参数 / 搭配建议 / 用过它的流全部剥掉（那是指向一个下不回来的模型的幽灵推荐），
     * 界面据此打「文件已移除」角标，避免用户照着配却怎么都跑不通。
     */
    val missing: Boolean = false,
) {
    /**
     * 这条介绍值不值得给用户一个「查看」入口。
     *
     * 判据刻意**不看 summary**：没查到数据的模型也会有一句模板套话
     * （"「xxx」是扩散模型。（暂无更多介绍信息）"），拿它当判据会让每个模型都挂上一个
     * ⓘ，点开却只有废话。所以只认**实质字段**——名称、底模、作者、推荐参数、搭配建议。
     */
    /** 所在目录（相对 models/，正斜杠），浏览页按它分组。 */
    val folder: String
        get() = rel?.substringBeforeLast('/', "") ?: ""

    /**
     * 分类的展示值：空值/空白返回 null（界面整行不显示），非空返回原值。
     *
     * kind 本身就是中文（`大模型`/`扩散模型`/…），不需要映射表；数据侧以后扩充的新类型
     * 也直接显示原值——宁可让用户看到一个没见过的分类名，也不能把它丢成「未知」占位符。
     */
    fun kindLabel(): String? = kind?.trim()?.takeIf { it.isNotEmpty() }

    val hasContent: Boolean
        get() = !name.isNullOrBlank() || !base.isNullOrBlank() || !author.isNullOrBlank() ||
                params.isNotEmpty() || !companions.isNullOrBlank()
}

/**
 * 模型介绍表（随 APK 内置一份，之后可由服务器上的数据文件覆盖更新）。
 *
 * 查询键的选取是这张表最要紧的设计：**服务器 `/object_info` 返回的模型串只有文件名是稳定的**——
 * 它可能带子目录、分隔符可能是反斜杠也可能是正斜杠、大小写也可能变，所以匹配一律
 * 归一化路径后按**文件名（小写）**查；查不到就老实返回 null，让界面显示「暂无介绍」。
 *
 * 加载方式沿用项目里 [sh.hnet.comfychair.taglibrary.search.HanVariantTable] 的成熟范式：
 * 懒加载 + 双重检查 + 失败退化为空表（不影响主流程）+ 提供测试注入入口。
 */
object ModelIntroTable {

    private const val TAG = "ModelIntroTable"

    /**
     * Log 包装。
     *
     * `android.util.Log` 在 JVM 单测里是 stub（调用即抛 RuntimeException），
     * 而"数据坏了"这条分支恰恰是单测要覆盖的路径——不包一层的话，
     * 一个坏文件会把"优雅退化"变成"直接崩溃"。
     */
    private fun logW(message: String, e: Exception? = null) {
        try {
            if (e != null) Log.w(TAG, message, e) else Log.w(TAG, message)
        } catch (_: Throwable) {
            // 测试环境无 Log 实现，忽略
        }
    }

    /** 内置版本（随 APK 打包）；服务器上的更新版会写到私有目录，优先读后者。 */
    const val ASSET_PATH = "model_intros.json"

    /** 从服务器拉取后落盘的文件名（位于 filesDir）。 */
    const val CACHE_FILE_NAME = "model_intros.json"

    @Volatile
    private var byFile: Map<String, ModelIntro> = emptyMap()

    /** 顶层 `by_rel` 反查索引（rel 小写 → 条目），`lookupByRel` 专用，与 [byFile] 互不掺和。 */
    @Volatile
    private var byRel: Map<String, ModelIntro> = emptyMap()

    @Volatile
    private var tableVersion: Int = 0

    @Volatile
    private var tableGeneratedAt: String? = null

    /** 表是否已装好（供界面区分「还没加载」与「确实没收录」）。 */
    val isLoaded: Boolean
        get() = byFile.isNotEmpty()

    /** 数据文件里的版本号（服务器更新比对用）。 */
    val version: Int
        get() = tableVersion

    /** 数据文件的生成时间（展示用）。 */
    val generatedAt: String?
        get() = tableGeneratedAt

    /**
     * 懒加载：优先用服务器同步下来的那份（filesDir），没有才退回 APK 内置的一份。
     *
     * 失败时保持空表——介绍只是锦上添花，绝不能因为它让模型选择不可用。
     */
    fun ensureLoaded(context: Context) {
        if (isLoaded) return
        synchronized(this) {
            if (isLoaded) return
            val cacheText = readCacheText(context)
            val text = resolveInitialJson(cacheText) { readAssetText(context) }
            if (text == null) {
                logW("模型介绍不可用（缓存与内置都读不到），界面将显示「暂无介绍」")
                return
            }
            install(text, force = true)
        }
    }

    /** 缓存文件（filesDir 下），服务器同步与本地加载共用同一个位置。 */
    fun cacheFile(context: Context): java.io.File = java.io.File(context.filesDir, CACHE_FILE_NAME)

    private fun readCacheText(context: Context): String? = try {
        val f = cacheFile(context)
        if (f.exists()) f.readText() else null
    } catch (e: Exception) {
        logW("读取本地模型介绍缓存失败", e)
        null
    }

    private fun readAssetText(context: Context): String? = try {
        context.assets.open(ASSET_PATH).use { it.readBytes().decodeToString() }
    } catch (e: Exception) {
        logW("内置模型介绍不可用", e)
        null
    }

    /**
     * 「服务器同步下来的缓存」与「APK 内置的一份」二选一。
     *
     * 判断顺序：缓存能装成非空表就用缓存；否则（首次安装没缓存、缓存被写坏、缓存里是
     * 服务器返回的 HTML 错误页）一律退回内置表——**坏缓存不能让介绍整体消失**。
     * 两者都不可用时返回 null，界面显示「暂无介绍」，功能照常可用。
     *
     * @param readAsset 实际读取内置表的方式（传函数而不是字符串，缓存可用时就不白读一轮 assets）
     */
    internal fun resolveInitialJson(cacheText: String?, readAsset: () -> String?): String? {
        if (cacheText != null) {
            if (canParse(cacheText)) return cacheText
            logW("本地缓存的模型介绍不可用，回退 APK 内置表")
        }
        return readAsset()?.takeIf { canParse(it) }
    }

    private fun canParse(json: String): Boolean = try {
        parse(json) != null
    } catch (_: Exception) {
        false
    }

    /**
     * 按服务器返回的模型串查介绍。
     *
     * @param serverPath 形如 `image\anima\xxx.safetensors` 或 `checkpoints/SD1.5/x.safetensors`
     * @return 命中返回条目；未收录、入参为空、表未加载都返回 null
     */
    fun lookup(serverPath: String?): ModelIntro? {
        if (serverPath.isNullOrBlank()) return null
        val normalized = serverPath.replace('\\', '/').trim()
        if (normalized.isEmpty()) return null

        val fileName = normalized.substringAfterLast('/').lowercase()
        if (fileName.isNotEmpty()) {
            byFile[fileName]?.let { return it }
        }
        // 兜底：整条路径直接当键（表里也有 rel 形式的小写路径，用于同名文件消歧）
        byFile[normalized.lowercase()]?.let { return it }
        return null
    }

    /**
     * 按相对路径（`models/` 之后的部分）查介绍——顶层 `by_rel` 反查索引的公开入口。
     *
     * 与 [lookup] 的分工：[lookup] 以**文件名**为准（路径只是同名消歧的兜底），而同名文件
     * 只有数据侧各自出了独立介绍时才能各查到各的；当前数据里 `ae.safetensors` 两个位置
     * （vae/flux、vae/Z-Image）共用一条介绍，两条 rel 会返回同一条目。
     * 正反斜杠都接受、大小写不敏感；调用方多带了 `models/` 前缀也顺手剥掉。
     *
     * @return 命中返回条目；未收录、入参为空、表未加载都返回 null
     */
    fun lookupByRel(relPath: String?): ModelIntro? {
        if (relPath.isNullOrBlank()) return null
        val normalized = relPath.replace('\\', '/').trim().lowercase()
        if (normalized.isEmpty()) return null
        val withoutPrefix = normalized.removePrefix("models/")
        return byRel[withoutPrefix] ?: byRel[normalized]
    }

    /**
     * 全部条目，按「目录 → 文件名」排好序（供「模型介绍」浏览页整页展示）。
     *
     * 内部索引同时键了文件名和相对路径（同一个模型会命中两次），这里按文件名去重。
     */
    fun all(): List<ModelIntro> = byFile.values
        .distinctBy { it.file.lowercase() }
        .sortedWith(compareBy({ it.folder }, { it.file.lowercase() }))

    /** 清空（测试与登出时用）。 */
    fun clear() {
        synchronized(this) {
            byFile = emptyMap()
            byRel = emptyMap()
            tableVersion = 0
            tableGeneratedAt = null
        }
    }

    // ---------------- 解析 ----------------

    /**
     * 用一份新的表**替换**当前表（服务器同步、内置 assets 首次装载都走这里）。
     *
     * 与 [setTableJsonForTest] 的区别：这份数据可能是坏的，坏数据**不动现有表**、只返回 false。
     * 服务器返回的半截 JSON / HTML 错误页 / 空的 models 表都不该把用户已有的介绍清空。
     *
     * @return true 表示已装载非空表；false 表示数据不可用、现有表原样保留
     */
    fun reloadFrom(json: String): Boolean = install(json, force = false)

    /**
     * 载入一份表（测试用）。
     *
     * 解析失败时退化为空表而不抛异常：介绍数据坏了不该拖垮模型选择。
     */
    internal fun setTableJsonForTest(json: String) = install(json, force = true)

    internal fun clearForTest() = clear()

    /**
     * @param force true 表示"这份数据是唯一来源"（缓存/内置/测试）——装不上就清空，
     *              避免留着上一次的旧表造成"数据没更新但看起来更新了"的错觉
     */
    private fun install(json: String, force: Boolean): Boolean {
        val parsed = try {
            parse(json)
        } catch (e: Exception) {
            logW("模型介绍表解析失败", e)
            null
        }
        if (parsed == null) {
            if (force) {
                byFile = emptyMap()
                byRel = emptyMap()
                tableVersion = 0
                tableGeneratedAt = null
            }
            return false
        }
        byFile = parsed.byFile
        byRel = parsed.byRel
        tableVersion = parsed.version
        tableGeneratedAt = parsed.generatedAt
        return true
    }

    /** [parse] 的产物：文件名 / 相对路径两套索引 + 表头信息。 */
    private data class ParsedTable(
        val byFile: Map<String, ModelIntro>,
        val byRel: Map<String, ModelIntro>,
        val version: Int,
        val generatedAt: String?,
    )

    /**
     * 解析整张表。
     *
     * @return models 为空或结构不对时返回 null（调用方据此判断"这份数据不能用"）
     */
    private fun parse(json: String): ParsedTable? {
        val root = JSONObject(json)
        val models = root.optJSONObject("models") ?: return null
        val out = HashMap<String, ModelIntro>(models.length() * 2)
        val keys = models.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val o = models.optJSONObject(key) ?: continue
            val intro = ModelIntro(
                file = o.strOrNull("file") ?: key,
                rel = o.strOrNull("rel"),
                kind = o.strOrNull("kind"),
                name = o.strOrNull("name"),
                type = o.strOrNull("type"),
                base = o.strOrNull("base"),
                author = o.strOrNull("author"),
                version = o.strOrNull("version"),
                summary = o.strOrNull("summary"),
                effect = o.strOrNull("effect"),
                params = o.strMap("params"),
                authorParams = o.strMap("author_params"),
                companions = o.strOrNull("companions"),
                tags = o.strList("tags"),
                usedIn = o.strList("used_in"),
                url = o.strOrNull("url"),
                introOriginal = o.strOrNull("intro_original"),
                source = o.strOrNull("source"),
                // 数据侧 2026-11-15 起改用 removed（missing 是旧键名，兼容读一轮）
                missing = o.lenientBoolean("removed") || o.lenientBoolean("missing"),
            )
            out[key.lowercase()] = intro
            // 相对路径也建一份索引，供同名文件消歧
            intro.rel?.takeIf { it.isNotBlank() }?.let { out[it.lowercase()] = intro }
        }
        if (out.isEmpty()) return null
        // 顶层 by_rel（rel → 小写文件名）是独立的反查索引：同名文件各占一条，
        // lookupByRel 按完整路径精确匹配。指到 models 里没有的键（悬空值）直接跳过
        val relIndex = HashMap<String, ModelIntro>()
        root.optJSONObject("by_rel")?.let { relObj ->
            val relKeys = relObj.keys()
            while (relKeys.hasNext()) {
                val rel = relKeys.next()
                out[relObj.optString(rel, "").lowercase()]?.let { relIndex[rel.lowercase()] = it }
            }
        }
        val version = if (root.has("version")) root.optInt("version", 0) else 0
        val generatedAt = root.strOrNull("generated_at")
        return ParsedTable(out, relIndex, version, generatedAt)
    }

    /**
     * 布尔字段的类型宽容读取。[org.json.JSONObject.optBoolean] 只认 JSON boolean，
     * 遇到字符串 `"true"`、数字 `1` 一律返回默认值 false——数据链路是脚本生成的，
     * 类型漂移不该让条目被静默当成"未删除"。
     *
     * 兼容：布尔 `true`/`false`、字符串（忽略大小写与首尾空白，`"true"`/`"1"` 为真）、
     * 数字 `1`；其余一律 false。不抛异常。
     */
    private fun JSONObject.lenientBoolean(key: String): Boolean {
        if (!has(key) || isNull(key)) return false
        return when (val v = opt(key)) {
            is Boolean -> v
            is Number -> v.toInt() == 1
            is String -> when (v.trim().lowercase()) {
                "true", "1" -> true
                else -> false
            }
            else -> false
        }
    }

    private fun JSONObject.strOrNull(key: String): String? {
        if (!has(key) || isNull(key)) return null
        return optString(key, "").takeIf { it.isNotBlank() }
    }

    private fun JSONObject.strList(key: String): List<String> {
        val arr: JSONArray = optJSONArray(key) ?: return emptyList()
        val out = ArrayList<String>(arr.length())
        for (i in 0 until arr.length()) {
            val v = arr.optString(i, "")
            if (v.isNotBlank()) out.add(v)
        }
        return out
    }

    /**
     * 键值对字段（`{"步数": "28 步"}`）。
     *
     * **顺序按 [PARAM_ORDER] 重排**，不能直接用 `keys()` 的顺序：org.json 的 JSONObject
     * 底层是 HashMap，键序不保证（实测同一份数据两次读出可能不同序）。而用户明确要求
     * 「一个字段一行」且顺序要稳定可读 —— 步数永远在最前，不能这次读出来 CFG 在前。
     * 未列进预设表的键（以后新增的）排在后面，保持遍历顺序。
     *
     * 兼容旧数据里的字符串形态（老版本 `params` 是一整行文本）：遇到字符串就整条塞进
     * 一个「说明」键，界面照常能显示，不必等数据重抓。
     */
    private fun JSONObject.strMap(key: String): Map<String, String> {
        if (!has(key) || isNull(key)) return emptyMap()
        optJSONObject(key)?.let { obj ->
            val out = LinkedHashMap<String, String>()
            for (k in PARAM_ORDER) {
                val v = obj.optString(k, "")
                if (v.isNotBlank()) out[k] = v
            }
            val keys = obj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                if (k in out) continue
                val v = obj.optString(k, "")
                if (k.isNotBlank() && v.isNotBlank()) out[k] = v
            }
            return out
        }
        return optString(key, "").takeIf { it.isNotBlank() }
            ?.let { mapOf("说明" to it) } ?: emptyMap()
    }

    /** 参数键的显示顺序（语义顺序：先采样强度，再采样器，最后搭配类）。 */
    private val PARAM_ORDER = listOf(
        "步数", "CFG", "采样器", "调度器", "降噪", "推荐分辨率", "权重", "触发词"
    )
}
