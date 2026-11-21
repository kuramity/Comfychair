package sh.hnet.comfychair

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 空列表护栏的「接线还在不在」源码级钉子（P0-R5）。
 *
 * ## 为什么需要这一层
 *
 * [sh.hnet.comfychair.util.ViewerIndexBoundsTest] 只能证明纯函数 `viewerInitialPage` /
 * `galleryOpenIndex` 对 size=0 的返回值是对的，证明不了**屏幕真的调用了它们**。
 * 把 GalleryScreen 的 `galleryOpenIndex(...) ?: return`、MediaViewerScreen 的
 * `viewerInitialPage(...)` 或空态 `AUTO_CLOSE` 整段删掉，那组纯函数单测仍然全绿 ——
 * 这正是复核指出 P0-R5 的问题。这里按 [PagerInertiaWiringTest] / [ViewerExitWiringTest]
 * 同款写法直接读源码，把接线钉住：
 *
 * 1. 图库打开查看器前必须经过 `galleryOpenIndex`，且传给 Intent 的是夹紧后的 safeIndex；
 * 2. 查看器首屏页必须经过 `viewerInitialPage`；
 * 3. 图库队列为空时不得创建 pager，必须走统一退出入口 AUTO_CLOSE；
 * 4. `expandPage` / `onPanEdgeFling` 的空列表守卫必须还在；
 * 5. pager 的 key 与页内容不得再直接用 `items[index]` / `items[page]` 下标。
 *
 * MediaViewerScreen 依赖 Compose 运行时（pager、pointerInput、graphicsLayer），
 * JVM 单测起不来，所以「生产代码有没有真的接上去」只能在源码层钉。
 * 断言按**空白归一化后的子串**匹配：调整缩进/换行不会误报，删掉接线必红。
 */
class ViewerEmptyStateWiringTest {

    private val viewerPath = "src/main/java/sh/hnet/comfychair/ui/screens/MediaViewerScreen.kt"
    private val galleryPath = "src/main/java/sh/hnet/comfychair/ui/screens/GalleryScreen.kt"

    /** 单测工作目录是模块根（app/），与 PagerInertiaWiringTest / ViewerExitWiringTest 同口径。 */
    private fun source(path: String): String {
        val file = File(path)
        assertTrue(
            "源文件不存在：${file.absolutePath} —— 单测工作目录应为 app/ 模块根",
            file.exists()
        )
        return file.readText()
    }

    /** 把连续空白折叠成单个空格：只忽略排版，不忽略语义。 */
    private fun normalized(body: String): String =
        body.replace(Regex("\\s+"), " ").trim()

    private fun containsNormalized(body: String, fragment: String): Boolean =
        normalized(body).contains(normalized(fragment))

    // ============================================================
    // 图库 → 查看器
    // ============================================================

    @Test
    fun `图库打开查看器前必须经过空列表拦截`() {
        val body = source(galleryPath)
        assertTrue(
            "launchMediaViewer 必须先用 galleryOpenIndex 判空并夹紧索引；" +
                "删掉这一行，空列表会把 size=0 的队列直接交给 Pager",
            containsNormalized(
                body,
                "val safeIndex = galleryOpenIndex(clickedIndex, uiState.items.size) ?: return"
            )
        )
    }

    @Test
    fun `打开的初始页必须是夹紧后的 safeIndex 而不是原始点击索引`() {
        val body = source(galleryPath)
        assertTrue(
            "createGalleryIntent 的 initialIndex 必须传 safeIndex",
            containsNormalized(body, "initialIndex = safeIndex")
        )
        assertFalse(
            "不得回潮为 initialIndex = clickedIndex（原始索引可能越界）",
            containsNormalized(body, "initialIndex = clickedIndex")
        )
        assertTrue(
            "缓存优先级也必须按 safeIndex 计算",
            containsNormalized(body, "MediaCache.updateNavigationPriorities(safeIndex, allKeys)")
        )
    }

    // ============================================================
    // 查看器内部
    // ============================================================

    @Test
    fun `查看器首屏页必须经过空列表纯函数`() {
        val body = source(viewerPath)
        assertTrue(
            "rememberPagerState 的 initialPage 必须来自 viewerInitialPage(currentIndex, itemCount)；" +
                "直接写 coerceIn(0, size - 1) 在 size=0 时会抛 IllegalArgumentException",
            containsNormalized(
                body,
                "val initialPage = viewerInitialPage( currentIndex = uiState.currentIndex, itemCount = uiState.items.size )"
            )
        )
        assertFalse(
            "不得回潮为 currentIndex.coerceIn(0, items.size - 1)",
            containsNormalized(
                body,
                "uiState.currentIndex.coerceIn( 0, (uiState.items.size - 1).coerceAtLeast(0) )"
            )
        )
    }

    @Test
    fun `图库模式空队列必须自动关闭而不是创建 pager`() {
        val body = source(viewerPath)
        assertTrue(
            "空列表分支必须调用统一退出入口 AUTO_CLOSE（不建 pager、不渲染黑屏）",
            containsNormalized(
                body,
                "LaunchedEffect(Unit) { currentExitViewer(ViewerExitReason.AUTO_CLOSE) }"
            )
        )
        assertTrue(
            "pager 仍必须整体包在 items.isNotEmpty() 分支里",
            containsNormalized(body, "if (uiState.items.isNotEmpty()) {")
        )
    }

    @Test
    fun `图库回传键同时接受 NUL 与旧下划线两种格式`() {
        val body = source(galleryPath)
        assertTrue(
            "回传键查找必须认得 NUL 口径（Activity 侧切换到 itemIdentityKey 后无需再改）",
            containsNormalized(
                body,
                "GalleryMetadataCache.itemIdentityKey(item.promptId, item.filename) == currentKey"
            )
        )
        assertTrue(
            "回传键查找必须保留旧下划线格式（Activity 尚未切换，单改一端会断位置同步）",
            containsNormalized(body, "\"\${item.promptId}_\${item.filename}\" == currentKey")
        )
    }

    @Test
    fun `翻页手势的空列表守卫仍在`() {
        val body = source(viewerPath)
        assertTrue(
            "expandPage 必须先判空再 coerceIn",
            containsNormalized(body, "fun expandPage(targetPage: Int) {") &&
                containsNormalized(body, "if (uiState.items.isEmpty()) return")
        )
        assertTrue(
            "onPanEdgeFling 必须用 lastIndex < 0 挡住空列表",
            containsNormalized(body, "val lastIndex = uiState.items.size - 1") &&
                containsNormalized(body, "if (lastIndex < 0) return@launch")
        )
    }

    @Test
    fun `pager 的 key 与页内容不得直接用下标取条目`() {
        val body = source(viewerPath)
        assertFalse(
            "页内容不得回潮为 uiState.items[page]",
            containsNormalized(body, "val item = uiState.items[page]")
        )
        assertFalse(
            "pager key 不得回潮为 uiState.items[index].promptId",
            containsNormalized(body, "uiState.items[index].promptId")
        )
        assertTrue(
            "页内容必须用 getOrNull 兜住删除与测量之间的差一帧",
            containsNormalized(body, "uiState.items.getOrNull(page)")
        )
    }

    @Test
    fun `查看器内部条目键与图库去重口径一致`() {
        val body = source(viewerPath)
        assertTrue(
            "viewerItemKey 必须委托 GalleryMetadataCache.itemIdentityKey（NUL 分隔），" +
                "否则撞键只是从网格挪到 Pager",
            containsNormalized(
                body,
                "private fun viewerItemKey(promptId: String, filename: String): String = GalleryMetadataCache.itemIdentityKey(promptId, filename)"
            )
        )
        assertTrue(
            "pager 的 pageItemKey 必须使用 viewerItemKey",
            containsNormalized(body, "val pageItemKey = viewerItemKey(item.promptId, item.filename)")
        )
        assertFalse(
            "pageItemKey 不得回潮为下划线拼接",
            containsNormalized(body, "val pageItemKey = \"\${item.promptId}_\${item.filename}\"")
        )
    }
}