package sh.hnet.comfychair.ui.components.ripple

import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 水面波纹点阵的纯逻辑层（v5.x 按 HTML 观感基准重校）。
 *
 * 职责边界：
 * - **点阵生成**：按控件宽高 + 间距生成固定网格坐标，只在尺寸/低端机标记变化时重算；
 * - **波纹采样**：对每个点计算"被波前扫到"的激活系数 boost（0..1）与累计上凸 lift（dp），
 *   连同坐标写进 out 缓冲，供绘制层（[RippleFeedbackModifier]）逐帧直接取用。
 *
 * 纯 Kotlin、无 Compose / Android 依赖，几何与时间模型全部可单测。
 * 时间单位统一为秒、几何单位统一为 dp —— px ↔ dp 的换算由 Compose 层负责。
 *
 * 波纹模型（对齐基准）：每道波纹从触点向外匀速扩散，只有"波前带宽"内的点被点亮；
 * 波前越过之后该点立即回落为 0（水过无痕），寿命结束时整道波纹作废。
 * 与旧版的差异：寿命衰减全程线性、上凸按道累加（多道波纹叠出更高的凸起，总封顶 6dp）。
 */
internal class RippleDotGrid {

    /**
     * 一道触点波纹：(x, y) 为触点在控件内的 dp 坐标，[bornAtSec] 为出生时刻（秒，单调时钟）。
     */
    internal data class RippleCenter(
        val x: Float,
        val y: Float,
        val bornAtSec: Double,
        val speedDpPerSec: Double = DEFAULT_SPEED_DP_PER_SEC,
    ) {
        /** 年龄（秒）；负值表示尚未出生。 */
        fun ageAt(timeSec: Double): Double = timeSec - bornAtSec

        /**
         * 寿命衰减系数 0..1：基准语义为**全程线性**（`1 - age/LIFE`），
         * 出生瞬间（age=0）视为满强度、寿命尽头的点自然归零。
         */
        fun lifeFadeAt(timeSec: Double): Double {
            val age = ageAt(timeSec)
            if (age < 0.0) return 0.0
            return (1.0 - age / RIPPLE_LIFE_SEC).coerceIn(0.0, 1.0)
        }
    }

    companion object {
        /** 波纹扩散速度（dp/s），基准 SPEED=150。 */
        const val DEFAULT_SPEED_DP_PER_SEC = 150.0

        /** 波纹总寿命（秒），基准 LIFE=1.2，超龄即整体作废。 */
        const val RIPPLE_LIFE_SEC = 1.2

        /** 波前宽度（dp）：|点到圆心距离 - 波前半径| 小于此值时该点被点亮，基准 BAND=30。 */
        const val WAVEFRONT_WIDTH_DP = 30.0

        /** 同时活跃的波纹上限，超出丢最旧（快速连点时保留最近几次）。 */
        const val MAX_ACTIVE_RIPPLES = 4

        /** 点阵总点数上限，超出就自动加大间距。 */
        const val MAX_DOTS = 400

        /** out 缓冲里每个点占的 float 数：x / y / boost / lift（v5.x 起上凸由采样直接给出）。 */
        const val FLOATS_PER_DOT = 4

        /** 每道波纹对点的上凸贡献（dp）：boost=1 的波前扫过顶起 2.2dp，基准 lift += k*2.2。 */
        const val DOT_LIFT_PER_RIPPLE_DP = 2.2f

        /** 上凸总上限（dp）：多道波纹按道累加，超过后不再增长，基准总封顶 6dp。 */
        const val DOT_LIFT_TOTAL_MAX_DP = 6f

        /**
         * 点阵基础间距（dp）：控件**短边 ÷ 4** 再 clamp 到 [10,18] ——
         * 48dp 圆按钮自然落在 12dp（4×4 点）、56dp 高的胶囊落在 14dp（横 14 × 纵 4），
         * 与基准"点阵是按钮皮肤的一部分"的密度观感一致。
         */
        fun baseSpacingDp(widthDp: Float, heightDp: Float): Float =
            (min(widthDp, heightDp) / 4f).coerceIn(SPACING_MIN_DP, SPACING_MAX_DP)

        private const val SPACING_MIN_DP = 10f
        private const val SPACING_MAX_DP = 18f

        /** 点数超上限时间距的步进（超大控件兜底，正常按钮永远走不到）。 */
        private const val SPACING_GROW_STEP_DP = 2f
    }

    /** 当前点阵间距（dp）——单测用它断言自适应规则。 */
    var currentSpacingDp = 0f
        private set

    /** 当前点数；与 [rebuildGrid] 的结果一致，采样前必须先重建。 */
    var dotCount = 0
        private set

    private val dotXs = FloatArray(MAX_DOTS)
    private val dotYs = FloatArray(MAX_DOTS)
    private var gridWidthDp = 0f
    private var gridHeightDp = 0f
    private var gridLowRam = false

    /**
     * 重建点阵。网格在控件内居中铺开；宽高与低端机标记都没变时是零开销直通。
     */
    fun rebuildGrid(widthDp: Float, heightDp: Float, lowRam: Boolean) {
        if (widthDp == gridWidthDp && heightDp == gridHeightDp && lowRam == gridLowRam) return
        gridWidthDp = widthDp
        gridHeightDp = heightDp
        gridLowRam = lowRam

        // 低端机直接翻倍间距（降低逐帧采样/绘制的点数），不回 clamp —— 翻倍本身就是要"更稀"
        var spacing = baseSpacingDp(widthDp, heightDp)
        if (lowRam) spacing *= 2f

        var cols: Int
        var rows: Int
        while (true) {
            cols = (widthDp / spacing).toInt().coerceAtLeast(1)
            rows = (heightDp / spacing).toInt().coerceAtLeast(1)
            if (cols * rows <= MAX_DOTS) break
            spacing += SPACING_GROW_STEP_DP
        }
        currentSpacingDp = spacing
        dotCount = cols * rows

        // 网格整体居中：首尾点离边缘等距，避免贴边裁掉半颗点
        val offsetX = (widthDp - (cols - 1) * spacing) / 2f
        val offsetY = (heightDp - (rows - 1) * spacing) / 2f
        var idx = 0
        for (row in 0 until rows) {
            for (col in 0 until cols) {
                dotXs[idx] = offsetX + col * spacing
                dotYs[idx] = offsetY + row * spacing
                idx++
            }
        }
    }

    /**
     * 把一道新波纹压进调用方持有的列表；超过 [MAX_ACTIVE_RIPPLES] 时丢最旧。
     * 列表由调用方持有（Compose 侧用 SnapshotStateList 以便帧循环感知变化），这里只负责容量规则。
     */
    fun pushRipple(centers: MutableList<RippleCenter>, x: Float, y: Float, timeSec: Double) {
        centers.add(RippleCenter(x = x, y = y, bornAtSec = timeSec))
        while (centers.size > MAX_ACTIVE_RIPPLES) {
            centers.removeAt(0)
        }
    }

    /**
     * 对点阵逐点采样，结果写入 out（调用方保证容量 ≥ dotCount * [FLOATS_PER_DOT]）：
     *
     * - out[i*4+0] / [i*4+1]：点的 dp 坐标；
     * - out[i*4+2]：boost（0..1），多道波纹**取最大**（基准 boost = max(boost, k)）；
     * - out[i*4+3]：lift（dp），多道波纹**按道累加**（基准 lift += k*2.2），总封顶 6dp。
     *
     * 衰减语义：波前带之外（含"波已越过"与"还没到"）贡献为 0，寿命之外整道跳过。
     */
    fun sample(centers: List<RippleCenter>, timeSec: Double, out: FloatArray) {
        val wavefront = WAVEFRONT_WIDTH_DP.toFloat()
        for (i in 0 until dotCount) {
            val dx = dotXs[i]
            val dy = dotYs[i]
            var best = 0f
            var liftSum = 0f
            for (ripple in centers) {
                val age = ripple.ageAt(timeSec)
                if (age <= 0.0 || age >= RIPPLE_LIFE_SEC) continue
                val dist = sqrt((dx - ripple.x) * (dx - ripple.x) + (dy - ripple.y) * (dy - ripple.y))
                val delta = dist - (age * ripple.speedDpPerSec).toFloat() // 有符号：+ 未到 / - 已过
                val absDelta = abs(delta)
                if (absDelta >= wavefront) continue
                val k = (1f - absDelta / wavefront) * ripple.lifeFadeAt(timeSec).toFloat()
                if (k > best) {
                    best = k
                }
                // 上凸按道累加（基准 lift += k*2.2），总上限 6dp —— 多道连点叠出更陡的凸起
                liftSum += k * DOT_LIFT_PER_RIPPLE_DP
            }
            out[i * FLOATS_PER_DOT] = dx
            out[i * FLOATS_PER_DOT + 1] = dy
            out[i * FLOATS_PER_DOT + 2] = best.coerceIn(0f, 1f)
            out[i * FLOATS_PER_DOT + 3] = liftSum.coerceIn(0f, DOT_LIFT_TOTAL_MAX_DP)
        }
    }
}
