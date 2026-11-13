package sh.hnet.comfychair.ui.components

/**
 * 图位「选择 / 删除模式」的状态，给 `ImageSlotGrid` 当受控组件用（设计 §3：长按 = 批量清理入口）。
 *
 * 全部是纯 Kotlin 逻辑（零 Android / 零 Compose import），因此可以在 JVM 单测里把交互序列完整跑一遍，
 * 先例见 `model/NumberedSlotContainer.kt`。页面层按下面这样接（状态归页面持有，组件保持无状态）：
 *
 * ```kotlin
 * var selection by remember { mutableStateOf(SlotSelection()) }
 * val placeholders = uiState.imageSlots.map { it.placeholder }.toSet()
 *
 * // 图位定义变了（换工作流 / 删图位）就收敛，避免勾选项指向已不存在的图位
 * LaunchedEffect(placeholders) { selection = selection.retainAvailable(placeholders) }
 *
 * ImageSlotGrid(
 *     selectionMode = selection.active,
 *     selected = selection.selected,
 *     onSlotClick = { slot ->
 *         if (selection.active) selection = selection.toggle(slot.placeholder)
 *         else /* 空位选图 / 已填位全屏查看 */
 *     },
 *     onSlotLongClick = { slot -> selection = selection.enter(slot.placeholder) }
 * )
 * ```
 *
 * @property active 是否处于选择模式（网格显示勾选框、隐藏「+ 添加」，顶部出操作栏）
 * @property selected 已勾选的图位**占位符名**，与 `ImageSlotGrid.selected` 同一口径
 */
data class SlotSelection(
    val active: Boolean = false,
    val selected: Set<String> = emptySet()
) {
    /** 操作栏「删除所选」是否可用：一个都没勾时没得删 */
    val canDelete: Boolean get() = selected.isNotEmpty()

    /**
     * 长按图位：进入选择模式，并保证该图位在勾选集合内。
     *
     * 已在模式内时是**并入**而不是重置——长按第二个格子不该把已有的勾选清掉（长按的语义是"清理"，
     * 不该有破坏性副作用）；重复长按同一个格子也是幂等的。
     */
    fun enter(placeholder: String): SlotSelection =
        SlotSelection(active = true, selected = selected + placeholder)

    /**
     * 选择模式下点击图位：切换勾选。
     *
     * 勾掉最后一个**仍留在模式内**——退出只由「取消」「删除」「图位定义变化」（[retainAvailable]）
     * 三条路径触发，避免用户在换选时被踢出模式（与简报的退出条件一致）。
     * 不在选择模式时原样返回：调用方若忘了分支，也不会留下界面上看不见的勾选。
     */
    fun toggle(placeholder: String): SlotSelection = when {
        !active -> this
        placeholder in selected -> copy(selected = selected - placeholder)
        else -> copy(selected = selected + placeholder)
    }

    /** 取消 / 删除所选之后退出：连同勾选一起清空 */
    fun exit(): SlotSelection = SlotSelection()

    /**
     * 图位定义变化后收敛：丢掉已不存在的图位；勾选一个都不剩时退出选择模式。
     *
     * 批量删除走的就是这条分支——勾选的图位全被摘掉后自动落进"空 → 退出"，页面无需额外收尾。
     * 勾选的图位还有活着的则保持模式与勾选（换工作流后同名图位仍可继续操作）。
     */
    fun retainAvailable(available: Set<String>): SlotSelection {
        val kept = selected.intersect(available)
        return if (kept.isEmpty()) SlotSelection() else copy(selected = kept)
    }
}
