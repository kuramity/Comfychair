package sh.hnet.comfychair.util

/**
 * 图库 / 查看器在「空列表 + 索引失效」场景下的统一边界计算（纯函数）。
 *
 * 为什么单独抽出来：P0 崩溃的复现里，列表被删到 0 之后仍有一次迟到的点击/恢复流程
 * 把 `size = 0` 交给下游 —— 而 `coerceIn(0, size - 1)` 在 size = 0 时会退化成
 * `coerceIn(0, -1)`，直接抛 `IllegalArgumentException: Cannot coerce value to an
 * empty range`。这类边界必须只有**一处**定义，并由 JVM 单测逐个 size 钉住
 * （0 / 1 / 中间 / 末尾 / 越界 / 负数），而不是散落在 UI 里靠"应该不会发生"。
 *
 * 所有函数都不依赖 Android，可在 JVM 单测里直接调用。
 */

/**
 * 查看器首屏页（`rememberPagerState(initialPage = ...)` 的入参）。
 *
 * - `itemCount <= 0`：返回 0。**注意**：空列表本身不应进入查看器，调用方必须
 *   先拦截（见 [galleryOpenIndex]）；这里返回 0 只是给"已进入后条目被删空"的
 *   中间帧一个合法值，真正的退出由空态分支负责。
 * - 否则把 `currentIndex` 夹到 `[0, itemCount - 1]`，绝不产生越界页。
 */
fun viewerInitialPage(currentIndex: Int, itemCount: Int): Int =
    if (itemCount <= 0) 0 else currentIndex.coerceIn(0, itemCount - 1)

/**
 * 打开查看器时的页索引。
 *
 * @return `null` = **列表为空，禁止启动查看器**（调用方必须直接 return）；
 *         否则返回夹到 `[0, itemCount - 1]` 的合法索引。
 *
 * 返回可空而不是"空列表返回 0"，是为了把"非法状态"变成编译期必须处理的类型，
 * 防止某个调用点忘了判空就把 size = 0 的队列交给 Pager。
 */
fun galleryOpenIndex(clickedIndex: Int, itemCount: Int): Int? =
    if (itemCount <= 0) null else clickedIndex.coerceIn(0, itemCount - 1)

/**
 * LazyGrid 滚动恢复的初值 / 锚点索引。
 *
 * - `itemCount <= 0`：返回 0（空列表合法初值，LazyGrid 自身会夹，但绝不能让 0 以外的
 *   值流到任何数组下标里）。
 * - 否则把保存的索引夹到 `[0, itemCount - 1]`。删除条目后旧索引可能大于新长度，
 *   直接 `scrollToItem(旧索引)` 虽然 LazyGrid 会自己夹，但同一份值若被别的代码
 *   拿去当数组下标就会越界 —— 统一在源头夹紧。
 */
fun galleryInitialFirstVisibleItemIndex(itemCount: Int, savedFirstVisibleIndex: Int): Int =
    if (itemCount <= 0) 0 else savedFirstVisibleIndex.coerceIn(0, itemCount - 1)

/**
 * 删除撤销时把条目插回列表的位置（`List.add(index, item)` 的 index）。
 *
 * `add` 允许 `index == size`（插到末尾），所以上界是 `itemCount` 而不是 `itemCount - 1`。
 * 空列表时返回 0 —— 空列表插回第 0 位，这是唯一合法位置。
 */
fun viewerInsertIndex(requestedIndex: Int, itemCount: Int): Int =
    requestedIndex.coerceIn(0, itemCount.coerceAtLeast(0))