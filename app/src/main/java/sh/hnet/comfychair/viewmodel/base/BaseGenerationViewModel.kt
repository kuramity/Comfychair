package sh.hnet.comfychair.viewmodel.base

import android.content.Context
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import sh.hnet.comfychair.ComfyUIClient
import sh.hnet.comfychair.WorkflowManager
import sh.hnet.comfychair.model.LoraSelection
import sh.hnet.comfychair.model.contract.CommonGenerationState
import sh.hnet.comfychair.storage.WorkflowValuesStorage
import sh.hnet.comfychair.util.ValidationUtils
import sh.hnet.comfychair.viewmodel.GenerationViewModel

/**
 * Base ViewModel for generation screens (Text-to-Image, Image-to-Image, Text-to-Video, Image-to-Video).
 * Provides common state management, dependencies, and utility methods.
 *
 * @param UiState The UI state type for this ViewModel
 * @param Event The event type for this ViewModel
 */
abstract class BaseGenerationViewModel<UiState : CommonGenerationState, Event> : ViewModel() {

    // State management - subclasses must provide initial state
    protected abstract val initialState: UiState

    protected val _uiState: MutableStateFlow<UiState> by lazy {
        MutableStateFlow(initialState)
    }
    val uiState: StateFlow<UiState> by lazy {
        _uiState.asStateFlow()
    }

    protected val _events = MutableSharedFlow<Event>()
    val events: SharedFlow<Event> = _events.asSharedFlow()

    // Common dependencies
    protected var applicationContext: Context? = null
        private set
    protected var comfyUIClient: ComfyUIClient? = null
        private set
    protected var workflowValuesStorage: WorkflowValuesStorage? = null
        private set

    // Reference to GenerationViewModel for event handling
    protected var generationViewModelRef: GenerationViewModel? = null

    // Track which promptId we've already cleared preview for (prevents duplicate clears on navigation)
    protected var lastClearedForPromptId: String? = null

    // Initialization flag
    private var isInitialized = false

    /**
     * Initialize the ViewModel with common dependencies.
     * Subclasses should override onInitialize() to perform their specific initialization.
     */
    fun initialize(context: Context, client: ComfyUIClient) {
        if (isInitialized) return

        applicationContext = context.applicationContext
        comfyUIClient = client
        WorkflowManager.ensureInitialized(context)
        workflowValuesStorage = WorkflowValuesStorage(context)

        onInitialize()
        isInitialized = true
    }

    /**
     * Called after common initialization is complete.
     * Subclasses should override this to perform their specific initialization
     * (e.g., loading workflows, restoring preferences, restoring last generated media).
     */
    protected abstract fun onInitialize()

    /**
     * Check if the current configuration is valid for generation.
     */
    abstract fun hasValidConfiguration(): Boolean

    /**
     * 本页在 [GenerationViewModel] 里的注册键（各子类 companion object 的 OWNER_ID）。
     */
    protected abstract val generationOwnerId: String

    /**
     * Stop listening for generation events.
     * Note: We keep the refs if generation is still running,
     * as the handler may still be called for completion events.
     */
    fun stopListening(generationViewModel: GenerationViewModel) {
        generationViewModel.unregisterSubmittedCallback(generationOwnerId)
        generationViewModel.unregisterEventHandler(generationOwnerId)
        // Only clear refs if no generation is active (handler was actually unregistered)
        // If generation is running, the handler is kept and needs the refs
        if (!generationViewModel.generationState.value.isGenerating) {
            if (generationViewModelRef == generationViewModel) {
                generationViewModelRef = null
            }
        }
    }

    /**
     * Validate model selection against available models.
     * Returns current if valid, otherwise defaults to first available.
     */
    protected fun validateModelSelection(current: String, available: List<String>): String {
        return ValidationUtils.validateModelSelection(current, available)
    }

    /**
     * CLIP 候选的「格式门控」过滤（实现见 [ValidationUtils.filterClipsByGate]）。
     *
     * 工作流带 `clip_gguf` 开关时：开=只留 .gguf，关=只留非 .gguf；没有该开关则原样返回。
     */
    protected fun filterClipsByGate(clips: List<String>, values: Map<String, String>): List<String> {
        return ValidationUtils.filterClipsByGate(clips, values)
    }

    /** 门控切换后挑合法选中值（原值不在新列表则落到第一项，避免空框）。 */
    protected fun pickClipsAfterGate(current: String, gated: List<String>): String {
        return ValidationUtils.pickClipsAfterGate(current, gated)
    }

    /**
     * Check if the ViewModel has been initialized.
     */
    fun isInitialized(): Boolean = isInitialized

    /**
     * 用新的主 LoRA 链复制当前状态（各页 UiState 的 `copy(loraChain = ...)`）。
     * 供 [updateLoraChain] 使用。
     */
    protected abstract fun withLoraChain(state: UiState, chain: List<LoraSelection>): UiState

    /**
     * 主 LoRA 链的统一更新：纯计算新链 → 无变化短路 → 写状态 → 由调用方保存。
     *
     * 计算函数拿到的是**同一份状态快照**（与 `state.loraChain` 的读取来源一致），
     * 避免二次读 `_uiState.value` 造成两个快照。
     *
     * @param save 写状态后要执行的保存（各页保存方法不同：savePreferences / saveConfiguration）
     * @param compute 以当前状态为输入算出新链（LoraChainManager 无变化时返回原实例）
     */
    protected fun updateLoraChain(
        save: () -> Unit,
        compute: (UiState) -> List<LoraSelection>
    ) {
        val state = _uiState.value
        val newChain = compute(state)
        if (newChain === state.loraChain) return
        _uiState.value = withLoraChain(state, newChain)
        save()
    }
}
