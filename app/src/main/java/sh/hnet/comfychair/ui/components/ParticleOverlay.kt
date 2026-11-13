package sh.hnet.comfychair.ui.components

import android.app.ActivityManager
import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * 全局点击粒子特效（v4.6.1，模块 B）。
 *
 * 性能约束（低端 Android 13 机型也要流畅）：
 * - **对象池**：[Particle] 全部预分配、复用，点击时不会 new 对象，避免 GC 抖动；
 * - **单次数量受控**：默认 12 个（需求 10–15），低内存设备降到 8；
 * - **帧驱动**：`withFrameNanos` 固定节奏更新，没有粒子的帧直接跳过重绘；
 * - **不消费触摸**：只在 [PointerEventPass.Initial] 观察按下坐标，不调用 `consume()`，
 *   底层按钮/手势照常工作；
 * - **重组隔离**：粒子状态全部在 [ParticleSystem] 里（普通类 + 一个 Int 计数器），
 *   Canvas 只读计数器触发重绘，不碰任何业务 UI 状态。
 */
private class Particle {
    var active = false
    var x = 0f
    var y = 0f
    var vx = 0f
    var vy = 0f
    var life = 0f
    var maxLife = 1f
    var size = 0f
}

/**
 * 粒子池与物理更新。
 *
 * 纯 Kotlin、无 Compose 依赖，便于单测（update 的返回值和池容量都可断言）。
 */
internal class ParticleSystem(
    private val capacity: Int = 96,
    private val maxPerTap: Int = 12
) {
    private val pool = Array(capacity) { Particle() }
    private var cursor = 0
    private val active = ArrayList<Particle>(16)

    /** 当前活跃粒子数（测试/调试用） */
    val activeCount: Int get() = active.size

    /**
     * 在 (x, y) 处喷发一次。
     *
     * 池满时**丢弃**本次喷发而不是扩容：保证点击高峰不会分配新对象。
     */
    fun emit(x: Float, y: Float, count: Int = maxPerTap) {
        val n = count.coerceIn(0, maxPerTap)
        repeat(n) {
            val p = obtain() ?: return@repeat
            p.active = true
            p.x = x
            p.y = y
            val angle = Random.nextFloat() * (PI * 2).toFloat()
            val speed = 120f + Random.nextFloat() * 260f
            p.vx = cos(angle) * speed
            p.vy = sin(angle) * speed - 70f  // 整体略向上，像被"点"出来的
            p.maxLife = 0.45f + Random.nextFloat() * 0.35f
            p.life = p.maxLife
            p.size = 1.5f + Random.nextFloat() * 2.5f
            active.add(p)
        }
    }

    private fun obtain(): Particle? {
        repeat(capacity) {
            val p = pool[cursor]
            cursor = (cursor + 1) % capacity
            if (!p.active) return p
        }
        return null
    }

    /**
     * 推进一帧。
     *
     * @param dt 秒（调用方已 clamp，避免后台恢复时 dt 过大导致粒子瞬移）
     * @return true = 仍有活跃粒子，需要重绘
     */
    fun update(dt: Float): Boolean {
        if (active.isEmpty()) return false
        var i = 0
        while (i < active.size) {
            val p = active[i]
            p.life -= dt
            if (p.life <= 0f) {
                p.active = false
                active.removeAt(i)
                continue
            }
            p.vy += 620f * dt                              // 重力
            p.vx *= (1f - 3.2f * dt).coerceAtLeast(0f)     // 空气摩擦
            p.vy *= (1f - 1.2f * dt).coerceAtLeast(0f)
            p.x += p.vx * dt
            p.y += p.vy * dt
            i++
        }
        return true
    }

    fun draw(scope: DrawScope) {
        for (p in active) {
            val t = (p.life / p.maxLife).coerceIn(0f, 1f)
            val alpha = (t * t * 0.9f).coerceIn(0f, 1f)    // 末段快速淡出
            val radius = p.size * (0.6f + 0.4f * t)
            scope.drawCircle(
                color = Color.White.copy(alpha = alpha),
                radius = radius,
                center = Offset(p.x, p.y)
            )
        }
    }
}

/**
 * 全屏粒子覆盖层。
 *
 * 挂载在 Activity 内容层的 `Box` 最上方（`MainContainerActivity`），
 * 只覆盖主内容区；`AlertDialog` / `ModalBottomSheet` 是独立 Window，天然在它之上。
 */
@Composable
fun ParticleOverlay(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    // 低内存设备降级：8 个/次（需求下限 10 是常规机型；低端机给 8 保帧率）
    val perTap = remember {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        if (am?.isLowRamDevice == true) 8 else 12
    }
    val system = remember { ParticleSystem(maxPerTap = perTap) }
    // 重绘计数器：只有它变化才会重跑 Canvas 的 draw
    var frameTick by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        var last = withFrameNanos { it }
        while (true) {
            val now = withFrameNanos { it }
            val dt = ((now - last) / 1_000_000_000f).coerceIn(0f, 0.05f)
            last = now
            if (system.update(dt)) frameTick++
        }
    }

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        // Initial pass：从根到叶，父层一定能看到；只观察不消费，按钮照常响应
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.type == PointerEventType.Press) {
                            event.changes.firstOrNull()?.let { change ->
                                system.emit(change.position.x, change.position.y)
                            }
                        }
                    }
                }
            }
    ) {
        // 读 frameTick 建立快照订阅；-1 的用法只是让编译器不把表达式当常量
        if (frameTick >= 0) system.draw(this)
    }
}
