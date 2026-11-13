package sh.hnet.comfychair.taglibrary.state

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import sh.hnet.comfychair.storage.TagFavoritesStore
import sh.hnet.comfychair.taglibrary.data.CooccurTable
import sh.hnet.comfychair.taglibrary.data.TagRepository
import sh.hnet.comfychair.taglibrary.model.AppliedTag
import sh.hnet.comfychair.taglibrary.model.Tag
import sh.hnet.comfychair.taglibrary.model.TagCategory

/**
 * 标签库 ViewModel。
 *
 * 职责：
 * - 初始化标签库（解压数据库 + 构建内存索引）
 * - 搜索（120ms 防抖 + 请求序号防乱序）
 * - 维护"当前工作流"的标签记录（正向/负向），切换工作流时自动切换
 * - 通过 [PromptBridge] 与生成页面双向同步提示词
 *
 * 工作流绑定：状态以 `ownerId::workflowId` 为键存放于内存 [workflowStates]，
 * 并持久化到 SharedPreferences，App 重启后仍可恢复（需求 5.1）。
 */
class TagLibraryViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "TagLibraryVM"

        /** 输入防抖时长（需求 4.1.2）。 */
        private const val DEBOUNCE_MS = 120L

        private const val PREFS_NAME = "tag_library_state"
        private const val KEY_STATE_PREFIX = "wf_state_"
    }

    /** 标签库事件（Toast 提示等）。 */
    sealed class Event {
        /** 追加失败：当前没有可写入的生成页面。 */
        data object NoTarget : Event()

        /** 追加成功（携带通道与标签名）。 */
        data class Added(val channel: PromptBridge.Channel, val tagName: String) : Event()

        /** 按位置插入成功（携带通道、标签名与插入位置）。 */
        data class Inserted(
            val channel: PromptBridge.Channel,
            val tagName: String,
            val segmentIndex: Int
        ) : Event()

        /** 已复制到剪贴板（未写入提示词区）。 */
        data class Copied(val tagName: String) : Event()

        /** 复制到剪贴板失败。 */
        data object CopyFailed : Event()

        /** 数据库初始化失败。 */
        data class InitFailed(val message: String) : Event()
    }

    private val repository = TagRepository.getInstance()

    /** 收藏 / 最近使用（纯本地文件，不依赖标签库数据库）。 */
    private val favoritesStore by lazy { TagFavoritesStore(getApplication()) }

    private val _uiState = MutableStateFlow(TagLibraryUiState())
    val uiState: StateFlow<TagLibraryUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<Event>(extraBufferCapacity = 4)
    val events: SharedFlow<Event> = _events.asSharedFlow()

    /** 当前工作流的标签记录（键 = ownerId::workflowId）。 */
    private val workflowStates = HashMap<String, WorkflowTagState>()

    private var searchJob: Job? = null
    private var workflowJob: Job? = null

    /** 请求序号：只接受最新一次搜索的结果（需求 4.1.3）。 */
    private var searchSequence = 0

    /** 当前绑定键（用于切换时保存）。 */
    private var currentKey: String = ""

    /** 是否已触发过初始化（避免重复构建索引）。 */
    private var initializeRequested = false

    init {
        // 注意：这里【不】初始化数据库与索引。
        // 索引构建需要读取 170 万行标签并建立约 10 万条内存索引（实测 3-12 秒），
        // 若在 App 启动时执行，会与图片库/画廊的图片加载争抢 CPU 与磁盘 IO，
        // 导致右下角图片库明显变慢。改为由 TagLibraryScreen 首次进入时触发（见 ensureInitialized）。
        observeActiveTarget()

        // 收藏与最近使用是纯本地文件，不依赖数据库：立刻读出，进标签库页就能看到
        _uiState.update {
            it.copy(favorites = favoritesStore.loadFavorites(), recents = favoritesStore.loadRecents())
        }
    }

    // ==================== 初始化 ====================

    /**
     * 懒初始化：由标签库页面首次进入时调用。
     *
     * 重复调用无副作用（内部只执行一次）。
     */
    fun ensureInitialized() {
        if (initializeRequested) return
        initializeRequested = true
        initialize()
    }

    /** 加载数据库与内存索引（IO 线程）。 */
    private fun initialize() {
        _uiState.update { it.copy(isIndexLoading = true) }
        viewModelScope.launch {
            val result = repository.ensureReady(getApplication())
            result.fold(
                onSuccess = {
                    Log.i(TAG, "标签库就绪：高频标签 ${it.memoryIndex?.hotTagCount ?: 0} 条")
                    // 常见搭配表（共现库提取的 456KB）随标签库一起就绪
                    CooccurTable.ensureLoaded(getApplication())
                    _uiState.update { it.copy(isDbReady = true, isIndexLoading = false, errorMessage = null) }
                    // 就绪后按当前关键词补一次搜索（首次进入时关键词通常为空，展示默认列表）
                    refreshResults()
                },
                onFailure = { e ->
                    Log.e(TAG, "标签库初始化失败", e)
                    _uiState.update {
                        it.copy(
                            isDbReady = false,
                            isIndexLoading = false,
                            errorMessage = e.message ?: "标签库加载失败"
                        )
                    }
                    _events.tryEmit(Event.InitFailed(e.message ?: "标签库加载失败"))
                }
            )
        }
    }

    /** 用户手动重试初始化（需求 8.4）。 */
    fun retryInitialize() {
        if (_uiState.value.isIndexLoading) return
        repository.release()
        initializeRequested = true
        initialize()
    }

    // ==================== 工作流绑定 ====================

    /** 监听当前活动生成页面变化。 */
    private fun observeActiveTarget() {
        viewModelScope.launch {
            PromptBridge.activeOwnerId.collect { ownerId -> bindOwner(ownerId) }
        }
    }

    private fun bindOwner(ownerId: String) {
        saveCurrentWorkflowState()
        val target = PromptBridge.targetOf(ownerId)
        if (target == null) {
            currentKey = ""
            _uiState.update {
                it.copy(targetOwnerId = "", targetWorkflowId = "", targetWorkflowName = "")
            }
            return
        }
        // 监听该页面的工作流切换
        workflowJob?.cancel()
        workflowJob = viewModelScope.launch {
            target.workflowId.collect { workflowId ->
                loadWorkflowState(ownerId, workflowId, target.workflowName.value)
            }
        }
    }

    /** 切换/加载指定工作流的标签记录。 */
    private fun loadWorkflowState(ownerId: String, workflowId: String, workflowName: String) {
        val key = "$ownerId::$workflowId"
        if (key == currentKey) {
            // 同一工作流，仅更新显示名
            _uiState.update { it.copy(targetWorkflowName = workflowName) }
            return
        }
        saveCurrentWorkflowState()
        currentKey = key

        val state = workflowStates.getOrPut(key) { loadPersistedState(key) }
        _uiState.update {
            it.copy(
                targetOwnerId = ownerId,
                targetWorkflowId = workflowId,
                targetWorkflowName = workflowName,
                keyword = state.keyword,
                results = emptyList(),
                positiveTags = state.positiveTags,
                negativeTags = state.negativeTags,
                builderExpanded = state.builderExpanded
            )
        }
        // 恢复该工作流上次的搜索状态（需求 4.1.6）
        if (state.keyword.isNotBlank()) {
            viewModelScope.launch { performSearch(state.keyword) }
        }
    }

    /** 保存当前工作流状态（切换前调用）。 */
    private fun saveCurrentWorkflowState() {
        if (currentKey.isEmpty()) return
        val s = _uiState.value
        val state = WorkflowTagState(
            positiveTags = s.positiveTags,
            negativeTags = s.negativeTags,
            keyword = s.keyword,
            builderExpanded = s.builderExpanded
        )
        workflowStates[currentKey] = state
        persistState(currentKey, state)
    }

    // ==================== 搜索 ====================

    /** 搜索框内容变化（防抖 120ms）。 */
    fun onKeywordChange(value: String) {
        _uiState.update { it.copy(keyword = value) }
        searchJob?.cancel()
        if (value.isBlank()) {
            searchSequence++  // 使进行中的请求结果失效
            _uiState.update { it.copy(results = emptyList(), isSearching = false) }
            return
        }
        searchJob = viewModelScope.launch {
            delay(DEBOUNCE_MS)
            performSearch(value)
        }
    }

    /** 立即搜索（键盘"搜索"键 / 点击搜索按钮）。 */
    fun searchNow() {
        val keyword = _uiState.value.keyword
        searchJob?.cancel()
        viewModelScope.launch { performSearch(keyword) }
    }

    /** 切换分类过滤。 */
    fun onCategoryChange(category: TagCategory?) {
        _uiState.update { it.copy(selectedCategory = category) }
        viewModelScope.launch { performSearch(_uiState.value.keyword) }
    }

    /** 清空关键词与结果。 */
    fun clearKeyword() {
        searchJob?.cancel()
        searchSequence++
        _uiState.update { it.copy(keyword = "", results = emptyList(), isSearching = false) }
    }

    /** 执行搜索（带序号防乱序）。 */
    private suspend fun performSearch(keyword: String) {
        val searcher = repository.searcherOrNull() ?: return
        val sequence = ++searchSequence
        _uiState.update { it.copy(isSearching = true) }
        val category = _uiState.value.selectedCategory
        val favoritesOnly = _uiState.value.favoritesOnly
        val favorites = _uiState.value.favorites

        val results = withContext(Dispatchers.IO) {
            val base = if (keyword.isBlank()) {
                // 只看收藏且没输关键词：直接按收藏名取详情（收藏通常只有几十个）
                if (favoritesOnly && favorites.isNotEmpty()) {
                    searcher.loadByNames(favorites.toList())
                } else {
                    searcher.loadDefault(category)
                }
            } else {
                searcher.search(keyword, category)
            }
            // 有关键词时，"只看收藏"是在搜索结果里再过滤一道
            if (favoritesOnly) base.filter { it.name in favorites } else base
        }
        if (sequence != searchSequence) return  // 旧结果丢弃

        _uiState.update { it.copy(results = results, isSearching = false) }
    }

    /** 索引就绪后刷新当前结果。 */
    private fun refreshResults() {
        viewModelScope.launch { performSearch(_uiState.value.keyword) }
    }

    // ==================== 标签操作 ====================

    /**
     * 添加标签到指定通道（需求 4.3）。
     *
     * 同时：写入提示词末尾 + 记录到已添加列表（允许重复）。
     */
    fun addTag(channel: PromptBridge.Channel, tagName: String) {
        val applied = AppliedTag(tagName, AppliedTag.DEFAULT_WEIGHT)
        if (!PromptBridge.appendTag(channel, tagName, applied.weight)) {
            _events.tryEmit(Event.NoTarget)
            return
        }
        _uiState.update { state ->
            if (channel == PromptBridge.Channel.POSITIVE) {
                state.copy(positiveTags = state.positiveTags + applied)
            } else {
                state.copy(negativeTags = state.negativeTags + applied)
            }
        }
        saveCurrentWorkflowState()
        recordRecent(tagName)
        _events.tryEmit(Event.Added(channel, tagName))
    }

    /**
     * 把标签插入到提示词的**指定位置**（而不只是末尾）。
     *
     * @param segmentIndex 插到第几段**之前**（0 = 最前面；等于段数时等价于追加到末尾）
     */
    fun insertTagAt(channel: PromptBridge.Channel, tagName: String, segmentIndex: Int) {
        val applied = AppliedTag(tagName, AppliedTag.DEFAULT_WEIGHT)
        if (!PromptBridge.insertTagAt(channel, tagName, applied.weight, segmentIndex)) {
            _events.tryEmit(Event.NoTarget)
            return
        }
        _uiState.update { state ->
            if (channel == PromptBridge.Channel.POSITIVE) {
                state.copy(positiveTags = state.positiveTags + applied)
            } else {
                state.copy(negativeTags = state.negativeTags + applied)
            }
        }
        saveCurrentWorkflowState()
        recordRecent(tagName)
        _events.tryEmit(Event.Inserted(channel, tagName, segmentIndex))
    }

    /** 只把标签复制到系统剪贴板，**不写入任何提示词区**（粘贴位置由用户自己决定）。 */
    fun copyTagToClipboard(tagName: String) {
        if (tagName.isBlank()) return
        val clipboard = getApplication<android.app.Application>()
            .getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
        val ok = if (clipboard == null) {
            false
        } else {
            try {
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("tag", tagName))
                true
            } catch (e: Exception) {
                android.util.Log.w("TagLibraryVM", "写入剪贴板失败", e)
                false
            }
        }
        recordRecent(tagName)
        _events.tryEmit(if (ok) Event.Copied(tagName) else Event.CopyFailed)
    }

    /** 当前提示词按逗号切出的各段（供"选择插入位置"界面展示）。 */
    fun segmentsOf(channel: PromptBridge.Channel): List<String> =
        PromptBridge.readPromptOf(channel)?.let { PromptBridge.splitSegments(it) } ?: emptyList()

    // ==================== 收藏与最近使用 ====================

    /** 收藏 / 取消收藏一个标签。 */
    fun toggleFavorite(tagName: String) {
        val current = _uiState.value.favorites
        val next = if (tagName in current) current - tagName else current + tagName
        favoritesStore.saveFavorites(next)
        _uiState.update { it.copy(favorites = next) }
    }

    /** 是否只看收藏（与关键词、分类筛选叠加）。 */
    fun setFavoritesOnly(value: Boolean) {
        if (_uiState.value.favoritesOnly == value) return
        _uiState.update { it.copy(favoritesOnly = value) }
        refreshResults()
    }

    /** 记一次使用：加入提示词时调用（最新的排最前，最多保留 30 条）。 */
    private fun recordRecent(tagName: String) {
        val next = favoritesStore.pushRecent(tagName)
        _uiState.update { it.copy(recents = next) }
    }

    /**
     * 修改某个已添加标签的权重（需求 4.4.3）。
     *
     * 同步更新提示词中该标签（按标签名匹配第一个匹配项）。
     */
    fun updateTagWeight(channel: PromptBridge.Channel, index: Int, newWeight: Float) {
        val list = tagsOf(channel)
        if (index !in list.indices) return
        val target = list[index]
        val clamped = newWeight.coerceIn(MIN_WEIGHT, MAX_WEIGHT)
        if (clamped == target.weight) return

        // 先同步提示词，再更新记录
        PromptBridge.updateTagWeight(channel, target.name, clamped)
        replaceTags(channel, list.toMutableList().also { it[index] = target.copy(weight = clamped) })
    }

    /**
     * 从已添加列表移除标签（需求 4.4.4：单向删除，不改提示词）。
     */
    fun removeTag(channel: PromptBridge.Channel, index: Int) {
        val list = tagsOf(channel)
        if (index !in list.indices) return
        replaceTags(channel, list.toMutableList().also { it.removeAt(index) })
    }

    /** 切换构建器展开/收起（需求 4.4.5）。 */
    fun toggleBuilder() {
        _uiState.update { it.copy(builderExpanded = !it.builderExpanded) }
        saveCurrentWorkflowState()
    }

    private fun tagsOf(channel: PromptBridge.Channel): List<AppliedTag> =
        if (channel == PromptBridge.Channel.POSITIVE) _uiState.value.positiveTags
        else _uiState.value.negativeTags

    private fun replaceTags(channel: PromptBridge.Channel, tags: List<AppliedTag>) {
        _uiState.update { state ->
            if (channel == PromptBridge.Channel.POSITIVE) state.copy(positiveTags = tags)
            else state.copy(negativeTags = tags)
        }
        saveCurrentWorkflowState()
    }

    // ==================== 持久化 ====================

    private data class WorkflowTagState(
        val positiveTags: List<AppliedTag>,
        val negativeTags: List<AppliedTag>,
        val keyword: String,
        val builderExpanded: Boolean
    )

    private fun prefs() = getApplication<Application>()
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun persistState(key: String, state: WorkflowTagState) {
        val json = JSONObject().apply {
            put("positive", tagsToJson(state.positiveTags))
            put("negative", tagsToJson(state.negativeTags))
            put("keyword", state.keyword)
            put("expanded", state.builderExpanded)
        }
        prefs().edit().putString(KEY_STATE_PREFIX + key, json.toString()).apply()
    }

    private fun loadPersistedState(key: String): WorkflowTagState {
        val raw = prefs().getString(KEY_STATE_PREFIX + key, null)
            ?: return WorkflowTagState(emptyList(), emptyList(), "", true)
        return try {
            val json = JSONObject(raw)
            WorkflowTagState(
                positiveTags = tagsFromJson(json.optJSONArray("positive")),
                negativeTags = tagsFromJson(json.optJSONArray("negative")),
                keyword = json.optString("keyword"),
                builderExpanded = json.optBoolean("expanded", true)
            )
        } catch (e: Exception) {
            Log.w(TAG, "标签记录解析失败，按空状态处理", e)
            WorkflowTagState(emptyList(), emptyList(), "", true)
        }
    }

    private fun tagsToJson(tags: List<AppliedTag>): JSONArray = JSONArray().apply {
        for (tag in tags) {
            put(JSONObject().apply {
                put("name", tag.name)
                put("weight", tag.weight.toDouble())
            })
        }
    }

    private fun tagsFromJson(array: JSONArray?): List<AppliedTag> {
        if (array == null) return emptyList()
        val out = ArrayList<AppliedTag>(array.length())
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val name = obj.optString("name")
            if (name.isNotEmpty()) {
                out.add(AppliedTag(name, obj.optDouble("weight", 1.0).toFloat()))
            }
        }
        return out
    }

    /** 当前关键词对应的默认列表（供详情页返回后复用）。 */
    fun currentResults(): List<Tag> = _uiState.value.results

    override fun onCleared() {
        super.onCleared()
        saveCurrentWorkflowState()
    }
}

/** 权重允许范围（与构建器输入框一致）。 */
private const val MIN_WEIGHT = 0.1f
private const val MAX_WEIGHT = 2.0f
