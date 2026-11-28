package sh.hnet.comfychair.workflow

import sh.hnet.comfychair.WorkflowManager

/**
 * 服务器下发预置流与既有工作流的合并谓词（B5）。
 *
 * 下发层（filesDir/overlay/prebuilt_workflows/，isBuiltIn=true 的"预置流下发版"）与
 * 基础列表（内置 + 用户）按 **name** 合并，语义四条：
 *
 * 1. **重名下发优先**：下发条目与**内置**（isBuiltIn=true）条目同名 → 在原位替换内置版
 *    （下发版语义就是"同名预置流的更新版"，JSON 校验准入已保证内容可用）；
 * 2. **用户流优先于下发**：下发条目与**用户自建**（isBuiltIn=false）条目同名 → 丢弃下发版、
 *    保留用户版（用户改过的东西不许被服务器覆盖）；
 * 3. **下发独有 → 追加**到列表尾部；
 * 4. **下发清单内部同名** → 后到覆盖先到（防服务器清单重复条目在界面上出现重复流）。
 *
 * 纯函数（不碰 Context / 磁盘），JVM 直测见 DistributedWorkflowMergeTest；
 * WorkflowManager 的全部列表出口经 [WorkflowManager.mergedWorkflows] 走这里。
 */
object DistributedWorkflowMerge {

    fun merge(base: List<WorkflowManager.Workflow>, distributed: List<WorkflowManager.Workflow>): List<WorkflowManager.Workflow> {
        if (distributed.isEmpty()) return base

        // ① 下发条目先按 name 自归并（LinkedHashMap 保插入序 = 落盘文件名序，重复名后到覆盖先到）
        val distributedByName = LinkedHashMap<String, WorkflowManager.Workflow>()
        for (d in distributed) distributedByName[d.name] = d

        // ② 遍历基础列表：内置同名条目在原位替换为下发版，其余原样保留
        val out = ArrayList<WorkflowManager.Workflow>(base.size)
        val replacedNames = mutableSetOf<String>()
        for (b in base) {
            val replacement = distributedByName[b.name]
            if (b.isBuiltIn && replacement != null) {
                out.add(replacement)
                replacedNames.add(b.name)
            } else {
                out.add(b)
            }
        }

        // ③ 下发独有：追加到尾部；但同名用户流存在时丢弃下发版（用户流优先于下发）
        for ((name, d) in distributedByName) {
            if (name in replacedNames) continue
            val userOwnsName = base.any { !it.isBuiltIn && it.name == name }
            if (!userOwnsName) out.add(d)
        }
        return out
    }
}
