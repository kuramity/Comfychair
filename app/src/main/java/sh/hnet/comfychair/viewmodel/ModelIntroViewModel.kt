package sh.hnet.comfychair.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import sh.hnet.comfychair.R
import sh.hnet.comfychair.model.ModelIntro
import sh.hnet.comfychair.model.ModelIntroTable

/**
 * 介绍库的界面状态与用户写操作（卸载 / 恢复）。
 *
 * 为什么单独一个 ViewModel：覆盖层的写操作都是**磁盘 IO + 覆盖层文件级读写**，
 * 不能让 Compose 直接在主线程调 [ModelIntroTable.hide]；
 * 同时也把「操作结果 → 中文提示」的映射收在一处，界面只负责收集 Toast 事件。
 *
 * 分层约束：`viewmodel` 包不得 import `ui`（见 `ViewModelUiDependencyTest`），
 * 所以这里只依赖 model 层与 Android 基础 API；提示文案用资源 id 传出，由界面拼串。
 */
class ModelIntroViewModel : ViewModel() {

    /** 已卸载列表的一项（键是归一化文件名，展示名优先取条目名）。 */
    data class HiddenItem(val file: String, val displayName: String)

    data class UiState(
        val entries: List<ModelIntro> = emptyList(),
        /** 当前被本地卸载的条目（顺序稳定：按归一化文件名排序） */
        val hidden: List<HiddenItem> = emptyList(),
        /** 首次装载是否完成（区分「还没加载」与「确实没收录」） */
        val loading: Boolean = true,
        /** 批量管理模式：条目行改为勾选，而不是打开详情 */
        val selectMode: Boolean = false,
        /** 已勾选的条目 `file`（原样大小写，卸载时再归一） */
        val selected: Set<String> = emptySet(),
    )

    /** 一次性事件（Toast）。文案 id + 格式参数，界面统一 `getString`。 */
    sealed interface Event {
        data class Toast(val messageResId: Int, val formatArgs: List<Any> = emptyList()) : Event
    }

    private val _uiState = MutableStateFlow(UiState())
    val uiState = _uiState.asStateFlow()

    // extraBufferCapacity 保证 tryEmit 永远不会因为无人收集而丢事件
    private val _events = MutableSharedFlow<Event>(extraBufferCapacity = 16)
    val events = _events.asSharedFlow()

    /**
     * 装载/刷新视图：介绍表（含覆盖层合并结果）+ 已卸载列表。
     *
     * 进页面与每次写操作之后都会调用——覆盖层写成功后视图必须立刻反映（卸载即消失、
     * 恢复即出现），不能等下次冷启动。
     */
    fun load(context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            refresh(context)
        }
    }

    // ---------------- 卸载 / 恢复 ----------------

    /** 卸载单条（长按确认后调用）。 */
    fun hide(context: Context, file: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val ok = ModelIntroTable.hide(file)
            refresh(context)
            toast(if (ok) R.string.model_intro_uninstall_done else R.string.model_intro_op_failed)
        }
    }

    /** 批量卸载（批量管理模式下调用）。逐条写入，任一条失败仍继续并提示失败。 */
    fun hideMany(context: Context, files: Collection<String>) {
        if (files.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            var succeeded = 0
            var failed = false
            for (file in files) {
                if (ModelIntroTable.hide(file)) succeeded++ else failed = true
            }
            refresh(context)
            exitSelectMode()
            if (succeeded > 0) {
                toast(R.string.model_intro_batch_done, listOf(succeeded))
            }
            if (failed || succeeded == 0) {
                toast(R.string.model_intro_op_failed)
            }
        }
    }

    /** 恢复一条已卸载的介绍（「已卸载」页逐条恢复）。 */
    fun restore(context: Context, file: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val ok = ModelIntroTable.restore(file)
            refresh(context)
            toast(if (ok) R.string.model_intro_restore_done else R.string.model_intro_op_failed)
        }
    }

    // ---------------- 编辑器写入口（v4.21：保存 / 删除自建） ----------------

    /**
     * 编辑器「保存」：整条写入覆盖层（[ModelIntroTable.upsertEntry]），完成后回主线程回调结果。
     *
     * 结果经 `onResult` 而不是 `events` 流下发：保存成功时编辑器会立刻 pop 回列表，
     * 事件发在还没回到组合的列表页上会丢；失败留在编辑器内提示，所以回调更可靠。
     * 成功后仍走 [refresh]：列表页恢复组合时会重新 load，但覆盖层视图必须先更新。
     */
    fun save(context: Context, intro: ModelIntro, onResult: (Boolean) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val ok = ModelIntroTable.upsertEntry(intro)
            refresh(context)
            toast(if (ok) R.string.model_intro_editor_saved else R.string.model_intro_op_failed)
            withContext(Dispatchers.Main) { onResult(ok) }
        }
    }

    /**
     * 删除一条覆盖层自建条目（[ModelIntroTable.deleteCustomEntry]），语义同 [save] 的回调约定。
     *
     * 只有 [isCustomEntry] 为 true 时界面才给删除入口；内置条目走 [hide]（本地卸载），
     * 数据层也会拒绝删除，这里不重复判定——界面与数据层各管各的一层。
     */
    fun deleteCustom(context: Context, file: String, onResult: (Boolean) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val ok = ModelIntroTable.deleteCustomEntry(file)
            refresh(context)
            toast(if (ok) R.string.model_intro_editor_deleted else R.string.model_intro_op_failed)
            withContext(Dispatchers.Main) { onResult(ok) }
        }
    }

    /** 这条是不是覆盖层自建（决定编辑器给不给删除按钮）。表未装载时 false。 */
    fun isCustomEntry(file: String): Boolean = ModelIntroTable.isCustomEntry(file)

    // ---------------- 批量管理状态 ----------------

    fun enterSelectMode() {
        _uiState.value = _uiState.value.copy(selectMode = true, selected = emptySet())
    }

    fun exitSelectMode() {
        _uiState.value = _uiState.value.copy(selectMode = false, selected = emptySet())
    }

    fun toggleSelected(file: String) {
        val current = _uiState.value
        if (!current.selectMode) return
        val next = if (file in current.selected) current.selected - file else current.selected + file
        _uiState.value = current.copy(selected = next)
    }

    // ---------------- 内部 ----------------

    private fun refresh(context: Context) {
        ModelIntroTable.ensureLoaded(context)
        val entries = ModelIntroTable.all()
        val hidden = ModelIntroTable.hiddenFiles().map { key ->
            val intro = ModelIntroTable.hiddenIntro(key)
            HiddenItem(
                file = key,
                displayName = intro?.name?.takeIf { it.isNotBlank() }
                    ?: intro?.file?.takeIf { it.isNotBlank() }
                    ?: key,
            )
        }
        val visible = entries.mapTo(HashSet()) { it.file }
        val state = _uiState.value
        _uiState.value = state.copy(
            entries = entries,
            hidden = hidden,
            loading = false,
            // 卸载后从选择集里剔掉已经不可见的条目，避免批量卸载的计数虚高
            selected = state.selected.filterTo(HashSet()) { it in visible },
        )
    }

    private fun toast(messageResId: Int, formatArgs: List<Any> = emptyList()) {
        _events.tryEmit(Event.Toast(messageResId, formatArgs))
    }
}