package sh.hnet.comfychair.ui.components

import android.app.ActivityManager
import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * 全局点击反馈特效（v4.8.0：修复点击屏蔽 + 视觉升级为涟漪 / 辉光 / 微粒子三层）。
 *
 * ## 一、修复：这一层绝不能挂触摸修饰符
 *
 * v4.7.0 在 `Canvas` 上挂了 `Modifier.pointerInput`，Compsoe 命中测试默认
 * `sharePointerInputWithSiblings = false`，上层指针节点命中后下层兄弟节点不再命中 ——
 * 表现为"所有控件点不动"。**本次把 `pointerInput` 整个去掉**，覆盖层只负责绘制；
 * 触点坐标改由 `MainContainerActivity.dispatchTouchEvent` 读取（返回 super，不消费）。
 * 详见 [ParticleTouchRouter]。
 *
 * ## 二、视觉：三层结构，对标 Discord / Telegram
 *
 * 1. **辉光**（glow）：触点处一小团低透明度径向高光，快速淡出 —— 制造"被点亮"的感觉。
 * 2. **涟漪**（ripple）：从触点向外扩散的细圆环，半径增大、透明度渐隐，边缘细而柔和。
 * 3. **微粒子**（particle）：少量细碎高光点径向轻飘 + 轻微下坠，作为点缀而非主体。
 *
 * 绘制顺序固定为 辉光 → 涟漪 → 粒子（由内到外、由柔到锐），避免粒子被辉光糊住。
 *
 * ## 三、性能约束（低端 Android 13 机型也要稳）
 *
 * - **全部对象池**：粒子 / 涟漪 / 辉光都预分配复用，点击时零 new；
 * - **零 Brush 分配**：径向渐变换成 3 层同心圆（视觉近似），避免每帧建 `Brush`；
 * - **Stroke 复用**：圆环用预建的常量 [RIPPLE_STROKE]，不逐帧构造；
 * - **单次数量受控**：粒子 12 个（低内存设备 8），涟漪与辉光各 1 个；
 * - **帧驱动**：`withFrameNanos` 更新，没有活跃特效的帧直接跳过重绘；
 * - **重组隔离**：状态全在 [ParticleSystem]（普通类 + 一个 Int 计数器），只让 Canvas 重绘。
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

/** 涟漪：从触点向外扩散的圆环 */
private class Ripple {
    var active = false
    var x = 0f
    var y = 0f
    var life = 0f
    var maxLife = 0f
    var startRadius = 0f
    var endRadius = 0f
}

/** 辉光：触点处的一团柔和高光 */
private class Glow {
    var active = false
    var x = 0f
    var y = 0f
    var life = 0f
    var maxLife = 0f
    var radius = 0f
}

/**
 * 特效池与物理更新。
 *
 * 纯 Kotlin、无 Compose 依赖（[DrawScope] 只用于绘制），便于单测：
 * 池容量、寿命归零、空系统不重绘都可以直接断言。
 *
 * @param capacity 粒子池容量
 * @param maxPerTap 单次点击最多生成的粒子数
 * @param density 屏幕密度，用于把 dp 规格换算成像素
 */
internal class ParticleSystem(
    private val capacity: Int = 96,
    private val maxPerTap: Int = 12,
    private val density: Float = 3f
) {
    private val pool = Array(capacity) { Particle() }
    private var cursor = 0
    private val active = ArrayList<Particle>(16)

    // 涟漪与辉光各 4 个槽位：快速连点时保留最近 4 次，超出即覆盖最旧的
    private val ripples = Array(4) { Ripple() }
    private var rippleCursor = 0
    private val glows = Array(4) { Glow() }
    private var glowCursor = 0

    /** 当前活跃粒子数（单测/调试用，只统计粒子） */
    val activeCount: Int get() = active.size

    /**
     * 在 (x, y) 处触发一次完整反馈：1 个辉光 + 1 个涟漪 + [count] 个粒子。
     *
     * 池满时**丢弃**而不是扩容，保证点击高峰不分配新对象。
     */
    fun emit(x: Float, y: Float, count: Int = maxPerTap) {
        spawnGlow(x, y)
        spawnRipple(x, y)

        val n = count.coerceIn(0, maxPerTap)
        repeat(n) {
            val p = obtain() ?: return@repeat
            p.active = true
            p.x = x
            p.y = y
            val angle = Random.nextFloat() * (PI * 2).toFloat()
            // 速度比 v4.7.0 收敛：粒子是点缀，不该"炸开"
            val speed = (40f + Random.nextFloat() * 130f) * density
            p.vx = cos(angle) * speed
            p.vy = sin(angle) * speed - 40f * density // 略偏上，像被"点"起来
            p.maxLife = 0.32f + Random.nextFloat() * 0.26f
            p.life = p.maxLife
            p.size = (0.7f + Random.nextFloat() * 1.3f) * density
            active.add(p)
        }
    }

    private fun spawnRipple(x: Float, y: Float) {
        val r = ripples[rippleCursor]
        rippleCursor = (rippleCursor + 1) % ripples.size
        r.active = true
        r.x = x
        r.y = y
        r.maxLife = 0.46f
        r.life = r.maxLife
        r.startRadius = 6f * density
        r.endRadius = 42f * density
    }

    private fun spawnGlow(x: Float, y: Float) {
        val g = glows[glowCursor]
        glowCursor = (glowCursor + 1) % glows.size
        g.active = true
        g.x = x
        g.y = y
        g.maxLife = 0.30f
        g.life = g.maxLife
        g.radius = 30f * density
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
     * @param dt 秒（调用方已 clamp，避免后台恢复时 dt 过大导致特效瞬移）
     * @return true = 仍有活跃特效，需要重绘
     */
    fun update(dt: Float): Boolean {
        var alive = false

        // 辉光
        for (g in glows) {
            if (!g.active) continue
            g.life -= dt
            if (g.life <= 0f) g.active = false else alive = true
        }

        // 涟漪
        for (r in ripples) {
            if (!r.active) continue
            r.life -= dt
            if (r.life <= 0f) r.active = false else alive = true
        }

        // 粒子
        if (active.isNotEmpty()) {
            alive = true
            var i = 0
            while (i < active.size) {
                val p = active[i]
                p.life -= dt
                if (p.life <= 0f) {
                    p.active = false
                    active.removeAt(i)
                    continue
                }
                p.vy += 260f * density * dt                  // 重力：比 v4.7.0 轻，慢慢落
                p.vx *= (1f - 3.6f * dt).coerceAtLeast(0f)   // 空气摩擦
                p.vy *= (1f - 1.6f * dt).coerceAtLeast(0f)
                p.x += p.vx * dt
                p.y += p.vy * dt
                i++
            }
        }
        return alive
    }

    /** 绘制顺序：辉光（最柔）→ 涟漪（轮廓）→ 粒子（最锐） */
    fun draw(scope: DrawScope, color: Color) {
        drawGlows(scope, color)
        drawRipples(scope, color)
        drawParticles(scope, color)
    }

    private fun drawGlows(scope: DrawScope, color: Color) {
        for (g in glows) {
            if (!g.active) continue
            val t = (g.life / g.maxLife).coerceIn(0f, 1f)
            // 用 3 层同心圆近似径向渐变：中心最亮、外圈最淡，零 Brush 分配
            val base = t * t * 0.20f
            scope.drawCircle(color.copy(alpha = base * 0.45f), g.radius, Offset(g.x, g.y))
            scope.drawCircle(color.copy(alpha = base * 0.75f), g.radius * 0.58f, Offset(g.x, g.y))
            scope.drawCircle(color.copy(alpha = base), g.radius * 0.28f, Offset(g.x, g.y))
        }
    }

    private fun drawRipples(scope: DrawScope, color: Color) {
        for (r in ripples) {
            if (!r.active) continue
            val t = 1f - (r.life / r.maxLife).coerceIn(0f, 1f) // 0 → 1 扩散进度
            val radius = r.startRadius + (r.endRadius - r.startRadius) * easeOut(t)
            // 前段快速出现、后段柔和淡出
            val alpha = (1f - t) * (1f - t) * 0.55f
            scope.drawCircle(
                color = color.copy(alpha = alpha),
                radius = radius,
                center = Offset(r.x, r.y),
                style = RIPPLE_STROKE
            )
        }
    }

    private fun drawParticles(scope: DrawScope, color: Color) {
        for (p in active) {
            val t = (p.life / p.maxLife).coerceIn(0f, 1f)
            val alpha = (t * t * 0.85f).coerceIn(0f, 1f) // 末段快速淡出，不留残影
            val radius = p.size * (0.55f + 0.45f * t)    // 先胀开再收缩
            scope.drawCircle(color.copy(alpha = alpha), radius, Offset(p.x, p.y))
        }
    }

    /** 缓出：扩散起初快、接近尾声慢下来，比线性更像"柔和涟漪" */
    private fun easeOut(t: Float): Float {
        val inv = 1f - t
        return 1f - inv * inv * inv
    }

    private companion object {
        /** 圆环描边常量：复用同一个 Stroke，避免每帧构造对象 */
        val RIPPLE_STROKE = Stroke(width = 2.4f)
    }
}

/**
 * 全屏特效覆盖层（**纯绘制，无任何触摸修饰符**）。
 *
 * 挂在 `MainContainerActivity` 内容层 `Box` 的最上方；`AlertDialog` / `ModalBottomSheet`
 * 是独立 Window，天然在它之上。
 *
 * 触点来源：[ParticleTouchRouter]（由 Activity 的 `dispatchTouchEvent` 转发，不消费事件）。
 */
@Composable
fun ParticleOverlay(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val density = LocalDensity.current.density
    // 低内存设备降级：8 个/次（常规机型 12，需求区间 10–15）
    val perTap = remember {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        if (am?.isLowRamDevice == true) 8 else 12
    }
    val system = remember(density, perTap) {
        ParticleSystem(maxPerTap = perTap, density = density)
    }
    // 重绘计数器：只有它变化才会重跑 Canvas 的 draw
    var frameTick by remember { mutableIntStateOf(0) }

    // 注册坐标接收端：Activity 只转发坐标，不参与事件分发决策。
    // 用 DisposableEffect 而不是 LaunchedEffect，确保离开组合时立刻注销，
    // 不会留下指向已销毁 system 的悬挂引用。
    DisposableEffect(system) {
        ParticleTouchRouter.register { x, y -> system.emit(x, y) }
        onDispose { ParticleTouchRouter.register(null) }
    }

    LaunchedEffect(system) {
        var last = withFrameNanos { it }
        while (true) {
            val now = withFrameNanos { it }
            val dt = ((now - last) / 1_000_000_000f).coerceIn(0f, 0.05f)
            last = now
            if (system.update(dt)) frameTick++
        }
    }

    // 关键：这里只有 fillMaxSize + 绘制，**没有任何 pointerInput / clickable**，
    // 不参与命中测试，下层业务 UI 的点击、焦点、手势完全不受影响。
    //
    // 颜色跟主题走：浅色主题下白色特效叠在白底/浅色图上完全看不见（实测），
    // 用 colorScheme.primary 则深浅两种主题都保证与背景有对比度。
    val effectColor = MaterialTheme.colorScheme.primary
    Canvas(modifier = modifier.fillMaxSize()) {
        if (frameTick >= 0) system.draw(this, effectColor)
    }
}
