package sh.hnet.comfychair.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Test
import sh.hnet.comfychair.ui.components.ViewerPageMotion

/**
 * G2（v4.16.12）接线测试：把 Compose Pager 的吸附回调
 * （`PagerSnapDistance.calculateTargetPage`）喂进 [ViewerPagerSnapDistance]，
 * 断言它严格按纯函数 [ViewerPageMotion.snapTargetPage] 的口径出结果。
 *
 * 为什么要单独测这一层：根因是框架默认实现 `PagerSnapDistance.atMost(1)`
 * （Compose Foundation 1.10 字节码取证）把跨页数钉死在 1。接线层如果参数顺序、
 * 页距换算、页数来源任一处错位，纯函数单测全绿、真机手感依旧"只翻一张"，
 * 所以把这一段也用 JVM 单测钉住。
 */
class ViewerPagerSnapDistanceTest {

    private val pitch = 1080

    /**
     * 接线默认按"队列模式"构造（[allowInertia] = true）：既有断言全部是在
     * 惯性开启的前提下写的（快速甩动跨多张），默认值必须保持不变。
     * 全屏单图的用例显式传 false（v4.16.14：惯性只保留在队列模式）。
     */
    private fun snapDistance(pageCount: Int, allowInertia: Boolean = true) =
        ViewerPagerSnapDistance(
            pageCountProvider = { pageCount },
            allowInertia = { allowInertia }
        )

    @Test
    fun `慢速松手保持逐张`() {
        val adapter = snapDistance(pageCount = 9)
        // 框架给出的衰减终点就在下一张：允许（慢速拖过半 → 翻一张）
        assertEquals(
            4,
            adapter.calculateTargetPage(
                startPage = 3,
                suggestedTargetPage = 4,
                velocity = 200f,
                pageSize = pitch,
                pageSpacing = 0
            )
        )
        // 就算框架给出的终点很远（异常/极端衰减），慢速也只许挪一张
        assertEquals(
            4,
            adapter.calculateTargetPage(
                startPage = 3,
                suggestedTargetPage = 9,
                velocity = 0f,
                pageSize = pitch,
                pageSpacing = 0
            )
        )
    }

    @Test
    fun `中速允许跨一张`() {
        val adapter = snapDistance(pageCount = 9)
        // 3000px/s → 惯性 1 张：终点在两页外也只许挪一张（不是"全部丢弃速度"，
        // 而是"最多跨 1 张"，所以快速与否在同一速度下都可预测）
        assertEquals(
            4,
            adapter.calculateTargetPage(
                startPage = 3,
                suggestedTargetPage = 5,
                velocity = 3000f,
                pageSize = pitch,
                pageSpacing = 0
            )
        )
        // 终点刚好一张：原样吸附
        assertEquals(
            4,
            adapter.calculateTargetPage(
                startPage = 3,
                suggestedTargetPage = 4,
                velocity = 3000f,
                pageSize = pitch,
                pageSpacing = 0
            )
        )
    }

    @Test
    fun `快速甩动允许跨多张`() {
        val adapter = snapDistance(pageCount = 20)
        // 12000px/s → 惯性 7 张：终点 6 张外也吸附得到
        assertEquals(
            9,
            adapter.calculateTargetPage(
                startPage = 3,
                suggestedTargetPage = 9,
                velocity = 12000f,
                pageSize = pitch,
                pageSpacing = 0
            )
        )
        // 终点超过跨页上限：被夹到 3 + 7
        assertEquals(
            10,
            adapter.calculateTargetPage(
                startPage = 3,
                suggestedTargetPage = 19,
                velocity = 12000f,
                pageSize = pitch,
                pageSpacing = 0
            )
        )
    }

    @Test
    fun `页距按页宽加页间距换算`() {
        val adapter = snapDistance(pageCount = 20)
        // 页距 = pageSize + pageSpacing（与纯函数同口径）：把 1080 拆成 1040 + 40
        // 不能改变跨页结果，否则带页间距的配置会凭空少跨一张
        val wholePitch = adapter.calculateTargetPage(
            startPage = 3,
            suggestedTargetPage = 19,
            velocity = 12000f,
            pageSize = pitch,
            pageSpacing = 0
        )
        val splitPitch = adapter.calculateTargetPage(
            startPage = 3,
            suggestedTargetPage = 19,
            velocity = 12000f,
            pageSize = pitch - 40,
            pageSpacing = 40
        )
        assertEquals(wholePitch, splitPitch)
    }

    @Test
    fun `页数两端夹取`() {
        val adapter = snapDistance(pageCount = 5)
        // 末页：吸附不能越到页数之外
        assertEquals(
            4,
            adapter.calculateTargetPage(
                startPage = 4,
                suggestedTargetPage = 12,
                velocity = 20000f,
                pageSize = pitch,
                pageSpacing = 0
            )
        )
        // 首张往上一张方向：夹在 0
        assertEquals(
            0,
            adapter.calculateTargetPage(
                startPage = 0,
                suggestedTargetPage = -6,
                velocity = -20000f,
                pageSize = pitch,
                pageSpacing = 0
            )
        )
    }

    @Test
    fun `页宽未测得或页数为空时不崩且返回有效页`() {
        // pageSize = 0（首帧尚未测量）：跨页数按 0 处理，仍不越出 ±1
        assertEquals(
            4,
            snapDistance(pageCount = 9).calculateTargetPage(
                startPage = 3,
                suggestedTargetPage = 8,
                velocity = 12000f,
                pageSize = 0,
                pageSpacing = 0
            )
        )
        // 页数 0：返回 0（调用方随后会重建 pager，不会拿它去滚）
        assertEquals(
            0,
            snapDistance(pageCount = 0).calculateTargetPage(
                startPage = 3,
                suggestedTargetPage = 8,
                velocity = 12000f,
                pageSize = pitch,
                pageSpacing = 0
            )
        )
    }

    /**
     * v4.16.14 用户规格：横向甩动的"跨多张惯性"**只保留在队列模式（并排三张）**；
     * 单图全屏模式下不管甩多大力都只滑一张。
     *
     * 断言口径（同一组输入只切换 allowInertia，证明差异确实来自这个开关）：
     * - false（全屏）→ 12000px/s 的衰减终点在 7 张之外，也只许落到相邻一张；
     * - true（队列）→ 同样的输入跨 7 张（既有 G2 行为，不许被这次改动带走）；
     * - false 时慢速拖过半仍然翻一张 —— "只滑一张"不是"不滑"。
     */
    @Test
    fun `全屏单图不产生跨页惯性`() {
        val fullScreen = snapDistance(pageCount = 20, allowInertia = false)
        // 正向：终点 12（起点 5 之外 7 张）→ 只进一张（5 + 1）
        assertEquals(
            6,
            fullScreen.calculateTargetPage(
                startPage = 5,
                suggestedTargetPage = 12,
                velocity = 12000f,
                pageSize = pitch,
                pageSpacing = 0
            )
        )
        // 反向镜像：终点 -2（起点 5 之前 7 张）→ 只退一张（5 - 1）
        assertEquals(
            4,
            fullScreen.calculateTargetPage(
                startPage = 5,
                suggestedTargetPage = -2,
                velocity = -12000f,
                pageSize = pitch,
                pageSpacing = 0
            )
        )
        // 队列模式同一组输入仍跨多张：G2 已验收的多张惯性不得被削弱
        assertEquals(
            12,
            snapDistance(pageCount = 20, allowInertia = true).calculateTargetPage(
                startPage = 5,
                suggestedTargetPage = 12,
                velocity = 12000f,
                pageSize = pitch,
                pageSpacing = 0
            )
        )
        // 全屏态松手零速：慢速拖过半依旧吸附相邻一张（逐张手感不变）
        assertEquals(
            6,
            fullScreen.calculateTargetPage(
                startPage = 5,
                suggestedTargetPage = 6,
                velocity = 0f,
                pageSize = pitch,
                pageSpacing = 0
            )
        )
    }

    /**
     * v4.16.14 复核 GATE-1：惯性判据必须是"**当前真的在队列态**"，不是"队列功能开着"。
     *
     * 这条用例就是 GATE-1 的反证：修复前接线传的是裸 `threeLevelEnabled`
     * （设置项默认 true），于是"功能开着但此刻全屏"也会放开多张惯性 ——
     * 用户看到的现象与修复前一模一样。只有 (功能开 && 当前并排) 才允许跨页。
     */
    @Test
    fun `惯性只在当前真的处于队列态时才放开`() {
        // 队列态（并排三张）：放开多张惯性
        assertEquals(true, pagerInertiaAllowed(threeLevelEnabled = true, sideBySideActive = true))
        // 功能开着但此刻是全屏：**不放**（GATE-1 的核心断言）
        assertEquals(false, pagerInertiaAllowed(threeLevelEnabled = true, sideBySideActive = false))
        // 功能关着：并排链路整体停用，不存在"队列模式"，同样不放
        assertEquals(false, pagerInertiaAllowed(threeLevelEnabled = false, sideBySideActive = true))
        assertEquals(false, pagerInertiaAllowed(threeLevelEnabled = false, sideBySideActive = false))
    }
}