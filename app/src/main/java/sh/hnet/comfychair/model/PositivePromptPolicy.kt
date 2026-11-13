package sh.hnet.comfychair.model

/**
 * 「正面提示词跟随工作流」的取值策略 + 锁开关的**决策纯函数**。
 *
 * 为什么要有这个对象：这套判断本来要同时出现在四个地方（TTI 的载入/保存、ITI 修复流与编辑流的
 * 载入/保存），每处各写一遍 if/else 必然写出不一样的口径；而且 ViewModel 主体在 JVM 单测里
 * 跑不起来（依赖 Android/ConnectionManager），放进 ViewModel 就等于这段逻辑**不可测**。
 * 抽成不依赖任何东西的纯函数后，语义在 `PositivePromptPolicyTest` 里逐条钉死。
 *
 * 锁的定义（用户原话直译）：**锁上时切换工作流完全不动正面提示词——不保存也不载入**。
 * 推论是锁定期间手打的词不落盘、杀进程会丢；这是"这份词我要跨工作流一直用"这一诉求的
 * 自然代价，不是缺陷（见测试里固化这条语义的用例）。
 */
object PositivePromptPolicy {

    /**
     * 切到新工作流时，界面上的正面提示词该取什么值。
     *
     * @param locked 当前是否锁定
     * @param current 切换前界面上的值（锁定时要原样保留的就是它）
     * @param saved 目标工作流已保存的正面词；`null` = 这个工作流没存过，`""` = 用户明确清空过
     * @param default 目标工作流的默认正面词（来自工作流 JSON 的 `defaults.positive_prompt`）
     */
    fun resolveOnLoad(locked: Boolean, current: String, saved: String?, default: String?): String {
        if (locked) return current
        // 注意顺序：`saved` 只要不是 null（哪怕空串）就优先，空串代表"用户明确清空过"，
        // 不该被默认词顶回来
        return saved ?: default ?: ""
    }

    /**
     * 保存某个工作流时，`positivePrompt` 字段该写什么。
     *
     * @param locked 当前是否锁定；锁定时**不更新该字段**（写回 [existing] 原样）
     * @param current 界面上当前的正面词
     * @param existing 该工作流记录里已有的正面词（用于锁定/非激活模式时原样保留）
     * @param isActiveMode 当前工作流是否就是界面上正在编辑的那一个。
     *   只有图生图会传 false：它只有一个正面输入框却有两套工作流记录（修复流 / 编辑流），
     *   保存非激活那套时必须原样保留，否则来回切模式就会互相污染。
     * @return 要写入的值；`null` 表示不写该字段（下次载入回退到工作流默认词）
     */
    fun resolveOnSave(locked: Boolean, current: String, existing: String?, isActiveMode: Boolean): String? {
        if (!isActiveMode) return existing
        if (locked) return existing
        // 空串按"没有值"处理，与 negativePrompt 的 takeIf { it.isNotEmpty() } 一致
        return current.takeIf { it.isNotEmpty() }
    }

    /**
     * 老版本的**全局**正面词该不该迁移给某个工作流（一次性迁移的判断部分）。
     *
     * 两条规则，缺一不可：
     * 1. 老键确实有值（`null` = 从来没写过，没什么可迁的）；
     * 2. 目标工作流**还没有**正面词记录。已有值时不覆盖——用户的既有数据优先于迁移，
     *    误覆盖等于把用户给这个工作流单独写过的词抹掉。
     *
     * 注意 `existingSaved == ""` 也算"已有记录"（用户明确清空过），同样不覆盖。
     */
    fun shouldMigrateLegacy(legacy: String?, existingSaved: String?): Boolean =
        legacy != null && existingSaved == null
}
