package sh.hnet.comfychair.storage

/**
 * 启动清理任务的准入判定（纯函数，便于 JVM 单测）。
 *
 * 原则是 fail-closed：只要有一侧读取失败，或者服务器数量从有到无却拿不到
 * 用户显式删除的证据，就当数据可能损坏，中止本次清理，绝不清空凭据与参数。
 * 工作流侧同理：内置工作流恒存在，列表为空必然是读取失败（静默 catch 吞异常），
 * 继续清理会把全部工作流参数当成孤儿数据删掉。
 */
object MaintenancePlan {

    /**
     * 工作流加载结果换算：加载过程中出现过异常即视为「读取失败」（fail-closed）。
     *
     * 抽成纯函数便于单测：只要把这里改成恒 true（永远视为成功），
     * `MaintenancePlanTest` 里对应的用例就会变红。
     */
    fun workflowsLoadOk(lastLoadHadErrors: Boolean): Boolean = !lastLoadHadErrors

    sealed interface Decision {
        /** 中止清理，[reason] 用于日志。 */
        data class Abort(val reason: String) : Decision

        /** 允许执行清理。 */
        data object Proceed : Decision
    }

    fun decide(
        serversOk: Boolean,
        workflowsOk: Boolean,
        workflowCount: Int,
        serverCount: Int,
        previousServerCount: Int,
        explicitDeletion: Boolean
    ): Decision = when {
        !serversOk -> Decision.Abort("服务器列表读取失败")
        !workflowsOk -> Decision.Abort("工作流列表读取失败")
        // 内置工作流恒存在（30 个），空列表只可能是读取失败后的静默空集
        workflowCount == 0 -> Decision.Abort("工作流列表为空，判定为读取失败")
        serverCount == 0 && previousServerCount > 0 && !explicitDeletion ->
            Decision.Abort("服务器数由 $previousServerCount 变为 0 且无显式删除证据")
        else -> Decision.Proceed
    }
}