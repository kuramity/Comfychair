package sh.hnet.comfychair.viewmodel.base

/**
 * 生成页 → 总控的事件汇入口（B1 自 BaseGenerationViewModel 解纠缠）。
 *
 * base 依赖本接口而非 [sh.hnet.comfychair.viewmodel.GenerationViewModel] 具体类，
 * 使 viewmodel.base 不再 import 父包、可独立演进；接口只暴露 base（含 stopListening）
 * 实际用到的成员（2026-11-28 grep 实证：completeGeneration / unregisterSubmittedCallback /
 * unregisterEventHandler / isGenerating 四处）。
 *
 * 默认参数在此声明（Kotlin 规则：override 成员不得重复声明默认值），
 * 与原 `GenerationViewModel.completeGeneration(promptId: String? = null)` 的无参调用兼容。
 */
interface GenerationCompletionRelay {
    fun completeGeneration(promptId: String? = null)
    fun unregisterSubmittedCallback(ownerId: String)
    fun unregisterEventHandler(ownerId: String)

    /** 生成是否进行中（对 [sh.hnet.comfychair.viewmodel.GenerationViewModel] 的 `generationState.value.isGenerating` 的窄接口） */
    val isGeneratingNow: Boolean
}
