package sh.hnet.comfychair.taglibrary.state

import kotlinx.coroutines.flow.StateFlow

/**
 * 提示词桥接目标工厂。
 *
 * 4 个生成页面 ViewModel 的提示词字段名与 setter 完全一致（positivePrompt /
 * negativePrompt + onXxxChange），差异仅在于 ITI 的负向提示词需要按模式分发。
 * 这里用一个工厂收敛样板代码，各 ViewModel 只需在 init 中调用一次。
 */
object PromptTargetFactory {

    /**
     * 创建桥接目标。
     *
     * @param targetOwnerId 生成页面归属 ID（与 ViewModel.OWNER_ID 一致）
     * @param workflowId    当前工作流 ID 流（用于标签库绑定状态切换）
     * @param workflowName  当前工作流名流（用于界面提示）
     * @param readPositive  读取正向提示词
     * @param writePositive 写入正向提示词（同步到输入框）
     * @param readNegative  读取负向提示词
     * @param writeNegative 写入负向提示词
     */
    fun create(
        targetOwnerId: String,
        workflowId: StateFlow<String>,
        workflowName: StateFlow<String>,
        readPositive: () -> String,
        writePositive: (String) -> Unit,
        readNegative: () -> String,
        writeNegative: (String) -> Unit
    ): PromptBridge.Target = object : PromptBridge.Target {
        override val ownerId: String = targetOwnerId
        override val workflowId: StateFlow<String> = workflowId
        override val workflowName: StateFlow<String> = workflowName

        override fun readPrompt(channel: PromptBridge.Channel): String = when (channel) {
            PromptBridge.Channel.POSITIVE -> readPositive()
            PromptBridge.Channel.NEGATIVE -> readNegative()
        }

        override fun writePrompt(channel: PromptBridge.Channel, text: String) {
            when (channel) {
                PromptBridge.Channel.POSITIVE -> writePositive(text)
                PromptBridge.Channel.NEGATIVE -> writeNegative(text)
            }
        }
    }
}
