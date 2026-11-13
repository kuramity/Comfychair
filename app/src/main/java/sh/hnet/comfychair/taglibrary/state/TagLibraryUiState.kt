package sh.hnet.comfychair.taglibrary.state

import sh.hnet.comfychair.taglibrary.model.AppliedTag
import sh.hnet.comfychair.taglibrary.model.Tag
import sh.hnet.comfychair.taglibrary.model.TagCategory

/**
 * 标签库页面 UI 状态。
 *
 * 状态与"当前活动工作流"绑定（需求 5.1）：切换工作流时，
 * [positiveTags] / [negativeTags] / [keyword] / [builderExpanded] 会切换为
 * 该工作流自己的记录；新工作流首次进入时为空。
 */
data class TagLibraryUiState(
    // ---------- 搜索 ----------
    /** 搜索关键词。 */
    val keyword: String = "",
    /** 搜索结果（按精确命中 → 热度排序）。 */
    val results: List<Tag> = emptyList(),
    /** 分类过滤（null = 全部）。 */
    val selectedCategory: TagCategory? = null,
    /** 搜索进行中。 */
    val isSearching: Boolean = false,

    // ---------- 收藏与最近使用（本地持久化，见 TagFavoritesStore） ----------
    /** 收藏的标签名。 */
    val favorites: Set<String> = emptySet(),
    /** 最近使用的标签，最新在前。 */
    val recents: List<String> = emptyList(),
    /** 只看收藏（与关键词、分类筛选叠加）。 */
    val favoritesOnly: Boolean = false,

    // ---------- 初始化状态 ----------
    /** 数据库已就绪。 */
    val isDbReady: Boolean = false,
    /** 内存索引构建中。 */
    val isIndexLoading: Boolean = false,
    /** 初始化错误信息（非空时 UI 显示重试）。 */
    val errorMessage: String? = null,

    // ---------- 构建器 ----------
    /** 构建器是否展开（收起后仅保留标题栏）。 */
    val builderExpanded: Boolean = true,
    /** 通过搜索添加的正向标签记录。 */
    val positiveTags: List<AppliedTag> = emptyList(),
    /** 通过搜索添加的负向标签记录。 */
    val negativeTags: List<AppliedTag> = emptyList(),

    // ---------- 当前同步目标 ----------
    /** 当前活动生成页面归属（TEXT_TO_IMAGE 等）。 */
    val targetOwnerId: String = "",
    /** 当前工作流 ID。 */
    val targetWorkflowId: String = "",
    /** 当前工作流显示名。 */
    val targetWorkflowName: String = ""
) {
    /** 是否已绑定到某个工作流（决定构建器是否可写入提示词）。 */
    val hasTarget: Boolean get() = targetOwnerId.isNotEmpty() && targetWorkflowId.isNotEmpty()

    /** 未输入关键词时的空态提示类型。 */
    val showEmptyHint: Boolean get() = keyword.isBlank() && results.isEmpty() && !isSearching
}
