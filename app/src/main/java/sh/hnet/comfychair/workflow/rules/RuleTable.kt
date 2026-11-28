package sh.hnet.comfychair.workflow.rules

import android.content.Context
import android.content.res.AssetManager
import org.json.JSONObject
import sh.hnet.comfychair.distribute.AssetDistributor
import sh.hnet.comfychair.distribute.AssetKind
import sh.hnet.comfychair.util.DebugLogger
import java.io.File

/**
 * 占位符规则表（B3 数据化）：recognized 清单 + 编号型模式 + 通道元数据。
 *
 * 数据源（B5 起三层优先级）：**服务器下发覆盖层**（`filesDir/overlay/rules/placeholder_rules.json`，
 * 存在且 schema 合规 → 优先用）→ **assets/rules/placeholder_rules.json**（无覆盖层或覆盖层
 * 坏时用）→ [DEFAULT_TABLE]（编译期内置，与 2026-11-28 TemplateKeyRegistry 现值逐条一致）。
 * 全部失败路径都有 DebugLogger 告警，不阻塞启动；坏 overlay 绝不「半生效」——直接回退下一层。
 *
 * 为什么数据化：TemplateKeyRegistry.RECOGNIZED_PLACEHOLDERS 是编译期硬编码，新工作流模板
 * 引入新占位符必须发版才能让动态参数引擎放行；抽成规则文件后 B5 可下发调整，不必发版。
 * DEFAULT_TABLE 保证「识别行为永远有底」——规则文件坏了宁可退回编译期现值，也不空表启动。
 *
 * 一致性由 RuleTableTest 钉住（防漂移）：DEFAULT_TABLE 与 TemplateKeyRegistry 现值双向 diff、
 * 规则文件解析结果与 DEFAULT_TABLE 全字段比对、overlay→assets→内置的三层回退顺序。
 * 上游改清单时这些测试会先响，提示同步更新。
 */
object RuleTable {

    private const val TAG = "RuleTable"
    private const val RULES_ASSET_PATH = "rules/placeholder_rules.json"

    /** 单个占位符的通道元数据：作用于哪些工作流通道、在哪些通道被禁用。 */
    data class PlaceholderMeta(
        val key: String,
        val type: String,
        val channels: List<String>,
        val forbiddenIn: List<String>,
        val note: String?,
    )

    /** 一份完整的规则表：version + recognized 清单 + 编号型模式 + meta 索引。 */
    data class Table(
        val version: Int,
        val recognized: Set<String>,
        val patterns: List<Regex>,
        val meta: Map<String, PlaceholderMeta>,
    )

    @Volatile
    private var loaded: Table? = null

    /**
     * 加载规则表并缓存为当前表（重复调用以最后一次为准，B5 下发刷新场景）。
     *
     * 优先读 [overlayFile]（服务器下发覆盖层，存在且 schema 合规 → 用之；坏 → 回退 assets），
     * assets 也失败 → [DEFAULT_TABLE]（与 B3 行为完全一致）。全部 IO 都在调用线程同步完成
     * （ComfyChairApp 从 Dispatchers.IO 调用，见其 onCreate 注释）。
     */
    fun load(assets: AssetManager, overlayFile: File? = null): Table {
        val overlayRaw = overlayFile?.takeIf { it.isFile }?.let {
            try {
                it.readText()
            } catch (e: Exception) {
                DebugLogger.w(TAG, "读取规则表覆盖层失败（${e.message}），按无覆盖层处理")
                null
            }
        }
        val assetsRaw = try {
            assets.open(RULES_ASSET_PATH).bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            DebugLogger.w(TAG, "读取 assets 规则文件失败（${e.message}）")
            null
        }
        val table = pickTable(overlayRaw, assetsRaw)
        loaded = table
        return table
    }

    /**
     * 三层选择（internal 纯函数，RuleTableTest 直测）：overlay 有效 → overlay；overlay 坏/缺 →
     * assets；两者都坏 → [DEFAULT_TABLE]。每层失败都单独告警，绝不产出「半生效」的表。
     */
    internal fun pickTable(overlayRaw: String?, assetsRaw: String?): Table {
        if (overlayRaw != null) {
            try {
                return parseTableJson(overlayRaw).also {
                    DebugLogger.i(
                        TAG,
                        "占位符规则表使用服务器下发覆盖层：v${it.version}，recognized ${it.recognized.size} 条，patterns ${it.patterns.size} 条",
                    )
                }
            } catch (e: Exception) {
                DebugLogger.w(TAG, "规则表覆盖层 schema 不合规（${e.message}），回退 assets 内置文件")
            }
        }
        if (assetsRaw != null) {
            try {
                return parseTableJson(assetsRaw).also {
                    DebugLogger.i(
                        TAG,
                        "占位符规则表加载成功：v${it.version}，recognized ${it.recognized.size} 条，patterns ${it.patterns.size} 条",
                    )
                }
            } catch (e: Exception) {
                DebugLogger.w(TAG, "占位符规则表加载失败（${e.message}），回退内置默认表")
            }
        }
        return DEFAULT_TABLE
    }

    /** 服务器下发覆盖层的标准位置（与 AssetKind.RULE_TABLE 的落盘路径三方共用同一常量）。 */
    fun overlayFile(context: Context): File = File(
        File(File(context.filesDir, AssetDistributor.OVERLAY_DIR), AssetKind.RULE_TABLE.dirName),
        AssetKind.RULE_TABLE_FILENAME,
    )

    /**
     * 解析规则 JSON 并做 schema 校验（internal：RuleTableTest 用它做「文件 = DEFAULT_TABLE」
     * 等价性钉子，B5 服务器下发的原始 JSON 也走这里）。任何不合规（version <1、recognized
     * 空表、pattern 非法）直接抛异常、由调用方回退，绝不产出「半生效」的表。
     */
    internal fun parseTableJson(raw: String): Table {
        val root = JSONObject(raw)
        val version = root.getInt("version")
        require(version >= 1) { "version 必须 ≥1，实际 $version" }

        val recognizedArray = root.getJSONArray("recognized_placeholders")
        require(recognizedArray.length() > 0) { "recognized_placeholders 不能为空数组" }
        val recognized = buildSet {
            for (i in 0 until recognizedArray.length()) add(recognizedArray.getString(i))
        }

        val patternsArray = root.getJSONArray("recognized_patterns")
        val patterns = buildList {
            for (i in 0 until patternsArray.length()) add(Regex(patternsArray.getString(i)))
        }

        val meta = LinkedHashMap<String, PlaceholderMeta>()
        if (root.has("placeholders_meta")) {
            val metaArray = root.getJSONArray("placeholders_meta")
            for (i in 0 until metaArray.length()) {
                val entry = metaArray.getJSONObject(i)
                val channels = buildList {
                    val arr = entry.getJSONArray("channels")
                    for (j in 0 until arr.length()) add(arr.getString(j))
                }
                val forbiddenIn = if (entry.has("forbidden_in")) {
                    val arr = entry.getJSONArray("forbidden_in")
                    buildList {
                        for (j in 0 until arr.length()) add(arr.getString(j))
                    }
                } else {
                    emptyList()
                }
                val item = PlaceholderMeta(
                    key = entry.getString("key"),
                    type = entry.optString("type", "text"),
                    channels = channels,
                    forbiddenIn = forbiddenIn,
                    note = if (entry.has("note")) entry.getString("note") else null,
                )
                meta[item.key] = item
            }
        }

        return Table(version = version, recognized = recognized, patterns = patterns, meta = meta)
    }

    /** 当前生效的规则表：未加载（或加载失败未缓存）时是内置默认表。 */
    fun current(): Table = loaded ?: DEFAULT_TABLE

    val recognizedPlaceholders: Set<String> get() = current().recognized
    val recognizedPatterns: List<Regex> get() = current().patterns

    /** 占位符是否已被专用逻辑识别（清单命中或编号型模式命中）。 */
    fun isRecognized(placeholder: String): Boolean =
        placeholder in current().recognized || current().patterns.any { it.matches(placeholder) }

    /** 该占位符在指定通道是否被禁用（meta 未登记 = 无约束 = 恒 false）。 */
    fun forbiddenIn(key: String, channel: String): Boolean =
        current().meta[key]?.forbiddenIn?.contains(channel) == true

    // ===========================================
    // 编译期内置默认表（与 assets/rules/placeholder_rules.json 完全一致）。
    // 改这里必须同步改规则文件，反之亦然 —— RuleTableTest 的等价性测试钉住。
    // ===========================================

    /** 全部五个工作流通道（B3 取证的「5 入口替换调用序列」通道词汇）。 */
    private val ALL_CHANNELS = listOf("tti", "iti_inpainting", "iti_editing", "ttv", "itv")

    /** 提示词额外作用于 skill 通道。 */
    private val ALL_PLUS_SKILL = ALL_CHANNELS + "skill"

    private fun meta(
        key: String,
        type: String,
        channels: List<String>,
        forbiddenIn: List<String> = emptyList(),
        note: String? = null,
    ) = PlaceholderMeta(key, type, channels, forbiddenIn, note)

    private val DEFAULT_TABLE: Table = Table(
        version = 1,
        recognized = setOf(
            // —— TemplateKeyRegistry.PLACEHOLDER_TO_KEY 的 key（声明序）——
            "positive_prompt",
            "negative_prompt",
            "ckpt_name",
            "unet_name",
            "vae_name",
            "clip_name",
            "clip_name1",
            "clip_name2",
            "clip_name3",
            "clip_name4",
            "width",
            "height",
            "steps",
            "cfg",
            "sampler_name",
            "scheduler",
            "megapixels",
            "highnoise_unet_name",
            "lownoise_unet_name",
            "highnoise_lora_name",
            "lownoise_lora_name",
            "length",
            "frame_rate",
            "image_filename",
            "reference_image_1",
            "reference_image_2",
            "seed",
            "denoise",
            "batch_size",
            "upscale_method",
            "scale_by",
            "stop_at_clip_layer",
            "text_encoder_name",
            "latent_upscale_model",
            "upscale_model",
            "aspect_ratio",
            "speed_mode",
            "lora_strength",
            // —— RECOGNIZED_PLACEHOLDERS 的额外 7 条（含 v4.23.1 增补的 skill_model/skill_mmproj）——
            "lora_name",
            "noise_seed",
            "audio_filename",
            "skill_file_name",
            "skill_file_content",
            "skill_model",
            "skill_mmproj",
        ),
        patterns = listOf(
            Regex("""reference_image_\d+"""),
            Regex("""reference_audio_\d+"""),
        ),
        meta = listOf(
            meta("positive_prompt", "text", ALL_PLUS_SKILL),
            meta("negative_prompt", "text", ALL_PLUS_SKILL),
            // 模型类：全五通道
            meta("ckpt_name", "model", ALL_CHANNELS),
            meta("unet_name", "model", ALL_CHANNELS),
            meta("vae_name", "model", ALL_CHANNELS),
            meta("clip_name", "model", ALL_CHANNELS),
            meta("clip_name1", "model", ALL_CHANNELS),
            meta("clip_name2", "model", ALL_CHANNELS),
            meta("clip_name3", "model", ALL_CHANNELS),
            meta("clip_name4", "model", ALL_CHANNELS),
            meta("text_encoder_name", "model", ALL_CHANNELS),
            meta("highnoise_unet_name", "model", ALL_CHANNELS),
            meta("lownoise_unet_name", "model", ALL_CHANNELS),
            meta("highnoise_lora_name", "model", ALL_CHANNELS),
            meta("lownoise_lora_name", "model", ALL_CHANNELS),
            meta("lora_name", "model", ALL_CHANNELS, note = "replaceLoraPlaceholder 专用处理"),
            meta("latent_upscale_model", "model", ALL_CHANNELS),
            meta("upscale_model", "model", listOf("iti_editing"), note = "仅 ITI 编辑认领"),
            // 全五通道的公共参数
            meta("seed", "number", ALL_CHANNELS, note = "replaceCommonPlaceholders 无条件替换"),
            meta("noise_seed", "number", ALL_CHANNELS, note = "seed 别名（RandomNoise 等节点），与 seed 同源"),
            meta("steps", "number", ALL_CHANNELS),
            meta("cfg", "number", ALL_CHANNELS),
            meta("sampler_name", "enum", ALL_CHANNELS),
            meta("scheduler", "enum", ALL_CHANNELS),
            meta("denoise", "number", ALL_CHANNELS),
            meta("batch_size", "number", ALL_CHANNELS),
            meta("megapixels", "number", ALL_CHANNELS),
            meta("aspect_ratio", "enum", ALL_CHANNELS),
            // 尺寸：编辑类禁用（规格文档第三条硬规则）
            meta("width", "number", listOf("tti", "ttv", "itv"), forbiddenIn = listOf("iti_inpainting", "iti_editing"),
                note = "编辑类禁用：改用 megapixels，混用必 400"),
            meta("height", "number", listOf("tti", "ttv", "itv"), forbiddenIn = listOf("iti_inpainting", "iti_editing"),
                note = "编辑类禁用：改用 megapixels，混用必 400"),
            meta("speed_mode", "enum", listOf("iti_inpainting", "iti_editing", "ttv", "itv"), note = "TTI 不传"),
            // 视频流专属
            meta("lora_strength", "number", listOf("ttv", "itv"), note = "视频流主 LoRA 权重（节点输入键 strength_model）"),
            meta("length", "number", listOf("ttv", "itv"), note = "视频流专属"),
            meta("frame_rate", "number", listOf("ttv", "itv"), note = "视频流专属"),
            // 仅 TTI 放大链
            meta("upscale_method", "enum", listOf("tti"), note = "仅 TTI 放大链"),
            meta("scale_by", "number", listOf("tti"), note = "仅 TTI 放大链"),
            meta("stop_at_clip_layer", "number", listOf("tti"), note = "仅 TTI 放大链"),
            // 图 / 音频位
            meta("image_filename", "image", listOf("iti_inpainting", "iti_editing", "itv", "skill"), note = "含 skill 通道（技能流参考图）"),
            meta("reference_image_1", "image", listOf("iti_editing"), note = "仅 ITI 编辑（编号型模式的两条穷举位）"),
            meta("reference_image_2", "image", listOf("iti_editing"), note = "仅 ITI 编辑（编号型模式的两条穷举位）"),
            meta("audio_filename", "audio", listOf("itv"), note = "AudioSlotDetector + injectAudioSlots 专用处理"),
            // 技能流
            meta("skill_file_name", "text", listOf("skill"), note = "applySkillPrompt 整体替换，选择器是技能提示词下拉"),
            meta("skill_file_content", "text", listOf("skill"), note = "applySkillPrompt 整体替换，选择器是技能提示词下拉"),
            meta("skill_model", "model", listOf("skill"), note = "技能流配套 gguf 模型名，从工作流 defaults 读（v4.23.1 登记）"),
            meta("skill_mmproj", "model", listOf("skill"), note = "技能流配套视觉投影文件，从工作流 defaults 读（v4.23.1 登记）"),
        ).associateBy { it.key },
    )
}
