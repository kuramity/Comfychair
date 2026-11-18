package sh.hnet.comfychair.storage

import android.content.Context

/**
 * Global app settings singleton.
 * Provides access to app-wide settings that need to be checked from multiple places.
 */
object AppSettings {
    private const val PREFS_NAME = "AppSettings"
    private const val KEY_MEDIA_CACHE_DISABLED = "media_cache_disabled"
    private const val KEY_MEMORY_FIRST_CACHE = "memory_first_cache"
    private const val KEY_LIVE_PREVIEW_ENABLED = "live_preview_enabled"
    private const val KEY_DEBUG_LOGGING_ENABLED = "debug_logging_enabled"
    private const val KEY_AUTO_CONNECT = "autoConnect"
    private const val KEY_SHOW_BUILT_IN_WORKFLOWS = "show_built_in_workflows"
    private const val KEY_OFFLINE_MODE = "offline_mode"
    private const val KEY_EDGE_ROUTER = "edge_router"
    private const val DEFAULT_EDGE_ROUTER = "hermite"
    private const val KEY_PROMPT_SPELL_CHECK = "prompt_spell_check"
    private const val KEY_PARTICLE_OVERLAY_ENABLED = "particle_overlay_enabled"
    private const val KEY_RIPPLE_DOT_FEEDBACK_ENABLED = "ripple_dot_feedback_enabled"

    /**
     * Check if live preview is enabled.
     * When enabled (default), intermediate preview images are shown during generation.
     * When disabled, only the final result is displayed.
     */
    fun isLivePreviewEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_LIVE_PREVIEW_ENABLED, true)  // Default: true (show live previews)
    }

    /**
     * Set whether live preview should be enabled.
     */
    fun setLivePreviewEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_LIVE_PREVIEW_ENABLED, enabled)
            .apply()
    }

    /**
     * Check if memory-first caching is enabled.
     * When enabled (default), media is kept in RAM and persisted to disk on app background.
     * When disabled, media is written directly to disk (disk-first mode for low-end devices).
     */
    fun isMemoryFirstCache(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_MEMORY_FIRST_CACHE, true)  // Default: true (memory-first)
    }

    /**
     * Set whether memory-first caching should be enabled.
     */
    fun setMemoryFirstCache(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_MEMORY_FIRST_CACHE, enabled)
            .apply()
    }

    /**
     * Check if media cache is disabled.
     * When disabled, the app does not persist preview images and videos to disk.
     * Only applicable in memory-first mode.
     */
    fun isMediaCacheDisabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_MEDIA_CACHE_DISABLED, false)
    }

    /**
     * Set whether media cache should be disabled.
     */
    fun setMediaCacheDisabled(context: Context, disabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_MEDIA_CACHE_DISABLED, disabled)
            .apply()
    }

    /**
     * Check if debug logging is enabled.
     * When enabled, operational logs are captured in memory for troubleshooting.
     * Default is false (off).
     */
    fun isDebugLoggingEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_DEBUG_LOGGING_ENABLED, false)
    }

    /**
     * Set whether debug logging should be enabled.
     */
    fun setDebugLoggingEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_DEBUG_LOGGING_ENABLED, enabled)
            .apply()
    }

    /**
     * Check if auto-connect is enabled.
     * When enabled (default), the app auto-connects to the last selected server on startup.
     */
    fun isAutoConnectEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_AUTO_CONNECT, true)  // Default: true (auto-connect)
    }

    /**
     * Set whether auto-connect should be enabled.
     */
    fun setAutoConnectEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_AUTO_CONNECT, enabled)
            .apply()
    }

    /**
     * Check if built-in workflows should be shown.
     * When enabled (default), built-in workflows are displayed alongside user workflows.
     * When disabled, only user-created workflows are shown.
     */
    fun isShowBuiltInWorkflows(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_SHOW_BUILT_IN_WORKFLOWS, true)  // Default: true (show built-in)
    }

    /**
     * Set whether built-in workflows should be shown.
     */
    fun setShowBuiltInWorkflows(context: Context, show: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_SHOW_BUILT_IN_WORKFLOWS, show)
            .apply()
    }

    /**
     * 用户是否开启了「强制离线」（设置页开关）。
     *
     * 语义是用户的**持久化意图**：打开了就一直待在离线态，后台不做任何网络请求
     * （不建 WebSocket、不做周期探测）。它**不等于**"当前在离线态"——
     * 网络断开时应用会自动切到离线（此时连接状态为 `OfflineConnected`，
     * 但本开关仍是 false），恢复后又会自动切回在线。
     *
     * 判断"现在能不能联网"请看 `ConnectionManager.isOffline`；
     * 判断"用户是不是要求别联网"才看这里。
     * 默认 false（不强制离线）。
     */
    fun isOfflineMode(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_OFFLINE_MODE, false)
    }

    /**
     * 设置「强制离线」开关。
     *
     * 注意：只改这个标志位是不够的——必须通过
     * `ConnectionManager.setForcedOffline(context, enabled)` 让连接状态、
     * 离线缓存与后台连接一起跟着变。
     */
    fun setOfflineMode(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_OFFLINE_MODE, enabled)
            .apply()
    }

    /**
     * Get the selected edge router ID for the workflow editor.
     * Default is "hermite" (Hermite spline).
     */
    fun getEdgeRouterId(context: Context): String {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_EDGE_ROUTER, DEFAULT_EDGE_ROUTER) ?: DEFAULT_EDGE_ROUTER
    }

    /**
     * Set the edge router ID for the workflow editor.
     */
    fun setEdgeRouterId(context: Context, routerId: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_EDGE_ROUTER, routerId)
            .apply()
    }

    /**
     * Check if spell check and autocorrect are enabled in prompt input fields.
     * Default is false — AI prompts contain many non-standard tokens that would be
     * flagged as errors, so the feature is opt-in.
     */
    fun isPromptSpellCheckEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_PROMPT_SPELL_CHECK, false)
    }

    fun setPromptSpellCheckEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_PROMPT_SPELL_CHECK, enabled)
            .apply()
    }

    /**
     * 全屏粒子特效覆盖层（v4.8.0 的 [sh.hnet.comfychair.ui.components.ParticleOverlay]）
     * 是否挂载。默认 **false**：2026-11-14 起新验收标准禁止"全屏描边圆环 + 向外飞散的
     * 实心粒子"，默认不组合、不转发触点；打开开关可一键回退旧特效。
     */
    fun isParticleOverlayEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_PARTICLE_OVERLAY_ENABLED, false)  // Default: false（按钮内 ripple 皮肤替代）
    }

    fun setParticleOverlayEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_PARTICLE_OVERLAY_ENABLED, enabled)
            .apply()
    }

    /**
     * 按钮内"水面波纹点阵"皮肤（v4.9.x 的 [sh.hnet.comfychair.ui.components.ripple.rippleFeedback]）
     * 是否启用。默认 **true**；置 false 时各接入点以 `rippleFeedback(enabled = false)`
     * 原样返回，按钮恢复无特效的原始形态。
     */
    fun isRippleDotFeedbackEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_RIPPLE_DOT_FEEDBACK_ENABLED, true)  // Default: true
    }

    fun setRippleDotFeedbackEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_RIPPLE_DOT_FEEDBACK_ENABLED, enabled)
            .apply()
    }

    // ============================================================
    // 按钮皮肤 v2 总回退开关
    // ============================================================

    private const val KEY_UI_SKIN_V2_ENABLED = "ui_skin_v2_enabled"

    /**
     * 按钮皮肤 v2（顶栏裸图标化 + 主按钮向下投影 + 齿轮浅底）是否启用，默认 **true**。
     *
     * 置 false 时的回退形态（逐项对照，全部用参数分支实现、没有复制两套 UI）：
     * 顶栏图标回 48dp 圆形 + 白边 + 外辉光；主按钮白边回 50%；下拉按钮回 56×56；
     * 齿轮回 `OutlinedIconButton`；间距回原值。波纹点阵不受本开关影响（另有总开关）。
     */
    fun isUiSkinV2Enabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_UI_SKIN_V2_ENABLED, true)  // Default: true
    }

    fun setUiSkinV2Enabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_UI_SKIN_V2_ENABLED, enabled)
            .apply()
    }

    // ============================================================
    // 下拉按钮图标显式居中开关（v4.15.x 安全阀）
    // ============================================================

    private const val KEY_TRAILING_ICON_CENTERED = "trailing_icon_centered"

    /**
     * 下拉按钮（队列菜单）箭头图标的显式几何居中是否启用，默认 **true**。
     *
     * 启用：图标从 `SplitButtonDefaults.ElevatedTrailingButton` 的 content 槽里外置到按钮
     * 容器上，用 `Modifier.align(Alignment.Center)` 显式居中 —— 图标中心恒等于按钮几何
     * 中心，`Modifier.rotate` 的轴心随之落在按钮中心，收起/展开两态一致。
     *
     * 置 false 时回退到改动前路径：图标留在 M3 content 槽内，居中交给
     * `ElevatedTrailingButton` 内部排布（含 M3 的光学水平偏移），旋转仍挂在
     * `Modifier.rotate` 上 —— 与旧版逐像素一致。
     */
    fun isTrailingIconCentered(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_TRAILING_ICON_CENTERED, true)  // Default: true
    }

    fun setTrailingIconCentered(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_TRAILING_ICON_CENTERED, enabled)
            .apply()
    }

    // ============================================================
    // 按压反馈与选中态统一开关（模块 2 安全阀）
    // ============================================================

    private const val KEY_PRESS_FEEDBACK_UNIFIED = "press_feedback_unified"

    /**
     * 按压反馈统一 + 选中态联动 + 下拉同色度（模块 2）是否启用，默认 **true**。
     *
     * 启用时的规格（逐项对照）：
     * 顶栏图标按压去 M3 圆形 ripple（只剩圆角矩形底色 + scale）、按压底色统一 13%
     * （顶栏 / 导航 tab / 齿轮三处终值同源）、齿轮打开态 primary@0.18 底 + primary 图标、
     * 下拉打开态白边提亮到 32%、下拉基色改主按钮渐变中点色（纯色）且白边与主按钮同源。
     *
     * 置 false 时**完全回到本模块改动前**的状态：pressBg 0.08/0.06、M3 圆形 ripple 保留、
     * 齿轮打开只旋转无底色、下拉 0.30 混合渐变 + 0.18 白边（静置/打开同值）。
     */
    fun isPressFeedbackUnified(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_PRESS_FEEDBACK_UNIFIED, true)  // Default: true
    }

    fun setPressFeedbackUnified(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_PRESS_FEEDBACK_UNIFIED, enabled)
            .apply()
    }

    // ============================================================
    // 图库持久化缓存开关（v4.16.1 安全阀）
    // ============================================================

    private const val KEY_GALLERY_PERSISTENT_CACHE = "gallery_persistent_cache"

    /**
     * 图库持久化缓存（v4.16.1）是否启用，默认 **true**。
     *
     * 启用：图库列表缩略图走"统一档"链路 —— `preview=jpeg;75` 拉服务端压缩版，
     * 客户端采样到长边 720、JPEG80 落盘到 `filesDir/gallery_store`（图片）与
     * `filesDir/gallery_thumbs`（视频首帧）。filesDir 不受系统存储清理影响，
     * 重开 App 直接命中磁盘缓存、立刻清晰；统一档不受「内存优先」开关控制，一律落盘。
     *
     * 置 false 时**完全回到旧行为**：`cacheDir` 两处目录 + 512 长边档 +
     * 原图不落盘（cacheDir 在存储紧张时会被系统清空，即"重开就变模糊"的旧根因）。
     * 新旧两条链路互不读写对方目录（详见 MediaCache.fetchThumbnail 的分流注释）。
     */
    fun isGalleryPersistentCacheEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_GALLERY_PERSISTENT_CACHE, true)  // Default: true（持久化）
    }

    fun setGalleryPersistentCacheEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_GALLERY_PERSISTENT_CACHE, enabled)
            .apply()
    }

    // ============================================================
    // 图库三级手势开关（v4.16.2 安全阀）
    // ============================================================

    private const val KEY_GALLERY_THREE_LEVEL = "gallery_three_level"
    private const val KEY_GALLERY_PINCH_COLUMNS = "gallery_pinch_columns"

    /**
     * 单图查看器"并排预览"三级手势（v4.16.2，总体计划 D1~D5）是否启用，默认 **true**。
     *
     * 启用：图库模式查看器里，未放大态双指合拢 → 图片跟手缩小 → 松手吸附到"并排"
     * （中间约 70%、两侧各露约 15%）；并排态继续合拢 → 临时再缩小一圈（约 55%）
     * → 松手弹回并排；并排态张开手指 → 回全屏。并排态左右滑交给 pager 翻页。
     *
     * 置 false 时**完全回到旧行为**：未放大态双指合拢到阈值即"缩小淡出关闭查看器"
     * （v4.15.x 的捏合退出链路原样保留，见 ImageViewer.playPinchCloseDismiss）。
     */
    fun isGalleryThreeLevelEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_GALLERY_THREE_LEVEL, true)  // Default: true（三级手势）
    }

    fun setGalleryThreeLevelEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_GALLERY_THREE_LEVEL, enabled)
            .apply()
    }

    /**
     * 图库网格"捏合改列数"（v4.16.2，总体计划 C1~C3）是否启用，默认 **true**。
     *
     * 启用：图库网格上双指捏合 → 格子跟手连续缩放 → 松手吸附到最近档位
     * （2 / 3 / 4 列，含 scroll 锚定保住当前看的图）。
     *
     * 置 false 时**完全回到旧行为**：固定 2 列（[androidx.compose.foundation.lazy.grid.GridCells.Fixed]（2）），
     * 不挂捏合手势，捏合无任何效果。
     */
    fun isGalleryPinchColumnsEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_GALLERY_PINCH_COLUMNS, true)  // Default: true（捏合改列数）
    }

    fun setGalleryPinchColumnsEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_GALLERY_PINCH_COLUMNS, enabled)
            .apply()
    }

    // ============================================================
    // 下拉透出图库 + 位置飞回开关（v4.16.3 安全阀，B-23b 第三批）
    // ============================================================

    private const val KEY_GALLERY_DISMISS_REVEAL = "gallery_dismiss_reveal"

    /**
     * 单图查看器"下拉透出图库 + 位置飞回"（v4.16.3，总体计划 E1~E5）是否启用，默认 **true**。
     *
     * 启用：图库模式查看器全屏态向下拖动，偏离原位约 10% 后黑底快速（约 120ms）淡到
     * 0.2，进查看器那一刻截下的图库画面从背后透出（被点的那格已挖黑占位）；松手达到
     * 关闭阈值时图片边缩小边飞回那格（落格尺寸 = 其他缩略图），未达阈值则弹回全屏、
     * 背景恢复纯黑。
     *
     * 置 false 时**完全回到旧行为**：纯黑背景 + 现有下滑关闭（无截图透出、无飞回），
     * 且打开查看器不再截屏（省一份位图内存）。
     */
    fun isGalleryDismissRevealEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_GALLERY_DISMISS_REVEAL, true)  // Default: true（下拉透出 + 飞回）
    }

    fun setGalleryDismissRevealEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_GALLERY_DISMISS_REVEAL, enabled)
            .apply()
    }

    // ============================================================
    // 下拉透出"挖空截图"开关（v4.16.8 安全阀，N2）
    // ============================================================

    private const val KEY_GALLERY_REVEAL_HOLE_PUNCH = "gallery_reveal_hole_punch"

    /**
     * 下拉透出的"原位挖空"（v4.16.8，N2）是否启用，默认 **true**。
     *
     * 启用：截取图库画面时直接把被点格子的矩形区域在 Bitmap 像素空间里填成
     * 图库背景色 —— 透出的画面上那格就是"空置格子"，与周围 gutter 视觉一致。
     * 挖空发生在静态截图内，天然没有跨窗口坐标系对齐问题（旧覆盖层方案曾因
     * 漏算网格 contentPadding 整体偏移 8dp，格子右缘/底缘各露出一条）。
     *
     * 置 false 时**回到旧覆盖层方案**：截图不挖空，由查看器在截图之上画黑矩形
     * 占位（MediaViewerScreen 的 E3 Canvas）。
     */
    fun isGalleryRevealHolePunch(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_GALLERY_REVEAL_HOLE_PUNCH, true)  // Default: true（截图内挖空）
    }

    fun setGalleryRevealHolePunch(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_GALLERY_REVEAL_HOLE_PUNCH, enabled)
            .apply()
    }

    private const val KEY_PROMPT_EXPAND = "prompt_expand"

    fun isPromptExpandEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_PROMPT_EXPAND, false)
    }

    fun setPromptExpandEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_PROMPT_EXPAND, enabled)
            .apply()
    }

    // ============================================================
    // LoRA 档案并入目录树开关（v4.16.6 安全阀）
    // ============================================================

    private const val KEY_LORA_ARCHIVE_MERGED = "lora_archive_merged"

    /**
     * 「LoRA 效果档案并入目录树」（v4.16.6 Q4）是否启用，默认 **true**。
     *
     * 启用：模型介绍页重建为「类别 → 分组 → 条目」三层目录树；档案区不再单独列出
     * 47 条——42 条已收录到介绍表的条目并入树中对应模型（效果在详情抽屉里看），
     * 档案只保留 5 条介绍表没有的孤儿条目，作为一个普通一级文件夹节点挂在树顶。
     * 合并只在内存展示层完成，数据文件（assets / filesDir）零修改。
     *
     * 置 false 时**完全回到旧行为**：顶部独立「LoRA 效果档案」分区（47 条全部列出）
     * + 按存放路径展开的旧目录树（v4.16.5 形态）。
     */
    fun isLoraArchiveMerged(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_LORA_ARCHIVE_MERGED, true)  // Default: true（合并形态）
    }

    fun setLoraArchiveMerged(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_LORA_ARCHIVE_MERGED, enabled)
            .apply()
    }

    // ============================================================
    // 生成点击的键盘收起时机修复（v4.16.7 安全阀）
    // ============================================================

    private const val KEY_GENERATE_CLICK_IME_FIX = "generate_click_ime_fix"

    /**
     * 「点生成时先提交、收键盘延后一帧」（v4.16.7 S2 安全阀），默认 **true**。
     *
     * 背景：键盘收起会触发 imePadding 逐帧重排，按钮在窗口里位移 246px
     * （R4 取证）。开启时 `onGenerate()` 先同步执行、`clearFocus()` 用
     * `view.post` 推后一帧 —— 点击手势期间按钮布局保持稳定。
     *
     * 置 false 时**完全回到旧行为**：onClick 首行立即 clearFocus（v4.16.6 及之前
     * 的顺序）。
     */
    fun isGenerateClickImeFixEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_GENERATE_CLICK_IME_FIX, true)  // Default: true（修复形态）
    }

    fun setGenerateClickImeFixEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_GENERATE_CLICK_IME_FIX, enabled)
            .apply()
    }

    // ============================================================
    // 更新下载回退开关（v4.12.0 安全阀）
    // ============================================================

    private const val KEY_UPDATE_DOWNLOAD_LEGACY_MODE = "update_download_legacy_mode"

    /**
     * 更新下载的回退开关（默认 false = 新逻辑）。
     *
     * 置 true 回退到 v4.11.0 之前的下载行为：
     * 不修状态机（自动重试后进度不再恢复，会停摆）、
     * 不判 416（续传起点越界时无限重试，.part 永不清理）、
     * 不做异常分型（任何 IO 异常都无限重试，HTTP 4xx 也不失败）。
     * 只影响 Worker 的下载策略，不影响通知与 UI 展示。
     */
    fun isUpdateDownloadLegacyMode(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_UPDATE_DOWNLOAD_LEGACY_MODE, false)  // Default: false (新逻辑)
    }

    fun setUpdateDownloadLegacyMode(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_UPDATE_DOWNLOAD_LEGACY_MODE, enabled)
            .apply()
    }

    // ============================================================
    // 调色盘（界面自定义配色）
    // ============================================================

    private const val KEY_THEME_CUSTOM_ACCENT = "theme_custom_accent"
    private const val KEY_THEME_CUSTOM_BASE = "theme_custom_base"
    private const val KEY_THEME_CUSTOM_TEXT = "theme_custom_text"

    /**
     * 「这一项用户没动过」的哨兵值。
     *
     * 用 `Int.MIN_VALUE` 而不是 0：0 是合法的颜色值（透明黑），
     * 万一将来把默认值写成 0，会读出一个谁都想不到的颜色。
     */
    const val UNSET_COLOR = Int.MIN_VALUE

    /**
     * 读取用户自定义配色的三个基准色 —— 顺序固定为 [强调色, 底色, 文字色]。
     *
     * 每项可能是 [UNSET_COLOR]，表示**用户没动过这一项**，界面照旧用原厂色。
     * 返回 null 表示三项都没动过（完全原厂）。
     * 这里刻意只进出 Int、不引用 ui 层的类型，免得 storage 反向依赖界面。
     *
     * 为什么只存三个色值：深浅两套色板由它们推导（见 ThemePaletteTransform），
     * 存一套就够，也杜绝了「两套参数改到互相打架」。
     */
    fun getThemeCustomColors(context: Context): IntArray? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (!prefs.contains(KEY_THEME_CUSTOM_ACCENT)) return null
        return intArrayOf(
            prefs.getInt(KEY_THEME_CUSTOM_ACCENT, UNSET_COLOR),
            prefs.getInt(KEY_THEME_CUSTOM_BASE, UNSET_COLOR),
            prefs.getInt(KEY_THEME_CUSTOM_TEXT, UNSET_COLOR)
        )
    }

    /** 写入自定义配色；三项全空（或传 null）表示回到原厂色板，键一并抹掉。 */
    fun setThemeCustomColors(context: Context, colors: IntArray?) {
        val editor = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
        val allUnset = colors == null || colors.all { it == UNSET_COLOR }
        if (allUnset) {
            editor.remove(KEY_THEME_CUSTOM_ACCENT)
                .remove(KEY_THEME_CUSTOM_BASE)
                .remove(KEY_THEME_CUSTOM_TEXT)
        } else {
            // 三项一起写（未动过的写哨兵），这样"有没有自定义过"只看一个键就够，
            // 不会出现"读到一半、另两个键缺位"的半套参数
            editor.putInt(KEY_THEME_CUSTOM_ACCENT, colors.getOrElse(0) { UNSET_COLOR })
                .putInt(KEY_THEME_CUSTOM_BASE, colors.getOrElse(1) { UNSET_COLOR })
                .putInt(KEY_THEME_CUSTOM_TEXT, colors.getOrElse(2) { UNSET_COLOR })
        }
        editor.apply()
    }

}
