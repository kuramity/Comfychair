package sh.hnet.comfychair.i18n

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 翻译完整性契约测试（纯 JVM，不依赖 Android 资源系统）。
 *
 * ## 策略与理由
 *
 * 1. **中文（values-zh）要求零缺口**：中文是随源码一起维护的一等语言，文案必须与默认
 *    `values/strings.xml` 一一对应；缺任何一条，中文界面就会回落到默认值（往往是英文），
 *    属于用户可直接感知的缺陷，因此测试要求缺口集合为空。
 *
 * 2. **德/西/法/波本轮不强制补齐**：这四个语种各缺约 220 条（标签库、3D 工作流、
 *    模型介绍、时间轴等新功能）。在没有人审校的情况下批量机翻会降低文案质量，所以本轮
 *    不补，只把已验证的缺口**显式记录**在 [KNOWN_GAPS_DE_ES_FR_PL] 里，测试断言
 *    「缺口集合 == allowlist」。这样：
 *    - 已知缺口被钉住，不会因为文件被误删/误改而悄悄恶化；
 *    - 默认文件**新增** key → 缺口集合变大，测试失败，提醒维护者补翻译或显式记入
 *      allowlist；
 *    - 翻译文件**已补** key → 缺口集合变小，测试同样失败，提醒把该 key 从 allowlist
 *      删除（保持"allowlist 就是真实缺口"的语义）。
 *
 * 3. **`translatable="false"` 的键不参与多语种比对**：默认文件里 app_name、label_cfg、
 *    language_name_* 这类品牌名/技术缩写/语言自称/纯格式串共 27 条被标为不可翻译，
 *    它们只有默认资源里那一份，各语种直接复用即可。若把它们算进"缺口"，就会逼着各语种
 *    复制一份同样的文案 —— 「不可翻译」标记失去意义，改品牌名还要改六处（见 N6 复核
 *    意见）。因此比对前先按该属性过滤，并用单独用例禁止各语种文件重复定义这些键。
 *
 * 4. 反向差集（翻译文件有、默认文件已没有的 key）也做断言：默认文件删除 key 后，各语种
 *    残留的条目永远不会再被读取，属于僵尸翻译，应当一并清掉。
 *
 * 5. **key 齐全 ≠ 已经翻译**：前几条只比 key 集合，发现不了"key 有了、内容还是英文"
 *    （`debug_log_save` 曾长期是英文 "Save"）。因此另有一条语言检测用例：中文资源里
 *    "没有汉字、只含英文单词"的条目必须落在显式允许列表（品牌名/许可证/技术缩写）内。
 *
 * ## 实现说明
 *
 * 解析方式刻意保持极简：用正则抽 `<string ...>` 标签的 name 与 translatable 属性求差集，
 * 不接入 Android 资源编译器/aapt，保证单测在任意 JVM 上都能跑，也不受构建工具版本影响。
 * 只是读仓库里的文本、不做任何实体展开，因此不存在 XML 外部实体解析风险。
 */
class TranslationCompletenessTest {

    /** 所有非默认语种目录（与 res/values-* 一一对应）。 */
    private val localeDirs = listOf(
        "values-zh",
        "values-de",
        "values-es",
        "values-fr",
        "values-pl",
    )

    @Test
    fun `中文翻译零缺口`() {
        val base = readTranslatableNames("values")
        val zh = readStringNames("values-zh")
        val missing = (base - zh).sorted()

        assertEquals(
            "values-zh 缺少以下 key，请在 values-zh/strings.xml 补齐真实中文翻译：\n" +
                missing.joinToString("\n"),
            emptySet<String>(),
            base - zh,
        )
    }

    @Test
    fun `德西法波缺口与 allowlist 完全一致`() {
        val base = readTranslatableNames("values")

        for (locale in listOf("values-de", "values-es", "values-fr", "values-pl")) {
            val missing = base - readStringNames(locale)
            assertEquals(
                "$locale 的已知翻译缺口发生变化。\n" +
                    "· 新增缺失：先补翻译；确实本轮不补的 key 显式加入 " +
                    "KNOWN_GAPS_DE_ES_FR_PL 并在评审里说明。\n" +
                    "· 已补翻译：把对应 key 从 KNOWN_GAPS_DE_ES_FR_PL 删除。\n" +
                    "实际缺口（${missing.size} 条）：\n" + missing.sorted().joinToString("\n"),
                KNOWN_GAPS_DE_ES_FR_PL,
                missing,
            )
        }
    }

    @Test
    fun `翻译文件不存在默认文件已删除的僵尸 key`() {
        // 这里用**全部**默认键（含 translatable=false）比对：某键在默认文件里仍存在，
        // 只是在语种文件里多写了一份，属于冗余而非僵尸，交给下一条用例单独判定。
        val base = readStringNames("values")

        for (locale in localeDirs) {
            val extra = readStringNames(locale) - base
            assertEquals(
                "$locale 存在默认文件里已不存在的 key（僵尸翻译，应从该语种文件删除）：\n" +
                    extra.sorted().joinToString("\n"),
                emptySet<String>(),
                extra,
            )
        }
    }

    @Test
    fun `翻译文件不得重复定义标记为不可翻译的 key`() {
        val nonTranslatable = readStringNames("values") - readTranslatableNames("values")

        for (locale in localeDirs) {
            val duplicated = readStringNames(locale) intersect nonTranslatable
            assertEquals(
                "$locale 重复定义了默认文件里标为 translatable=\"false\" 的 key：" +
                    "这些键只有一份默认文案即可，语种文件里应删除，否则改品牌名/技术缩写" +
                    "要同步多处：\n" + duplicated.sorted().joinToString("\n"),
                emptySet<String>(),
                duplicated,
            )
        }
    }

    /**
     * 中文文案里不得出现"原样照抄英文"的条目。
     *
     * 为什么单列一条：前面几条只校验 **key 是否齐全**，无法发现"key 有了、内容还是英文"
     * 这种实质性漏翻（`debug_log_save` 就曾长期是英文 "Save"）。这里用"既没有汉字、
     * 又含两个以上连续拉丁字母"作为启发式判据，允许列表只留品牌名/许可证全名/技术缩写
     * 这类**本就该保留英文**的条目。
     *
     * 允许列表同时是双向契约：条目被清理干净（不再命中判据）时测试也会失败，提醒把它删掉，
     * 避免豁免范围悄悄扩大。
     */
    @Test
    fun `中文文案不得是未翻译的纯英文`() {
        val nonTranslatable = readStringNames("values") - readTranslatableNames("values")
        val zhValues = readStringValues("values-zh").filterKeys { it !in nonTranslatable }

        val suspicious = zhValues
            .filter { (_, text) -> !CJK.containsMatchIn(text) && LATIN_WORD.containsMatchIn(text) }
            .keys

        assertEquals(
            "values-zh 里出现了「没有汉字、只有英文单词」的条目，通常是漏翻（请补真实中文）；" +
                "确实应保留英文的（品牌名/许可证/技术缩写）加入 ALLOWED_ASCII_ONLY_ZH：\n" +
                (suspicious - ALLOWED_ASCII_ONLY_ZH).sorted().joinToString("\n"),
            ALLOWED_ASCII_ONLY_ZH,
            suspicious,
        )
    }

    // ---------------- 解析与路径 ----------------

    /** 汉字判据：任何一个 CJK 统一表意文字都算"已本地化"。 */
    private val CJK = Regex("[\\u4e00-\\u9fff]")

    /** 英文词判据：两个以上连续拉丁字母（单个字母如格式串里的 s/d 不算）。 */
    private val LATIN_WORD = Regex("[A-Za-z]{2,}")

    /**
     * 允许在中文资源里保持英文的条目：品牌名、许可证全名、技术缩写。
     * 每项都要写明为什么保留，新增豁免须在评审里说明理由。
     */
    private val ALLOWED_ASCII_ONLY_ZH = setOf(
        // 许可证全名：中文社区也普遍直接引用英文原名，翻译反而不好检索
        "label_about_license_name",
        // 服务器信息行的标签：产品/技术名 + 全角冒号，产品名不译
        "label_server_info_comfyui",
        "label_server_info_python",
        "label_server_info_pytorch",
        // ComfyUI 采样参数缩写，与界面其他 CFG 标签保持一致
        "sampler_param_cfg",
    )

    /**
     * 默认文件里有、四个语种都还没有的**可翻译** key（四份翻译文件的缺口集合在 2026-11
     * 完全一致，因此共用一份 allowlist）。维护方式见类 KDoc 第 2 条。
     */
    private val KNOWN_GAPS_DE_ES_FR_PL = setOf(
        "action_confirm",
        "action_gallery_trash",
        "audio_section_title",
        "audio_slot_add",
        "audio_slot_empty_hint",
        "audio_slot_label",
        "audio_slot_remove",
        "button_back",
        // G4（图库回收站与服务器联动删除）新增键：本轮未补德/西/法/波人审翻译，按约定记入 allowlist
        "button_confirm_and_purge",
        "button_deselect_all",
        // 图库「加载更早」分页按钮：本轮未补德/西/法/波机翻，按约定记入 allowlist（中文已补）
        "button_gallery_load_older",
        "button_free_memory",
        "button_select_all",
        "button_undo",
        "changelog_badge_current",
        "changelog_empty",
        "changelog_entry_subtitle",
        "changelog_entry_title",
        "changelog_screen_title",
        "content_description_prompt_lock",
        "content_description_prompt_unlock",
        "dialog_delete_selected_message",
        "dialog_delete_selected_title",
        "dialog_trash_purge_message",
        "dialog_trash_purge_note",
        "dialog_trash_purge_title",
        "dynamic_param_mode_label",
        "error_local_store_corrupted",
        "error_save_audio",
        "free_memory_confirm_message",
        "free_memory_confirm_title",
        "free_memory_failed",
        "free_memory_success",
        "generation_cancel_failed",
        "gallery_trash_back",
        "gallery_trash_clear_all",
        "gallery_trash_delete_forever",
        "gallery_trash_empty",
        "gallery_trash_in_trash_since",
        "gallery_trash_other_server",
        "gallery_trash_pending_retry",
        "gallery_trash_restore",
        "gallery_trash_title",
        "label_3d_badge",
        "label_lora_strength_input",
        "login_background_change",
        "login_background_failed",
        "login_background_pick",
        "login_background_reset",
        "login_background_reset_done",
        "lora_strength_range_hint",
        "media_viewer_copy_link",
        "model_intro_author_params",
        "model_intro_close",
        "model_intro_collapse",
        "model_intro_companions",
        "model_intro_community_base",
        "model_intro_community_count",
        "model_intro_community_notice",
        "model_intro_community_params",
        "model_intro_community_prompt",
        "model_intro_confidence_note",
        "model_intro_copied",
        "model_intro_copy_params",
        "model_intro_detail_title",
        "model_intro_effect",
        "model_intro_effect_archive",
        "model_intro_effect_archive_orphan_only",
        "model_intro_expand",
        "model_intro_intro_missing",
        "model_intro_kind",
        "model_intro_lora_weight",
        "model_intro_lora_weight_default_only",
        "model_intro_lora_weight_max_only",
        "model_intro_lora_weight_min_only",
        "model_intro_lora_weight_range",
        "model_intro_lora_weight_range_default",
        "model_intro_missing_badge",
        "model_intro_missing_notice",
        "model_intro_negative_prompt",
        "model_intro_none",
        "model_intro_original",
        "model_intro_params",
        "model_intro_path",
        "model_intro_screen_count",
        "model_intro_screen_title",
        "model_intro_source_from",
        "model_intro_style_tags",
        "model_intro_tags",
        "model_intro_used_in",
        "model_list_loading",
        "mode_opt_25",
        "mode_opt_28",
        "mode_opt_50",
        "mode_opt_c25",
        "mode_opt_spec25",
        "msg_copy_prompt_failed",
        "msg_download_link_copied",
        "msg_no_negative_prompt",
        "msg_backup_restore_skipped_servers",
        "msg_workflow_feature_removed",
        "msg_trash_kept_other_server",
        "msg_trash_moved",
        "msg_trash_purge_pending",
        "msg_trash_purged_already_gone",
        "msg_trash_purged_local_only",
        "msg_trash_purged_server",
        "msg_trash_restore_failed",
        "msg_trash_restored",
        "nav_model_intro",
        "nav_tag_library",
        "node_params_out_of_range",
        // P4（后台长任务通知）：轮询重试上限后「结果待确认」提示，本轮未补德/西/法/波人审翻译
        "notify_generation_pending_text",
        "notify_generation_pending_title",
        "prompt_library_filter_favorites",
        "prompt_preset_copy_negative",
        "prompt_preset_copy_positive",
        "prompt_preset_negative_line",
        "sampler_param_cfg",
        "sampler_param_clip_gguf",
        "sampler_param_clip_gguf_hint",
        "sampler_param_denoise",
        "sampler_param_pe_mmproj",
        "sampler_param_pe_model",
        "sampler_param_pe_model_hint",
        "sampler_param_pe_on",
        "sampler_param_pe_on_hint",
        "sampler_param_refine_denoise",
        "sampler_param_refine_on",
        "sampler_param_refine_on1",
        "sampler_param_refine_steps",
        "sampler_param_sampler",
        "sampler_param_scale",
        "sampler_param_scale_hint",
        "sampler_param_scheduler",
        "sampler_param_steps",
        "sampler_param_vosr2_on",
        "sampler_param_vosr2_on_hint",
        "sampler_stage_clip",
        "sampler_stage_comp",
        "sampler_stage_detail",
        "sampler_stage_hires",
        "sampler_stage_pe",
        "sampler_stage_stage2",
        "sampler_stage_struct",
        "sampler_stage_title",
        "sampler_stage_vosr2",
        "seed_mode_decrement",
        "seed_mode_fixed",
        "seed_mode_increment",
        "seed_mode_random",
        "tag_add_negative",
        "tag_add_positive",
        "tag_alias_en",
        "tag_alias_group_title",
        "tag_alias_ja",
        "tag_alias_zh",
        "tag_builder_collapse",
        "tag_builder_counts",
        "tag_builder_empty",
        "tag_builder_expand",
        "tag_builder_negative",
        "tag_builder_no_target",
        "tag_builder_positive",
        "tag_builder_remove",
        "tag_builder_title",
        "tag_category_all",
        "tag_category_artist",
        "tag_category_character",
        "tag_category_copyright",
        "tag_category_general",
        "tag_category_meta",
        "tag_copied",
        "tag_copy_failed",
        "tag_detail_back",
        "tag_detail_count",
        "tag_detail_meaning",
        "tag_empty_hint",
        "tag_empty_no_result",
        "tag_error_init",
        "tag_favorite",
        "tag_favorites_only",
        "tag_insert_after",
        "tag_insert_at_start",
        "tag_insert_channel_negative",
        "tag_insert_channel_positive",
        "tag_insert_empty",
        "tag_insert_title",
        "tag_inserted",
        "tag_loading_db",
        "tag_menu_append",
        "tag_menu_copy",
        "tag_menu_insert",
        "tag_partners_label",
        "tag_recents_label",
        "tag_retry",
        "tag_search_clear",
        "tag_search_hint",
        "tag_searching",
        "tag_toast_added",
        "tag_toast_no_target",
        "tag_unfavorite",
        "tag_weight_cancel",
        "tag_weight_confirm",
        "tag_weight_hint",
        "three_d_download_failed",
        "three_d_downloading",
        "three_d_loading",
        "three_d_no_connection",
        "three_d_no_result_hint",
        "three_d_render_failed",
        "three_d_reset_view",
        "three_d_tab_model",
        "three_d_tab_render",
        "three_d_viewer_fullscreen",
        "three_d_viewer_title",
        "update_changelog_empty",
        "update_changelog_title",
        "update_channel_progress_desc",
        "update_channel_progress_name",
        // v4.18.0（R5/R6）新增键：非 WiFi 确认 + 重试耗尽提示；本轮未补德/西/法/波人审翻译
        "update_action_cancel",
        "update_action_continue",
        "update_cellular_confirm_message",
        "update_cellular_confirm_message_unknown",
        "update_cellular_confirm_title",
        "update_current_version_line",
        "update_error_connect",
        "update_error_disk",
        "update_error_http",
        "update_error_install_missing",
        "update_error_install_unavailable",
        "update_error_legacy_interrupted",
        "update_error_retry_exhausted",
        "update_error_server",
        "update_error_timeout",
        "update_error_unexpected",
        "update_error_unknown",
        "update_error_verify",
        "update_failed_retry_hint",
        "update_failed_title",
        "update_notes_title",
        "update_progress_meta",
        "update_progress_retrying",
        "update_progress_text",
        "update_progress_title",
        "update_progress_unknown_total",
        "update_progress_verifying_title",
        "update_ready_install_text",
        "viewer_copy_download_link",
        "viewer_large_image_notice",
        "viewer_load_failed_file_missing",
        "viewer_load_failed_generic",
        "viewer_load_failed_network",
        "viewer_retry",
        "workflow_section_iti_3d",
        "workflow_section_tti_3d",
    )

    /** 读取某个语种目录下 strings.xml 里全部 `<string name="...">` 的 name 集合。 */
    private fun readStringNames(localeDir: String): Set<String> =
        readStringTags(localeDir).keys

    /** 读取某个语种目录的 key → 文案正文（用于"是否漏翻"的语言检测）。 */
    private fun readStringValues(localeDir: String): Map<String, String> {
        val xml = readResourceText(localeDir)
        val out = LinkedHashMap<String, String>()
        for (match in Regex("""<string\s+([^>]*)>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(xml)) {
            val attrs = match.groupValues[1]
            val name = Regex("""name="([A-Za-z0-9_.]+)"""").find(attrs)?.groupValues?.get(1)
                ?: continue
            out[name] = match.groupValues[2]
        }
        return out
    }

    /** 只取默认文件里**没有**标记 `translatable="false"` 的 key（各语种应补齐的集合）。 */
    private fun readTranslatableNames(localeDir: String): Set<String> =
        readStringTags(localeDir).filterValues { it }.keys

    /**
     * 定位并读取某个语种目录的 strings.xml 全文。
     *
     * Gradle 单元测试的工作目录是本模块（app/）；同时兼容从仓库根目录执行的场景。
     */
    private fun readResourceText(localeDir: String): String {
        val resDir = listOf(File("src/main/res"), File("app/src/main/res"))
            .firstOrNull { it.isDirectory }
            ?: error(
                "找不到 res 目录（当前工作目录=${File(".").absolutePath}）；" +
                    "单测应在 app 模块目录下执行"
            )
        val file = File(File(resDir, localeDir), "strings.xml")
        assertTrue("资源文件不存在：${file.absolutePath}", file.isFile)
        return file.readText(Charsets.UTF_8)
    }

    /**
     * 解析某个语种目录的 strings.xml：key → 是否可翻译（`translatable="false"` 为 false）。
     */
    private fun readStringTags(localeDir: String): Map<String, Boolean> =
        parseStringTags(readResourceText(localeDir))

    /**
     * 抽 `<string ...>` 标签的 name 属性与 translatable 标记。
     * 允许属性跨行；`<string-array>` / `<plurals>` 不会命中（它们不叫 string）。
     */
    private fun parseStringTags(xml: String): Map<String, Boolean> {
        val out = LinkedHashMap<String, Boolean>()
        for (match in Regex("""<string\s+([^>]*)>""").findAll(xml)) {
            val attrs = match.groupValues[1]
            val name = Regex("""name="([A-Za-z0-9_.]+)"""").find(attrs)?.groupValues?.get(1)
                ?: continue
            out[name] = !attrs.contains("translatable=\"false\"")
        }
        return out
    }
}