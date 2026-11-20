package sh.hnet.comfychair.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import sh.hnet.comfychair.R
import sh.hnet.comfychair.model.PromptPreset
import sh.hnet.comfychair.model.ScreenType
import sh.hnet.comfychair.storage.PromptPresetStorage

/**
 * UI state for prompt preset management.
 */
data class PromptPresetUiState(
    val favorites: List<PromptPreset> = emptyList(),
    val allPresets: List<PromptPreset> = emptyList(),
    val availableTags: Set<String> = emptySet(),
    val searchQuery: String = "",
    val selectedTags: Set<String> = emptySet(),
    val filterFavoritesOnly: Boolean = false,
    val activePresetId: String? = null,
    val showLibrarySideSheet: Boolean = false,
    val showSaveDialog: Boolean = false,
    val editingPreset: PromptPreset? = null,
    val currentPromptForSave: String = "",
    /** 保存对话框里的负面提示词初值（与正面一起存进同一条预设） */
    val currentNegativePromptForSave: String = ""
)

/**
 * Events emitted by the PromptPresetViewModel.
 */
sealed class PromptPresetEvent {
    /**
     * 一条预设被选中，要写回当前页面。
     *
     * 正面与负面**一起给**：存的时候是一起存的，还原时也要一起还原，
     * 否则用户换了预设还得手动去补负面（两段本来就属于同一套提示词）。
     * [negativePrompt] 可能是空串 —— 表示这条预设没有负面，页面据此写空/保持原样由调用方决定。
     */
    data class PresetApplied(val prompt: String, val negativePrompt: String) : PromptPresetEvent()
    data class ShowToast(val messageResId: Int) : PromptPresetEvent()
    data object MaxFavoritesReached : PromptPresetEvent()
    data object ResetPrompt : PromptPresetEvent()
}

/**
 * ViewModel for managing prompt presets.
 * Each instance handles presets for a specific screen type.
 */
class PromptPresetViewModel : ViewModel() {
    private var storage: PromptPresetStorage? = null
    private var currentScreenType: ScreenType = ScreenType.TEXT_TO_IMAGE

    /** 本次 ViewModel 生命周期内是否已提示过「存储损坏」，避免每次刷新都重复弹 toast。 */
    private var corruptionNoticeShown = false

    private val _uiState = MutableStateFlow(PromptPresetUiState())
    val uiState: StateFlow<PromptPresetUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<PromptPresetEvent>()
    val events = _events.asSharedFlow()

    /**
     * Initialize the ViewModel with context and screen type.
     * Must be called before using other methods.
     */
    fun initialize(context: Context, screenType: ScreenType) {
        storage = PromptPresetStorage(context)
        currentScreenType = screenType
        refreshPresets()
    }

    /**
     * Refresh presets from storage.
     * Call this when returning to the screen to catch external changes.
     */
    fun refreshPresets() {
        val storage = storage ?: return
        val newAllPresets = storage.getPresetsForScreen(currentScreenType)
        _uiState.update { state ->
            // Clear activePresetId if the preset no longer exists
            val validActivePresetId = if (state.activePresetId != null &&
                newAllPresets.none { it.id == state.activePresetId }) {
                null
            } else {
                state.activePresetId
            }
            state.copy(
                favorites = storage.getFavoritesForScreen(currentScreenType),
                allPresets = newAllPresets,
                availableTags = storage.getTagsForScreen(currentScreenType),
                activePresetId = validActivePresetId
            )
        }
        // 存储损坏时列表会显示为空：主动说明一次原因，避免用户以为预设被清空了
        if (storage.didLastParseFail() && !corruptionNoticeShown) {
            corruptionNoticeShown = true
            viewModelScope.launch {
                _events.emit(PromptPresetEvent.ShowToast(R.string.error_local_store_corrupted))
            }
        }
    }

    /**
     * Update search query for filtering.
     */
    fun onSearchQueryChange(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
    }

    /**
     * Toggle a tag in the filter selection.
     */
    fun onTagToggle(tag: String) {
        _uiState.update { state ->
            val newTags = if (tag in state.selectedTags) {
                state.selectedTags - tag
            } else {
                state.selectedTags + tag
            }
            state.copy(selectedTags = newTags)
        }
    }

    /**
     * Clear all tag filters.
     */
    fun clearTagFilters() {
        _uiState.update { it.copy(selectedTags = emptySet()) }
    }

    /**
     * Toggle the favorites-only filter.
     */
    fun onToggleFavoritesFilter() {
        _uiState.update { it.copy(filterFavoritesOnly = !it.filterFavoritesOnly) }
    }

    /**
     * Get presets filtered by current search query, selected tags, and favorites filter.
     */
    fun getFilteredPresets(): List<PromptPreset> {
        val state = _uiState.value
        return state.allPresets.filter { preset ->
            val matchesFavorites = !state.filterFavoritesOnly || preset.isFavorite
            // 负面也参与搜索：用户记得住负面里的关键词（"nsfw"之类）却未必记得名字
            val matchesSearch = state.searchQuery.isEmpty() ||
                preset.name.contains(state.searchQuery, ignoreCase = true) ||
                preset.prompt.contains(state.searchQuery, ignoreCase = true) ||
                preset.negativePrompt.contains(state.searchQuery, ignoreCase = true)
            val matchesTags = state.selectedTags.isEmpty() ||
                preset.tags.any { it in state.selectedTags }
            matchesFavorites && matchesSearch && matchesTags
        }
    }

    /**
     * Select and apply a preset.
     * Also dismisses the library side sheet if it was open.
     */
    fun onPresetSelected(presetId: String) {
        val preset = storage?.getPreset(presetId) ?: return
        _uiState.update { state ->
            state.copy(
                activePresetId = presetId,
                showLibrarySideSheet = false,
                searchQuery = "",
                selectedTags = emptySet()
            )
        }
        viewModelScope.launch {
            _events.emit(PromptPresetEvent.PresetApplied(preset.prompt, preset.negativePrompt))
        }
    }

    /**
     * Request prompt reset to default.
     * The actual reset is handled by the generation screen's ViewModel.
     */
    fun resetPrompt() {
        _uiState.update { it.copy(activePresetId = null) }
        viewModelScope.launch {
            _events.emit(PromptPresetEvent.ResetPrompt)
        }
    }

    /**
     * Toggle favorite status of a preset.
     */
    fun onToggleFavorite(presetId: String) {
        val storage = storage ?: return
        val success = storage.toggleFavorite(presetId)
        if (!success) {
            // 失败有两种原因：收藏已满 / 存储损坏导致写入被拒绝；两者的提示不能混用
            val corrupted = storage.didLastParseFail()
            if (corrupted) corruptionNoticeShown = true
            viewModelScope.launch {
                _events.emit(
                    if (corrupted) {
                        PromptPresetEvent.ShowToast(R.string.error_local_store_corrupted)
                    } else {
                        PromptPresetEvent.MaxFavoritesReached
                    }
                )
            }
        }
        refreshPresets()
    }

    /**
     * Save a new preset or update an existing one.
     *
     * 正面与负面一起存进同一条预设（两个字段），[negativePrompt] 允许为空。
     */
    fun onSavePreset(name: String, prompt: String, negativePrompt: String, tags: List<String>) {
        val storage = storage ?: return
        val editing = _uiState.value.editingPreset
        val saved: Boolean

        if (editing != null) {
            // Update existing preset
            val updated = editing.copy(
                name = name,
                prompt = prompt,
                negativePrompt = negativePrompt,
                tags = tags
            )
            saved = storage.updatePreset(updated)
        } else {
            // Create new preset
            val preset = PromptPreset.create(
                screenType = currentScreenType,
                name = name,
                prompt = prompt,
                tags = tags,
                negativePrompt = negativePrompt
            )
            saved = storage.addPreset(preset)
        }

        if (!saved) {
            // 存储损坏时写入被拒绝：绝不弹「已保存」，也不关闭对话框，
            // 让用户明确知道内容没有落盘（原数据已备份，修复后仍可找回）
            corruptionNoticeShown = true
            refreshPresets()
            viewModelScope.launch {
                _events.emit(PromptPresetEvent.ShowToast(R.string.error_local_store_corrupted))
            }
            return
        }

        _uiState.update { it.copy(showSaveDialog = false, editingPreset = null) }
        refreshPresets()

        viewModelScope.launch {
            _events.emit(PromptPresetEvent.ShowToast(R.string.msg_prompt_preset_saved))
        }
    }

    /**
     * Duplicate a preset with a "(n)" suffix where n is the next available number.
     */
    fun onDuplicatePreset(presetId: String) {
        val storage = storage ?: return
        val original = storage.getPreset(presetId) ?: return
        val existingNames = _uiState.value.allPresets.map { it.name }.toSet()
        val duplicateName = generateUniqueName(original.name, existingNames)

        val duplicate = PromptPreset.create(
            screenType = original.screenType,
            name = duplicateName,
            prompt = original.prompt,
            tags = original.tags,
            // 副本要连负面一起复制：只复制正面会让"另存为副本"变成半个提示词
            negativePrompt = original.negativePrompt
        )
        val saved = storage.addPreset(duplicate)
        if (!saved) {
            corruptionNoticeShown = true
            refreshPresets()
            viewModelScope.launch {
                _events.emit(PromptPresetEvent.ShowToast(R.string.error_local_store_corrupted))
            }
            return
        }
        refreshPresets()

        viewModelScope.launch {
            _events.emit(PromptPresetEvent.ShowToast(R.string.msg_prompt_preset_duplicated))
        }
    }

    /**
     * Generate a unique name by appending (n) where n is the next available number.
     * The original name is kept intact, e.g. "My Preset (2)" becomes "My Preset (2) (1)".
     */
    private fun generateUniqueName(originalName: String, existingNames: Set<String>): String {
        // Find all numbers already used with this exact base name
        val numberPattern = Regex("""^\Q$originalName\E \((\d+)\)$""")
        val usedNumbers = existingNames.mapNotNull { name ->
            numberPattern.matchEntire(name)?.groupValues?.get(1)?.toIntOrNull()
        }.toSet()

        // Find the next available number starting from 1
        var nextNumber = 1
        while (nextNumber in usedNumbers) {
            nextNumber++
        }

        return "$originalName ($nextNumber)"
    }

    /**
     * Delete a preset.
     */
    fun onDeletePreset(presetId: String) {
        val storage = storage ?: return
        val deleted = storage.deletePreset(presetId)
        if (!deleted) {
            // 删除同样可能被「存储损坏」拦下：不更新选中态、不弹「已删除」
            corruptionNoticeShown = true
            refreshPresets()
            viewModelScope.launch {
                _events.emit(PromptPresetEvent.ShowToast(R.string.error_local_store_corrupted))
            }
            return
        }
        if (_uiState.value.activePresetId == presetId) {
            _uiState.update { it.copy(activePresetId = null) }
        }
        refreshPresets()

        viewModelScope.launch {
            _events.emit(PromptPresetEvent.ShowToast(R.string.msg_prompt_preset_deleted))
        }
    }

    /**
     * Show the save dialog for creating a new preset.
     */
    fun showSaveDialog(currentPrompt: String, currentNegativePrompt: String) {
        _uiState.update { state ->
            state.copy(
                showSaveDialog = true,
                editingPreset = null,
                currentPromptForSave = currentPrompt,
                currentNegativePromptForSave = currentNegativePrompt
            )
        }
    }

    /**
     * Show the edit dialog for an existing preset.
     */
    fun showEditDialog(presetId: String) {
        val preset = storage?.getPreset(presetId) ?: return
        _uiState.update { state ->
            state.copy(
                showSaveDialog = true,
                editingPreset = preset,
                currentPromptForSave = preset.prompt,
                currentNegativePromptForSave = preset.negativePrompt
            )
        }
    }

    /**
     * Dismiss the save/edit dialog.
     */
    fun dismissSaveDialog() {
        _uiState.update { state ->
            state.copy(
                showSaveDialog = false,
                editingPreset = null,
                currentPromptForSave = "",
                currentNegativePromptForSave = ""
            )
        }
    }

    /**
     * Show the prompt library side sheet.
     */
    fun showLibrary() {
        _uiState.update { it.copy(showLibrarySideSheet = true) }
    }

    /**
     * Dismiss the prompt library side sheet.
     */
    fun dismissLibrary() {
        _uiState.update { state ->
            state.copy(
                showLibrarySideSheet = false,
                searchQuery = "",
                selectedTags = emptySet(),
                filterFavoritesOnly = false
            )
        }
    }

    /**
     * Clear the active preset indicator.
     * Called when user modifies the prompt manually.
     */
    fun clearActivePreset() {
        _uiState.update { it.copy(activePresetId = null) }
    }

    /**
     * Check if a name is already taken for the current screen type.
     */
    fun isNameTaken(name: String, excludeId: String? = null): Boolean {
        return storage?.isNameTaken(name, currentScreenType, excludeId) ?: false
    }
}
