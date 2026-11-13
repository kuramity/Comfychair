package sh.hnet.comfychair.prompt

import android.content.Context

/**
 * 技能提示词选择的持久化：按**工作流 id** 记住用户选了哪个 md。
 *
 * 没记录过的工作流走 [SkillPromptRepository.defaultFor] 的默认映射（文生图/图像编辑各一份），
 * 所以用户不动它时不需要任何存储；改过之后的选择会一直跟着这个工作流。
 */
object SkillPromptStore {

    private const val PREFS = "SkillPromptPrefs"
    private const val KEY_PREFIX = "skill_"

    /** 返回用户为该工作流选定的技能文件名；从未选过时返回 null（调用方走默认映射）。 */
    fun get(context: Context, workflowId: String): String? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getString(KEY_PREFIX + workflowId, null)
    }

    /** 记住选择（传 [SkillPromptRepository.NONE] 表示明确选择「不注入」）。 */
    fun set(context: Context, workflowId: String, fileName: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PREFIX + workflowId, fileName)
            .apply()
    }

    /** 清掉自定义选择，回到默认映射。 */
    fun clear(context: Context, workflowId: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_PREFIX + workflowId)
            .apply()
    }
}
