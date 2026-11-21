package sh.hnet.comfychair.update

import android.content.Context
import android.content.SharedPreferences

/**
 * 「一个版本只主动弹一次」的判定（v4.18.0，R1）。
 *
 * 用户实测痛点：只要有新版本，每次进 App 都会弹一次更新弹窗，忽略也没用 ——
 * 因为「已提示过这个版本」这个事实从来没有任何存储，只存在于 [UpdateDialogHost]
 * 的 `remember { mutableStateOf(false) }` 里：进程一换、界面一重建就重置。
 *
 * 这里把判定收成纯函数（JVM 单测直接覆盖），存储放到同一个文件的
 * [UpdatePromptStore]（SharedPreferences，与 AppSettings / ServerStorage 同风格，
 * 不引新库）。
 *
 * 语义：
 * - **自动检查**（进 App / 回前台）只在该 versionCode 大于「上次主动提示过的版本」时才弹；
 * - **手动检查**（用户点「检查更新」）只要有更新就弹，不受配额限制 —— 那是用户自己要求的；
 * - 一旦某个版本被提示过，无论用户点了什么（忽略 / 关闭 / 杀后台），同一版本**不再主动弹**；
 * - 出现更高的 versionCode 时自动重新获得一次主动提示配额。
 */
object UpdatePromptPolicy {

    /** 从未主动提示过任何版本（SharedPreferences 的默认值） */
    const val DEFAULT_LAST_PROMPTED_VERSION_CODE = 0

    /** 本次检查是否应该把更新弹窗展示给用户 */
    enum class PromptDecision {
        /** 展示弹窗（并记录配额） */
        PROMPT,

        /** 静默跳过，不打扰用户 */
        SKIP
    }

    /**
     * 是否应该**主动**弹出更新浮层（R1 的核心判据）。
     *
     * @param latestCode 清单里的 versionCode
     * @param lastPromptedCode 上次主动提示过的 versionCode（从磁盘读，默认 0）
     */
    fun shouldPromptAutomatically(latestCode: Int, lastPromptedCode: Int): Boolean =
        latestCode > lastPromptedCode

    /** 记录「已主动提示过该版本」；返回写盘后的值，便于调用方共用同一次比较结果 */
    fun markPrompted(latestCode: Int): Int = latestCode

    /**
     * 统一判定入口：手动检查不受配额限制，自动检查受 R1 约束。
     *
     * 注意：这里只判「弹不弹」。手动检查同样会把版本号记录进配额 ——
     * 用户已经手动看过这个版本，下一次进 App 再主动弹一遍纯属重复打扰
     * （与「一个版本只主动弹一次」的目标一致）。
     */
    fun decide(manual: Boolean, latestCode: Int, lastPromptedCode: Int): PromptDecision =
        if (manual || shouldPromptAutomatically(latestCode, lastPromptedCode)) {
            PromptDecision.PROMPT
        } else {
            PromptDecision.SKIP
        }
}

/**
 * 「上次主动提示过的 versionCode」的持久化（R1）。
 *
 * 为什么单独一个 prefs 文件而不是塞进 AppSettings：本次改造只动更新模块，
 * 不碰设置页的读写链（那边另有一批改动在进行）。键名沿用派工单约定的
 * `update.lastPromptedVersionCode`。
 */
object UpdatePromptStore {

    private const val PREFS_NAME = "UpdateSettings"

    /** 键名（派工单指定，不要改） */
    const val KEY_LAST_PROMPTED_VERSION_CODE = "update.lastPromptedVersionCode"

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 读「上次主动提示过的 versionCode」；从未记录过返回 0 */
    fun lastPromptedVersionCode(context: Context): Int =
        prefs(context).getInt(
            KEY_LAST_PROMPTED_VERSION_CODE,
            UpdatePromptPolicy.DEFAULT_LAST_PROMPTED_VERSION_CODE
        )

    /** 记录某个版本已被主动提示过（跨进程、跨杀后台有效） */
    fun markPrompted(context: Context, versionCode: Int) {
        prefs(context).edit()
            .putInt(KEY_LAST_PROMPTED_VERSION_CODE, UpdatePromptPolicy.markPrompted(versionCode))
            .apply()
    }
}