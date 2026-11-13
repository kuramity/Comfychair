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
