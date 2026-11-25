package sh.hnet.comfychair.ui.screens

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * P2（图库网格卡顿 / 阻尼感 / 零拉伸）五条主线的源码级钉子。
 *
 * 为什么读源码：这五条都是"接线"层面的改动——
 * - A 是"外层有没有再包一个把 overscroll factory 置空的容器"，
 * - B 是"预取 worker 拿到的是缩略图任务还是原图任务"，
 * - C 是"缩略图预算的分母"，
 * - D 是"滚动状态在 composition 阶段读还是在 snapshotFlow 里读"，
 * - E 是"网格有没有 contentType / 稳定 key"。
 * 它们都不会让现有行为测试变红（组件在 JVM 单测里根本不渲染），
 * 但任意一条被改回去，用户手里的卡顿就会原样回来。口径与
 * [sh.hnet.comfychair.cache.MediaCacheConcurrencyWiringTest] 一致：
 * 单测工作目录是模块根（`app/`）。
 */
class GalleryGridPerformanceWiringTest {

    private val galleryPath = "src/main/java/sh/hnet/comfychair/ui/screens/GalleryScreen.kt"
    private val cachePath = "src/main/java/sh/hnet/comfychair/cache/MediaCache.kt"
    private val noOverscrollPath =
        "src/main/java/sh/hnet/comfychair/ui/components/shared/NoOverscrollContainer.kt"

    private fun source(path: String): String {
        val file = File(path)
        assertTrue(
            "源文件不存在：${file.absolutePath} —— 单测工作目录应为 app/ 模块根",
            file.exists()
        )
        return file.readText()
    }

    /** 去掉 `//` 行注释与 `/* */` 块注释：注释里提到某个容器名不算真的用了它。 */
    private fun codeOnly(text: String): String =
        text.replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), " ")
            .replace(Regex("""//[^\n]*"""), " ")

    /** 折叠空白，便于跨换行匹配签名与调用。 */
    private fun normalized(text: String): String = text.replace(Regex("""\s+"""), " ")

    // ------------------------------------------------------------------ A

    /**
     * 2026-11-25 用户拍板**反转**本条断言：网格必须包回 NoOverscrollContainer。
     *
     * 历史：P2 批次曾要求「网格恢复 Android 12+ 的默认 stretch」，于是本条从
     * 「必须包」改成了「不许包」（assertTrue → assertFalse）。v4.22.1 上线后用户反馈
     * 「图库滑动老是回弹、很僵硬」——网格滚不满一屏时（捏合到 4 列 + 条目少，列表高度
     * 小于可视高度），任何一次上滑都只产生「拉伸→弹回」。因此按原作者基线
     * （comfychair-original 的 GalleryScreen.kt:302）回退这层防护。
     *
     * 若要再次拆掉这层，必须同时复查「列表滚不满一屏」场景的手感，并同步反转本条。
     */
    @Test
    fun `A 图库网格必须关闭 overscroll（2026-11-25 回退 P2-A）`() {
        val code = normalized(codeOnly(source(galleryPath)))
        assertTrue(
            "网格必须被 NoOverscrollContainer 包住：Android 12+ 的 stretch 在" +
                "「列表滚不满一屏」时会让每次上滑都变成拉伸再弹回（用户实测的一滑就弹）",
            code.contains("NoOverscrollContainer")
        )
        // 只包容器还不够：组件本身必须真的把 factory 置空，否则包了也不生效
        assertTrue(
            "NoOverscrollContainer 的定义必须把 LocalOverscrollFactory 置空",
            normalized(codeOnly(source(noOverscrollPath)))
                .contains("LocalOverscrollFactory provides null")
        )
        // 包裹不等于拆掉下拉刷新：网格仍必须在 PTR 容器里、仍是 LazyVerticalGrid
        assertTrue("网格必须仍由 PullToRefreshBox 包住", code.contains("PullToRefreshBox("))
        assertTrue("网格必须是 LazyVerticalGrid", code.contains("LazyVerticalGrid("))
    }

    // ------------------------------------------------------------------ B

    @Test
    fun `B 图库预取默认只暖缩略图档`() {
        val code = normalized(codeOnly(source(cachePath)))
        assertTrue(
            "updateGalleryPosition 的 thumbnailOnly 默认值必须为 true：网格只显示 720 档，" +
                "按原图预取等于给每格预览都拉 6144 px",
            code.contains(
                "fun updateGalleryPosition( firstVisibleIndex: Int, visibleItemCount: Int, " +
                    "allItems: List<PrefetchItem>, columnsInGrid: Int = 2, " +
                    "thumbnailOnly: Boolean = true )"
            )
        )
        assertTrue(
            "initialPrefetch 必须支持并默认走缩略图",
            code.contains("fun initialPrefetch(items: List<PrefetchItem>, thumbnailOnly: Boolean = true)")
        )
        assertTrue(
            "预取 worker 必须按 thumbnailOnly 分两条链路",
            code.contains("if (request.thumbnailOnly) { fetchThumbnail(")
        )
        assertTrue(
            "预取任务的 isCached 判定必须带上 thumbnailOnly 口径",
            code.contains("isCached(item.key, item.isVideo, thumbnailOnly)")
        )
    }

    @Test
    fun `B isCached 认缩略图命中，且原图口径不被缩略图污染`() {
        val code = normalized(codeOnly(source(cachePath)))
        assertTrue(
            "缩略图命中必须直接算已缓存（修复前只认原图，缩略图在内存里也照样排原图任务）",
            code.contains("if (thumbnailOnly && thumbnailCache.get(keyStr) != null) return true")
        )
        assertTrue(
            "查看器口径（thumbnailOnly = false）不能把缩略图当原图：查缓存分支必须保留",
            code.contains("private fun isCached( key: MediaCacheKey, isVideo: Boolean, thumbnailOnly: Boolean = false )")
        )
    }

    @Test
    fun `B 同一张缩略图不会同时跑两趟（单飞闸门）`() {
        val code = normalized(codeOnly(source(cachePath)))
        assertTrue(
            "缺单飞闸门：格子组合与预取 worker 会各请求一次同一张缩略图，" +
                "服务端要现场压两遍（大图单次约 9 秒）",
            code.contains("thumbnailInFlight")
        )
        assertTrue(
            "单飞必须是 putIfAbsent 原子入闸",
            code.contains("thumbnailInFlight.putIfAbsent(keyStr, gate)")
        )
        assertTrue(
            "单飞闸门必须在 finally 里清理并唤醒等待者",
            code.contains("if (!gate.isCompleted) gate.complete(null)")
        )
    }

    // ------------------------------------------------------------------ C

    @Test
    fun `C 缩略图内存预算为堆的八分之一（不是旧的十六分之一）`() {
        val code = codeOnly(source(cachePath))
        assertTrue(
            "缩略图预算分母必须是 8：旧值 16 在 256 MB 堆上只有 ≈8 张 720 档，一屏都不够",
            Regex("""THUMBNAIL_HEAP_DIVISOR\s*=\s*8\b""").containsMatchIn(code)
        )
        assertTrue(
            "缩略图缓存必须使用算出来的预算",
            normalized(code).contains("maxSize = thumbnailCacheSize")
        )
    }

    // ------------------------------------------------------------------ D

    @Test
    fun `D 滚动跟踪走 snapshotFlow，不再在组合阶段读 gridState`() {
        val code = normalized(codeOnly(source(galleryPath)))
        assertTrue("缺 snapshotFlow", code.contains("snapshotFlow {"))
        assertTrue("缺 distinctUntilChanged", code.contains("distinctUntilChanged()"))
        assertTrue("缺 collectLatest 去抖", code.contains("collectLatest {"))
        assertFalse(
            "滚动跟踪又把 gridState.firstVisibleItemIndex 写回 LaunchedEffect 的 key 位置：" +
                "每滚一行换一次 key = 每行一次整页重组",
            code.contains("LaunchedEffect( gridState.firstVisibleItemIndex")
        )
    }

    // ------------------------------------------------------------------ E

    @Test
    fun `E 网格卡片、页脚、状态占位分池复用且占位项有稳定 key`() {
        val code = normalized(codeOnly(source(galleryPath)))
        assertTrue(
            "卡片必须带 contentType",
            code.contains("contentType = { _, _ -> GALLERY_GRID_CARD_CONTENT_TYPE }")
        )
        assertTrue(
            "页脚必须带 contentType",
            code.contains("contentType = GALLERY_GRID_FOOTER_CONTENT_TYPE")
        )
        assertTrue(
            "状态占位必须带 contentType",
            code.contains("contentType = GALLERY_GRID_STATE_CONTENT_TYPE")
        )
        assertTrue(
            "loading 占位必须带稳定 key",
            code.contains("key = GALLERY_STATE_LOADING_KEY")
        )
        assertTrue(
            "empty 占位必须带稳定 key",
            code.contains("key = GALLERY_STATE_EMPTY_KEY")
        )
    }
}
