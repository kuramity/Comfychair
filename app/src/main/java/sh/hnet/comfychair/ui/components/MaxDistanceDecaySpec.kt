package sh.hnet.comfychair.ui.components

import androidx.compose.animation.core.DecayAnimationSpec
import androidx.compose.animation.core.TwoWayConverter
import androidx.compose.animation.core.VectorizedDecayAnimationSpec
import androidx.compose.animation.core.AnimationVector
import kotlin.math.abs

/**
 * 把「衰减滑行距离」硬钳在 [maxDistance]（px）以内的 [DecayAnimationSpec] 包装器
 * （P1 · 图库全屏翻页「滑出去又弹回来」，2026-11-24）。
 *
 * ## 为什么需要它
 *
 * 框架（foundation 1.10）的 `SnapFlingBehavior` 松手后分两段：
 * 1. **approach 段**：用 `decayAnimationSpec` 把位移衰减出去
 *    （`calculateTargetValue(initialVelocity, 0f)` 得到 decayOffset，
 *    再交给 `SnapLayoutInfoProvider.calculateApproachOffset`）；
 * 2. **snap 段**：执行 [androidx.compose.foundation.pager.PagerSnapDistance]
 *    收窄后的目标，用 `snapAnimationSpec` 的弹簧收尾。
 *
 * 本项目在**第 2 步**做了钳制（全屏态目标恒为 `start ± 1`，见 MediaViewerScreen 的
 * `ViewerPagerSnapDistance` / `ViewerPageMotion.snapTargetPage`），
 * 但**第 1 步**用的仍是框架默认的 spline 衰减——它按真实甩速衰减，
 * 8000-12000px/s 对应 3-9 页的滑行距离。
 *
 * 本类只做一件事：让衰减段的距离也不再超过 [maxDistance]。
 * 判定/常量/吸附目标一律不改——吸附仍由 `PagerSnapDistance` 决定。
 *
 * ## 参数由调用方按模式决定（关键）
 *
 * [maxDistance] 是**取值时机在松手那一刻**的 lambda，不是构造快照：
 * - 全屏单图态：传单页步距（页宽 + 页间距离），把滑行距离钳在一页内；
 * - 并排预览（队列）态：传 `Float.POSITIVE_INFINITY`（等于不钳），
 *   多张惯性一字不动。
 *
 * 传 0 / 负数 / NaN / 无穷 以外的非法值一律**退化为直通**（不钳），
 * 宁可不生效，也不把 0 距离写进动画（尺寸未测得时 `pagerSize.width == 0`，
 * 若无条件钳制会把所有 fling 变成"原地不动"）。
 *
 * ## 时长：按 delegate 真实到达钳制值的时刻切开
 *
 * 只按"距离比例"压缩时长会在末段"冻住"——spline 是减速曲线，
 * 距离走完钳制比例时**时间**还没到钳制比例，剩下的时间位移停在钳制值不动，
 * 观感是一顿一停。这里改成二分查找「delegate 首次到达钳制距离的时刻」作为时长，
 * 曲线形状与 delegate 前段完全一致，到点即结束。
 *
 * ## 与 Compose 组合作用域的关系
 *
 * 本类本身**不读** Compose 状态（不加 `@Composable`、不碰 `LocalDensity`）。
 * spline 实例（`rememberSplineBasedDecay()`）必须由调用方在 Composable 作用域取好再传进来。
 *
 * ## 可回退性
 *
 * 只有一个使用点（MediaViewerScreen 的 `PagerDefaults.flingBehavior` 的
 * `decayAnimationSpec` 形参）。回退 = 把那一个实参换回 `rememberSplineBasedDecay()`，
 * 然后删除本文件；其余代码对零依赖（本类不被任何单测或纯函数引用）。
 *
 * ## 已知边界（真机复核要点）
 *
 * 在 foundation 1.10 的执行链路里，全屏态的 approach 距离**本来就已经被第 2 步的
 * 页级钳制反推为 0**（`calculateApproachOffset` 内部：
 * `proposedFlingOffset = (correctedTargetPage - startPage) * pitch`，
 * `flingApproachOffsetPx = (|proposedFlingOffset| - pitch).coerceAtLeast(0)`，
 * 钳到 1 张时两边相等 → 0 → `tryApproach` 直接短路，衰减段根本不跑）。
 * 也就是说：本类在全屏态**是一层保险**，它不会改变"先滑出去多远"的可见结果，
 * 只能保证"若将来页级钳制放松/框架行为变化，衰减距离不再越过一页"。
 * 详细证据与推论见执行报告-P1.md 的「根因」一节。
 */
class MaxDistanceDecaySpec(
    private val delegate: DecayAnimationSpec<Float>,
    private val maxDistance: () -> Float,
) : DecayAnimationSpec<Float> {

    override fun <V : AnimationVector> vectorize(
        typeConverter: TwoWayConverter<Float, V>
    ): VectorizedDecayAnimationSpec<V> {
        val inner = delegate.vectorize(typeConverter)
        return object : VectorizedDecayAnimationSpec<V> {

            override val absVelocityThreshold: Float
                get() = inner.absVelocityThreshold

            override fun getTargetValue(initialValue: V, initialVelocity: V): V {
                val limit = effectiveLimit()
                if (limit == null) {
                    return inner.getTargetValue(initialValue, initialVelocity)
                }
                val start = typeConverter.convertFromVector(initialValue)
                val raw = typeConverter.convertFromVector(
                    inner.getTargetValue(initialValue, initialVelocity)
                )
                return typeConverter.convertToVector(clampDelta(start, raw, limit))
            }

            override fun getValueFromNanos(
                playTimeNanos: Long,
                initialValue: V,
                initialVelocity: V
            ): V {
                val limit = effectiveLimit()
                if (limit == null) {
                    return inner.getValueFromNanos(playTimeNanos, initialValue, initialVelocity)
                }
                val start = typeConverter.convertFromVector(initialValue)
                val raw = typeConverter.convertFromVector(
                    inner.getValueFromNanos(playTimeNanos, initialValue, initialVelocity)
                )
                return typeConverter.convertToVector(clampDelta(start, raw, limit))
            }

            override fun getVelocityFromNanos(
                playTimeNanos: Long,
                initialValue: V,
                initialVelocity: V
            ): V = inner.getVelocityFromNanos(playTimeNanos, initialValue, initialVelocity)

            override fun getDurationNanos(initialValue: V, initialVelocity: V): Long {
                val total = inner.getDurationNanos(initialValue, initialVelocity)
                val limit = effectiveLimit() ?: return total
                if (total <= 0L) return total
                val start = typeConverter.convertFromVector(initialValue)
                val rawTarget = typeConverter.convertFromVector(
                    inner.getTargetValue(initialValue, initialVelocity)
                )
                // 未越过钳制值：时长与轨迹都原样交还 delegate
                if (abs(rawTarget - start) <= limit) return total
                var low = 0L
                var high = total
                while (high - low > TIME_STEP_NANOS) {
                    val mid = (low + high) / 2
                    val midDelta = typeConverter.convertFromVector(
                        inner.getValueFromNanos(mid, initialValue, initialVelocity)
                    ) - start
                    if (abs(midDelta) >= limit) high = mid else low = mid
                }
                return high.coerceIn(1L, total)
            }
        }
    }

    /** 生效的钳制值；非法 / 未测得 / 显式放开 → null（直通 delegate） */
    private fun effectiveLimit(): Float? {
        val limit = maxDistance()
        return if (limit.isFinite() && limit > 0f) limit else null
    }

    private fun clampDelta(start: Float, raw: Float, limit: Float): Float {
        if (!raw.isFinite()) return raw
        return start + (raw - start).coerceIn(-limit, limit)
    }

    private companion object {
        /** 二分精度（1ms，远小于一帧 16ms；对观感无影响） */
        const val TIME_STEP_NANOS = 1_000_000L
    }
}
